#!/usr/bin/env python3
"""Publish a verified full APK as a raw OCI blob, without Docker/tar wrapping.

Authentication belongs in ORAS's private registry config, never in CLI arguments.
This helper does not build or verify APK signatures/models; the caller must run
verify_android_apk.py --require-models before publication.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


ARTIFACT_TYPE = "application/vnd.vexto.android-apk.v1"
APK_TYPE = "application/vnd.android.package-archive"
APK_NAME = "vexto-offline.apk"
SOURCE = "https://github.com/Droidje/vexto"


def file_identity(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
            size += len(block)
    if not 0 < size < 10_000_000_000:
        raise ValueError("APK is empty or exceeds the GHCR layer size limit")
    return size, digest.hexdigest()


def validate_manifest(raw: bytes, *, size: int, sha256: str, source_sha: str) -> dict:
    value = json.loads(raw)
    layers = value.get("layers", [])
    if value.get("schemaVersion") != 2 or value.get("artifactType") != ARTIFACT_TYPE or len(layers) != 1:
        raise ValueError("Expected one raw APK layer in the Vexto OCI artifact")
    layer = layers[0]
    if (layer.get("mediaType") != APK_TYPE or layer.get("digest") != f"sha256:{sha256}"
            or layer.get("size") != size
            or layer.get("annotations", {}).get("org.opencontainers.image.title") != APK_NAME):
        raise ValueError("Published APK descriptor differs from the local file")
    annotations = value.get("annotations", {})
    expected = {
        "org.opencontainers.image.source": SOURCE,
        "org.opencontainers.image.revision": source_sha,
        "io.vexto.apk.sha256": sha256,
        "io.vexto.apk.size": str(size),
    }
    if any(annotations.get(key) != expected_value for key, expected_value in expected.items()):
        raise ValueError("Published APK provenance differs from the expected build")
    if not re.fullmatch(r"[0-9]+", annotations.get("io.vexto.ci.run-id", "")):
        raise ValueError("Published APK has no valid workflow provenance")
    return value


def fetch_manifest(oras: list[str], reference: str) -> bytes | None:
    result = subprocess.run(oras + ["manifest", "fetch", reference], capture_output=True)
    if result.returncode == 0:
        return result.stdout
    error = result.stderr.decode("utf-8", errors="replace").lower()
    if "manifest_unknown" in error or "manifest unknown" in error or "not found" in error:
        return None
    # Avoid printing registry diagnostics, which may include authentication data.
    raise RuntimeError("Cannot read the registry manifest; publication stopped")


def current_main_sha() -> str:
    result = subprocess.run(
        ["gh", "api", "repos/Droidje/vexto/git/ref/heads/main", "--jq", ".object.sha"],
        check=True, capture_output=True, text=True,
    )
    value = result.stdout.strip()
    if not re.fullmatch(r"[a-f0-9]{40}", value):
        raise RuntimeError("Cannot establish the current main source revision")
    return value


def publish(apk: Path, repository: str, source_sha: str, run_id: str,
            oras: list[str], promote_latest: bool, expected_main: str | None = None) -> dict:
    if not re.fullmatch(r"ghcr\.io/[a-z0-9][a-z0-9-]*/[a-z0-9][a-z0-9._-]*", repository):
        raise ValueError("Expected a lowercase GHCR owner/package reference")
    if not re.fullmatch(r"[a-f0-9]{40}", source_sha) or not re.fullmatch(r"[0-9]+", run_id):
        raise ValueError("Invalid trusted source SHA or workflow run ID")
    if promote_latest and (expected_main is None or not re.fullmatch(r"[a-f0-9]{40}", expected_main)):
        raise ValueError("Latest promotion requires the expected main source revision")
    if apk.name != APK_NAME or not apk.is_file():
        raise ValueError(f"Expected a saved {APK_NAME} file")
    size, digest = file_identity(apk)
    reference = f"{repository}:sha-{source_sha}"
    raw = fetch_manifest(oras, reference)
    reused = raw is not None
    if raw is None:
        annotations = {
            "org.opencontainers.image.source": SOURCE,
            "org.opencontainers.image.revision": source_sha,
            "io.vexto.apk.sha256": digest,
            "io.vexto.apk.size": str(size),
            "io.vexto.ci.run-id": run_id,
        }
        command = oras + ["push", "--artifact-type", ARTIFACT_TYPE]
        for name, value in annotations.items():
            command += ["--annotation", f"{name}={value}"]
        command += [reference, f"{APK_NAME}:{APK_TYPE}"]
        subprocess.run(command, cwd=apk.resolve().parent, check=True, stdout=sys.stderr)
        raw = fetch_manifest(oras, reference)
        if raw is None:
            raise RuntimeError("Uploaded artifact has no readable manifest")
    validate_manifest(raw, size=size, sha256=digest, source_sha=source_sha)
    manifest_digest = "sha256:" + hashlib.sha256(raw).hexdigest()
    promotion = "not-requested"
    if promote_latest:
        # Uploading a multi-GB blob can take minutes. Recheck only after it has
        # completed and been validated, immediately before changing the tag.
        if current_main_sha() != expected_main:
            promotion = "skipped-main-changed"
        else:
            subprocess.run(oras + ["tag", f"{repository}@{manifest_digest}", "latest"],
                           check=True, stdout=sys.stderr)
            latest = fetch_manifest(oras, f"{repository}:latest")
            if latest is None or hashlib.sha256(latest).digest() != hashlib.sha256(raw).digest():
                raise RuntimeError("Latest tag does not resolve to the verified APK manifest")
            promotion = "promoted"
    return {"repository": repository, "tag": reference, "source_sha": source_sha,
            "apk_sha256": digest, "apk_bytes": size, "manifest_digest": manifest_digest,
            "reused": reused, "latest": promotion == "promoted", "promotion": promotion}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--repository", default="ghcr.io/droidje/vexto-offline")
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--oras", default="oras")
    parser.add_argument("--registry-config", type=Path)
    parser.add_argument("--promote-latest", action="store_true")
    parser.add_argument("--expected-main", help="Main commit checked for APK compatibility; required for promotion")
    args = parser.parse_args()
    oras = [str(Path(args.oras).resolve())] if "/" in args.oras else [args.oras]
    if args.registry_config:
        oras += ["--registry-config", str(args.registry_config.resolve())]
    print(json.dumps(publish(args.apk, args.repository, args.source_sha, args.run_id, oras,
                             args.promote_latest, args.expected_main), sort_keys=True))


if __name__ == "__main__":
    main()
