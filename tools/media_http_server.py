"""Serve media with byte ranges and optional FFmpeg video thumbnails."""

import argparse
from collections import OrderedDict
from functools import partial
import html
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import io
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import threading
from urllib.parse import parse_qs, quote, unquote, urlsplit


VIDEO_EXTENSIONS = {".mp4", ".mkv", ".webm", ".3gp", ".mov", ".avi", ".m4v"}
PREVIEW_QUERY = "scrtvwr-preview"
PREVIEW_HEADER = "X-SCRTVWR-Video-Previews"
MAX_PREVIEW_BYTES = 1024 * 1024


class VideoPreviewCache:
    """Small in-memory JPEG cache shared by request threads; originals never leave the PC."""

    def __init__(self, ffmpeg):
        self.ffmpeg = ffmpeg
        self._cache = OrderedDict()
        self._cache_bytes = 0
        self._lock = threading.Lock()
        self._workers = threading.BoundedSemaphore(2)

    def get(self, path):
        stat = os.stat(path)
        key = (path, stat.st_mtime_ns, stat.st_size)
        with self._lock:
            if key in self._cache:
                self._cache.move_to_end(key)
                return self._cache[key]
        if not self._workers.acquire(timeout=1):
            raise TimeoutError("Video preview workers are busy")
        try:
            # Use a file so decoder output cannot grow unbounded in Python memory.
            with tempfile.TemporaryDirectory(prefix="scrtvwr-preview-") as directory:
                target = Path(directory, "frame.jpg")
                subprocess.run(
                    [self.ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin",
                     "-threads", "1", "-i", path, "-map", "0:v:0", "-frames:v", "1",
                     "-an", "-sn", "-vf",
                     "scale=256:256:force_original_aspect_ratio=decrease,setsar=1",
                     "-threads", "1", "-q:v", "4", "-y", str(target)],
                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL, timeout=20, check=True,
                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                )
                with target.open("rb") as source:
                    data = source.read(MAX_PREVIEW_BYTES + 1)
                if not data or len(data) > MAX_PREVIEW_BYTES:
                    raise OSError("Video preview is empty or too large")
            with self._lock:
                previous = self._cache.pop(key, b"")
                self._cache_bytes -= len(previous)
                self._cache[key] = data
                self._cache_bytes += len(data)
                while self._cache_bytes > 32 * 1024 * 1024 or len(self._cache) > 512:
                    _, removed = self._cache.popitem(last=False)
                    self._cache_bytes -= len(removed)
            return data
        finally:
            self._workers.release()


def parse_byte_range(value, size):
    """Return inclusive offsets for a single byte range, including suffix ranges."""
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", value.strip())
    if not match or size <= 0 or not any(match.groups()):
        raise ValueError("Invalid byte range")
    first, last = match.groups()
    if not first:
        suffix = int(last)
        if suffix <= 0:
            raise ValueError("Empty suffix range")
        return max(0, size - suffix), size - 1
    start = int(first)
    end = min(int(last), size - 1) if last else size - 1
    if start >= size or start > end:
        raise ValueError("Unsatisfiable byte range")
    return start, end


class MediaRequestHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, previews=None, **kwargs):
        self.previews = previews
        super().__init__(*args, **kwargs)

    def handle(self):
        try:
            super().handle()
        except ConnectionError:
            # Seeking, leaving a video, or cancelling a download can close the socket early.
            pass

    def end_headers(self):
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "no-store")
        if self.previews is not None:
            self.send_header(PREVIEW_HEADER, "1")
        super().end_headers()

    def send_head(self):
        self.remaining_bytes = None
        if PREVIEW_QUERY in parse_qs(urlsplit(self.path).query, keep_blank_values=True):
            return self.send_video_preview()
        path = self.translate_path(self.path)
        range_header = self.headers.get("Range")
        # Directory listings, redirects, normal GETs, and HEAD keep the standard behavior.
        if self.command != "GET" or not range_header or not os.path.isfile(path):
            return super().send_head()
        # Multiple ranges are optional in HTTP. Ignore them and serve the complete file.
        if "," in range_header:
            return super().send_head()
        try:
            source = open(path, "rb")
        except OSError:
            self.send_error(HTTPStatus.NOT_FOUND, "File not found")
            return None
        try:
            stat = os.fstat(source.fileno())
            modified = self.date_time_string(stat.st_mtime)
            if_range = self.headers.get("If-Range")
            if if_range and if_range != modified:
                source.close()
                return super().send_head()
            try:
                start, end = parse_byte_range(range_header, stat.st_size)
            except ValueError:
                source.close()
                self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                self.send_header("Content-Range", f"bytes */{stat.st_size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return None
            source.seek(start)
            self.remaining_bytes = end - start + 1
            self.send_response(HTTPStatus.PARTIAL_CONTENT)
            self.send_header("Content-Type", self.guess_type(path))
            self.send_header("Content-Range", f"bytes {start}-{end}/{stat.st_size}")
            self.send_header("Content-Length", str(self.remaining_bytes))
            self.send_header("Last-Modified", modified)
            self.end_headers()
            return source
        except BaseException:
            source.close()
            raise

    def send_video_preview(self):
        if self.previews is None:
            self.send_error(HTTPStatus.NOT_IMPLEMENTED, "Video previews require FFmpeg")
            return None
        path = os.path.realpath(self.translate_path(self.path))
        root = os.path.realpath(self.directory)
        try:
            inside_root = os.path.commonpath((root, path)) == root
        except ValueError:
            inside_root = False
        if not inside_root or not os.path.isfile(path) or Path(path).suffix.lower() not in VIDEO_EXTENSIONS:
            self.send_error(HTTPStatus.NOT_FOUND, "Video not found")
            return None
        if self.command == "HEAD":
            # Capability probes never invoke the decoder.
            data = b""
        else:
            try:
                data = self.previews.get(path)
            except (OSError, subprocess.SubprocessError, TimeoutError):
                self.send_error(HTTPStatus.SERVICE_UNAVAILABLE, "Video preview unavailable")
                return None
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "image/jpeg")
        if self.command != "HEAD":
            self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        return io.BytesIO(data)

    def list_directory(self, path):
        if self.previews is None:
            return super().list_directory(path)
        try:
            names = sorted(os.listdir(path), key=str.lower)
        except OSError:
            self.send_error(HTTPStatus.NOT_FOUND, "No permission to list directory")
            return None
        title = html.escape(f"Directory listing for {unquote(urlsplit(self.path).path)}")
        lines = [f'<!DOCTYPE html><html><head><meta charset="utf-8"><title>{title}</title>',
                 '<style>li { margin: 8px 0; } img { width: 128px; height: 80px; '
                 'object-fit: contain; vertical-align: middle; margin-right: 8px; }</style>',
                 f'</head><body><h1>{title}</h1><hr><ul>']
        for name in names:
            is_directory = os.path.isdir(os.path.join(path, name))
            display = name + ("/" if is_directory else "")
            href = html.escape(quote(display), quote=True)
            image = ""
            if not is_directory and Path(name).suffix.lower() in VIDEO_EXTENSIONS:
                image = (f'<img loading="lazy" alt="" src="{href}?{PREVIEW_QUERY}=1" '
                         'onerror="this.hidden=true">')
            lines.append(f'<li><a href="{href}">{image}{html.escape(display)}</a></li>')
        lines.append('</ul><hr></body></html>')
        data = "\n".join(lines).encode("utf-8")
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        return io.BytesIO(data)

    def copyfile(self, source, output):
        remaining = self.remaining_bytes
        while remaining is None or remaining > 0:
            block = source.read(min(1024 * 1024, remaining) if remaining is not None else 1024 * 1024)
            if not block:
                break
            output.write(block)
            if remaining is not None:
                remaining -= len(block)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("port", nargs="?", type=int, default=8000)
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--directory", default=".")
    parser.add_argument("--ffmpeg", default="ffmpeg", help="FFmpeg executable name or path for video previews")
    parser.add_argument("--no-previews", action="store_true", help="Disable video preview generation")
    args = parser.parse_args()
    ffmpeg = None if args.no_previews else shutil.which(args.ffmpeg)
    previews = VideoPreviewCache(ffmpeg) if ffmpeg else None
    handler = partial(MediaRequestHandler, directory=os.path.abspath(args.directory), previews=previews)
    with ThreadingHTTPServer((args.bind, args.port), handler) as server:
        print(f"Serving {os.path.abspath(args.directory)} on {args.bind}:{args.port} with byte ranges", flush=True)
        print("Video previews enabled" if previews else "Video previews disabled (FFmpeg unavailable or --no-previews)", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
