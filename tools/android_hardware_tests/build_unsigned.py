#!/usr/bin/env python3
"""Build the isolated Android validation harness; never sign, install or use ADB.

Licensed under GPL-3.0, as the project; see ../../LICENSE.
"""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

BASE = Path(__file__).resolve().parent
PROJECT = BASE.parent.parent


def properties_value(value):
    """Decode Java-properties escapes, including Android's escaped Windows paths."""
    result = []
    index = 0
    escapes = {"t": "\t", "n": "\n", "r": "\r", "f": "\f"}
    while index < len(value):
        char = value[index]
        index += 1
        if char != "\\":
            result.append(char)
            continue
        if index == len(value):
            raise ValueError("Trailing escape in sdk.dir")
        char = value[index]
        index += 1
        if char == "u":
            digits = value[index:index + 4]
            if len(digits) != 4:
                raise ValueError("Incomplete Unicode escape in sdk.dir")
            result.append(chr(int(digits, 16)))
            index += 4
        else:
            result.append(escapes.get(char, char))
    return "".join(result)


def local_sdk():
    properties = PROJECT / "local.properties"
    if not properties.is_file():
        return None
    for line in properties.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        if "=" in line:
            key, value = line.split("=", 1)
            if key.strip() == "sdk.dir":
                return properties_value(value.strip())
    return None


def configured_path(value, explanation, parser):
    if not value:
        parser.error(explanation)
    return Path(value).expanduser().resolve()


def require_file(path, parser):
    if not path.is_file():
        parser.error(f"Required SDK/JDK file not found: {path}")
    return path


def run(arguments):
    result = subprocess.run([str(argument) for argument in arguments], capture_output=True,
                            text=True, timeout=120)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip()
                           or f"Build tool failed with exit code {result.returncode}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--sdk", help="Android SDK root (default: ANDROID_SDK_ROOT, ANDROID_HOME, local.properties)")
    parser.add_argument("--jdk", help="JDK 17 root (default: JAVA_HOME)")
    parser.add_argument("--output-dir", default=str(PROJECT / ".cache" / "android_hardware_tests"),
                        help="Directory for the unsigned aligned APK; temporary classes are isolated and cleaned")
    args = parser.parse_args()
    try:
        sdk_value = args.sdk or os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or local_sdk()
    except (OSError, ValueError) as error:
        parser.error(f"Cannot read sdk.dir from local.properties: {error}")
    sdk = configured_path(sdk_value, "Pass --sdk or set ANDROID_SDK_ROOT (or sdk.dir in local.properties).", parser)
    jdk = configured_path(args.jdk or os.environ.get("JAVA_HOME"), "Pass --jdk or set JAVA_HOME to JDK 17.", parser)
    output = Path(args.output_dir).expanduser().resolve()
    tools = sdk / "build-tools" / "36.0.0"
    suffix = ".exe" if os.name == "nt" else ""
    javac = require_file(jdk / "bin" / ("javac" + suffix), parser)
    java = require_file(jdk / "bin" / ("java" + suffix), parser)
    aapt = require_file(tools / ("aapt2" + suffix), parser)
    zipalign = require_file(tools / ("zipalign" + suffix), parser)
    d8 = require_file(tools / "lib" / "d8.jar", parser)
    android = require_file(sdk / "platforms" / "android-36" / "android.jar", parser)
    sources = [BASE / name for name in ("HardwareInstrumentation.java", "TestControlService.java", "TestDocumentsProvider.java")]
    output.mkdir(parents=True, exist_ok=True)
    aligned = output / "hardwaretests-aligned.apk"
    with tempfile.TemporaryDirectory(prefix="hardwaretests-build-", dir=output) as temporary:
        work = Path(temporary).resolve()
        if work.parent != output:
            raise RuntimeError("Refusing a temporary build directory outside the selected output directory")
        classes = work / "classes"
        dex = work / "dex"
        classes.mkdir()
        dex.mkdir()
        run([javac, "--release", "8", "-encoding", "UTF-8", "-cp", android, "-d", classes, *sources])
        class_files = sorted(classes.rglob("*.class"))
        run([java, "-cp", d8, "com.android.tools.r8.D8", "--min-api", "26", "--lib", android,
             "--output", dex, *class_files])
        unsigned = work / "hardwaretests-unsigned.apk"
        run([aapt, "link", "-I", android, "--manifest", BASE / "AndroidManifest.xml", "-o", unsigned])
        with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as archive:
            info = zipfile.ZipInfo("classes.dex", date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, (dex / "classes.dex").read_bytes())
        run([zipalign, "-f", "4", unsigned, aligned])
        run([zipalign, "-c", "4", aligned])
    print(f"Unsigned instrumentation built: {aligned}")
    print(f"Bytes: {aligned.stat().st_size}; no signing, installation or hardware operation performed")


if __name__ == "__main__":
    main()
