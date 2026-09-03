#!/usr/bin/env python3
"""Build a re-sign-only Google Health 5.06.1 control split set.

The control keeps the exact Google Health manifest and DEX contents. It only removes the original
APK signatures, normalizes the ZIP container as required, zipaligns, and signs every split with the
research certificate. Use it before the Gadget build to distinguish signing/integrity/login
failures from Gadget loading or instrumentation detection.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

import patch_google_health_506 as core


def rewrite_without_code_changes(source: Path, destination: Path) -> None:
    with zipfile.ZipFile(source, "r") as incoming, zipfile.ZipFile(
        destination, "w", allowZip64=True
    ) as outgoing:
        for info in incoming.infolist():
            if core.is_old_v1_signature(info.filename):
                continue
            outgoing.writestr(core.copy_zip_info(info), incoming.read(info.filename))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apkm", type=Path, required=True, help="Exact Google Health 5.06.1 APKM")
    parser.add_argument("--output", type=Path, required=True, help="Output directory")
    parser.add_argument("--build-tools", type=Path, help="Android SDK build-tools version directory")
    parser.add_argument("--keystore", type=Path, help="Research keystore; generated if absent")
    parser.add_argument("--alias", default="fba-research")
    parser.add_argument("--store-password", default="fitbitair")
    parser.add_argument("--key-password", default="fitbitair")
    parser.add_argument("--keytool", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        apkm = args.apkm.expanduser().resolve()
        output = args.output.expanduser().resolve()
        if not apkm.is_file():
            raise core.PatchError(f"APKM not found: {apkm}")

        build_tools = core.find_build_tools(args.build_tools)
        keytool = core.find_executable("keytool", args.keytool)
        output.mkdir(parents=True, exist_ok=True)
        signed_dir = output / "signed"
        if signed_dir.exists():
            shutil.rmtree(signed_dir)
        signed_dir.mkdir(parents=True)
        keystore = (args.keystore or (output / "fba-research.jks")).expanduser().resolve()

        with tempfile.TemporaryDirectory(prefix="gh506-control-") as temp_name:
            work = Path(temp_name)
            extracted = work / "apkm"
            unsigned_dir = work / "unsigned"
            extracted.mkdir()
            unsigned_dir.mkdir()
            with zipfile.ZipFile(apkm) as archive:
                archive.extractall(extracted)
            core.validate_apkm(extracted)
            core.log("exact Google Health 5.06.1 APKM validated for re-sign-only control")

            apk_paths = sorted(extracted.glob("*.apk"))
            if not apk_paths:
                raise core.PatchError("no APK files found in APKM")
            for source_apk in apk_paths:
                rewrite_without_code_changes(source_apk, unsigned_dir / source_apk.name)

            core.ensure_keystore(
                keystore,
                args.alias,
                args.store_password,
                args.key_password,
                keytool,
            )
            for unsigned_apk in sorted(unsigned_dir.glob("*.apk")):
                core.sign_apk(
                    unsigned_apk,
                    signed_dir / unsigned_apk.name,
                    build_tools,
                    keystore,
                    args.alias,
                    args.store_password,
                    args.key_password,
                    work,
                )

        metadata = {
            "mode": "resign-control",
            "package": core.PACKAGE_NAME,
            "versionCode": core.EXPECTED_VERSION_CODE,
            "versionName": core.EXPECTED_VERSION_NAME,
            "manifestOrDexModified": False,
            "gadgetEmbedded": False,
            "signedApkCount": len(list(signed_dir.glob("*.apk"))),
            "keystore": str(keystore),
        }
        (output / "build-info.json").write_text(
            json.dumps(metadata, indent=2, ensure_ascii=False), encoding="utf-8"
        )
        core.log(f"re-sign-only control complete: {signed_dir}")
        core.log("Use this control for sign-in/pair/sync validation before installing Gadget.")
        return 0
    except (core.PatchError, OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"[gh506-control] ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
