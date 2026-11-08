import copy
import http.client
import io
import json
import threading
import unittest
import urllib.request

import gateway


def manifest(size=16):
    digest = "a" * 64
    return {
        "schemaVersion": 2, "mediaType": gateway.MANIFEST_TYPE,
        "artifactType": gateway.ARTIFACT_TYPE,
        "layers": [{"mediaType": gateway.APK_TYPE, "digest": "sha256:" + digest, "size": size,
                    "annotations": {"org.opencontainers.image.title": "vexto-offline.apk"}}],
        "annotations": {"org.opencontainers.image.source": gateway.SOURCE,
                        "org.opencontainers.image.revision": "b" * 40,
                        "io.vexto.apk.sha256": digest, "io.vexto.apk.size": str(size),
                        "io.vexto.ci.run-id": "12345"},
    }


class Response(io.BytesIO):
    def __init__(self, body=b"", status=200, headers=None):
        super().__init__(body)
        self.status, self.headers = status, headers or {}


class FakeTransport:
    def __init__(self, value=None):
        self.value = value or manifest()
        self.calls = []

    def open(self, method, url, headers):
        self.calls.append((method, url, headers))
        value = {"token": "fixture-token"} if "/token?" in url else self.value
        return Response(json.dumps(value).encode())


class RegistryTest(unittest.TestCase):
    def test_manifest_contract(self):
        self.assertEqual(16, gateway.validate_manifest(manifest()).size)
        for change in [
            lambda value: value.update(artifactType="container"),
            lambda value: value["layers"][0].update(mediaType="application/x-tar"),
            lambda value: value["layers"][0].update(size=True),
            lambda value: value["layers"][0].update(size=gateway.MAX_BYTES + 1),
            lambda value: value["layers"][0].update(digest="sha256:not-a-digest"),
            lambda value: value["layers"].append(copy.deepcopy(value["layers"][0])),
            lambda value: value["annotations"].update({"org.opencontainers.image.source": "https://example.com"}),
            lambda value: value["annotations"].update({"io.vexto.apk.size": "17"}),
            lambda value: value["annotations"].update({"io.vexto.apk.sha256": "0" * 64}),
        ]:
            value = manifest()
            change(value)
            with self.assertRaises(gateway.Unavailable):
                gateway.validate_manifest(value)
        for value in [None, [], {"schemaVersion": 2}]:
            with self.assertRaises(gateway.Unavailable):
                gateway.validate_manifest(value)

    def test_metadata_cache_expires_and_auth_stays_at_registry(self):
        now = [1]
        transport = FakeTransport()
        registry = gateway.Registry(transport, token="fixture-read-token", clock=lambda: now[0])
        first = registry.metadata()
        self.assertIs(first, registry.metadata())
        self.assertEqual(2, len(transport.calls))
        self.assertTrue(transport.calls[0][2]["Authorization"].startswith("Basic "))
        self.assertEqual("Bearer fixture-token", transport.calls[1][2]["Authorization"])
        now[0] = 32
        registry.metadata()
        self.assertEqual(4, len(transport.calls))

    def test_metadata_errors_do_not_use_expired_snapshot(self):
        now = [1]
        transport = FakeTransport()
        registry = gateway.Registry(transport, clock=lambda: now[0])
        registry.metadata()
        now[0] = 32
        transport.value = {"error": "unavailable"}
        with self.assertRaises(gateway.Unavailable):
            registry.metadata()

    def test_redirect_strips_auth_and_rejects_untrusted_targets(self):
        request = urllib.request.Request("https://ghcr.io/v2/blob", headers={"Authorization": "Bearer fixture", "Range": "bytes=1-4"})
        redirect = gateway.SafeRedirect()
        same = redirect.redirect_request(request, None, 302, "", {}, "https://ghcr.io/other")
        self.assertEqual("Bearer fixture", same.get_header("Authorization"))
        cdn = redirect.redirect_request(request, None, 302, "", {}, "https://pkg-containers.githubusercontent.com/blob?signature=fixture")
        self.assertIsNone(cdn.get_header("Authorization"))
        self.assertEqual("bytes=1-4", cdn.get_header("Range"))
        head = urllib.request.Request("https://ghcr.io/v2/blob", method="HEAD")
        self.assertEqual("HEAD", redirect.redirect_request(head, None, 307, "", {}, "https://pkg-containers.githubusercontent.com/blob").get_method())
        for url in ["http://ghcr.io/blob", "https://example.com/blob", "https://ghcr.io.evil.example/blob", "https://user@ghcr.io/blob", "https://ghcr.io:444/blob"]:
            with self.assertRaises(gateway.Unavailable):
                redirect.redirect_request(request, None, 302, "", {}, url)


