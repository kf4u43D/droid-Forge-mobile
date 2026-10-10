"""Statically inspect this project's AAB; does not certify Play or APK installation."""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
from pathlib import Path
from zipfile import ZipFile

from verify_apk import REQUIRED_NOTICES, verify_elf


def _varint(data: bytes, position: int) -> tuple[int, int]:
    value = 0
    for shift in range(0, 70, 7):
        if position >= len(data):
            raise ValueError("Truncated protobuf varint")
        byte = data[position]
        position += 1
        if shift == 63 and byte > 1:
            raise ValueError("Oversized protobuf varint")
        value |= (byte & 127) << shift
        if byte < 128:
            return value, position
    raise ValueError("Oversized protobuf varint")


def fields(data: bytes) -> dict[int, list[int | bytes]]:
    """Read only the protobuf wire envelope, without a runtime dependency."""
    result, position = {}, 0
    while position < len(data):
        tag, position = _varint(data, position)
        number, wire = tag >> 3, tag & 7
        if not number:
            raise ValueError("Invalid protobuf field number")
        if wire == 0:
            value, position = _varint(data, position)
        elif wire in (1, 5):
            end = position + (8 if wire == 1 else 4)
            if end > len(data):
                raise ValueError("Truncated protobuf fixed field")
            value, position = data[position:end], end
        elif wire == 2:
            length, position = _varint(data, position)
            end = position + length
            if end > len(data):
                raise ValueError("Truncated protobuf message")
            value, position = data[position:end], end
        else:
            raise ValueError(f"Unsupported protobuf wire type: {wire}")
        result.setdefault(number, []).append(value)
    return result


def _message(data: bytes, field: int) -> bytes:
    values = fields(data).get(field, [])
    if len(values) != 1 or not isinstance(values[0], bytes):
        raise ValueError(f"Missing or repeated protobuf config message: {field}")
    return values[0]


def verify_bundle_config(data: bytes) -> dict:
    # Official google/bundletool src/main/proto/config.proto:
    # BundleConfig.optimizations=2; Optimizations.uncompress_native_libraries=2;
    # UncompressNativeLibraries.enabled=1 and alignment=2, PAGE_ALIGNMENT_16K=2.
    native = fields(_message(_message(data, 2), 2))
    if native.get(1) != [1] or native.get(2) != [2]:
        raise ValueError("BundleConfig must request uncompressed libraries with PAGE_ALIGNMENT_16K")
    return {"uncompressed_native_libraries": True, "apk_page_alignment": "PAGE_ALIGNMENT_16K"}


def verify_symbols(data: bytes, name: str) -> dict:
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError(f"Expected AArch64 ELF native symbols: {name}")
    offset = struct.unpack_from("<Q", data, 40)[0]
    size, count = struct.unpack_from("<HH", data, 58)
    if size < 64 or offset + size * count > len(data):
        raise ValueError(f"Invalid native symbol section headers: {name}")
    symbol_count = 0
    for index in range(count):
        section = struct.unpack_from("<IIQQQQIIQQ", data, offset + size * index)
        _name, kind, _flags, _address, position, length, _link, _info, _alignment, stride = section
        if kind == 2:  # SHT_SYMTAB, not merely the exported dynamic symbol table.
            if not stride or length % stride or position + length > len(data):
                raise ValueError(f"Invalid native symbol table: {name}")
            symbol_count += length // stride
    if symbol_count < 2:
        raise ValueError(f"Missing native symbol table: {name}")
    return {"name": name, "bytes": len(data), "symbols": symbol_count}


def verify(bundle: Path) -> dict:
    data = bundle.read_bytes()
    with ZipFile(bundle) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or archive.testzip() is not None:
            raise ValueError("Invalid AAB ZIP entries or CRC")
        for name in ("BundleConfig.pb", "base/manifest/AndroidManifest.xml", "base/resources.pb", *
                     ("base/" + notice for notice in REQUIRED_NOTICES)):
            if name not in names or not archive.read(name):
                raise ValueError(f"Missing AAB content: {name}")
        if not any(name.startswith("base/dex/") and name.endswith(".dex") for name in names):
            raise ValueError("Missing AAB DEX code")
        config = verify_bundle_config(archive.read("BundleConfig.pb"))
        libraries = [name for name in names if "/lib/" in name and name.endswith(".so")]
        if not libraries:
            raise ValueError("Missing AAB native engine")
        checked, symbols = [], []
        for name in libraries:
            if not name.startswith("base/lib/arm64-v8a/"):
                raise ValueError(f"Unexpected module or ABI: {name}")
            binary = archive.read(name)
            checked.append({"name": name, "bytes": len(binary), **verify_elf(binary, name)})
            symbol_name = "BUNDLE-METADATA/com.android.tools.build.debugsymbols/arm64-v8a/" + Path(name).name + ".sym"
            if symbol_name not in names:
                raise ValueError(f"Missing AAB native debug symbols: {symbol_name}")
            symbols.append(verify_symbols(archive.read(symbol_name), symbol_name))
        return {"aab": str(bundle), "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                "bundle_config": config, "native_libraries": checked, "native_symbols": symbols,
                "packaged_notices": ["base/" + notice for notice in REQUIRED_NOTICES],
                "validation_scope": "Static structure/configuration only; no Play or generated APK validation"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aab", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    rendered = json.dumps(verify(args.aab), indent=2) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(rendered, encoding="utf-8")
    print(rendered)
