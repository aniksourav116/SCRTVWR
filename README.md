# SCRTVWR

Made this Media Viewer Application to brush up my Kotlin skills.
Nothing out of the ordinary here.

## Password-protected HTTP server

`python -m http.server 8000 --bind 0.0.0.0` lets anyone on the network read the folder.
`tools/auth_http_server.py` serves a folder the same way, but every request must sign in with a
username and password (HTTP Basic). It only needs the Python standard library.

```bash
python tools/auth_http_server.py 8000 --bind 0.0.0.0 --directory D:/Media --user alice
```

The script asks for the password, or reads it from the `SCRTVWR_HTTP_PASSWORD` environment
variable, so it stays out of the shell history. In SCRTVWR, add the address the script prints and
fill in the username and password.

Over plain `http://` the password is only base64-encoded, so anyone watching the network can read
it. To encrypt it, create a self-signed certificate once (Git for Windows ships `openssl`) and
serve HTTPS:

```bash
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -keyout key.pem -out cert.pem -subj "/CN=scrtvwr"
```

```bash
python tools/auth_http_server.py 8000 --bind 0.0.0.0 --directory D:/Media --user alice --cert cert.pem --key key.pem
```

Add the server in SCRTVWR with an `https://` address. Its card reports that the certificate is not
trusted. Tap **Review**, check that the fingerprint matches the `Certificate SHA-256` line the
script printed, and trust it. SCRTVWR then accepts only that exact certificate for the server, so
no IP address or host name needs to be in it. If the certificate changes, SCRTVWR stops and asks
again.
