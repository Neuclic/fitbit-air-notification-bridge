#!/usr/bin/env python3
"""Install a same-certificate Google Health 5.05 research update without uninstalling app data.

Use this only after the re-sign-only control is already installed and has passed the compatibility
gate. The Gadget build must be signed with the exact same research keystore. This script never
uninstalls the package; a signature mismatch or install failure stops safely.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

import install_google_health_505 as installer

PACKAGE = "com.fitbit.FitbitMobile"


class UpdateError(RuntimeError):
    pass


def capture(argv: list[str], *, check: bool = True) -> str:
    completed = subprocess.run(
        argv,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        check=False,
    )
    if check and completed.returncode != 0:
        raise UpdateError(
            f"command failed ({completed.returncode}): {' '.join(argv)}\n{completed.stdout}"
        )
    return completed.stdout.strip()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--signed-dir", type=Path, required=True)
    parser.add_argument("--adb", type=Path)
    parser.add_argument("--install", action="store_true", help="Actually update; default is dry-run")
    parser.add_argument(
        "--confirm-control-passed",
        action="store_true",
        help="Required with --install; confirms the re-sign-only control passed sign-in/pair/sync",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        signed_dir = args.signed_dir.expanduser().resolve()
        adb_value = args.adb.expanduser().resolve() if args.adb else None
        if adb_value and not adb_value.is_file():
            raise UpdateError(f"adb not found: {adb_value}")
        adb = adb_value or (Path(shutil.which("adb")) if shutil.which("adb") else None)
        if adb is None:
            raise UpdateError("adb not found in PATH; pass --adb")

        if capture([str(adb), "get-state"]) != "device":
            raise UpdateError("ADB device is not ready")
        installed = capture([str(adb), "shell", "pm", "path", PACKAGE], check=False)
        if not installed.startswith("package:"):
            raise UpdateError(
                "Google Health is not installed. Install and validate the re-sign-only control first."
            )

        selected = installer.select_splits(signed_dir, adb)
        command = [str(adb), "install-multiple", "-r", "-d", *map(str, selected)]
        print("selected same-signer update set:")
        for apk in selected:
            print(f"  {apk}")
        print("\nupdate command:")
        print(" ".join(f'"{item}"' if " " in item else item for item in command))

        if not args.install:
            print("\ndry-run only. The installed package and its data were not changed.")
            return 0
        if not args.confirm_control_passed:
            raise UpdateError("refusing update without --confirm-control-passed")

        output = capture(command)
        print(output)
        print(
            "\nResearch update installed in place. Existing research-signed app data should remain. "
            "If the command reports a signature mismatch, stop: do not uninstall automatically."
        )
        return 0
    except (UpdateError, installer.InstallError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
