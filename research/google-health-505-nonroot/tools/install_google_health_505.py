#!/usr/bin/env python3
"""Select and install the re-signed Google Health 5.05 split APKs through ADB.

The script is dry-run by default. Pass --install --confirm-official-data-synced to
uninstall the official package and install the research build.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Sequence

PACKAGE = "com.fitbit.FitbitMobile"
DENSITIES = {
    "ldpi": 120,
    "mdpi": 160,
    "hdpi": 240,
    "xhdpi": 320,
    "xxhdpi": 480,
    "xxxhdpi": 640,
}


class InstallError(RuntimeError):
    pass


def run_capture(command: Sequence[str | os.PathLike[str]], *, check: bool = True) -> str:
    argv = [str(item) for item in command]
    completed = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=False)
    if check and completed.returncode != 0:
        raise InstallError(f"command failed ({completed.returncode}): {' '.join(argv)}\n{completed.stdout}")
    return completed.stdout.strip()


def parse_density(text: str) -> int:
    override = re.search(r"Override density:\s*(\d+)", text)
    physical = re.search(r"Physical density:\s*(\d+)", text)
    direct = re.search(r"\b(\d{3,4})\b", text)
    match = override or physical or direct
    return int(match.group(1)) if match else 480


def choose_density_split(signed_dir: Path, density: int) -> Path | None:
    available: list[tuple[int, Path]] = []
    for qualifier, dpi in DENSITIES.items():
        path = signed_dir / f"split_config.{qualifier}.apk"
        if path.is_file():
            available.append((dpi, path))
    if not available:
        return None
    return min(available, key=lambda item: abs(item[0] - density))[1]


def normalize_locale(value: str) -> str:
    value = value.strip().replace("_", "-")
    if not value or value.lower() in {"null", "undefined"}:
        return "en"
    return value.split(",", 1)[0].split("-", 1)[0].lower()


def select_splits(signed_dir: Path, adb: Path) -> list[Path]:
    base = signed_dir / "base.apk"
    arm64 = signed_dir / "split_config.arm64_v8a.apk"
    if not base.is_file() or not arm64.is_file():
        raise InstallError("signed directory must contain base.apk and split_config.arm64_v8a.apk")

    abi_list = run_capture([adb, "shell", "getprop", "ro.product.cpu.abilist"])
    if "arm64-v8a" not in abi_list:
        raise InstallError(f"device does not report arm64-v8a: {abi_list}")

    density_text = run_capture([adb, "shell", "wm", "density"], check=False)
    density = parse_density(density_text)
    locale = normalize_locale(run_capture([adb, "shell", "getprop", "persist.sys.locale"], check=False))

    selected = [base, arm64]
    density_split = choose_density_split(signed_dir, density)
    if density_split:
        selected.append(density_split)

    for language in dict.fromkeys(("en", locale)):
        split = signed_dir / f"split_config.{language}.apk"
        if split.is_file():
            selected.append(split)

    print(f"device ABI: {abi_list}")
    print(f"device density: {density}; selected: {density_split.name if density_split else 'base resources'}")
    print(f"device locale: {locale}")
    return selected


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--signed-dir", type=Path, required=True)
    parser.add_argument("--adb", type=Path)
    parser.add_argument("--install", action="store_true", help="Actually uninstall/install; default is dry-run")
    parser.add_argument(
        "--confirm-official-data-synced",
        action="store_true",
        help="Required with --install; confirms latest official-app data is synced to the cloud",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        signed_dir = args.signed_dir.expanduser().resolve()
        adb_value = args.adb.expanduser().resolve() if args.adb else None
        if adb_value and not adb_value.is_file():
            raise InstallError(f"adb not found: {adb_value}")
        adb = adb_value or (Path(shutil.which("adb")) if shutil.which("adb") else None)
        if adb is None:
            raise InstallError("adb not found in PATH; pass --adb")

        state = run_capture([adb, "get-state"])
        if state != "device":
            raise InstallError(f"ADB device state is not 'device': {state}")

        selected = select_splits(signed_dir, adb)
        print("selected split set:")
        for apk in selected:
            print(f"  {apk}")

        install_command = [adb, "install-multiple", "-r", "-d", *selected]
        print("\ninstall command:")
        print(" ".join(f'"{part}"' if " " in str(part) else str(part) for part in install_command))

        if not args.install:
            print("\ndry-run only. No package was changed.")
            return 0
        if not args.confirm_official_data_synced:
            raise InstallError(
                "refusing to uninstall the official app without --confirm-official-data-synced"
            )

        installed = run_capture([adb, "shell", "pm", "path", PACKAGE], check=False)
        if installed.startswith("package:"):
            print("uninstalling official/research package because the signing certificate will differ...")
            output = run_capture([adb, "uninstall", PACKAGE])
            print(output)

        output = run_capture(install_command)
        print(output)
        print(
            "\nInstalled. Open Google Health, manually enable its supported web links if Android asks, "
            "then complete the compatibility gate: sign in, pair Air, and perform two real syncs."
        )
        run_capture([
            adb,
            "shell",
            "monkey",
            "-p", PACKAGE,
            "-c", "android.intent.category.LAUNCHER",
            "1",
        ], check=False)
        return 0
    except InstallError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
