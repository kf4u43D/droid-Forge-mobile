#!/usr/bin/env python3
"""Import the official blue-7 catalog, images and reference manual.

Only named directories/files are imported; firmware binaries and desktop
installers are deliberately outside the Android editor's resource bundle.
Requires Python 3.9+ and only the standard library.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import struct
import urllib.request
import zipfile


VERSION = "blue-7"
ARCHIVE_URL = "https://dermannmitdermaschine.de/download/droid-blue-7.zip"
ARCHIVE_SHA256 = "e3f3ef93bd70b88e101fbc0254df0c26534a432d241ba6b177d5648a1b68258c"
DOWNLOADS_URL = "https://shop.dermannmitdermaschine.de/pages/downloads"
PREFIX = "droid-blue-7/"
ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "resources" / "official"
CACHE = ROOT / ".cache" / "droid-blue-7.zip"
MAX_ARCHIVE_BYTES = 100 * 1024 * 1024
MAX_IMPORT_BYTES = 100 * 1024 * 1024


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def download() -> bytes:
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    if CACHE.is_file():
        data = CACHE.read_bytes()
        if len(data) > MAX_ARCHIVE_BYTES:
            raise ValueError("Cached archive exceeds the size limit")
        return data
    request = urllib.request.Request(ARCHIVE_URL, headers={"User-Agent": "AndroForge-resource-importer/0.1"})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read(MAX_ARCHIVE_BYTES + 1)
    if len(data) > MAX_ARCHIVE_BYTES:
        raise ValueError("Official archive exceeds the size limit")
    if not zipfile.is_zipfile(io.BytesIO(data)):
        raise ValueError("Official download is not a ZIP archive")
    CACHE.write_bytes(data)
    return data


def import_resources() -> dict:
    archive_data = download()
    archive_hash = sha256(archive_data)
    if archive_hash != ARCHIVE_SHA256:
        raise ValueError("Archive differs from the pinned blue-7 SHA256; review the official release before updating")
    manifest_path = RESOURCES / "manifest.json"
    if manifest_path.is_file():
        previous = json.loads(manifest_path.read_text(encoding="utf-8"))
        previous_hash = previous.get("archive", {}).get("sha256")
        if previous_hash and archive_hash != previous_hash:
            raise ValueError("Archive differs from the recorded SHA256; review the official release before updating")

    entries = []
    imported_size = 0

    def save(destination: Path, data: bytes, archive_path: str, **extra) -> None:
        nonlocal imported_size
        destination = destination.resolve()
        destination.relative_to(ROOT.resolve())
        imported_size += len(data)
        if imported_size > MAX_IMPORT_BYTES:
            raise ValueError("Imported resources exceed the size limit")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
        entries.append({
            "path": destination.relative_to(ROOT).as_posix(),
            "archive_path": archive_path,
            "bytes": len(data),
            "sha256": sha256(data),
            **extra,
        })

    with zipfile.ZipFile(io.BytesIO(archive_data)) as archive:
        def read_member(member: str) -> bytes:
            info = archive.getinfo(member)
            if info.file_size > MAX_IMPORT_BYTES or imported_size + info.file_size > MAX_IMPORT_BYTES:
                raise ValueError("Oversized archive member: " + member)
            return archive.read(member)

        catalog_path = PREFIX + "utilities/editors/circuits.json"
        catalog_data = read_member(catalog_path)
        catalog = json.loads(catalog_data)
        if catalog.get("firmware_version") != VERSION:
            raise ValueError("Unexpected firmware version in the official circuit catalog")
        save(RESOURCES / "circuits.blue-7.json", catalog_data, catalog_path,
             circuits=len(catalog["circuits"]))

        for folder, output in [("faceplates", "modules"), ("circuits", "circuits")]:
            image_prefix = PREFIX + "graphics/" + folder + "/"
            for member in sorted(archive.namelist()):
                if not member.startswith(image_prefix) or not member.endswith(".png"):
                    continue
                image_name = member[len(image_prefix):]
                # Accept an immediate filename only; never extract archive paths.
                if not image_name or "/" in image_name or "\\" in image_name:
                    raise ValueError("Unexpected image path in archive: " + member)
                if PurePosixPath(image_name).name != image_name or image_name in {".", ".."}:
                    raise ValueError("Unsafe image filename: " + image_name)
                image_data = read_member(member)
                if not image_data.startswith(b"\x89PNG\r\n\x1a\n") or len(image_data) < 24:
                    raise ValueError("Invalid PNG in archive: " + member)
                width, height = struct.unpack(">II", image_data[16:24])
                save(ROOT / "app/src/main/assets" / output / image_name, image_data, member,
                     width=width, height=height)

        manual_path = PREFIX + "manual/droid-manual-blue-7.pdf"
        save(RESOURCES / "droid-manual-blue-7.pdf", read_member(manual_path), manual_path)
        readme_path = PREFIX + "README.txt"
        readme = read_member(readme_path).decode("utf-8")
        graphics_paragraph = readme.split("graphics:\n", 1)[1].split("\nutilities:", 1)[0].strip()
        license_data = (
            "Official graphics reuse permission\n"
            "Source: " + ARCHIVE_URL + "\n"
            "Archive member: " + readme_path + "\n"
            "Archive SHA256: " + archive_hash + "\n\n"
            + graphics_paragraph + "\n"
        ).encode("utf-8")
        save(RESOURCES / "GRAPHICS-LICENSE.txt", license_data, readme_path,
             transformation="graphics permission paragraph with provenance")

    manifest = {
        "firmware_version": VERSION,
        "downloads_page": DOWNLOADS_URL,
        "archive": {"url": ARCHIVE_URL, "bytes": len(archive_data), "sha256": archive_hash},
        "catalog": {"circuits": len(catalog["circuits"]), "available_memory": catalog.get("available_memory")},
        "files": entries,
    }
    RESOURCES.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return manifest


def verify() -> dict:
    manifest = json.loads((RESOURCES / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("firmware_version") != VERSION:
        raise ValueError("Manifest version does not match the importer")
    for entry in manifest["files"]:
        destination = (ROOT / entry["path"]).resolve()
        destination.relative_to(ROOT.resolve())
        data = destination.read_bytes()
        if len(data) != entry["bytes"] or sha256(data) != entry["sha256"]:
            raise ValueError("Resource verification failed: " + entry["path"])
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="Verify imported files without network access")
    arguments = parser.parse_args()
    manifest = verify() if arguments.verify else import_resources()
    verb = "Verified" if arguments.verify else "Imported"
    print(f"{verb} {len(manifest['files'])} files; {manifest['catalog']['circuits']} blue-7 circuits")
    print("Archive SHA256: " + manifest["archive"]["sha256"])


if __name__ == "__main__":
    main()
