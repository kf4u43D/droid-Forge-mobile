"""Regression checks for the release validator, including the missed 4 KiB RELRO."""
import struct
import unittest

from verify_apk import verify_elf


def fixture(relro_end=0x8000, load_flags=5, stack_flags=6, immediate=True):
    binary = bytearray(512)
    binary[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", binary, 18, 183)
    struct.pack_into("<Q", binary, 32, 64)
    struct.pack_into("<HH", binary, 54, 56, 4)
    headers = [
        (1, load_flags, 0, 0, 0, 512, 512, 16384),
        (0x6474E552, 4, 0, 0x4000, 0, 0, relro_end - 0x4000, 1),
        (0x6474E551, stack_flags, 0, 0, 0, 0, 0, 0),
        (2, 6, 320, 320, 320, 32, 32, 8),
    ]
    for index, header in enumerate(headers):
        struct.pack_into("<IIQQQQQQ", binary, 64 + index * 56, *header)
    struct.pack_into("<QQ", binary, 320, 30, 8 if immediate else 0)
    return binary


class ElfReleaseChecks(unittest.TestCase):
    def test_aligned_hardened_binary(self):
        result = verify_elf(fixture(), "fixture")
        self.assertEqual(result["elf_relro_ends"], [0x8000])
        self.assertTrue(result["non_executable_stack"])
        self.assertTrue(result["immediate_binding"])

    def test_rejects_relro_aligned_only_to_4_kib(self):
        with self.assertRaisesRegex(ValueError, "RELRO end lacks"):
            verify_elf(fixture(relro_end=0x9000), "fixture")

    def test_rejects_writable_executable_load(self):
        with self.assertRaisesRegex(ValueError, "Writable executable"):
            verify_elf(fixture(load_flags=7), "fixture")

    def test_rejects_executable_stack(self):
        with self.assertRaisesRegex(ValueError, "Missing ELF"):
            verify_elf(fixture(stack_flags=7), "fixture")

    def test_rejects_lazy_binding(self):
        with self.assertRaisesRegex(ValueError, "Missing ELF"):
            verify_elf(fixture(immediate=False), "fixture")

    def test_rejects_truncated_headers(self):
        binary = fixture()
        struct.pack_into("<Q", binary, 32, 500)
        with self.assertRaisesRegex(ValueError, "Truncated ELF program headers"):
            verify_elf(binary, "fixture")

    def test_rejects_truncated_segments(self):
        with self.assertRaisesRegex(ValueError, "Truncated ELF segment"):
            verify_elf(fixture()[:-1], "fixture")

    def test_rejects_wrong_abi(self):
        binary = fixture()
        struct.pack_into("<H", binary, 18, 62)
        with self.assertRaisesRegex(ValueError, "AArch64"):
            verify_elf(binary, "fixture")


if __name__ == "__main__":
    unittest.main()
