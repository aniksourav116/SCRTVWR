import base64
from functools import partial
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from pathlib import Path
import socket
import tempfile
import threading
import time
import unittest

from tools.media_http_server import BasicCredentials, MediaRequestHandler, parse_byte_range


class ByteRangeTest(unittest.TestCase):
    def test_large_offsets_do_not_overflow(self):
        size = 5 * 1024**3
        start = 4 * 1024**3
        self.assertEqual((start, size - 1), parse_byte_range(f"bytes={start}-", size))

    def test_suffix_and_clipped_end(self):
        self.assertEqual((90, 99), parse_byte_range("bytes=-10", 100))
        self.assertEqual((0, 99), parse_byte_range("bytes=-200", 100))
        self.assertEqual((95, 99), parse_byte_range("bytes=95-200", 100))

    def test_unit_is_case_insensitive(self):
        self.assertEqual((0, 9), parse_byte_range("Bytes=0-9", 100))

    def test_unsatisfiable_ranges(self):
        for value, size in [("bytes=100-", 100), ("bytes=-0", 100), ("bytes=0-", 0), ("bytes=-5", 0)]:
            with self.subTest(value=value, size=size):
                with self.assertRaises(ValueError):
                    parse_byte_range(value, size)

    def test_malformed_ranges_are_ignored(self):
        for value in ["bytes=20-10", "bytes=-", "bytes=abc", "items=0-5", "bytes=0-1,5-6", "0-5"]:
            with self.subTest(value=value):
                self.assertIsNone(parse_byte_range(value, 100))


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

    def test_malformed_range_sends_full_file(self):
        for value in ["bytes=20-10", "bytes=abc", "bytes=0-1,5-6"]:
            with self.subTest(value=value):
                status, headers, body = self.request(headers={"Range": value})
                self.assertEqual(200, status)
                self.assertNotIn("Content-Range", headers)
                self.assertEqual(self.payload, body)

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


class AuthenticationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.payload = bytes(range(256)) * 4
        Path(cls.directory.name, "video.mp4").write_bytes(cls.payload)
        cls.servers = []

    @classmethod
    def tearDownClass(cls):
        for server, thread in cls.servers:
            server.shutdown()
            server.server_close()
            thread.join()
        cls.directory.cleanup()

    def start(self, credentials):
        handler = partial(QuietHandler, directory=self.directory.name, credentials=credentials)
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.servers.append((server, thread))
        return server.server_address

    def request(self, address, method="GET", path="/video.mp4", headers=None):
        connection = HTTPConnection(*address, timeout=5)
        try:
            connection.request(method, path, headers=headers or {})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    @staticmethod
    def basic(username, password):
        return {"Authorization": "Basic " + base64.b64encode(f"{username}:{password}".encode()).decode()}

    def test_missing_credentials_get_a_browser_challenge(self):
        address = self.start(BasicCredentials("secret"))
        for method in ["GET", "HEAD"]:
            with self.subTest(method=method):
                status, headers, body = self.request(address, method=method)
                self.assertEqual(401, status)
                self.assertTrue(headers["WWW-Authenticate"].startswith("Basic "))
                self.assertNotIn(self.payload, body)
        status, _, _ = self.request(address, path="/")
        self.assertEqual(401, status)

    def test_any_username_with_the_password_is_accepted(self):
        address = self.start(BasicCredentials("pässword"))
        status, _, body = self.request(address, headers=self.basic("anyone", "pässword"))
        self.assertEqual(200, status)
        self.assertEqual(self.payload, body)
        status, headers, body = self.request(address, headers={**self.basic("", "pässword"), "Range": "bytes=0-9"})
        self.assertEqual(206, status)
        self.assertEqual(self.payload[:10], body)

    def test_wrong_or_malformed_credentials_are_rejected(self):
        address = self.start(BasicCredentials("secret", username="media"))
        for headers in [
            self.basic("media", "wrong"),
            self.basic("other", "secret"),
            {"Authorization": "Bearer secret"},
            {"Authorization": "Basic not-base64!"},
            {"Authorization": "Basic " + base64.b64encode(b"no-separator").decode()},
        ]:
            with self.subTest(headers=headers):
                status, _, _ = self.request(address, headers=headers)
                self.assertEqual(401, status)
        status, _, _ = self.request(address, headers=self.basic("media", "secret"))
        self.assertEqual(200, status)

    def test_password_containing_a_colon(self):
        address = self.start(BasicCredentials("a:b"))
        status, _, _ = self.request(address, headers=self.basic("user", "a:b"))
        self.assertEqual(200, status)


class QuietHandler(MediaRequestHandler):
    def log_message(self, format, *args):
        pass


class QuietShortTimeoutHandler(MediaRequestHandler):
    timeout = 0.2

    def log_message(self, format, *args):
        pass


class TimeoutTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        # Larger than the socket buffers, so sending it blocks while the client is not reading.
        cls.payload = bytes(range(256)) * (64 * 1024)
        Path(cls.directory.name, "video.mp4").write_bytes(cls.payload)
        handler = partial(QuietShortTimeoutHandler, directory=cls.directory.name)
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()
        cls.directory.cleanup()

    def connect(self):
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
        client.settimeout(10)
        client.connect(self.server.server_address)
        return client

    def test_idle_connection_is_closed(self):
        with self.connect() as client:
            self.assertEqual(b"", client.recv(1))

    def test_paused_reader_receives_whole_body(self):
        with self.connect() as client:
            client.sendall(b"GET /video.mp4 HTTP/1.0\r\nRange: bytes=0-\r\n\r\n")
            time.sleep(QuietShortTimeoutHandler.timeout * 5)
            response = bytearray()
            while block := client.recv(1024 * 1024):
                response += block
        head, _, body = bytes(response).partition(b"\r\n\r\n")
        self.assertTrue(head.startswith(b"HTTP/1.0 206"))
        self.assertEqual(len(self.payload), len(body))
        self.assertEqual(self.payload, body)


if __name__ == "__main__":
    unittest.main()