class FakeRegistry:
    def __init__(self):
        self.body = b"0123456789abcdef"
        self.apk = gateway.validate_manifest(manifest(len(self.body)))
        self.calls = []
        self.fail = False
        self.truncate = False
        self.bad_range = False

    def metadata(self):
        if self.fail:
            raise gateway.Unavailable()
        return self.apk

    def blob(self, apk, method, byte_range=None):
        self.calls.append((method, byte_range))
        body, status = self.body, 200
        headers = {"Content-Length": str(len(body))}
        if byte_range:
            start, end = gateway.requested_range(byte_range, len(body))
            body, status = body[start:end + 1], 206
            headers = {"Content-Length": str(len(body)), "Content-Range": f"bytes {start}-{end}/{len(self.body)}"}
            if self.bad_range:
                headers["Content-Range"] = "bytes 0-1/2"
        if self.truncate:
            body = body[:3]
        return Response(b"" if method == "HEAD" else body, status, headers)


class HTTPTest(unittest.TestCase):
    def setUp(self):
        self.registry = FakeRegistry()
        self.server = gateway.GatewayServer(("127.0.0.1", 0), self.registry)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)

    def request(self, method="GET", path=gateway.PATH, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        connection.request(method, path, headers=headers or {})
        response = connection.getresponse()
        try:
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def test_get_head_and_range_have_browser_download_headers(self):
        status, headers, body = self.request()
        self.assertEqual((200, self.registry.body), (status, body))
        self.assertEqual(str(len(body)), headers["Content-Length"])
        self.assertEqual('attachment; filename="vexto-offline.apk"', headers["Content-Disposition"])
        self.assertEqual(gateway.APK_TYPE, headers["Content-Type"])
        self.assertEqual("no", headers["X-Accel-Buffering"])
        status, headers, body = self.request("HEAD")
        self.assertEqual((200, b""), (status, body))
        self.assertEqual("16", headers["Content-Length"])
        status, headers, body = self.request(headers={"Range": "bytes=4-8", "If-Range": self.registry.apk.etag})
        self.assertEqual((206, b"45678"), (status, body))
        self.assertEqual("bytes 4-8/16", headers["Content-Range"])

    def test_suffix_open_and_if_range_changed(self):
        self.assertEqual(b"cdef", self.request(headers={"Range": "bytes=-4"})[2])
        self.assertEqual(b"cdef", self.request(headers={"Range": "bytes=12-"})[2])
        self.assertEqual((200, self.registry.body), self.request(headers={"Range": "bytes=4-8", "If-Range": '"old-sha"'})[::2])

    def test_invalid_and_multi_ranges_rejected(self):
        for header in ["bytes=30-", "bytes=5-2", "bytes=-0", "bytes=0-1,4-5", "items=0-4"]:
            status, headers, body = self.request(headers={"Range": header})
            self.assertEqual((416, b""), (status, body))
            self.assertEqual("bytes */16", headers["Content-Range"])

    def test_unavailable_invalid_upstream_and_saturation(self):
        self.registry.fail = True
        self.assertEqual(503, self.request()[0])
        self.registry.fail = False
        self.registry.bad_range = True
        self.assertEqual(503, self.request(headers={"Range": "bytes=0-2"})[0])
        for _ in range(4):
            self.assertTrue(self.server.downloads.acquire(False))
        try:
            self.assertEqual(503, self.request()[0])
        finally:
            for _ in range(4):
                self.server.downloads.release()
        self.assertEqual(200, self.request()[0])

    def test_truncated_transfer_can_resume(self):
        self.registry.truncate = True
        with self.assertRaises(http.client.IncompleteRead) as failure:
            self.request()
        self.assertEqual(b"012", failure.exception.partial)
        self.registry.truncate = False
        status, _, resumed = self.request(headers={"Range": "bytes=3-"})
        self.assertEqual(206, status)
        self.assertEqual(self.registry.body, failure.exception.partial + resumed)

    def test_fixed_route_and_liveness(self):
        self.assertEqual(200, self.request(path="/healthz")[0])
        for path in [gateway.PATH + "?url=https://example.com", "/", "/vexto/download/other.apk"]:
            self.assertEqual(404, self.request(path=path)[0])

    def test_head_supports_multi_gigabyte_size_without_reading_a_body(self):
        size = 4_250_206_121
        self.registry.apk = gateway.validate_manifest(manifest(size))
        response = Response(headers={"Content-Length": str(size)})
        response.read = lambda *_args: self.fail("HEAD must not read the APK")
        self.registry.blob = lambda *_args: response
        status, headers, body = self.request("HEAD")
        self.assertEqual((200, b""), (status, body))
        self.assertEqual(str(size), headers["Content-Length"])


if __name__ == "__main__":
    unittest.main()
