"""Regression tests for GNU-note discovery and strict source-build comparison."""
import struct
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from verify_source_build import build_id, compare_apks, compare_elf


def note(name, descriptor, kind):
    return struct.pack("<III", len(name), len(descriptor), kind) + name + bytes(-len(name) % 4) + descriptor + bytes(-len(descriptor) % 4)


def elf(identifier=b"a" * 20, code=b"REAL-CODE", note_offset=192, sectionless=False, extended=False):
    notes = note(b"other", b"prefix", 1) + note(b"GNU\0", identifier, 3)
    section_offset = (note_offset + len(notes) + len(code) + 7) & ~7
    data = bytearray(section_offset + (0 if sectionless else 128))
    struct.pack_into("<16sHHIQQQIHHHHHH", data, 0, b"\x7fELF\x02\x01\x01" + bytes(9), 3, 183, 1, 0, 64,
                     0 if sectionless else section_offset, 0, 64, 56, 1, 64, 0 if sectionless or extended else 2, 0)
    struct.pack_into("<IIQQQQQQ", data, 64, 4, 4, note_offset, note_offset, 0, len(notes), len(notes), 4)
    data[note_offset:note_offset + len(notes)] = notes
    data[note_offset + len(notes):note_offset + len(notes) + len(code)] = code
    if not sectionless:
        if extended:
            struct.pack_into("<Q", data, section_offset + 32, 2)
        struct.pack_into("<IIQQQQIIQQ", data, section_offset + 64, 0, 7, 2, note_offset, note_offset, len(notes), 0, 0, 4, 0)
    return bytes(data)


def apk(path, binary, **entries):
    with ZipFile(path, "w") as archive:
        archive.writestr("AndroidManifest.xml", entries.pop("manifest", b"manifest"))
        archive.writestr("classes.dex", entries.pop("dex", b"dex"))
        archive.writestr("lib/arm64-v8a/libandroforge.so", binary)
        for name, value in entries.items():
            archive.writestr(name, value)


class SourceBuildComparison(unittest.TestCase):
    def test_notes_discovered_with_prefix_and_duplicate_table_views(self):
        binary = elf()
        found = build_id(binary)
        self.assertEqual(found["value"], (b"a" * 20).hex())
        self.assertEqual(found["offset"], 192 + len(note(b"other", b"prefix", 1)) + 16)
        self.assertEqual(found["bytes"], 20)

    def test_sectionless_and_extended_count(self):
        self.assertEqual(build_id(elf(sectionless=True))["value"], (b"a" * 20).hex())
        self.assertEqual(build_id(elf(extended=True))["value"], (b"a" * 20).hex())

    def test_only_id_change_matches_without_claiming_exact_bytes(self):
        result = compare_elf(elf(b"a" * 20), elf(b"b" * 20))
        self.assertFalse(result["same_bytes"])
        self.assertTrue(result["same_bytes_outside_gnu_build_id"])
        self.assertEqual(result["differing_byte_count"], 20)

    def test_code_and_note_header_changes_are_not_hidden(self):
        self.assertFalse(compare_elf(elf(), elf(code=b"BAD!-CODE"))["same_bytes_outside_gnu_build_id"])
        before = elf()
        changed = bytearray(before)
        changed[192 + 8] = 2  # Other vendor note's type must remain observable.
        self.assertFalse(compare_elf(before, bytes(changed))["same_bytes_outside_gnu_build_id"])

    def test_moved_or_resized_id_does_not_mask_layout_differences(self):
        self.assertFalse(compare_elf(elf(), elf(note_offset=256))["same_bytes_outside_gnu_build_id"])
        self.assertFalse(compare_elf(elf(), elf(identifier=b"a" * 16))["same_bytes_outside_gnu_build_id"])

    def test_truncation_and_empty_ids_fail(self):
        with self.assertRaises(ValueError):
            build_id(elf()[:200])
        with self.assertRaises(ValueError):
            build_id(elf(identifier=b""))
        malformed = bytearray(elf())
        struct.pack_into("<I", malformed, 192 + 4, 999999)
        with self.assertRaises(ValueError):
            build_id(bytes(malformed))

    def test_apk_payloads_only_allow_native_id_change(self):
        with tempfile.TemporaryDirectory() as directory:
            before, after = Path(directory) / "reference.apk", Path(directory) / "rebuilt.apk"
            apk(before, elf(b"a" * 20))
            apk(after, elf(b"b" * 20))
            result = compare_apks(before, after)
            self.assertTrue(result["comparison_matches_allowing_only_gnu_build_ids"])
            self.assertEqual(result["different_zip_payload_entries"], ["lib/arm64-v8a/libandroforge.so"])
            self.assertEqual(result["identical_zip_payload_entries"], 2)
            apk(after, elf(b"b" * 20), dex=b"changed")
            self.assertFalse(compare_apks(before, after)["comparison_matches_allowing_only_gnu_build_ids"])

    def test_added_payloads_are_not_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            before, after = Path(directory) / "reference.apk", Path(directory) / "rebuilt.apk"
            apk(before, elf())
            apk(after, elf(), **{"extra.txt": b"extra"})
            result = compare_apks(before, after)
            self.assertFalse(result["comparison_matches_allowing_only_gnu_build_ids"])
            self.assertEqual(result["added_zip_entries"], ["extra.txt"])


if __name__ == "__main__":
    unittest.main()
