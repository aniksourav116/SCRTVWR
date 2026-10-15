from functools import partial
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from pathlib import Path
import tempfile
import threading
import unittest

from tools.media_http_server import MediaRequestHandler, parse_byte_range


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


if __name__ == "__main__":
    unittest.main()
