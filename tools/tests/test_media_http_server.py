from functools import partial
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from pathlib import Path
import os
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

from tools.media_http_server import (
    MAX_PREVIEW_BYTES, MediaRequestHandler, PREVIEW_HEADER, VideoPreviewCache, parse_byte_range,
)


class ByteRangeTest(unittest.TestCase):
    def test_large_offsets_do_not_overflow(self):
        size = 5 * 1024**3
        start = 4 * 1024**3
        self.assertEqual((start, size - 1), parse_byte_range(f"bytes={start}-", size))

    def test_suffix_and_clipped_end(self):
        self.assertEqual((90, 99), parse_byte_range("bytes=-10", 100))
        self.assertEqual((0, 99), parse_byte_range("bytes=-200", 100))
        self.assertEqual((95, 99), parse_byte_range("bytes=95-200", 100))

    def test_unsatisfiable_ranges(self):
        for value, size in [("bytes=100-", 100), ("bytes=20-10", 100),
                            ("bytes=-0", 100), ("bytes=-", 100), ("bytes=0-", 0)]:
            with self.subTest(value=value, size=size):
                with self.assertRaises(ValueError):
                    parse_byte_range(value, size)


class MediaServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.payload = bytes(range(256)) * 64
        Path(cls.directory.name, "video.mp4").write_bytes(cls.payload)
        handler = partial(MediaRequestHandler, directory=cls.directory.name)
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()
        cls.directory.cleanup()

    def request(self, method="GET", path="/video.mp4", headers=None):
        connection = HTTPConnection(*self.server.server_address, timeout=5)
        try:
            connection.request(method, path, headers=headers or {})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def test_get_and_directory_listing(self):
        status, headers, body = self.request()
        self.assertEqual(200, status)
        self.assertEqual("bytes", headers["Accept-Ranges"])
        self.assertEqual("no-store", headers["Cache-Control"])
        self.assertEqual(self.payload, body)
        status, _, body = self.request(path="/")
        self.assertEqual(200, status)
        self.assertIn(b"video.mp4", body)

    def test_seek_returns_only_requested_bytes(self):
        status, headers, body = self.request(headers={"Range": "bytes=1000-1099"})
        self.assertEqual(206, status)
        self.assertEqual(f"bytes 1000-1099/{len(self.payload)}", headers["Content-Range"])
        self.assertEqual("100", headers["Content-Length"])
        self.assertEqual(self.payload[1000:1100], body)

    def test_suffix_and_open_ended_requests(self):
        for value in ["bytes=-17", f"bytes={len(self.payload) - 17}-"]:
            with self.subTest(value=value):
                status, _, body = self.request(headers={"Range": value})
                self.assertEqual(206, status)
                self.assertEqual(self.payload[-17:], body)

    def test_range_beyond_end_returns_416(self):
        status, headers, body = self.request(headers={"Range": f"bytes={len(self.payload)}-"})
        self.assertEqual(416, status)
        self.assertEqual(f"bytes */{len(self.payload)}", headers["Content-Range"])
        self.assertEqual(b"", body)

    def test_head_ignores_range_and_reports_full_length(self):
        status, headers, body = self.request(method="HEAD", headers={"Range": "bytes=10-20"})
        self.assertEqual(200, status)
        self.assertEqual(str(len(self.payload)), headers["Content-Length"])
        self.assertEqual(b"", body)

    def test_if_range_mismatch_sends_full_file(self):
        status, _, body = self.request(headers={"Range": "bytes=10-20", "If-Range": '"old-etag"'})
        self.assertEqual(200, status)
        self.assertEqual(self.payload, body)

    def test_if_range_match_sends_partial_file(self):
        _, headers, _ = self.request(method="HEAD")
        status, _, body = self.request(headers={"Range": "bytes=10-20", "If-Range": headers["Last-Modified"]})
        self.assertEqual(206, status)
        self.assertEqual(self.payload[10:21], body)

    def test_disabled_preview_does_not_serve_the_original_video(self):
        status, headers, body = self.request(path="/video.mp4?scrtvwr-preview=1")
        self.assertEqual(501, status)
        self.assertNotIn(PREVIEW_HEADER, headers)
        self.assertNotEqual(self.payload, body)


class VideoPreviewServerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.name = "clip & \u00e9.mp4"
        Path(self.directory.name, self.name).write_bytes(b"original video")
        Path(self.directory.name, "image.jpg").write_bytes(b"image")
        self.previews = Mock()
        self.previews.get.return_value = b"\xff\xd8thumbnail\xff\xd9"
        handler = partial(MediaRequestHandler, directory=self.directory.name, previews=self.previews)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.directory.cleanup()

    def request(self, path="/clip%20%26%20%C3%A9.mp4?scrtvwr-preview=1", method="GET"):
        connection = HTTPConnection(*self.server.server_address, timeout=5)
        try:
            connection.request(method, path)
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def test_preview_is_a_jpeg_instead_of_video_bytes(self):
        status, headers, body = self.request()
        self.assertEqual(200, status)
        self.assertEqual("image/jpeg", headers["Content-Type"])
        self.assertEqual("1", headers[PREVIEW_HEADER])
        self.assertEqual(str(len(body)), headers["Content-Length"])
        self.assertEqual(self.previews.get.return_value, body)
        self.previews.get.assert_called_once_with(os.path.realpath(Path(self.directory.name, self.name)))

    def test_head_checks_support_without_generating_a_frame(self):
        status, headers, body = self.request(method="HEAD")
        self.assertEqual(200, status)
        self.assertEqual("1", headers[PREVIEW_HEADER])
        self.assertEqual(b"", body)
        self.previews.get.assert_not_called()

    def test_listing_preserves_links_and_escapes_names(self):
        status, _, body = self.request("/")
        self.assertEqual(200, status)
        self.assertIn(b'href="clip%20%26%20%C3%A9.mp4"', body)
        self.assertIn(b'src="clip%20%26%20%C3%A9.mp4?scrtvwr-preview=1"', body)
        self.assertIn("clip &amp; \u00e9.mp4".encode(), body)
        self.assertIn(b'loading="lazy"', body)
        self.previews.get.assert_not_called()

    def test_missing_and_non_video_files_are_rejected(self):
        for name in ["missing.mp4", "image.jpg"]:
            with self.subTest(name=name):
                status, _, _ = self.request(f"/{name}?scrtvwr-preview=1")
                self.assertEqual(404, status)
        self.previews.get.assert_not_called()

    def test_decoder_failure_does_not_break_video_serving(self):
        self.previews.get.side_effect = subprocess.TimeoutExpired("ffmpeg", 20)
        status, _, _ = self.request()
        self.assertEqual(503, status)
        status, _, body = self.request("/clip%20%26%20%C3%A9.mp4")
        self.assertEqual(200, status)
        self.assertEqual(b"original video", body)


class VideoPreviewCacheTest(unittest.TestCase):
    def test_reuses_frame_until_video_changes_and_removes_temporary_files(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory, "video.mp4")
            source.write_bytes(b"video")
            outputs = []

            def generate(command, **kwargs):
                target = Path(command[-1])
                outputs.append(target)
                target.write_bytes(b"jpeg")

            with patch("tools.media_http_server.subprocess.run", side_effect=generate) as decoder:
                cache = VideoPreviewCache("ffmpeg")
                self.assertEqual(b"jpeg", cache.get(str(source)))
                self.assertEqual(b"jpeg", cache.get(str(source)))
                self.assertEqual(1, decoder.call_count)
                source.write_bytes(b"updated video")
                self.assertEqual(b"jpeg", cache.get(str(source)))
                self.assertEqual(2, decoder.call_count)
            self.assertTrue(all(not target.parent.exists() for target in outputs))

    def test_oversized_frame_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory, "video.mp4")
            source.write_bytes(b"video")

            def generate(command, **kwargs):
                Path(command[-1]).write_bytes(b"x" * (MAX_PREVIEW_BYTES + 1))

            with patch("tools.media_http_server.subprocess.run", side_effect=generate):
                with self.assertRaises(OSError):
                    VideoPreviewCache("ffmpeg").get(str(source))


if __name__ == "__main__":
    unittest.main()
