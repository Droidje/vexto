# Vexto APK gateway

The public path `/vexto/download/vexto-offline.apk` streams the full APK from the fixed GHCR OCI artifact `droidje/vexto-offline:latest`. Source code can remain private. The artifact must have the contract validated by `scripts/publish_phone_oci.py`; a base APK or container tar layer is rejected. This service does not retain the APK on disk. Traffic passes through MGG, so its bandwidth is used.

Run tests: `python3 -m unittest discover -s ops/apk-download-gateway -p 'test_*.py'`.

Deploy the directory to the existing host, run `docker compose up -d --build`, and include `nginx-location.conf` inside only the existing `droidje.com` HTTPS server. Run `nginx -t` before a scoped reload. Preserve all unrelated server blocks and services. The container is non-root, read-only, limited to 128 MB / 32 PIDs, exposed only at host loopback port 18768. `/healthz` is liveness; test the actual download path to verify GHCR availability.

Public GHCR artifacts need no personal credential. If a private artifact must be served, provide `GITHUB_USER` and `GITHUB_TOKEN` to the container through the host's secret management; the token needs only `read:packages` and access to this package. Do not put values into compose, the repository, command-line arguments, URLs or logs. With a private package, the endpoint still intentionally publishes its APK to anyone who knows the public URL. The public endpoint never exposes registry credentials.

Metadata/token cache lives for at most 30 seconds and never falls back to an unverified or smaller APK. GHCR token acquisition and manifest requests are serialized. Blob downloads use fixed-size 256 KiB buffers, at most four active downloads and eight HTTP connections, 45-second socket idle timeouts and a one-hour streaming limit. A failed or interrupted stream is closed and can be resumed with a single `Range` request. `HEAD`, single byte ranges, suffix ranges, `If-Range`, SHA-based ETag, exact size and attachment filename are supported. Multi-ranges are rejected with 416. Upstream redirects must remain HTTPS on GHCR/GitHub container CDN/Azure Blob hosts; Authorization is removed across hosts.

Acceptance checks after publishing:

```sh
curl -I https://droidje.com/vexto/download/vexto-offline.apk
curl -fsS -r 0-15 https://droidje.com/vexto/download/vexto-offline.apk -o apk-prefix.bin
curl -fL -C - -o vexto-offline.apk https://droidje.com/vexto/download/vexto-offline.apk
sha256sum vexto-offline.apk
```

Compare the complete file hash with the immutable OCI layer descriptor / CI report. `Content-Length` for the full download must match that descriptor, `Content-Disposition` must end with `vexto-offline.apk`, and the ranged response must be 206. Full-file validation happens in CI before promoting `latest`; the gateway validates manifest provenance, media type, digest syntax, size and transport length, rather than buffering the full APK before sending it.
