#!/usr/bin/env python3
"""Bounded GHCR-to-browser APK streaming. No APK file is retained on disk."""
import base64
from dataclasses import dataclass
import json
import os
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PATH = "/vexto/download/vexto-offline.apk"
ARTIFACT_TYPE = "application/vnd.vexto.android-apk.v1"
APK_TYPE = "application/vnd.android.package-archive"
MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json"
SOURCE = "https://github.com/Droidje/vexto"
MAX_BYTES = 8 * 1024**3
CHUNK = 256 * 1024


class Unavailable(Exception):
    pass


def safe_url(url):
    parsed = urllib.parse.urlsplit(url)
    host = parsed.hostname or ""
    allowed = host in {"ghcr.io", "pkg-containers.githubusercontent.com"} or host.endswith(".blob.core.windows.net")
    if parsed.scheme != "https" or not allowed or parsed.username or parsed.password or parsed.port not in {None, 443}:
        raise Unavailable("Untrusted registry redirect")
    return parsed


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        target = urllib.parse.urljoin(request.full_url, newurl)
        old, new = safe_url(request.full_url), safe_url(target)
        redirected = super().redirect_request(request, fp, code, message, headers, target)
        if redirected is not None and request.get_method() == "HEAD":
            # Older Python releases changed redirected HEAD requests into GET.
            redirected.method = "HEAD"
        if redirected is not None and old.hostname != new.hostname:
            redirected.remove_header("Authorization")
        return redirected


class Transport:
    def __init__(self):
        self.opener = urllib.request.build_opener(SafeRedirect())

    def open(self, method, url, headers):
        safe_url(url)
        request = urllib.request.Request(url, method=method, headers=headers)
        try:
            return self.opener.open(request, timeout=45)
        except urllib.error.HTTPError as error:
            return error


def bounded_json(response, limit=128 * 1024):
    with response:
        if response.status != 200:
            raise Unavailable("Registry metadata unavailable")
        body = response.read(limit + 1)
        if len(body) > limit:
            raise Unavailable("Oversized registry metadata")
        try:
            return json.loads(body)
        except (ValueError, UnicodeError) as error:
            raise Unavailable("Invalid registry metadata") from error


@dataclass(frozen=True)
class APK:
    digest: str
    size: int
    revision: str
    run_id: str

    @property
    def etag(self):
        return '"' + self.digest + '"'


