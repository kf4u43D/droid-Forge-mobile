#!/usr/bin/env python3
"""Build standalone Android tests of the production rack source; no ADB or signing.

Licensed under GPL-3.0; see ../../LICENSE.
"""

import argparse
import importlib.util
import os
from pathlib import Path
import tempfile
import zipfile

BASE = Path(__file__).resolve().parent
PROJECT = BASE.parent.parent
_spec = importlib.util.spec_from_file_location(
    "isolated_android_build", BASE.parent / "android_hardware_tests" / "build_unsigned.py")
_build = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_build)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--sdk", help="Android SDK root, or ANDROID_SDK_ROOT/ANDROID_HOME/local.properties")
    parser.add_argument("--jdk", help="JDK 17 root, or JAVA_HOME")
    parser.add_argument("--output-dir", default=str(PROJECT / ".cache" / "android_rack_tests"))
    args = parser.parse_args()
    try:
        sdk_value = args.sdk or os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or _build.local_sdk()
    except (OSError, ValueError) as error:
        parser.error(f"Cannot read sdk.dir: {error}")
    sdk = _build.configured_path(sdk_value, "Pass --sdk or configure the Android SDK.", parser)
    jdk = _build.configured_path(args.jdk or os.environ.get("JAVA_HOME"), "Pass --jdk or set JAVA_HOME.", parser)
    output = Path(args.output_dir).expanduser().resolve()
    tools = sdk / "build-tools" / "36.0.0"
    suffix = ".exe" if os.name == "nt" else ""
    javac = _build.require_file(jdk / "bin" / ("javac" + suffix), parser)
    java = _build.require_file(jdk / "bin" / ("java" + suffix), parser)
    aapt = _build.require_file(tools / ("aapt2" + suffix), parser)
    zipalign = _build.require_file(tools / ("zipalign" + suffix), parser)
    d8 = _build.require_file(tools / "lib" / "d8.jar", parser)
    android = _build.require_file(sdk / "platforms" / "android-36" / "android.jar", parser)
    production = PROJECT / "app" / "src" / "main" / "java" / "fr" / "androforge"
    sources = [BASE / "RackInstrumentation.java", production / "RackViewport.java", production / "ModuleGeometry.java"]
    for source in sources:
        _build.require_file(source, parser)
    output.mkdir(parents=True, exist_ok=True)
    aligned = output / "racktests-aligned.apk"
    with tempfile.TemporaryDirectory(prefix="racktests-build-", dir=output) as temporary:
        work = Path(temporary).resolve()
        if work.parent != output:
            raise RuntimeError("Temporary build directory outside selected output")
        generated, classes, dex = (work / name for name in ("generated", "classes", "dex"))
        for folder in (generated, classes, dex):
            folder.mkdir()
        resources = work / "resources.zip"
        unsigned = work / "racktests-unsigned.apk"
        _build.run([aapt, "compile", "--dir", BASE / "res", "-o", resources])
        _build.run([aapt, "link", "-I", android, "--manifest", BASE / "AndroidManifest.xml",
                    "--custom-package", "fr.androforge", "--java", generated,
                    "-o", unsigned, resources])
        _build.run([javac, "--release", "8", "-encoding", "UTF-8", "-cp", android,
                    "-d", classes, *sources, *sorted(generated.rglob("*.java"))])
        _build.run([java, "-cp", d8, "com.android.tools.r8.D8", "--min-api", "26",
                    "--lib", android, "--output", dex, *sorted(classes.rglob("*.class"))])
        with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as archive:
            info = zipfile.ZipInfo("classes.dex", date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, (dex / "classes.dex").read_bytes())
        _build.run([zipalign, "-f", "4", unsigned, aligned])
        _build.run([zipalign, "-c", "4", aligned])
    print(f"Unsigned standalone rack tests: {aligned}")
    print("No application data, signature, Gradle, ADB or hardware operation used")


if __name__ == "__main__":
    main()
