#!/usr/bin/env python3
"""Build signed model asset splits to install alongside a signed Vexto base APK."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from xml.etree import ElementTree as ET
from zipfile import ZIP_STORED, ZipFile

from prepare_quality_models import MANIFEST, contained, valid


ANDROID = "http://schemas.android.com/apk/res/android"
PASSWORD_ENV = "VEXTO_KEYSTORE_PASSWORD"
RELEASE_ASSET_LIMIT = 2**31
SPLIT_COUNT = 3


def output(*command: str | Path) -> str:
    return subprocess.check_output(command, text=True)


def base_attributes(aapt2: Path, base: Path) -> tuple[str, dict[str, str], dict[str, str]]:
    badging = output(aapt2, "dump", "badging", base)
    package = next(line for line in badging.splitlines() if line.startswith("package:"))
    attributes = dict(re.findall(r"([a-zA-Z]+)='([^']*)'", package))
    if attributes["name"] != "com.vexto.reflect" or "split" in attributes:
        raise ValueError("Expected the com.vexto.reflect base APK")
    versions = {f"{{{ANDROID}}}versionCode": attributes["versionCode"],
                f"{{{ANDROID}}}versionName": attributes["versionName"]}
    sdk = {}
    for attribute, source in (("minSdkVersion", "(?:minSdkVersion|sdkVersion)"),
                              ("targetSdkVersion", "targetSdkVersion")):
        match = re.search(rf"^{source}:'(\d+)'$", badging, re.MULTILINE)
        if not match:
            raise ValueError(f"Missing {attribute} in base APK")
        sdk[f"{{{ANDROID}}}{attribute}"] = match.group(1)
    return attributes["name"], versions, sdk


def certificate(apksigner: Path, apk: Path) -> str:
    result = output(apksigner, "verify", "--print-certs", apk)
    digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})$", result, re.MULTILINE)
    if len(digests) != 1:
        raise ValueError(f"Expected one verified signer on {apk.name}")
    return digests[0]


def model_groups(pack: Path) -> list[list[tuple[Path, str]]]:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if json.loads((pack / "manifest.json").read_text(encoding="utf-8")) != manifest:
        raise ValueError("Pack manifest differs from repository pins")
    # Both FAST and QUALITY assets are mandatory in every distributed pack.
    required_modes = {"whisper", "giga_encoder", "giga_decoder", "giga_joiner", "giga_tokens",
                      "whisper_fast", "giga_fast_model", "giga_fast_tokens"}
    if not required_modes.issubset(manifest["artifacts"]):
        raise ValueError("The offline pack must contain both processing modes")
    groups: list[list[tuple[Path, str]]] = [[] for _ in range(SPLIT_COUNT)]
    sizes = [0] * SPLIT_COUNT
    for name, artifact in sorted(manifest["artifacts"].items(),
                                 key=lambda item: (-item[1]["bytes"], item[0])):
        path = artifact["path"]
        file = contained(pack, path)
        if not valid(file, artifact):
            raise ValueError(f"Invalid model: {name}")
        index = sizes.index(min(sizes))
        sizes[index] += artifact["bytes"]
        if sizes[index] >= RELEASE_ASSET_LIMIT:
            raise ValueError(f"Model split {index + 1} exceeds the Release asset size limit")
        groups[index].append((file, f"assets/vexto-models/{path}"))
    return groups


def write_manifest(path: Path, number: int, package: str,
                   versions: dict[str, str], sdk: dict[str, str]) -> None:
    ET.register_namespace("android", ANDROID)
    root = ET.Element("manifest", {"package": package, "split": f"vexto_models_{number}", **versions})
    ET.SubElement(root, "uses-sdk", sdk)
    ET.SubElement(root, "application", {f"{{{ANDROID}}}hasCode": "false"})
    ET.ElementTree(root).write(path, encoding="utf-8", xml_declaration=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-apk", required=True, type=Path)
    parser.add_argument("--pack-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path, help="New directory for the signed splits")
    parser.add_argument("--android-home", required=True, type=Path)
    parser.add_argument("--keystore", required=True, type=Path)
    parser.add_argument("--key-alias", default="vexto")
    parser.add_argument("--notices-dir", type=Path)
    args = parser.parse_args()
    if not os.environ.get(PASSWORD_ENV):
        parser.error(f"Set {PASSWORD_ENV} before signing")
    if args.output_dir.exists():
        parser.error("--output-dir already exists")

    build_tools = args.android_home / "build-tools/35.0.0"
    aapt2 = build_tools / "aapt2"
    zipalign = build_tools / "zipalign"
    apksigner = build_tools / "apksigner"
    platform = args.android_home / "platforms/android-35/android.jar"
    package, versions, sdk = base_attributes(aapt2, args.base_apk)
    signer = certificate(apksigner, args.base_apk)
    groups = model_groups(args.pack_dir)
    if args.notices_dir is not None:
        entries = sorted(args.notices_dir.rglob("*"))
        notices = [file for file in entries if file.is_file()]
        if not notices or any(file.is_symlink() for file in entries):
            parser.error("--notices-dir must contain regular notice files")
        groups[0].extend((file, f"assets/vexto-model-notices/{file.relative_to(args.notices_dir).as_posix()}")
                         for file in notices)

    args.output_dir.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".model-splits-", dir=args.output_dir.parent) as temporary:
        work = Path(temporary)
        splits = work / "splits"
        splits.mkdir()
        for number, assets in enumerate(groups, 1):
            manifest = work / "AndroidManifest.xml"
            unsigned = work / "unsigned.apk"
            aligned = work / "aligned.apk"
            signed = splits / f"vexto-models-{number}.apk"
            write_manifest(manifest, number, package, versions, sdk)
            subprocess.run([aapt2, "link", "-I", platform, "--manifest", manifest, "-o", unsigned], check=True)
            with ZipFile(unsigned, "a", compression=ZIP_STORED, allowZip64=False) as apk:
                for file, destination in assets:
                    apk.write(file, destination)
            subprocess.run([zipalign, "-P", "16", "-f", "4", unsigned, aligned], check=True)
            unsigned.unlink()
            subprocess.run([apksigner, "sign", "--ks", args.keystore, "--ks-key-alias", args.key_alias,
                            "--ks-pass", f"env:{PASSWORD_ENV}", "--v4-signing-enabled", "false",
                            "--out", signed, aligned], check=True)
            aligned.unlink()
            subprocess.run([zipalign, "-c", "-P", "16", "4", signed], check=True)
            if signed.stat().st_size >= RELEASE_ASSET_LIMIT or certificate(apksigner, signed) != signer:
                raise ValueError(f"Invalid signed model split: {signed.name}")
            print(f"{signed.name}: verified, {signed.stat().st_size:,} bytes", flush=True)
        splits.rename(args.output_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
