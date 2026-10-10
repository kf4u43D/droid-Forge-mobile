"""Create a deterministic GPL corresponding-source archive without caches or keys."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
ROOT_FILES = ("LICENSE", "README.md", ".gitignore", ".gitattributes", "CMakeLists.txt", "build.gradle.kts",
              "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
              "app/build.gradle.kts", "app/proguard-rules.pro", "core/CMakeLists.txt")
SOURCE_DIRS = (".github", "app/src", "core/include", "core/src", "core/generated", "core/tests",
               "tools", "docs", "gradle/wrapper", "resources/official")
REFERENCE_ONLY = "resources/official/droid-manual-blue-7.pdf"
FORBIDDEN_SUFFIXES = (".keystore", ".jks", ".p12", ".pfx", ".pem", ".key", ".pyc",
                      ".apk", ".aab", ".apks", ".class", ".dex", ".so", ".dll", ".exe")
SIGNING_SECRETS = (b"androforgeStorePassword", b"androforgeKeyPassword")


def source_files() -> list[Path]:
    resolved_root = ROOT.resolve()
    paths = [ROOT / name for name in ROOT_FILES]
    for directory in SOURCE_DIRS:
        paths += [path for path in (ROOT / directory).rglob("*") if path.is_file()
                  and "__pycache__" not in path.parts
                  and path.relative_to(ROOT).as_posix() != REFERENCE_ONLY]
    result = []
    for path in sorted(set(paths), key=lambda value: value.relative_to(ROOT).as_posix()):
        if path.is_symlink():
            raise ValueError(f"Symlink not allowed in source archive: {path.relative_to(ROOT)}")
        # A regular file can still escape through a parent directory junction.
        # Check the resolved target before reading any selected source bytes.
        try:
            path.resolve().relative_to(resolved_root)
        except ValueError:
            raise ValueError(f"Resolved source file escapes project root: {path.relative_to(ROOT)}") from None
        if path.suffix.lower() in FORBIDDEN_SUFFIXES or path.name in ("local.properties", "signing.properties"):
            raise ValueError(f"Private/generated file in source tree: {path.relative_to(ROOT)}")
        if path.name == "gradle.properties" and any(secret in path.read_bytes() for secret in SIGNING_SECRETS):
            raise ValueError("Signing passwords belong in external user Gradle properties, outside the source tree")
        result.append(path)
    return result


def package(output: Path, apk: Path | None = None) -> dict:
    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    version = re.search(r'versionName\s*=\s*"([^"]+)"', gradle).group(1)
    version_code = int(re.search(r"versionCode\s*=\s*(\d+)", gradle).group(1))
    files = source_files()
    entries = [(path.relative_to(ROOT).as_posix(), path.read_bytes()) for path in files]
    manifest = {"version": version, "version_code": version_code,
                "files": [{"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                          for name, data in entries]}
    if apk:
        manifest["reference_apk"] = {"name": apk.name, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()}
    entries.append(("SOURCE-MANIFEST.json", (json.dumps(manifest, indent=2) + "\n").encode("utf-8")))
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, "w", compression=ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in entries:
            info = ZipInfo(f"droid-Forge-mobile-{version}/{name}", date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = (0o100755 if name == "gradlew" else 0o100644) << 16
            archive.writestr(info, data, compresslevel=9)
    data = output.read_bytes()
    return {"archive": str(output), "version": version, "files": len(files), "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest()}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--apk", type=Path, help="Record the corresponding APK hash in the source manifest")
    args = parser.parse_args()
    print(json.dumps(package(args.output, args.apk), indent=2))
