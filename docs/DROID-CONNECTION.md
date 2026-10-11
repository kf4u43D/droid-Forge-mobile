# DROID connection — 0.5.0

This document describes the official protocol and its Android 0.5.0 adaptation.
Local review and the adapter and interface tests are complete. After reconnecting
the X7 in square mode, card import and SD saving were verified byte for byte on
the real hardware. A private test harness restored the exact original file, then
the DocumentsUI action opened by **Eject SD** actually unmounted the DROID volume.
Android MIDI transfer to the X7 then succeeded. The user reports no error after
Activate; the complete operation of the patch remains uncertified.
The [physical X7 report](validation-data/0.5.0/droid-hardware-audit-0.5.0.json)
contains 24 passed assertions: 19 for SD, import and cleanup, followed by 5 for
Android MIDI transport. These remain separate from the private-provider tests.
The [0.5.0 evidence](VALIDATION-0.5.0.md) distinguishes the two scopes.
The [0.4.0 validation](VALIDATION-0.4.0.md) remains a separate historical report.

## X7 positions and the patch being retrieved

The blue-7 manual, chapter 8, pages 89–91, specifies three positions:

| Position | USB function | Consequence |
| --- | --- | --- |
| Left, square mode observed on the connected X7 | Mass storage, access to the SD card | The master does not play its patch during this access |
| Middle | USB disconnected | After host ejection, the master automatically reloads `droid.ini` |
| Right | USB MIDI | MIDI input/output and patch activation through SysEx |

