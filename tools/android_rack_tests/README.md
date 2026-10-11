# Isolated Android rack gesture tests

Standalone GPL-3.0 harness without an Activity, MIDI port, USB access or user
patch. It directly compiles the project's `RackViewport.java` and
`ModuleGeometry.java` with minimal test resources and uses Android's actual
gesture detectors on the main thread. The `fr.androforge.racktests` package does
not target the main application or use any of its files. It complements
interactive tests of the installed rack; it does not verify rendering or picker
integration.

It checks deferred single selection, double taps on modules and empty rack
areas, cancellation, second-tap dragging, ordinary dragging, pinching, an extra
pointer without zooming, long presses and the targeted port, small movements,
displaced release, rack replacement, detachment, focus loss/return, framework
input cancellation, reentrant picker/module callbacks, timing and accessibility
actions. TalkBack virtual clicks retain module opening.

Build the unsigned APK with JDK 17 / SDK 36 / Build Tools 36.0.0:

```text
python tools/android_rack_tests/build_unsigned.py --sdk SDK_PATH --jdk JDK_17_PATH
```

The builder calls neither Gradle nor ADB. Sign the test APK with a local
development key, install the isolated package and run:

```text
adb install -r .cache/android_rack_tests/racktests.apk
adb shell am instrument -w fr.androforge.racktests/fr.androforge.RackInstrumentation
adb uninstall fr.androforge.racktests
```

`racktests.apk` is the signed copy of `racktests-aligned.apk`. The response contains
`passed=true` and a JSON `report` when all checks pass. Do not add a keystore or APK
to the repository.