def validate_manifest(value):
    try:
        layers = value["layers"]
        annotation = value["annotations"]
        if value.get("schemaVersion") != 2 or value.get("mediaType") != MANIFEST_TYPE:
            raise ValueError()
        if value.get("artifactType") != ARTIFACT_TYPE or len(layers) != 1:
            raise ValueError()
        layer = layers[0]
        digest, size = layer["digest"], layer["size"]
        if layer.get("mediaType") != APK_TYPE or layer.get("annotations", {}).get("org.opencontainers.image.title") != "vexto-offline.apk":
            raise ValueError()
        if not isinstance(digest, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
            raise ValueError()
        if type(size) is not int or not 1 <= size <= MAX_BYTES:
            raise ValueError()
        revision, run_id = annotation["org.opencontainers.image.revision"], annotation["io.vexto.ci.run-id"]
        if not re.fullmatch(r"[0-9a-f]{40}", revision) or not re.fullmatch(r"[0-9]+", run_id):
            raise ValueError()
        if annotation.get("org.opencontainers.image.source") != SOURCE:
            raise ValueError()
        if annotation.get("io.vexto.apk.sha256") != digest[7:] or annotation.get("io.vexto.apk.size") != str(size):
            raise ValueError()
        return APK(digest, size, revision, run_id)
    except (KeyError, TypeError, ValueError, AttributeError) as error:
        raise Unavailable("Unverified APK manifest") from error


class Registry:
    def __init__(self, transport=None, token="", username="droidje", clock=time.monotonic):
        self.transport = transport or Transport()
        self.token, self.username, self.clock = token, username, clock
        self.lock = threading.Lock()
        self.cached = None
        self.expires = 0
        self.bearer = ""

    def metadata(self):
        with self.lock:
            if self.cached is not None and self.clock() < self.expires:
                return self.cached
            headers = {"User-Agent": "Vexto-APK-Gateway/1"}
            if self.token:
                credentials = base64.b64encode((self.username + ":" + self.token).encode()).decode()
                headers["Authorization"] = "Basic " + credentials
            url = "https://ghcr.io/token?" + urllib.parse.urlencode({
                "service": "ghcr.io", "scope": "repository:droidje/vexto-offline:pull",
            })
            token = bounded_json(self.transport.open("GET", url, headers), 16 * 1024)
            if not isinstance(token, dict):
                raise Unavailable("Registry token unavailable")
            bearer = token.get("token") or token.get("access_token")
            if not isinstance(bearer, str) or not bearer or len(bearer) > 12000:
                raise Unavailable("Registry token unavailable")
            headers = {"Authorization": "Bearer " + bearer, "Accept": MANIFEST_TYPE}
            manifest = bounded_json(self.transport.open("GET", "https://ghcr.io/v2/droidje/vexto-offline/manifests/latest", headers))
            apk = validate_manifest(manifest)
            self.bearer, self.cached = bearer, apk
            self.expires = self.clock() + 30
            return apk

    def blob(self, apk, method, byte_range=None):
        with self.lock:
            bearer = self.bearer
        headers = {"Authorization": "Bearer " + bearer, "Accept-Encoding": "identity"}
        if byte_range:
            headers["Range"] = byte_range
        return self.transport.open(method, "https://ghcr.io/v2/droidje/vexto-offline/blobs/" + apk.digest, headers)


def requested_range(header, size):
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", header)
    if not match or not any(match.groups()):
        raise ValueError("Unsupported range")
    first, last = match.groups()
    if not first:
        amount = int(last)
        if amount <= 0:
            raise ValueError("Empty suffix range")
        start, end = max(0, size - amount), size - 1
    else:
        start, end = int(first), min(int(last), size - 1) if last else size - 1
        if start >= size or end < start:
            raise ValueError("Unsatisfiable range")
    return start, end


class GatewayServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 8

    def __init__(self, address, registry, max_downloads=4):
        self.registry = registry
        self.downloads = threading.BoundedSemaphore(max_downloads)
        self.connections = threading.BoundedSemaphore(8)
        super().__init__(address, Handler)

    def process_request(self, request, client_address):
        if not self.connections.acquire(blocking=False):
            try:
                request.sendall(b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.connections.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.connections.release()

    def handle_error(self, _request, _client_address):
        # Avoid traceback URLs, request content and client-address logging.
        print("Gateway request failed", flush=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "VextoDownload"
    sys_version = ""

    def setup(self):
        super().setup()
        self.connection.settimeout(45)

    def log_message(self, *_args):
        # URLs can contain credentials or signed CDN query parameters upstream.
        pass

    def do_GET(self):
        self.download(False)

    def do_HEAD(self):
        self.download(True)

    def empty(self, code, extra=None):
        self.send_response(code)
        for name, value in (extra or {}).items():
            self.send_header(name, value)
        self.send_header("Content-Length", "0")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()

    def download(self, head):
        if self.path == "/healthz":
            self.empty(200)
            return
        if self.path != PATH:
            self.empty(404)
            return
        if not self.server.downloads.acquire(blocking=False):
            self.empty(503, {"Retry-After": "30"})
            return
        sent = False
        try:
            apk = self.server.registry.metadata()
            byte_range = None
            desired = None
            header = self.headers.get("Range")
            if header and self.headers.get("If-Range", apk.etag) == apk.etag:
                try:
                    desired = requested_range(header, apk.size)
                except ValueError:
                    self.empty(416, {"Content-Range": f"bytes */{apk.size}", "Accept-Ranges": "bytes"})
                    return
                byte_range = f"bytes={desired[0]}-{desired[1]}"
            with self.server.registry.blob(apk, "HEAD" if head else "GET", byte_range) as response:
                status = response.status
                length = apk.size
                if desired is not None:
                    expected_range = f"bytes {desired[0]}-{desired[1]}/{apk.size}"
                    if status != 206 or response.headers.get("Content-Range") != expected_range:
                        raise Unavailable("Registry did not honor the byte range")
                    length = desired[1] - desired[0] + 1
                elif status != 200:
                    raise Unavailable("Registry blob unavailable")
                if response.headers.get("Content-Encoding", "identity") != "identity" or response.headers.get("Content-Length") != str(length):
                    raise Unavailable("Registry blob length mismatch")
                self.send_response(status)
                headers = {"Content-Type": APK_TYPE, "Content-Length": str(length),
                           "Content-Disposition": 'attachment; filename="vexto-offline.apk"',
                           "Accept-Ranges": "bytes", "ETag": apk.etag,
                           "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
                           "X-Accel-Buffering": "no", "X-Vexto-Revision": apk.revision}
                if desired is not None:
                    headers["Content-Range"] = expected_range
                for name, value in headers.items():
                    self.send_header(name, value)
                self.end_headers()
                sent = True
                if not head:
                    remaining = length
                    deadline = time.monotonic() + 3600
                    while remaining:
                        if time.monotonic() > deadline:
                            raise Unavailable("Download time limit reached")
                        body = response.read(min(CHUNK, remaining))
                        if not body:
                            raise Unavailable("Truncated registry blob")
                        self.wfile.write(body)
                        remaining -= len(body)
        except (Unavailable, OSError, ValueError, urllib.error.URLError):
            if sent:
                self.close_connection = True
            else:
                self.empty(503, {"Retry-After": "30"})
        finally:
            self.server.downloads.release()


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "18768"))
    registry = Registry(token=os.environ.get("GITHUB_TOKEN", ""), username=os.environ.get("GITHUB_USER", "droidje"))
    GatewayServer((os.environ.get("BIND", "127.0.0.1"), port), registry).serve_forever()