The [manufacturer describes SD access as using a USB flash drive](https://shop.dermannmitdermaschine.de/products/x7),
including from a phone or tablet. The X7 is a USB peripheral; Android must act as
the host. Page 90 of the manual also specifies hardware-revision and firmware
requirements for a USB-C to USB-C cable; the application does not update firmware.

Reading `droid.ini` retrieves **the file saved on the card**, without converting
its text. This file may differ from a patch previously activated in RAM through
MIDI. Neither the blue-7 manual nor the reference Forge PC MIDI transport
documents a command to retrieve the active patch from RAM. No patch request or
acknowledgement message is invented.

References: [official blue-7 manual](https://dermannmitdermaschine.de/download/droid-manual-blue-7.pdf),
local copy `resources/official/droid-manual-blue-7.pdf`, pages 89–91 and 95–97.

## Activate, Save to SD and Eject SD in Forge PC

The PC reference is commit `60cbb7bbf9630c7d4a184e346a3d1120bde4d69f`:

- `droidforge/main/patchoperator.cpp:420`: `saveToSD()` checks patch problems,
  prepares `toDeployString()`, writes `droid.ini`, then requests ejection.
- `droidforge/main/tuning.h:18`: `DROID_PATCH_FILENAME` is `droid.ini`;
  `MAX_DROID_INI`, on line 36, limits the payload to 64,000 bytes.
- `droidforge/patch/patch.cpp:1338`: deployment assembles controllers followed by
  circuits. Editor annotations are separate from the deployed text.
- `droidforge/patch/patch.cpp:1375`, particularly 1405–1408: after writing, Forge
  reads the contents again and compares them with the requested contents.
- `droidforge/main/patchoperator.cpp:494`: ejection uses OS functions, including
  `diskutil` on Mac and `udisksctl` on Linux.
- `droidforge/os/midihost.cpp:6`: MIDI activation uses the same text, preceded by
  `F0 00 66 66 50` and followed by `F7`. `50` is the ASCII character `P`.

Pages 95–97 of the manual document sending this SysEx with the switch on the
right. The Mac workaround for isolated bytes in Forge PC is not a rule to apply
automatically to Android transport.

The Android engine separately prepares ASCII/LF text: active controllers first,
then active circuits and parameters in their order, without comments, labels or
annotations. It preserves the textual precision of numbers and rejects errors,
partial analysis, unknown active parameters and exceeded budgets. PC options for
deduplication, short names and cable compression are not included in this first
adaptation. The editable source remains separate and retains its metadata.

## Android SD access through SAF

The UI controller requests a directory with `ACTION_OPEN_DOCUMENT_TREE` and
retains only the permission granted by Android. Selecting a directory writes
nothing. The [SAF documentation](https://developer.android.com/guide/topics/providers/document-provider)
describes these accesses and temporary USB storage roots.

`DroidSdStorage` runs on the input/output worker:

1. `read(tree)` locates a unique `droid.ini` in the selected directory and returns
   its raw bytes, with a 4 MiB limit. Strict UTF-8 decoding is a separate step
   before replacing the patch in the editor.
2. `save(tree, deployed)` rejects an empty, non-ASCII or larger-than-64,000-byte
   payload. An already identical file is not rewritten.
3. Before replacement, the original is backed up in private application storage
   and read back byte for byte. A concurrent change to the original stops the
   operation before writing.
4. The write closes and synchronizes the file, then verifies exact readback.
   A failure may leave a partial file on the card; it remains explicitly reported
   and the private backup remains available. No automatic restoration or ejection
   occurs after this failure.
5. `listBackups()` and `readBackup(record)` allow recovery of private originals.
   Paths are restricted to the backup directory, and reads to 4 MiB. These methods
   return bytes; a non-UTF-8 source can be preserved through raw export without
   silent conversion.

Backups are not deleted automatically. Their budget of 128 files and 32 MiB
causes a visible refusal **before** a new overwrite. Readback verification does
not turn SAF into an atomic provider transaction and does not certify the card's
resilience to power loss.

The test driver identified the connected X7 as an MSC class 8 device, VID `0483`,
PID `a416`, with no MIDI device in this mode. Samsung mounted the volume without
a FUSE path visible to applications. This observation does not establish that SAF
is unavailable: the [AOSP provider](https://android.googlesource.com/platform/frameworks/base/+/master/packages/ExternalStorageProvider/src/com/android/externalstorage/ExternalStorageProvider.java)
can use an internal path if the volume is mounted for the correct user.
After reconnection, the DROID root was actually selected and granted in Samsung's
picker. Two SAF reads of the card file returned the same 74,269 bytes; accepted
import, autosave and a cold restart preserved this text exactly. Canceling the
import confirmation also preserved the current document.
The application does not bypass these permissions through direct access to
`/mnt/media_rw` or a privileged shell.

C++ preparation produced a 49,163-byte deployment without changing the source,
state or history. Save to SD wrote and read it back exactly on the card; an
independent instrumented read confirmed equality with the C++ result. The
74,269-byte original was backed up exactly in private storage before replacement.
A second unchanged save neither rewrote the file nor created a second private
original.

Raw export of the original verified its 74,269 bytes in a **distinct file**
created by the picker. It did not replace the existing `droid.ini`. Restoring the
original to that file was subsequently performed by a **private test harness**
with normal SAF permissions: three preflight checks of the deployed contents,
writing the original, synchronization and three exact readbacks. Two independent
SD reads and a host copy confirmed the 74,269 bytes from before the campaign.
This restoration is not an application command. Cleanup deleted only the
separate file created by export; the other entry identities and preferences
were unchanged, without comparing the contents of other card files byte for byte.
The contents, distinct filename and private hashes are not published.

## Actual ejection

[`DocumentsContract.ejectRoot()`](https://developer.android.com/reference/android/provider/DocumentsContract#ejectRoot(android.content.ContentResolver,%20android.net.Uri))
is a public API since Android 26, applicable to roots with
`FLAG_SUPPORTS_EJECT`. It can fail. The [AOSP provider check](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/DocumentsProvider.java)
requires write permission on the `/root/...` root URI; permission on a
`/tree/...` directory URI does not automatically grant it.

`canEject(tree)` checks an identified root, its specific permission and capability.
If the application cannot eject it, a public fallback opens the system picker
with `ACTION_OPEN_DOCUMENT_TREE`. In the storage list, the user touches the eject
icon beside DROID, waits for its disappearance or Android's confirmation, then
returns without selecting a directory. `EXTRA_INITIAL_URI` can suggest the
already granted DROID directory; this [initial position remains system-dependent](https://developer.android.com/reference/android/provider/DocumentsContract#EXTRA_INITIAL_URI).
The result of this ejection flow is ignored: it triggers no import, write or
retention of a new permission.

This flow is based on [Android 16 DocumentsUI code](https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/refs/tags/android-security-16.0.0_r1/src/com/android/documentsui/sidebar/RootItem.java):
the sidebar shows an icon for roots declaring ejection support, independently
of the selection action. The [system worker](https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/refs/tags/android-security-16.0.0_r1/src/com/android/documentsui/sidebar/EjectRootTask.java)
calls the ejection API with DocumentsUI permissions. This flow succeeded on the
Samsung tablet: **Eject SD** opened DocumentsUI, the DROID icon was targeted and
the DROID volume entered the `unmounted` state. The tablet's SD card remained
mounted. An ordinary application does not obtain DocumentsUI permissions by
opening the picker. Storage settings and the USB notification remain secondary
fallbacks.

Opening a screen, canceling the picker or revoking a SAF permission **is not an
ejection**. No intent result guarantees that a volume has been unmounted. An
accepted API request is also not proof that an asynchronous unmount has completed.

After actual ejection by Android, returning the X7 switch to the middle
disconnects USB and starts the card file, according to page 91 of the manual.
`DROIDERR.TXT` can then show a possible master error. These commands write no
firmware file.

## Real MIDI transfer and MASTER observation

After SD ejection, the user confirmed switching the X7 to MIDI mode. Android
detected **DROID X7 MIDI**, with one USB input and one output. The application
requested confirmation for this target and its **49,163 / 64,000-byte** deployment.
Canceling sent no bytes. After a second accepted confirmation, the original
**Transfert MIDI envoyé** message (MIDI transfer sent) reported that the
**49,169-byte** frame had been handed to the Android port. The dialog explicitly
stated the absence of an acknowledgement in the official protocol.
The editor source remained byte-identical after transfer; Android reported the
port closed and no active MIDI connection to this device. Activate remained
available. No main application crash was observed in the available crash buffer.

This result verifies completion of Android transport. The user reported
**“pas d’erreur” (no error)** after Activate; this observation is recorded
separately from the 24 instrumented checks. Complete patch operation remains
uncertified. No ACK is inferred from sending or this observation. The protocol
does not read the firmware version; a USB descriptor does not provide that proof.

## Completed tests and hardware limitations

The SAF classes compile against `android-36.jar`. The transaction policy passed
**311 JDK checks**: preservation of UTF-8/BOM/CRLF originals, corrupt or impossible
backups, concurrent conflicts, disconnection during writing, missing/altered
readback, no overwrite of an identical file, 64,000-byte/4 MiB limits and
validation of all 256 byte values. These tests use a simulated backend; they do
not write to the actual card.

**56 Android JNI/SAF checks** also passed with a private documents provider:
permissions and revocation, already identical files, creation/names, synchronized
writes, readback, backups and disconnection. They were rerun on the final
validation APK without writing to the X7. **22 GUI scenarios** passed with the
Android picker and this provider: directory selection and grants, SAVE
confirmation, writing/readback, import and backup of the previous document,
recovery after rotation and process termination, no second backup of an identical
file, exact raw export of the original after picker interruption, cancellation
and no false ejection confirmation. Both fixture-cleanup checks also passed.

The [reproducible GPL harness](../tools/android_hardware_tests/README.md)
documents permissions and fixture isolation. The [0.5.0 validation report](VALIDATION-0.5.0.md)
identifies the final APKs; the [tablet report](validation-data/0.5.0/tablet-audit-0.5.0.json)
details the interface and the [adapter report](validation-data/0.5.0/android-adapters-audit-0.5.0.json)
the JNI/SAF checks. None of these suites claims an operation on the physical card
or a MIDI activation of the master.

```powershell
javac -encoding UTF-8 -d .cache/sd-io-test app/src/main/java/fr/androforge/DroidSdIo.java tools/DroidSdIoTest.java
java -ea -cp .cache/sd-io-test fr.androforge.DroidSdIoTest
```

The physical follow-up verified card reading/import, confirmed writing and
readback, exact backup of the original and its distinct raw export, followed by
restoration through a private harness and actual system ejection. The installed
main APK was compared byte for byte with the preview APK delivered in `dist`,
whose SHA-256 is specified in the validation. Android MIDI transport subsequently
succeeded, and the user then reported no error. The [physical X7 report](validation-data/0.5.0/droid-hardware-audit-0.5.0.json)
records 24 passed assertions: 19 SD and 5 Android MIDI. The user's no-error
observation remains separate; complete patch operation is not certified.
The historical 22 GUI/56 adapter proofs are unchanged, and their counters are
not added to these physical assertions. No MIDI transmission or master operation
is inferred from an SD save or preparation of a SysEx frame.
