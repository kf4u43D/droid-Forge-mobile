"""Compare ARM64 rebuild payloads, allowing only GNU ELF build-ID differences.

This verifies binary correspondence, not the provenance of a build or a fully
reproducible APK. Run the documented fresh-source build separately, then compare
its unsigned release APK with the original unsigned release APK. ZIP timestamps,
compression and signing-block placement are outside the ZIP payload comparison.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
from pathlib import Path
from zipfile import BadZipFile, ZipFile


def _unpack(format_: str, data: bytes, position: int) -> tuple:
    size = struct.calcsize(format_)
    if position < 0 or position + size > len(data):
        raise ValueError("Truncated ELF structure")
    return struct.unpack_from(format_, data, position)


def _extent(data: bytes, position: int, length: int) -> tuple[int, int]:
    end = position + length
    if position < 0 or length < 0 or end > len(data):
        raise ValueError("Truncated ELF note/table range")
    return position, end


def _align4(value: int) -> int:
    return (value + 3) & ~3


def build_id(data: bytes) -> dict:
    """Find one GNU/type-3 note descriptor via ELF tables, without fixed offsets."""
    if len(data) < 64 or data[:7] != b"\x7fELF\x02\x01\x01":
        raise ValueError("Expected ELF64 little-endian binary")
    header = _unpack("<16sHHIQQQIHHHHHH", data, 0)
    if header[2] != 183 or header[3] != 1:
        raise ValueError("Expected AArch64 ELF version 1")
    program_offset, section_offset = header[5], header[6]
    header_size, program_stride, program_count, section_stride, section_count = header[8:13]
    if header_size < 64 or header_size > len(data):
        raise ValueError("Invalid ELF header size")
    section_zero = None
    if section_offset:
        if section_stride < 64:
            raise ValueError("Invalid ELF section-header stride")
        section_zero = _unpack("<IIQQQQIIQQ", data, section_offset)
        if section_count == 0:
            section_count = section_zero[5]  # ELF extended section count.
    elif section_count:
        raise ValueError("Missing ELF section-header table")
    if program_count == 0xFFFF:
        if section_zero is None:
            raise ValueError("Missing ELF extended program-header count")
        program_count = section_zero[7]

    ranges = set()
    if section_count:
        _extent(data, section_offset, section_stride * section_count)
        for index in range(section_count):
            section = _unpack("<IIQQQQIIQQ", data, section_offset + index * section_stride)
            if section[1] == 7:  # SHT_NOTE.
                ranges.add(_extent(data, section[4], section[5]))
    if program_count:
        if not program_offset or program_stride < 56:
            raise ValueError("Invalid ELF program-header table")
        _extent(data, program_offset, program_stride * program_count)
        for index in range(program_count):
            segment = _unpack("<IIQQQQQQ", data, program_offset + index * program_stride)
            if segment[0] == 4:  # PT_NOTE, also works for sectionless ELF.
                ranges.add(_extent(data, segment[2], segment[5]))

    descriptors = set()
    for start, end in sorted(ranges):
        position = start
        while position < end:
            if not any(data[position:end]):
                break  # Zero alignment padding is not another note.
            if position + 12 > end:
                raise ValueError("Truncated ELF note header")
            name_size, descriptor_size, kind = _unpack("<III", data, position)
            name_start = position + 12
            descriptor_start = name_start + _align4(name_size)
            next_note = descriptor_start + _align4(descriptor_size)
            if name_start + name_size > end or descriptor_start + descriptor_size > end or next_note > end:
                raise ValueError("Truncated ELF note name/descriptor")
            if data[name_start:name_start + name_size] == b"GNU\0" and kind == 3:
                if not descriptor_size:
                    raise ValueError("Empty GNU build ID")
                descriptors.add((descriptor_start, descriptor_size))
            position = next_note
    # A note may be described by both SHT_NOTE and PT_NOTE. Deduplicate its
    # physical descriptor range; distinct build-ID notes remain ambiguous.
    if len(descriptors) != 1:
        raise ValueError("Expected exactly one GNU build-ID descriptor")
    offset, size = next(iter(descriptors))
    return {"value": data[offset:offset + size].hex(), "offset": offset, "bytes": size}


def _without_build_id(data: bytes, identifier: dict) -> bytes:
    changed = bytearray(data)
    position, size = identifier["offset"], identifier["bytes"]
    changed[position:position + size] = bytes(size)
    return bytes(changed)


def compare_elf(reference: bytes, rebuilt: bytes) -> dict:
    original_id, rebuilt_id = build_id(reference), build_id(rebuilt)
    original_masked = _without_build_id(reference, original_id)
    rebuilt_masked = _without_build_id(rebuilt, rebuilt_id)
    different_bytes = sum(a != b for a, b in zip(reference, rebuilt)) + abs(len(reference) - len(rebuilt))
    return {"reference": {"bytes": len(reference), "sha256": hashlib.sha256(reference).hexdigest(), "build_id": original_id},
            "rebuilt": {"bytes": len(rebuilt), "sha256": hashlib.sha256(rebuilt).hexdigest(), "build_id": rebuilt_id},
            "same_bytes": reference == rebuilt,
            "same_bytes_outside_gnu_build_id": original_masked == rebuilt_masked,
            "differing_byte_count": different_bytes,
            "normalization": "Only the discovered GNU/type-3 descriptor bytes are zeroed; note headers and every other byte are compared"}


def compare_apks(reference: Path, rebuilt: Path) -> dict:
    reference_data, rebuilt_data = reference.read_bytes(), rebuilt.read_bytes()
    with ZipFile(reference) as before, ZipFile(rebuilt) as after:
        before_names, after_names = before.namelist(), after.namelist()
        if len(before_names) != len(set(before_names)) or len(after_names) != len(set(after_names)):
            raise ValueError("Duplicate ZIP entries in APK")
        if before.testzip() is not None or after.testzip() is not None:
            raise ValueError("Invalid APK entry CRC")
        removed, added = sorted(set(before_names) - set(after_names)), sorted(set(after_names) - set(before_names))
        native, changed, equal_count = [], [], 0
        allowed = not removed and not added
        for name in sorted(set(before_names) & set(after_names)):
            first, second = before.read(name), after.read(name)
            if first == second:
                equal_count += 1
            else:
                changed.append(name)
            if name.startswith("lib/") and name.endswith(".so"):
                comparison = compare_elf(first, second)
                native.append({"entry": name, **comparison})
                allowed = allowed and comparison["same_bytes_outside_gnu_build_id"]
            elif first != second:
                allowed = False
        if not native:
            raise ValueError("No common native engine entries in APKs")
    return {"reference": {"file": reference.name, "sha256": hashlib.sha256(reference_data).hexdigest(), "bytes": len(reference_data)},
            "rebuilt": {"file": rebuilt.name, "sha256": hashlib.sha256(rebuilt_data).hexdigest(), "bytes": len(rebuilt_data)},
            "same_apk_bytes": reference_data == rebuilt_data,
            "identical_zip_payload_entries": equal_count,
            "different_zip_payload_entries": changed, "removed_zip_entries": removed, "added_zip_entries": added,
            "native_libraries": native,
            "comparison_matches_allowing_only_gnu_build_ids": allowed,
            "scope": "Unsigned APK ZIP payloads and complete ARM64 ELF bytes; comparison alone does not certify fresh-source build provenance or bit-for-bit APK reproducibility"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path)
    parser.add_argument("rebuilt", type=Path)
    parser.add_argument("--elf", action="store_true", help="Compare two extracted ARM64 .so files instead of APKs")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        result = compare_elf(args.reference.read_bytes(), args.rebuilt.read_bytes()) if args.elf else compare_apks(args.reference, args.rebuilt)
        matched = result["same_bytes_outside_gnu_build_id"] if args.elf else result["comparison_matches_allowing_only_gnu_build_ids"]
        rendered = json.dumps(result, indent=2) + "\n"
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
        raise SystemExit(0 if matched else 1)
    except (ValueError, OSError, BadZipFile, struct.error) as error:
        parser.exit(1, f"Source-build comparison failed: {error}\n")
