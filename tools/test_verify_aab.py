"""Regression checks for AAB native packaging configuration and bounded parsing."""
import unittest

from verify_aab import fields, verify_bundle_config


def configuration(enabled=1, alignment=2):
    native = bytes([8, enabled, 16, alignment])
    optimization = bytes([18, len(native)]) + native
    return bytes([18, len(optimization)]) + optimization


class BundleConfigChecks(unittest.TestCase):
    def test_accepts_16_kib_uncompressed(self):
        self.assertEqual(verify_bundle_config(configuration())["apk_page_alignment"], "PAGE_ALIGNMENT_16K")

    def test_rejects_4_kib(self):
        with self.assertRaisesRegex(ValueError, "PAGE_ALIGNMENT_16K"):
            verify_bundle_config(configuration(alignment=1))

    def test_rejects_missing_alignment(self):
        with self.assertRaisesRegex(ValueError, "PAGE_ALIGNMENT_16K"):
            verify_bundle_config(configuration(alignment=0))

    def test_rejects_compressed_native_libraries(self):
        with self.assertRaisesRegex(ValueError, "PAGE_ALIGNMENT_16K"):
            verify_bundle_config(configuration(enabled=0))

    def test_rejects_truncated_message(self):
        with self.assertRaisesRegex(ValueError, "Truncated protobuf message"):
            verify_bundle_config(configuration()[:-1])

    def test_rejects_repeated_config(self):
        with self.assertRaisesRegex(ValueError, "Missing or repeated"):
            verify_bundle_config(configuration() + configuration())

    def test_rejects_varint_without_end(self):
        with self.assertRaisesRegex(ValueError, "Truncated protobuf varint"):
            fields(b"\x80")

    def test_rejects_oversized_varint(self):
        with self.assertRaisesRegex(ValueError, "Oversized protobuf varint"):
            fields(b"\xff" * 10)

    def test_rejects_invalid_tag(self):
        with self.assertRaisesRegex(ValueError, "Invalid protobuf field number"):
            fields(b"\x00")

    def test_rejects_unsupported_wire_type(self):
        with self.assertRaisesRegex(ValueError, "Unsupported protobuf wire type"):
            fields(b"\x0b")

    def test_skips_unknown_supported_field(self):
        self.assertEqual(verify_bundle_config(configuration() + b"\x38\x01")["apk_page_alignment"], "PAGE_ALIGNMENT_16K")


if __name__ == "__main__":
    unittest.main()
