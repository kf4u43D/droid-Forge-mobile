"""Check ARM64 ELF/ZIP/RELRO alignment, native hardening and packaged notices."""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile

PAGE_SIZE = 16384
REQUIRED_NOTICES = (
    "assets/legal/GPL-3.0.txt",
    "assets/legal/GRAPHICS-LICENSE.txt",
    "assets/legal/NDK-NOTICES.txt",
    "assets/legal/NDK-RUNTIME-LICENSES.txt",
    "assets/forge/LICENSE",
    "assets/forge/CREDITS",
)


def signing_material(apk: Path) -> dict:
    """Identify declared/damaged signing material, rather than relabel a failed signature unsigned."""
    data = apk.read_bytes()
    with ZipFile(apk) as archive:
        jar_signatures = [name for name in archive.namelist()
                          if name.upper().startswith("META-INF/")
                          and name.upper().endswith((".SF", ".RSA", ".DSA", ".EC"))]
        signing_block_magic = data[archive.start_dir - 16:archive.start_dir] == b"APK Sig Block 42"
        local_end = 0
        for entry in archive.infolist():
            name_size, extra_size = struct.unpack_from("<HH", data, entry.header_offset + 26)
            end = entry.header_offset + 30 + name_size + extra_size + entry.compress_size
            if entry.flag_bits & 8:
                # Android release ZIPs fit in 32 bits; handle either form of the data descriptor.
                end += 16 if data[end:end + 4] == b"PK\x07\x08" else 12
            local_end = max(local_end, end)
        gap = data[local_end:archive.start_dir]
        unexplained_gap = any(gap) and not signing_block_magic
        return {"present": bool(jar_signatures or signing_block_magic or unexplained_gap),
                "jar_signature_entries": jar_signatures, "apk_signing_block_magic": signing_block_magic,
                "central_directory_gap_bytes": len(gap), "unexplained_nonzero_gap": unexplained_gap}


def verify_elf(binary: bytes, name: str) -> dict:
    if len(binary) < 64 or binary[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", binary, 18)[0] != 183:
        raise ValueError(f"Expected little-endian AArch64 ELF: {name}")
    program_offset = struct.unpack_from("<Q", binary, 32)[0]
    entry_size, count = struct.unpack_from("<HH", binary, 54)
    if entry_size < 56 or program_offset + entry_size * count > len(binary):
        raise ValueError(f"Truncated ELF program headers: {name}")
    alignments, relro_ends = [], []
    non_executable_stack = False
    immediate_binding = False
    for index in range(count):
        segment = struct.unpack_from("<IIQQQQQQ", binary, program_offset + index * entry_size)
        kind, flags, offset, address, _physical, file_size, memory_size, alignment = segment
        if file_size and offset + file_size > len(binary):
            raise ValueError(f"Truncated ELF segment: {name}")
        if kind == 1:  # PT_LOAD
            if alignment < PAGE_SIZE or offset % PAGE_SIZE != address % PAGE_SIZE:
                raise ValueError(f"ELF load segment lacks 16 KiB alignment: {name}")
            if flags & 3 == 3:
                raise ValueError(f"Writable executable ELF segment: {name}")
            alignments.append(alignment)
        elif kind == 0x6474E552:  # PT_GNU_RELRO
            end = address + memory_size
            if end % PAGE_SIZE:
                raise ValueError(f"ELF RELRO end lacks 16 KiB alignment: {name} ({end:#x})")
            relro_ends.append(end)
        elif kind == 0x6474E551:  # PT_GNU_STACK
            non_executable_stack = not bool(flags & 1)
        elif kind == 2:  # PT_DYNAMIC
            if file_size % 16:
                raise ValueError(f"Malformed ELF dynamic segment: {name}")
            for entry_offset in range(offset, offset + file_size, 16):
                tag, value = struct.unpack_from("<QQ", binary, entry_offset)
                if tag == 24 or (tag == 30 and value & 8) or (tag == 0x6FFFFFFB and value & 1):
                    immediate_binding = True
    if not alignments or not relro_ends or not non_executable_stack or not immediate_binding:
        raise ValueError(f"Missing ELF load/RELRO/NX/immediate-binding protection: {name}")
    return {"elf_load_alignments": alignments, "elf_relro_ends": relro_ends,
            "non_executable_stack": non_executable_stack, "immediate_binding": immediate_binding}


def verify(apk: Path) -> dict:
    data = apk.read_bytes()
    with ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate ZIP entries in APK")
        notices = []
        for name in REQUIRED_NOTICES:
            if name not in names or not archive.read(name).strip():
                raise ValueError(f"Missing packaged notice: {name}")
            notices.append(name)
        libraries = [entry for entry in archive.infolist()
                     if entry.filename.startswith("lib/") and entry.filename.endswith(".so")]
        if not libraries:
            raise ValueError("No native engine in APK")
        checked = []
        for entry in libraries:
            if not entry.filename.startswith("lib/arm64-v8a/"):
                raise ValueError(f"Unexpected ABI: {entry.filename}")
            binary = archive.read(entry)
            elf = verify_elf(binary, entry.filename)
            name_size, extra_size = struct.unpack_from("<HH", data, entry.header_offset + 26)
            payload_offset = entry.header_offset + 30 + name_size + extra_size
            if entry.compress_type != ZIP_STORED or payload_offset % PAGE_SIZE:
                raise ValueError(f"APK library storage lacks 16 KiB alignment: {entry.filename}")
            checked.append({"name": entry.filename, "bytes": len(binary),
                            **elf, "apk_offset": payload_offset})
        if any("droid-manual" in entry.filename for entry in archive.infolist()):
            raise ValueError("Reference manual should stay outside the APK")
        return {"apk": str(apk), "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                "native_libraries": checked, "packaged_notices": notices,
                "signing_material": signing_material(apk)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--signing-material-only", action="store_true")
    args = parser.parse_args()
    result = signing_material(args.apk) if args.signing_material_only else verify(args.apk)
    rendered = json.dumps(result, indent=2) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(rendered, encoding="utf-8")
    print(rendered)
