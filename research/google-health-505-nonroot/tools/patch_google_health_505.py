#!/usr/bin/env python3
"""Build a non-root, research-only Google Health 5.05 APK split set with Frida Gadget.

This tool intentionally does not download or redistribute Google Health, Frida Gadget,
or signing keys. The operator supplies the exact APKM and an official arm64 Gadget binary.

It performs four narrowly-scoped modifications:
  1. Replaces the manifest Application class with an equal-length wrapper class name.
  2. Sets android:extractNativeLibs=true in the binary AndroidManifest.xml.
  3. Clears ACC_FINAL from the original FitbitRoot_Application class in classes.dex.
  4. Adds a small wrapper DEX and Frida Gadget/config, then re-signs every split.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

PACKAGE_NAME = "com.fitbit.FitbitMobile"
EXPECTED_VERSION_CODE = 1104094185
EXPECTED_VERSION_NAME = "5.05.health-mobile-1104094185-956655267"
ORIGINAL_APPLICATION = "com.fitbit.FitbitRoot_Application"
WRAPPER_APPLICATION = "com.fitbit.FitbitHook_Application"
ORIGINAL_APPLICATION_DESCRIPTOR = "Lcom/fitbit/FitbitRoot_Application;"
GADGET_LIB_NAME = "libfba_bridge.so"
GADGET_CONFIG_NAME = "libfba_bridge.config.so"
GADGET_SPLIT_PATH = f"lib/arm64-v8a/{GADGET_LIB_NAME}"
GADGET_CONFIG_SPLIT_PATH = f"lib/arm64-v8a/{GADGET_CONFIG_NAME}"

RES_STRING_POOL_TYPE = 0x0001
RES_XML_START_ELEMENT_TYPE = 0x0102
UTF8_FLAG = 0x00000100
TYPE_INT_BOOLEAN = 0x12
ACC_FINAL = 0x0010

SIGNATURE_SUFFIXES = (".RSA", ".DSA", ".EC", ".SF")


class PatchError(RuntimeError):
    pass


@dataclass(frozen=True)
class BuildTools:
    root: Path
    d8: Path
    zipalign: Path
    apksigner: Path


def log(message: str) -> None:
    print(f"[gh505-nonroot] {message}")


def run(command: Sequence[str | os.PathLike[str]], *, cwd: Path | None = None) -> None:
    argv = [str(item) for item in command]
    shown = " ".join(f'"{part}"' if " " in part else part for part in argv)
    log(f"run: {shown}")
    if os.name == "nt" and Path(argv[0]).suffix.lower() in {".bat", ".cmd"}:
        command_line = subprocess.list2cmdline(argv)
        argv = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/s", "/c", command_line]
    completed = subprocess.run(argv, cwd=cwd, check=False)
    if completed.returncode != 0:
        raise PatchError(f"command failed with exit code {completed.returncode}: {shown}")


def find_executable(name: str, explicit: Path | None = None) -> Path:
    if explicit is not None:
        candidate = explicit.expanduser().resolve()
        if candidate.is_file():
            return candidate
        raise PatchError(f"tool not found: {candidate}")

    path = shutil.which(name)
    if path:
        return Path(path).resolve()
    if os.name == "nt":
        path = shutil.which(f"{name}.exe") or shutil.which(f"{name}.bat")
        if path:
            return Path(path).resolve()
    raise PatchError(f"{name} not found in PATH")


def version_key(path: Path) -> tuple[int, ...]:
    values = []
    for part in re.split(r"[^0-9]+", path.name):
        if part:
            values.append(int(part))
    return tuple(values)


def find_build_tools(explicit: Path | None) -> BuildTools:
    candidates: list[Path] = []
    if explicit is not None:
        candidates.append(explicit.expanduser())
    else:
        for env_name in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
            value = os.environ.get(env_name)
            if value:
                candidates.extend((Path(value) / "build-tools").glob("*"))
        if os.name == "nt":
            local_app_data = os.environ.get("LOCALAPPDATA")
            if local_app_data:
                candidates.extend((Path(local_app_data) / "Android" / "Sdk" / "build-tools").glob("*"))
        candidates.extend((Path.home() / "Android" / "Sdk" / "build-tools").glob("*"))

    tool_names = {
        "d8": ("d8.bat", "d8") if os.name == "nt" else ("d8", "d8.bat"),
        "zipalign": ("zipalign.exe", "zipalign") if os.name == "nt" else ("zipalign", "zipalign.exe"),
        "apksigner": ("apksigner.bat", "apksigner") if os.name == "nt" else ("apksigner", "apksigner.bat"),
    }

    for root in sorted({candidate.resolve() for candidate in candidates if candidate.is_dir()}, key=version_key, reverse=True):
        found: dict[str, Path] = {}
        for key, names in tool_names.items():
            for name in names:
                tool = root / name
                if tool.is_file():
                    found[key] = tool
                    break
        if len(found) == 3:
            log(f"using Android build-tools: {root}")
            return BuildTools(root=root, d8=found["d8"], zipalign=found["zipalign"], apksigner=found["apksigner"])

    raise PatchError(
        "Android build-tools with d8, zipalign and apksigner were not found. "
        "Install build-tools;36.0.0 in Android Studio/SDK Manager or pass --build-tools."
    )


def read_uleb128(data: bytes | bytearray, offset: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while shift < 35:
        if offset >= len(data):
            raise PatchError("truncated ULEB128")
        value = data[offset]
        offset += 1
        result |= (value & 0x7F) << shift
        if value & 0x80 == 0:
            return result, offset
        shift += 7
    raise PatchError("malformed ULEB128")


def read_utf8_length(data: bytes, offset: int) -> tuple[int, int]:
    first = data[offset]
    offset += 1
    if first & 0x80:
        second = data[offset]
        offset += 1
        return ((first & 0x7F) << 8) | second, offset
    return first, offset


def read_utf16_length(data: bytes, offset: int) -> tuple[int, int]:
    first = struct.unpack_from("<H", data, offset)[0]
    offset += 2
    if first & 0x8000:
        second = struct.unpack_from("<H", data, offset)[0]
        offset += 2
        return ((first & 0x7FFF) << 16) | second, offset
    return first, offset


def parse_axml_string_pool(data: bytes, chunk_offset: int) -> list[str]:
    chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", data, chunk_offset)
    if chunk_type != RES_STRING_POOL_TYPE or header_size < 28:
        raise PatchError("invalid Android XML string pool")
    string_count, style_count, flags, strings_start, styles_start = struct.unpack_from(
        "<IIIII", data, chunk_offset + 8
    )
    offsets_base = chunk_offset + header_size
    strings_base = chunk_offset + strings_start
    utf8 = bool(flags & UTF8_FLAG)
    result: list[str] = []
    for index in range(string_count):
        relative = struct.unpack_from("<I", data, offsets_base + index * 4)[0]
        cursor = strings_base + relative
        if utf8:
            _, cursor = read_utf8_length(data, cursor)
            byte_length, cursor = read_utf8_length(data, cursor)
            raw = data[cursor : cursor + byte_length]
            result.append(raw.decode("utf-8", errors="strict"))
        else:
            char_length, cursor = read_utf16_length(data, cursor)
            raw = data[cursor : cursor + char_length * 2]
            result.append(raw.decode("utf-16le", errors="strict"))
    if chunk_offset + chunk_size > len(data):
        raise PatchError("truncated Android XML string pool chunk")
    return result


def patch_binary_manifest(manifest: bytes) -> bytes:
    if len(ORIGINAL_APPLICATION) != len(WRAPPER_APPLICATION):
        raise PatchError("wrapper Application class name must have the same length as the original")

    patched = bytearray(manifest)
    original_utf16 = ORIGINAL_APPLICATION.encode("utf-16le")
    wrapper_utf16 = WRAPPER_APPLICATION.encode("utf-16le")
    occurrences = manifest.count(original_utf16)
    if occurrences != 1:
        raise PatchError(f"expected one Application string in manifest, found {occurrences}")
    string_offset = manifest.find(original_utf16)
    patched[string_offset : string_offset + len(original_utf16)] = wrapper_utf16

    xml_type, xml_header_size, xml_size = struct.unpack_from("<HHI", manifest, 0)
    if xml_type != 0x0003 or xml_size > len(manifest):
        raise PatchError("not a valid binary AndroidManifest.xml")

    cursor = xml_header_size
    strings: list[str] | None = None
    extract_native_patched = 0
    while cursor + 8 <= xml_size:
        chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", manifest, cursor)
        if chunk_size < header_size or chunk_size == 0 or cursor + chunk_size > xml_size:
            raise PatchError(f"invalid Android XML chunk at 0x{cursor:x}")
        if chunk_type == RES_STRING_POOL_TYPE and strings is None:
            strings = parse_axml_string_pool(manifest, cursor)
        elif chunk_type == RES_XML_START_ELEMENT_TYPE and strings is not None:
            if header_size < 16:
                raise PatchError("invalid START_ELEMENT header")
            element_name_index = struct.unpack_from("<I", manifest, cursor + 20)[0]
            attribute_start, attribute_size, attribute_count = struct.unpack_from("<HHH", manifest, cursor + 24)
            element_name = strings[element_name_index]
            if element_name == "application":
                attribute_base = cursor + 16 + attribute_start
                for index in range(attribute_count):
                    attr = attribute_base + index * attribute_size
                    if attr + 20 > cursor + chunk_size:
                        raise PatchError("truncated application attribute")
                    attr_name_index = struct.unpack_from("<I", manifest, attr + 4)[0]
                    if strings[attr_name_index] != "extractNativeLibs":
                        continue
                    value_size = struct.unpack_from("<H", manifest, attr + 12)[0]
                    if value_size != 8:
                        raise PatchError("unexpected Res_value size for extractNativeLibs")
                    struct.pack_into("<I", patched, attr + 8, 0xFFFFFFFF)
                    patched[attr + 14] = 0
                    patched[attr + 15] = TYPE_INT_BOOLEAN
                    struct.pack_into("<I", patched, attr + 16, 0xFFFFFFFF)
                    extract_native_patched += 1
        cursor += chunk_size

    if extract_native_patched != 1:
        raise PatchError(f"expected one extractNativeLibs attribute, patched {extract_native_patched}")
    return bytes(patched)


def dex_strings(data: bytes) -> tuple[list[str], list[str], int, int]:
    if data[:4] != b"dex\n":
        raise PatchError("classes.dex has an invalid magic")
    string_ids_size, string_ids_off = struct.unpack_from("<II", data, 0x38)
    type_ids_size, type_ids_off = struct.unpack_from("<II", data, 0x40)
    class_defs_size, class_defs_off = struct.unpack_from("<II", data, 0x60)
    strings: list[str] = []
    for index in range(string_ids_size):
        item_offset = struct.unpack_from("<I", data, string_ids_off + index * 4)[0]
        _, cursor = read_uleb128(data, item_offset)
        end = data.find(b"\0", cursor)
        if end < 0:
            raise PatchError("unterminated DEX string")
        strings.append(data[cursor:end].decode("utf-8", errors="replace"))
    types: list[str] = []
    for index in range(type_ids_size):
        descriptor_index = struct.unpack_from("<I", data, type_ids_off + index * 4)[0]
        types.append(strings[descriptor_index])
    return strings, types, class_defs_size, class_defs_off


def patch_original_application_final(classes_dex: bytes) -> bytes:
    patched = bytearray(classes_dex)
    _, types, class_defs_size, class_defs_off = dex_strings(classes_dex)
    matches = 0
    for index in range(class_defs_size):
        entry = class_defs_off + index * 32
        class_index, access_flags = struct.unpack_from("<II", classes_dex, entry)
        if types[class_index] != ORIGINAL_APPLICATION_DESCRIPTOR:
            continue
        if access_flags & ACC_FINAL == 0:
            raise PatchError("FitbitRoot_Application is unexpectedly not final")
        struct.pack_into("<I", patched, entry + 4, access_flags & ~ACC_FINAL)
        matches += 1
    if matches != 1:
        raise PatchError(f"expected one {ORIGINAL_APPLICATION_DESCRIPTOR} class definition, found {matches}")

    patched[12:32] = hashlib.sha1(patched[32:]).digest()
    struct.pack_into("<I", patched, 8, zlib.adler32(patched[12:]) & 0xFFFFFFFF)
    return bytes(patched)


def validate_apkm(extracted: Path) -> None:
    base = extracted / "base.apk"
    arm64 = extracted / "split_config.arm64_v8a.apk"
    if not base.is_file() or not arm64.is_file():
        raise PatchError("APKM must contain base.apk and split_config.arm64_v8a.apk")

    info_path = extracted / "info.json"
    if info_path.is_file():
        info = json.loads(info_path.read_text(encoding="utf-8"))
        package_name = info.get("pname") or info.get("package_name") or info.get("packageName")
        version_code = info.get("versioncode") or info.get("version_code") or info.get("versionCode")
        version_name = info.get("versionname") or info.get("version_name") or info.get("versionName")
        if package_name and package_name != PACKAGE_NAME:
            raise PatchError(f"unexpected package: {package_name}")
        if version_code and int(version_code) != EXPECTED_VERSION_CODE:
            raise PatchError(f"unexpected versionCode: {version_code}")
        if version_name and version_name != EXPECTED_VERSION_NAME:
            raise PatchError(f"unexpected versionName: {version_name}")

    with zipfile.ZipFile(base) as archive:
        manifest = archive.read("AndroidManifest.xml")
        classes = archive.read("classes.dex")
    if ORIGINAL_APPLICATION.encode("utf-16le") not in manifest:
        raise PatchError("exact Google Health 5.05 Application class was not found")
    _, types, _, _ = dex_strings(classes)
    if ORIGINAL_APPLICATION_DESCRIPTOR not in types:
        raise PatchError("exact Google Health 5.05 Application DEX class was not found")


def compile_wrapper_dex(
    source_root: Path,
    stub_root: Path,
    build_tools: BuildTools,
    javac: Path,
    work: Path,
) -> Path:
    stub_classes = work / "stub-classes"
    wrapper_classes = work / "wrapper-classes"
    dex_output = work / "wrapper-dex"
    stub_classes.mkdir(parents=True)
    wrapper_classes.mkdir(parents=True)
    dex_output.mkdir(parents=True)

    stub_sources = sorted(stub_root.rglob("*.java"))
    wrapper_sources = sorted(source_root.rglob("*.java"))
    if not stub_sources or not wrapper_sources:
        raise PatchError("wrapper Java sources/stubs are missing")

    run([
        javac,
        "-source", "8",
        "-target", "8",
        "-encoding", "UTF-8",
        "-d", stub_classes,
        *stub_sources,
    ])
    run([
        javac,
        "-source", "8",
        "-target", "8",
        "-encoding", "UTF-8",
        "-classpath", stub_classes,
        "-d", wrapper_classes,
        *wrapper_sources,
    ])

    class_files = sorted(wrapper_classes.rglob("*.class"))
    if not class_files:
        raise PatchError("javac did not produce wrapper class files")
    run([
        build_tools.d8,
        "--min-api", "28",
        "--output", dex_output,
        *class_files,
    ])
    classes_dex = dex_output / "classes.dex"
    if not classes_dex.is_file():
        raise PatchError("d8 did not produce classes.dex")
    return classes_dex


def copy_zip_info(info: zipfile.ZipInfo) -> zipfile.ZipInfo:
    clone = zipfile.ZipInfo(info.filename, date_time=info.date_time)
    clone.compress_type = info.compress_type
    clone.comment = info.comment
    clone.extra = info.extra
    clone.internal_attr = info.internal_attr
    clone.external_attr = info.external_attr
    clone.create_system = info.create_system
    clone.create_version = info.create_version
    clone.extract_version = info.extract_version
    clone.flag_bits = info.flag_bits & ~0x08
    return clone


def is_old_v1_signature(name: str) -> bool:
    upper = name.upper()
    if not upper.startswith("META-INF/"):
        return False
    leaf = upper.rsplit("/", 1)[-1]
    return leaf == "MANIFEST.MF" or leaf.endswith(SIGNATURE_SUFFIXES)


def next_classes_name(names: Iterable[str]) -> str:
    highest = 0
    for name in names:
        match = re.fullmatch(r"classes(?:(\d+))?\.dex", name)
        if not match:
            continue
        highest = max(highest, int(match.group(1) or "1"))
    return f"classes{highest + 1}.dex"


def rewrite_apk(
    source: Path,
    destination: Path,
    *,
    base_patch: bool,
    arm64_patch: bool,
    wrapper_dex: bytes,
    gadget: bytes,
    gadget_config: bytes,
) -> None:
    with zipfile.ZipFile(source, "r") as incoming, zipfile.ZipFile(destination, "w", allowZip64=True) as outgoing:
        names = incoming.namelist()
        wrapper_name = next_classes_name(names) if base_patch else None
        for info in incoming.infolist():
            name = info.filename
            if is_old_v1_signature(name):
                continue
            if arm64_patch and name in {GADGET_SPLIT_PATH, GADGET_CONFIG_SPLIT_PATH}:
                continue
            data = incoming.read(name)
            if base_patch and name == "AndroidManifest.xml":
                data = patch_binary_manifest(data)
            elif base_patch and name == "classes.dex":
                data = patch_original_application_final(data)
            clone = copy_zip_info(info)
            outgoing.writestr(clone, data)

        if base_patch:
            assert wrapper_name is not None
            info = zipfile.ZipInfo(wrapper_name)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            outgoing.writestr(info, wrapper_dex)
            log(f"added wrapper DEX as {wrapper_name}")

        if arm64_patch:
            for name, data in (
                (GADGET_SPLIT_PATH, gadget),
                (GADGET_CONFIG_SPLIT_PATH, gadget_config),
            ):
                info = zipfile.ZipInfo(name)
                info.compress_type = zipfile.ZIP_STORED
                info.external_attr = 0o100644 << 16
                outgoing.writestr(info, data)
            log(f"added {GADGET_SPLIT_PATH} and config")


def ensure_keystore(
    keystore: Path,
    alias: str,
    store_password: str,
    key_password: str,
    keytool: Path,
) -> None:
    if keystore.is_file():
        return
    keystore.parent.mkdir(parents=True, exist_ok=True)
    log(f"creating research signing keystore: {keystore}")
    run([
        keytool,
        "-genkeypair",
        "-keystore", keystore,
        "-storepass", store_password,
        "-keypass", key_password,
        "-alias", alias,
        "-keyalg", "RSA",
        "-keysize", "4096",
        "-validity", "10000",
        "-dname", "CN=Fitbit Air Research,O=paceaitian,C=CN",
        "-noprompt",
    ])


def sign_apk(
    unsigned: Path,
    output: Path,
    build_tools: BuildTools,
    keystore: Path,
    alias: str,
    store_password: str,
    key_password: str,
    work: Path,
) -> None:
    aligned = work / f"{unsigned.stem}.aligned.apk"
    run([build_tools.zipalign, "-f", "-p", "4", unsigned, aligned])
    run([
        build_tools.apksigner,
        "sign",
        "--ks", keystore,
        "--ks-key-alias", alias,
        "--ks-pass", f"pass:{store_password}",
        "--key-pass", f"pass:{key_password}",
        "--v1-signing-enabled", "true",
        "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "true",
        "--v4-signing-enabled", "false",
        "--out", output,
        aligned,
    ])
    run([build_tools.apksigner, "verify", "--verbose", output])


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apkm", type=Path, required=True, help="Exact Google Health 5.05 APKM")
    parser.add_argument("--gadget", type=Path, required=True, help="Official Frida Gadget arm64 .so/.xz")
    parser.add_argument("--output", type=Path, required=True, help="Output directory")
    parser.add_argument("--build-tools", type=Path, help="Android SDK build-tools version directory")
    parser.add_argument("--source-root", type=Path, default=Path(__file__).resolve().parents[1] / "wrapper-src")
    parser.add_argument("--stub-root", type=Path, default=Path(__file__).resolve().parents[1] / "wrapper-stubs")
    parser.add_argument(
        "--gadget-config",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "frida" / GADGET_CONFIG_NAME,
    )
    parser.add_argument("--keystore", type=Path, help="Research keystore; generated if absent")
    parser.add_argument("--alias", default="fba-research")
    parser.add_argument("--store-password", default="fitbitair")
    parser.add_argument("--key-password", default="fitbitair")
    parser.add_argument("--javac", type=Path)
    parser.add_argument("--keytool", type=Path)
    return parser.parse_args()


def read_gadget(path: Path) -> bytes:
    data = path.read_bytes()
    if path.suffix.lower() == ".xz":
        import lzma

        data = lzma.decompress(data)
    if not data.startswith(b"\x7fELF"):
        raise PatchError("Frida Gadget input is not an ELF arm64 shared library")
    if len(data) < 0x14 or struct.unpack_from("<H", data, 0x12)[0] != 183:
        raise PatchError("Frida Gadget must be the Android arm64 build")
    return data


def main() -> int:
    args = parse_args()
    try:
        apkm = args.apkm.expanduser().resolve()
        gadget_path = args.gadget.expanduser().resolve()
        output = args.output.expanduser().resolve()
        source_root = args.source_root.expanduser().resolve()
        stub_root = args.stub_root.expanduser().resolve()
        config_path = args.gadget_config.expanduser().resolve()
        if not apkm.is_file():
            raise PatchError(f"APKM not found: {apkm}")
        if not gadget_path.is_file():
            raise PatchError(f"Gadget not found: {gadget_path}")
        if not config_path.is_file():
            raise PatchError(f"Gadget config not found: {config_path}")

        build_tools = find_build_tools(args.build_tools)
        javac = find_executable("javac", args.javac)
        keytool = find_executable("keytool", args.keytool)
        gadget = read_gadget(gadget_path)
        gadget_config = config_path.read_bytes()
        json.loads(gadget_config.decode("utf-8"))

        output.mkdir(parents=True, exist_ok=True)
        signed_dir = output / "signed"
        if signed_dir.exists():
            shutil.rmtree(signed_dir)
        signed_dir.mkdir(parents=True)
        keystore = (args.keystore or (output / "fba-research.jks")).expanduser().resolve()

        with tempfile.TemporaryDirectory(prefix="gh505-nonroot-") as temp_name:
            work = Path(temp_name)
            extracted = work / "apkm"
            extracted.mkdir()
            with zipfile.ZipFile(apkm) as archive:
                archive.extractall(extracted)
            validate_apkm(extracted)
            log("exact Google Health 5.05 APKM validated")

            wrapper_dex_path = compile_wrapper_dex(source_root, stub_root, build_tools, javac, work)
            wrapper_dex = wrapper_dex_path.read_bytes()
            unsigned_dir = work / "unsigned"
            unsigned_dir.mkdir()

            apk_paths = sorted(extracted.glob("*.apk"))
            if not apk_paths:
                raise PatchError("no APK files found in APKM")
            for source_apk in apk_paths:
                unsigned_apk = unsigned_dir / source_apk.name
                rewrite_apk(
                    source_apk,
                    unsigned_apk,
                    base_patch=source_apk.name == "base.apk",
                    arm64_patch=source_apk.name == "split_config.arm64_v8a.apk",
                    wrapper_dex=wrapper_dex,
                    gadget=gadget,
                    gadget_config=gadget_config,
                )

            ensure_keystore(
                keystore,
                args.alias,
                args.store_password,
                args.key_password,
                keytool,
            )
            for unsigned_apk in sorted(unsigned_dir.glob("*.apk")):
                sign_apk(
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
            "package": PACKAGE_NAME,
            "versionCode": EXPECTED_VERSION_CODE,
            "versionName": EXPECTED_VERSION_NAME,
            "application": WRAPPER_APPLICATION,
            "gadgetLibrary": GADGET_LIB_NAME,
            "gadgetConfig": GADGET_CONFIG_NAME,
            "signedApkCount": len(list(signed_dir.glob("*.apk"))),
            "keystore": str(keystore),
        }
        (output / "build-info.json").write_text(json.dumps(metadata, indent=2, ensure_ascii=False), encoding="utf-8")
        log(f"done: {signed_dir}")
        log("Do not uninstall the official app until its latest data has synced to the cloud.")
        return 0
    except (PatchError, OSError, ValueError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        print(f"[gh505-nonroot] ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
