#!/usr/bin/env python3
"""Serve a folder like `python -m http.server`, but only to clients that sign in.

Every GET and HEAD request must carry HTTP Basic credentials. With --cert and --key the server
speaks HTTPS, so those credentials are encrypted on the network. SCRTVWR trusts a self-signed
certificate once you confirm the SHA-256 fingerprint this script prints at startup.

    python tools/auth_http_server.py 8000 --bind 0.0.0.0 --user alice
    python tools/auth_http_server.py 8000 --bind 0.0.0.0 --user alice --cert cert.pem --key key.pem

The password is read from the SCRTVWR_HTTP_PASSWORD environment variable, or asked for, so it
never appears in the shell history or the process list. Only the standard library is needed.
"""

import argparse
import base64
import binascii
import functools
import getpass
import hashlib
import hmac
import http.server
import os
import socket
import ssl
import sys

PASSWORD_ENV = "SCRTVWR_HTTP_PASSWORD"
REALM = "SCRTVWR"
HANDSHAKE_TIMEOUT_SECONDS = 30


class AuthHandler(http.server.SimpleHTTPRequestHandler):
    """Directory handler that answers 401 unless the request carries the expected credentials."""

    def __init__(self, *args, user, password, **kwargs):
        # The base constructor handles the request, so the credentials must be set first.
        self._user = user.encode("utf-8")
        self._password = password.encode("utf-8")
        super().__init__(*args, **kwargs)

    def send_head(self):
        # do_GET and do_HEAD both start here, so this guards every file and listing.
        if not self._is_authorized():
            self.send_response(401)
            self.send_header("WWW-Authenticate", f'Basic realm="{REALM}", charset="UTF-8"')
            self.send_header("Content-Length", "0")
            self.end_headers()
            return None
        return super().send_head()

    def _is_authorized(self):
        scheme, _, token = self.headers.get("Authorization", "").partition(" ")
        if scheme.lower() != "basic":
            return False
        try:
            decoded = base64.b64decode(token.strip(), validate=True)
        except (binascii.Error, ValueError):
            return False
        user, separator, password = decoded.partition(b":")
        # Check both parts even when the first differs, so timing does not tell which was wrong.
        user_matches = hmac.compare_digest(user, self._user)
        password_matches = hmac.compare_digest(password, self._password)
        return bool(separator) and user_matches and password_matches


class AuthHTTPServer(http.server.ThreadingHTTPServer):
    """Threaded server that wraps each connection in TLS when [ssl_context] is set."""

    ssl_context = None

    def finish_request(self, request, client_address):
        if self.ssl_context is None:
            super().finish_request(request, client_address)
            return
        # The handshake runs on the connection's own thread, so a slow client cannot stall accept().
        request.settimeout(HANDSHAKE_TIMEOUT_SECONDS)
        try:
            tls = self.ssl_context.wrap_socket(request, server_side=True)
        except (ssl.SSLError, OSError):
            # Expected when SCRTVWR meets a certificate it does not trust yet: it reads the
            # certificate to show its fingerprint, then aborts the handshake.
            return
        # Like plain http.server, wait as long as a paused video player needs once connected.
        tls.settimeout(None)
        with tls:
            super().finish_request(tls, client_address)


def read_password():
    password = os.environ.get(PASSWORD_ENV)
    if password:
        return password
    while True:
        password = getpass.getpass("Password: ")
        if not password:
            print("The password cannot be empty.")
        elif getpass.getpass("Repeat password: ") != password:
            print("The passwords do not match.")
        else:
            return password


def certificate_fingerprint(cert_path):
    """SHA-256 of the first certificate in [cert_path], formatted the way SCRTVWR shows it."""
    with open(cert_path, encoding="ascii") as file:
        text = file.read()
    start = text.find(ssl.PEM_HEADER)
    end = text.find(ssl.PEM_FOOTER, start)
    if start < 0 or end < 0:
        raise ValueError(f"{cert_path} does not contain a PEM certificate")
    der = ssl.PEM_cert_to_DER_cert(text[start:end + len(ssl.PEM_FOOTER)])
    digest = hashlib.sha256(der).hexdigest().upper()
    return ":".join(digest[index:index + 2] for index in range(0, len(digest), 2))


def lan_address():
    """This computer's address on its default network, or None when there is no route."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        try:
            # Connecting a UDP socket only picks a route; no packet is sent.
            probe.connect(("192.0.2.1", 80))
            return probe.getsockname()[0]
        except OSError:
            return None


def main():
    parser = argparse.ArgumentParser(
        description="Serve a folder like `python -m http.server`, but require a username and password.",
    )
    parser.add_argument("port", nargs="?", type=int, default=8000, help="port to listen on (default: 8000)")
    parser.add_argument(
        "-b", "--bind", default="0.0.0.0", metavar="ADDRESS",
        help="address to listen on (default: all IPv4 interfaces)",
    )
    parser.add_argument("-d", "--directory", default=os.getcwd(), help="folder to serve (default: current folder)")
    parser.add_argument("-u", "--user", required=True, help="username clients sign in with")
    parser.add_argument("--cert", help="PEM certificate; serves HTTPS together with --key")
    parser.add_argument("--key", help="PEM private key for --cert")
    args = parser.parse_args()

    if not args.user or ":" in args.user:
        parser.error("the username must not be empty or contain ':'")
    if bool(args.cert) != bool(args.key):
        parser.error("--cert and --key must be given together")
    if not os.path.isdir(args.directory):
        parser.error(f"{args.directory} is not a folder")

    context = None
    fingerprint = None
    if args.cert:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(args.cert, args.key)
        fingerprint = certificate_fingerprint(args.cert)

    password = read_password()
    handler = functools.partial(
        AuthHandler,
        directory=args.directory,
        user=args.user,
        password=password,
    )
    with AuthHTTPServer((args.bind, args.port), handler) as server:
        server.ssl_context = context
        scheme = "https" if context else "http"
        host = (lan_address() if args.bind in ("", "0.0.0.0") else None) or args.bind
        print(f"Serving {os.path.abspath(args.directory)} to user {args.user!r}")
        print(f"Add this address in SCRTVWR: {scheme}://{host}:{args.port}/")
        if fingerprint:
            print(f"Certificate SHA-256: {fingerprint}")
            print("Trust the certificate in SCRTVWR only if it shows this same fingerprint.")
        else:
            print(
                "Warning: without --cert and --key the password crosses the network unencrypted, "
                "so anyone watching the network can read it.",
            )
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\nStopped.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
