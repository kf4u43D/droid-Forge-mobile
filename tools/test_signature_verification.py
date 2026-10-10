"""Exercise unsigned, self-signed, damaged and partially signed local fixtures.

Uses the existing Android development key; never generates or changes a key.
"""
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from verify_apk import signing_material

ROOT = Path(__file__).resolve().parents[1]


class SignatureDeliveryChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        key = Path.home() / ".android/debug.keystore"
        if not key.is_file():
            raise unittest.SkipTest("Existing Android development keystore required; none will be generated")
        cls.temporary = tempfile.TemporaryDirectory()
        cls.folder = Path(cls.temporary.name)
        cls.unsigned = cls.folder / "unsigned.jar"
        with ZipFile(cls.unsigned, "w") as archive:
            archive.writestr("base/dex/classes.dex", b"Public test fixture")
        cls.signed = cls.folder / "signed.jar"
        cls.signed.write_bytes(cls.unsigned.read_bytes())
        environment = dict(os.environ, ANDROFORGE_TEST_PASSWORD="android")
        subprocess.run(["jarsigner", "-keystore", str(key), "-storepass:env", "ANDROFORGE_TEST_PASSWORD",
                        "-keypass:env", "ANDROFORGE_TEST_PASSWORD", str(cls.signed), "androiddebugkey"],
                       env=environment, capture_output=True, check=True, timeout=30)

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, "temporary"):
            cls.temporary.cleanup()

    def jar_result(self, path):
        return subprocess.run(["java", str(ROOT / "tools/VerifyJarSignature.java"), str(path)],
                              capture_output=True, text=True, timeout=30)

    def modified_jar(self, name, extra=False):
        path = self.folder / name
        with ZipFile(self.signed) as source, ZipFile(path, "w") as target:
            for entry in source.infolist():
                data = source.read(entry)
                if not extra and entry.filename == "base/dex/classes.dex":
                    data = b"Changed signed content"
                target.writestr(entry, data)
            if extra:
                target.writestr("base/assets/unsigned.txt", b"Added unsigned entry")
        return path

    def test_unsigned_jar_is_classified_unsigned(self):
        result = self.jar_result(self.unsigned)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["status"], "unsigned")

    def test_self_signed_development_fixture_verifies(self):
        result = self.jar_result(self.signed)
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(report["status"], "signed")
        self.assertTrue(report["debug_certificate"])
        self.assertEqual(report["files"], report["signed_files"])

    def test_modified_signed_content_is_invalid(self):
        result = self.jar_result(self.modified_jar("damaged.jar"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Invalid JAR/AAB signature", result.stderr)

    def test_partial_signed_coverage_is_invalid(self):
        result = self.jar_result(self.modified_jar("partial.jar", extra=True))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("incomplete JAR/AAB signature coverage", result.stderr)

    def test_signed_jar_material_is_detected(self):
        self.assertTrue(signing_material(self.signed)["present"])
        self.assertFalse(signing_material(self.unsigned)["present"])

    def test_damaged_apk_block_is_not_relabelled_unsigned(self):
        apk = ROOT / "app/build/outputs/apk/tabletPreview/app-tabletPreview.apk"
        if not apk.is_file():
            self.skipTest("Built tablet preview required")
        self.assertTrue(signing_material(apk)["apk_signing_block_magic"])
        data = bytearray(apk.read_bytes())
        with ZipFile(apk) as archive:
            data[archive.start_dir - 1] ^= 1
        damaged = self.folder / "damaged.apk"
        damaged.write_bytes(data)
        report = signing_material(damaged)
        self.assertTrue(report["present"])
        self.assertTrue(report["unexplained_nonzero_gap"])


if __name__ == "__main__":
    unittest.main()
