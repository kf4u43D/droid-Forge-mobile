"""Check deterministic source delivery and exclusion of local signing material."""
import hashlib
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile

import package_source


class SourceDeliveryChecks(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for name in package_source.ROOT_FILES:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("public source\n", encoding="utf-8")
        (self.root / "app/build.gradle.kts").write_text('versionName = "1.2.3"\nversionCode = 7\n')
        self.replacement = patch.object(package_source, "ROOT", self.root)
        self.replacement.start()
        self.addCleanup(self.replacement.stop)

    def test_deterministic_archive_and_file_hashes(self):
        first = self.root / "one.zip"
        second = self.root / "two.zip"
        package_source.package(first)
        package_source.package(second)
        self.assertEqual(first.read_bytes(), second.read_bytes())
        with ZipFile(first) as archive:
            manifest = json.loads(archive.read("droid-Forge-mobile-1.2.3/SOURCE-MANIFEST.json"))
            self.assertEqual(manifest["version_code"], 7)
            for entry in manifest["files"]:
                data = archive.read("droid-Forge-mobile-1.2.3/" + entry["path"])
                self.assertEqual(hashlib.sha256(data).hexdigest(), entry["sha256"])

    def test_rejects_private_key_in_asset_tree(self):
        path = self.root / "app/src/main/assets/private.jks"
        path.parent.mkdir(parents=True)
        path.write_bytes(b"private signing key")
        with self.assertRaisesRegex(ValueError, "Private/generated file"):
            package_source.source_files()

    def test_rejects_compiled_binaries_in_all_source_locations(self):
        suffixes = (".apk", ".aab", ".apks", ".class", ".dex", ".so", ".dll", ".exe", ".APK")
        for directory in ("tools", "app/src/main/assets", ".github/workflows"):
            for suffix in suffixes:
                with self.subTest(directory=directory, suffix=suffix):
                    path = self.root / directory / ("not-source" + suffix)
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(b"compiled binary fixture")
                    try:
                        with self.assertRaisesRegex(ValueError, "Private/generated file"):
                            package_source.source_files()
                    finally:
                        path.unlink()

    def test_includes_git_attributes_and_ci_workflow_metadata(self):
        attributes = self.root / ".gitattributes"
        workflow = self.root / ".github/workflows/ci.yml"
        attributes.write_bytes(b"* text=auto\n*.png binary\n")
        workflow.parent.mkdir(parents=True)
        workflow.write_bytes(b"name: public-source-checks\non: [push]\n")
        source_files = package_source.source_files()
        self.assertIn(attributes, source_files)
        self.assertIn(workflow, source_files)
        archive_path = self.root / "metadata.zip"
        package_source.package(archive_path)
        with ZipFile(archive_path) as archive:
            prefix = "droid-Forge-mobile-1.2.3/"
            manifest = json.loads(archive.read(prefix + "SOURCE-MANIFEST.json"))
            entries = {entry["path"]: entry for entry in manifest["files"]}
            for path in (attributes, workflow):
                name = path.relative_to(self.root).as_posix()
                self.assertEqual(archive.read(prefix + name), path.read_bytes())
                self.assertEqual(entries[name]["bytes"], len(path.read_bytes()))
                self.assertEqual(entries[name]["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())

    def test_rejects_source_directory_resolving_outside_project(self):
        external = tempfile.TemporaryDirectory()
        self.addCleanup(external.cleanup)
        target = Path(external.name)
        (target / "external-source.txt").write_bytes(b"outside-project fixture")
        link = self.root / "tools"
        if os.name == "nt":
            # Directory junctions need no symlink privilege and expose the real
            # Windows case: descendants are regular files, not file symlinks.
            result = subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(target)],
                                    capture_output=True, timeout=10)
            if result.returncode:
                self.skipTest("The filesystem cannot create a directory junction")
        else:
            link.symlink_to(target, target_is_directory=True)

        def remove_link():
            if link.is_symlink():
                link.unlink()
            else:
                link.rmdir()  # Remove the junction itself; never traverse its target.

        self.addCleanup(remove_link)
        linked_file = link / "external-source.txt"
        self.assertTrue(linked_file.is_file())
        self.assertFalse(linked_file.is_symlink())
        with self.assertRaisesRegex(ValueError, "Resolved source file escapes project root"):
            package_source.source_files()
        self.assertEqual((target / "external-source.txt").read_bytes(), b"outside-project fixture")

    def test_rejects_local_signing_password(self):
        (self.root / "gradle.properties").write_text("androforgeStorePassword=secret\n")
        with self.assertRaisesRegex(ValueError, "Signing passwords belong"):
            package_source.source_files()

    def test_excludes_cache_and_reference_manual(self):
        cached = self.root / ".cache/key.jks"
        cached.parent.mkdir()
        cached.write_bytes(b"test private cache")
        manual = self.root / package_source.REFERENCE_ONLY
        manual.parent.mkdir(parents=True)
        manual.write_bytes(b"reference PDF")
        paths = package_source.source_files()
        self.assertNotIn(cached, paths)
        self.assertNotIn(manual, paths)

    def test_records_corresponding_apk_hash(self):
        apk = self.root / "app.apk"
        apk.write_bytes(b"APK fixture")
        archive_path = self.root / "source.zip"
        package_source.package(archive_path, apk)
        with ZipFile(archive_path) as archive:
            manifest = json.loads(archive.read("droid-Forge-mobile-1.2.3/SOURCE-MANIFEST.json"))
            self.assertEqual(manifest["reference_apk"]["sha256"], hashlib.sha256(apk.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
