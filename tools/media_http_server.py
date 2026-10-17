"""Serve media with byte ranges using only Python's standard library."""

import argparse
import base64
import binascii
from functools import partial
import hmac
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import os
import re

PASSWORD_ENVIRONMENT_VARIABLE = "MEDIA_HTTP_PASSWORD"


def parse_byte_range(value, size):
    """Return inclusive offsets for a single byte range, including suffix ranges.

    Returns None for a header to ignore: malformed, another unit, or several ranges. HTTP asks
    servers to answer those with the whole file. Raises ValueError when a valid range misses
    the file, which calls for 416.
    """
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", value.strip(), re.IGNORECASE)
    if not match or not any(match.groups()):
        return None
    first, last = match.groups()
    if first and last and int(last) < int(first):
        return None
    if not first:
        suffix = int(last)
        if suffix <= 0 or size <= 0:
            raise ValueError("Unsatisfiable suffix range")
        return max(0, size - suffix), size - 1
    start = int(first)
    if start >= size:
        raise ValueError("Unsatisfiable byte range")
    end = min(int(last), size - 1) if last else size - 1
    return start, end


class BasicCredentials:
    """Password, and optionally username, that requests must send with HTTP Basic auth."""

    def __init__(self, password, username=None):
        self.password = password.encode("utf-8")
        self.username = None if username is None else username.encode("utf-8")

    def accepts(self, header):
        scheme, _, value = (header or "").strip().partition(" ")
        if scheme.lower() != "basic":
            return False
        try:
            decoded = base64.b64decode(value.strip(), validate=True)
        except binascii.Error:
            return False
        username, separator, password = decoded.partition(b":")
        if not separator:
            return False
        # Check both parts every time, so response timing does not reveal which one was wrong.
        password_matches = hmac.compare_digest(password, self.password)
        username_matches = self.username is None or hmac.compare_digest(username, self.username)
        return password_matches and username_matches


class MediaRequestHandler(SimpleHTTPRequestHandler):
    # Seconds to wait for a request, so a client that connects and stalls cannot hold a thread
    # forever. Matches the app's read timeout.
    timeout = 60

    def __init__(self, *args, credentials=None, **kwargs):
        # Set before the base class, which handles the whole request inside __init__.
        self.credentials = credentials
        super().__init__(*args, **kwargs)

    def do_GET(self):
        if self.authorize():
            super().do_GET()

    def do_HEAD(self):
        if self.authorize():
            super().do_HEAD()

    def authorize(self):
        """Return whether the request may go on, answering 401 when it may not.

        The WWW-Authenticate challenge makes browsers ask for a username and password.
        """
        if self.credentials is None or self.credentials.accepts(self.headers.get("Authorization")):
            return True
        body = b"Authentication required\n"
        self.send_response(HTTPStatus.UNAUTHORIZED)
        self.send_header("WWW-Authenticate", 'Basic realm="media", charset="UTF-8"')
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)
        return False

    def handle(self):
        try:
            super().handle()
        except ConnectionError:
            # Seeking, leaving a video, or cancelling a download can close the socket early.
            pass

    def end_headers(self):
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def send_head(self):
        # The request has arrived. A paused player stops reading while its buffer is full,
        # which can block writing the body for minutes, so the response has no time limit.
        self.connection.settimeout(None)
        self.remaining_bytes = None
        path = self.translate_path(self.path)
        range_header = self.headers.get("Range")
        # Directory listings, redirects, normal GETs, and HEAD keep the standard behavior.
        if self.command != "GET" or not range_header or not os.path.isfile(path):
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
                byte_range = parse_byte_range(range_header, stat.st_size)
            except ValueError:
                source.close()
                self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                self.send_header("Content-Range", f"bytes */{stat.st_size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return None
            if byte_range is None:
                source.close()
                return super().send_head()
            start, end = byte_range
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

    def copyfile(self, source, output):
        remaining = self.remaining_bytes
        while remaining is None or remaining > 0:
            block = source.read(min(1024 * 1024, remaining) if remaining is not None else 1024 * 1024)
            if not block:
                break
            output.write(block)
            if remaining is not None:
                remaining -= len(block)


def read_password(path):
    """Password from the first line of the file at path, else from the environment, else None."""
    if path:
        # utf-8-sig drops the byte order mark Windows editors may add.
        with open(path, encoding="utf-8-sig") as file:
            return file.readline().rstrip("\r\n")
    return os.environ.get(PASSWORD_ENVIRONMENT_VARIABLE)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("port", nargs="?", type=int, default=8000)
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--directory", default=".")
    parser.add_argument(
        "--password-file",
        help="require HTTP Basic auth with the password on this file's first line; "
        f"{PASSWORD_ENVIRONMENT_VARIABLE} sets it too. Without either, anyone can connect.",
    )
    parser.add_argument("--user", help="username to require with the password; any username works without it")
    args = parser.parse_args()
    # Not a command-line value, which other users of the computer could see in the process list.
    password = read_password(args.password_file)
    if args.password_file and not password:
        parser.error(f"{args.password_file} has no password on its first line")
    if args.user and not password:
        parser.error(f"--user needs a password from --password-file or {PASSWORD_ENVIRONMENT_VARIABLE}")
    credentials = BasicCredentials(password, args.user) if password else None
    handler = partial(MediaRequestHandler, directory=os.path.abspath(args.directory), credentials=credentials)
    with ThreadingHTTPServer((args.bind, args.port), handler) as server:
        print(f"Serving {os.path.abspath(args.directory)} on {args.bind}:{args.port} with byte ranges", flush=True)
        if credentials:
            # Basic auth only encodes the password, so anyone watching plain HTTP traffic can read it.
            print("Requiring a password; it travels unencrypted over plain HTTP", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
