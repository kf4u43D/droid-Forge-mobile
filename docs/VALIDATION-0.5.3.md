# Validation 0.5.3 — English interface and rack gestures

Version 0.5.3, code 10, translates the application interface into English:
menus, dialogs, accessibility descriptions, native diagnostics, SD/MIDI
messages, backup dates, credits and the starter patch. The activity uses an
English resource context so English plural rules also apply on a French device.
Only locale and layout direction are overridden; device configuration changes
continue to apply.
Optional bundles retain their language resources.

The official examples' category labels, generated headings and explanatory
notes are translated for display. Their pinned manifests and all 630 patch
bodies remain unchanged. Existing user documents, comments, labels, source
drafts and storage keys are preserved. The user-supplied icon is unchanged;
its provenance remains in the [icon manifest](../resources/official/brand-logo-manifest.json).

Double-tapping anywhere in the module rack opens the controller chooser.
Single taps are confirmed before opening module details. Long presses retain
label editing; two-finger pinch and drag retain zoom and panning. Movement,
extra pointers, cancellation, focus loss, pending-input cancellation, rack
replacement and detachment cancel pending selections/additions. TalkBack
virtual module clicks still open details, and the rack offers an explicit
**Add module** accessibility action. The chooser uses the existing guards and
mutation/history path.

The final preview, isolated validation and unsigned release builds pass Android
Lint: zero errors and four warnings per variant (`OldTargetApi`,
`ChromeOsAbiSupport`, `ObsoleteSdkInt`, `MonochromeLauncherIcon`). ARM64 APK
checks confirm 16 KiB native/ZIP alignment, a non-executable stack, immediate
binding, RELRO and the packaged notices. The preview uses the existing
development signature; the release APK is unsigned.
Checkout-specific VCS metadata is omitted so a Git checkout and the GPL source
archive can produce comparable APK payloads. Code, resources and the manifest
are verified identical before and after this packaging change.

On Galaxy Tab S10 FE+ (SM-X620), Android 16, system locale `fr-FR`:

- **68 gesture/lifecycle assertions** pass using the production rack source
  and Android gesture detectors in a standalone package. These injected event
  tests cover taps, double taps, movement, pinch, long press, port targeting,
  accessibility and lifecycle cancellation. The [GPL harness](../tools/android_rack_tests/README.md)
  is reproducible; its [report](validation-data/0.5.3/rack-gestures-0.5.3.json)
  states this scope and does not claim visual or picker integration coverage.
- **13 editor scenarios** pass on the final optimized validation APK: English
  zero plurals and new-document template; both empty-rack and existing-module
  double taps; all nine controller choices; one module added/rendered and
  undone with exact source recovery; picker cancellation; single/long presses;
  zoom/reset; drag without accidental addition; English menus, DROID connection
  and credits; source preservation.
- The normal application is updated to **0.5.3-preview**. Its installed APK
  matches the local preview byte for byte. The existing editor source is
  identical before/after update, picker cancellation, backup listing and final
  cleanup. Portrait and landscape layouts retain English actions and exact
  source; the original rotation settings are restored. Existing backup dates
  display in English. Test packages and owned
  temporary UI files are removed, and the main editor is left in the foreground.
  The [tablet report](validation-data/0.5.3/tablet-audit-0.5.3.json) contains no
  user patch text, filename or fingerprint.

Local regression checks also pass: three CTest suites, including the 630-entry
official corpus; 222 strict host JNI checks; 2,174 MIDI transport checks;
311 SD transaction checks; 64 text-codec checks; 3,364 English metadata checks
over all 630 examples; and 41 Python tool tests. The packaged official corpus
is verified independently in the [asset report](validation-data/0.5.3/example-assets-verification-0.5.3.json).

The final preview is 9,240,152 bytes, SHA-256
`f015a4688aa8b573234b85b2d846f7dda5f2bfa64f212c4d2ac549bd936088ce`.
The unsigned release is 9,236,044 bytes, SHA-256
`4dc6434fa1e0c9222e47a174ee1cf2ab30d6a08599e943d50325e85fe19b3dbe`.
The [preview](validation-data/0.5.3/preview-apk-verification-0.5.3.json) and
[release](validation-data/0.5.3/release-apk-verification-0.5.3.json) reports record
their native protections and signing material. Matching source, R8 mappings,
native symbols and delivery hashes are prepared locally in `dist/`.

No physical DROID card write or MIDI transfer is performed in this campaign.
The hardware transfer evidence remains from [0.5.0](VALIDATION-0.5.0.md), and
icon rendering evidence from [0.5.2](VALIDATION-0.5.2.md). This report does not
extend those results to a new hardware transfer or full musical behavior.
