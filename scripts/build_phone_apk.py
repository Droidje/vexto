#!/usr/bin/env python3
"""Sign one installable Vexto APK with the exact models from release splits."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile
from contextlib import ExitStack

from build_model_splits import PASSWORD_ENV, base_attributes, certificate
from prepare_quality_models import MANIFEST, ROOT

ASSETS = "assets/vexto-models/"
MARKER = ASSETS + "embedded"


def append_models(base: Path, splits: list[Path]) -> None:
    manifest_bytes = MANIFEST.read_bytes()
    models = {
        ASSETS + item["path"]: item
        for item in json.loads(manifest_bytes)["artifacts"].values()
    }
    # APK ZIP offsets are unsigned 32-bit. Python conservatively switches at 2 GiB,
    # while Android accepts ZIP32 offsets up to the reserved ZIP64 sentinel.
    zipfile.ZIP64_LIMIT = 0xFFFFFFFE
    with ExitStack() as stack:
        sources = [stack.enter_context(zipfile.ZipFile(path)) for path in splits]
        apk = stack.enter_context(zipfile.ZipFile(base, "a", allowZip64=False))
        if apk.read("assets/quality/manifest.json") != manifest_bytes:
            raise ValueError("APK manifest differs from the pinned model manifest")
        if any(name.startswith(ASSETS) for name in apk.namelist()):
            raise ValueError("Base APK already contains model assets")
        found = set()
        for source in sources:
            for entry in source.infolist():
                if entry.filename not in models:
                    continue
                name = entry.filename
                model = models[name]
                if name in found or entry.file_size != model["bytes"]:
                    raise ValueError(f"Duplicate or invalid model size: {name}")
                digest = hashlib.sha256()
                target = zipfile.ZipInfo(name)
                target.file_size = entry.file_size
                with source.open(entry) as data, apk.open(target, "w") as out:
                    for block in iter(lambda: data.read(1 << 20), b""):
                        digest.update(block)
                        out.write(block)
                if digest.hexdigest() != model["sha256"]:
                    raise ValueError(f"Invalid model checksum: {name}")
                found.add(name)
        if found != models.keys():
            raise ValueError("Missing models: " + ", ".join(sorted(models.keys() - found)))
        apk.writestr(MARKER, b"1\n")
        for notice in sorted((ROOT / "tools/model-notices").iterdir()):
            if notice.is_file():
                apk.write(notice, f"assets/vexto-model-notices/{notice.name}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-apk", type=Path, required=True)
    parser.add_argument("--split", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--keystore", type=Path, required=True)
    args = parser.parse_args()
    if not os.environ.get(PASSWORD_ENV):
        parser.error(f"Set {PASSWORD_ENV} before signing")
    if args.output.exists():
        parser.error("Output APK already exists")
    base_attributes(args.build_tools / "aapt2", args.base_apk)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".phone-apk-", dir=args.output.parent) as scratch:
        work = Path(scratch)
        unsigned, aligned, signed = (work / name for name in ("unsigned.apk", "aligned.apk", "signed.apk"))
        shutil.copyfile(args.base_apk, unsigned)
        append_models(unsigned, args.split)
        zipalign, apksigner = (args.build_tools / tool for tool in ("zipalign", "apksigner"))
        subprocess.run([zipalign, "-P", "16", "-f", "4", unsigned, aligned], check=True)
        unsigned.unlink()
        subprocess.run([apksigner, "sign", "--ks", args.keystore, "--ks-key-alias", "vexto",
                        "--ks-pass", f"env:{PASSWORD_ENV}", "--v4-signing-enabled", "false",
                        "--out", signed, aligned], check=True)
        aligned.unlink()
        subprocess.run([zipalign, "-c", "-P", "16", "4", signed], check=True)
        print("Verified signer:", certificate(apksigner, signed), flush=True)
        signed.rename(args.output)


if __name__ == "__main__":
    main()
