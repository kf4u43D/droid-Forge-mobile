package fr.androforge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.AtomicFile;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User initiated deployment actions. Device discovery never sends anything. */
final class DroidActions implements AutoCloseable {
    static final int CHOOSE_SD = 103;
    private static final int EXPORT_BACKUP = 104;
    private static final int EJECT_PICKER = 105;
    interface Host {
        boolean ready();
        boolean hasDrafts();
        void busy(boolean value);
        void show(AlertDialog dialog);
        void error(String title, String message);
        void importSource(String source);
        void exportSource();
    }
    private enum Purpose { READ, SAVE }
    private enum SafAction { READ, SAVE, EXPORT }
    private final Activity activity;
    private final Host host;
    private final DroidMidiTransport midi;
    private final DroidSdStorage storage;
    private final SharedPreferences preferences;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable resumeSaf = this::onHostReady;
    private DroidMidiTransport.Snapshot snapshot;
    private volatile boolean closed;
    private volatile DroidOperationGate.Lease activeLease, midiLease;
    private String activeSafToken;

    DroidActions(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        preferences = activity.getSharedPreferences("droid-connection", 0);
        storage = new DroidSdStorage(activity.getApplicationContext());
        midi = new DroidMidiTransport(activity.getApplicationContext(), value -> {
            if (!closed) snapshot = value;
        });
        midi.start();
    }

    void connection() {
        midi.refresh();
        String state = snapshot == null ? "Searching for USB devices…" : snapshot.summary;
        String sd = tree() == null ? "SD card: no folder selected." : "SD card: folder access granted by Android.";
        show(new AlertDialog.Builder(activity).setTitle("DROID connection")
                .setMessage(state + "\n\n" + sd + "\n\nX7: square/left for the SD card; right for MIDI; centre to disconnect USB and load the file from the card after ejecting.\n\nThe editor uses the blue-7 catalogue. This protocol does not read the hardware firmware version.")
                .setNeutralButton("Choose SD card", (dialog, which) -> chooseTree(Purpose.READ))
                .setPositiveButton("Refresh", (dialog, which) -> connection())
                .setNegativeButton("Close", null).create());
    }

    void readPatch() {
        if (!canStart()) return;
        Uri selected = tree();
        if (selected == null) chooseTree(Purpose.READ); else read(selected);
    }

    void savePatch() {
        if (!canStart() || !requireApplied()) return;
        Uri selected = tree();
        if (selected == null) chooseTree(Purpose.SAVE); else prepare(null, selected);
    }

    void activate() {
        if (!canStart() || !requireApplied()) return;
        midi.refresh();
        List<DroidMidiTransport.Endpoint> endpoints = new ArrayList<>();
        if (snapshot != null) for (DroidMidiTransport.Endpoint endpoint : snapshot.endpoints)
            if (endpoint.x7) endpoints.add(endpoint);
        if (endpoints.isEmpty()) {
            host.error("X7 MIDI unavailable", "No DROID X7 MIDI port is available. In square mode, the X7 exposes the SD card. Eject it before switching to the right for MIDI activation, then refresh the connection.");
            return;
        }
        if (endpoints.size() == 1) prepare(endpoints.get(0), null);
        else {
            String[] names = new String[endpoints.size()];
            for (int i = 0; i < names.length; i++) names[i] = endpoints.get(i).label;
            show(new AlertDialog.Builder(activity).setTitle("Choose X7 MIDI")
                    .setItems(names, (dialog, which) -> prepare(endpoints.get(which), null))
                    .setNegativeButton("Cancel", null).create());
        }
    }

    private boolean canStart() {
        // An authorised picker result owns the immutable export snapshot until it finishes.
        return canStartOperation() && preferences.getString("pending-saf-action", "").isEmpty();
    }

    private boolean canStartOperation() {
        return !closed && activeLease == null && !DroidOperationGate.occupied() && host.ready();
    }

    private boolean requireApplied() {
        if (!host.hasDrafts()) return true;
        host.error("Some edits have not been applied", "Apply or recover your drafts before deployment. DROID receives only the applied patch.");
        return false;
    }

    private void chooseTree(Purpose purpose) {
        if (!canStart()) return;
        preferences.edit().putString("pending-purpose", purpose.name()).apply();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try { activity.startActivityForResult(intent, CHOOSE_SD); }
        catch (RuntimeException exception) {
            preferences.edit().remove("pending-purpose").apply();
            host.error("SD card inaccessible", message(exception));
        }
    }

    boolean onActivityResult(int request, int result, Intent data) {
        // Returning from DocumentsUI does not confirm that the volume was ejected.
        if (request == EJECT_PICKER) return true;
        if (request == EXPORT_BACKUP) {
            if (result == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri destination = data.getData();
                int flags = data.getFlags();
                try {
                    int required = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                    if ((flags & required) != required)
                        throw new SecurityException("Read and write access to the file is required to verify the export.");
                    if ((flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0)
                        activity.getContentResolver().takePersistableUriPermission(destination,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    rememberSafAction(SafAction.EXPORT, destination);
                } catch (RuntimeException exception) {
                    host.error("Original export failed", message(exception));
                }
            }
            else backupExport().delete();
            return true;
        }
        if (request != CHOOSE_SD) return false;
        String purpose = preferences.getString("pending-purpose", Purpose.READ.name());
        preferences.edit().remove("pending-purpose").apply();
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri selected = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0)
                throw new SecurityException("Read access was not granted.");
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
                activity.getContentResolver().takePersistableUriPermission(selected,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            else activity.getContentResolver().takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            preferences.edit().putString("sd-tree", selected.toString()).apply();
        } catch (SecurityException exception) {
            host.error("SD card permission was not retained", "Choose the card folder again and grant access. " + message(exception));
            return true;
        }
        rememberSafAction(Purpose.SAVE.name().equals(purpose) ? SafAction.SAVE : SafAction.READ, selected);
        return true;
    }

    /** Activity results can arrive before the editor's asynchronous initialisation finishes. */
    void onHostReady() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(resumeSaf); return; }
        main.removeCallbacks(resumeSaf);
        if (closed || activeSafToken != null) return;
        String actionName = preferences.getString("pending-saf-action", "");
        if (actionName.isEmpty()) return;
        String token = preferences.getString("pending-saf-token", "");
        String uriText = preferences.getString("pending-saf-uri", "");
        if (!canStartOperation()) { main.postDelayed(resumeSaf, 500); return; }
        try {
            SafAction action = SafAction.valueOf(actionName);
            if (token.isEmpty() || uriText.isEmpty()) throw new IllegalArgumentException("The picker result is incomplete. Choose the file or card again.");
            Uri uri = Uri.parse(uriText);
            if (!"content".equals(uri.getScheme())) throw new IllegalArgumentException("The picker did not provide a valid Android document.");
            activeSafToken = token;
            switch (action) {
                case READ: read(uri, token); break;
                case SAVE:
                    if (requireApplied()) prepare(null, uri, token);
                    else completeSafAction(token);
                    break;
                case EXPORT: exportBackupTo(uri, token); break;
                default: throw new IllegalArgumentException("Unrecognised picker action.");
            }
        } catch (RuntimeException exception) {
            completeSafAction(token);
            host.error("Could not resume the picker result", message(exception));
        }
    }

    private void rememberSafAction(SafAction action, Uri uri) {
        if (!preferences.edit().putString("pending-saf-action", action.name())
                .putString("pending-saf-uri", uri.toString())
                .putString("pending-saf-token", java.util.UUID.randomUUID().toString()).commit()) {
            // commit() changes the in-memory preferences even when persistence fails.
            preferences.edit().remove("pending-saf-action").remove("pending-saf-uri")
                    .remove("pending-saf-token").apply();
            host.error("Picker result was not retained", "The pending action could not be saved. Choose the file or card again.");
            return;
        }
        onHostReady();
    }

    private void completeSafAction(String token) {
        if (token == null) return;
        if (token.equals(activeSafToken)) activeSafToken = null;
        if (!token.equals(preferences.getString("pending-saf-token", ""))) {
            // A later picker result belongs to its own token and must still be resumed.
            if (!closed) main.post(resumeSaf);
            return;
        }
        if (!preferences.edit().remove("pending-saf-action").remove("pending-saf-uri")
                .remove("pending-saf-token").commit()) {
            main.post(() -> { if (!closed) host.error("Pending action status was not updated", "The action has finished, but its status could not be saved."); });
        }
    }

    private void retrySafAction(String token) {
        if (token == null) return;
        if (token.equals(activeSafToken)) activeSafToken = null;
        if (!closed) main.postDelayed(resumeSaf, 500);
    }

    private Uri tree() {
        String value = preferences.getString("sd-tree", "");
        return value.isEmpty() ? null : Uri.parse(value);
    }

    private DroidOperationGate.Lease begin() {
        return begin(null);
    }

    private DroidOperationGate.Lease begin(String safToken) {
        if (safToken == null) {
            if (!canStart()) return null;
        } else if (!canStartOperation() || !safToken.equals(activeSafToken)
                || !safToken.equals(preferences.getString("pending-saf-token", ""))) return null;
        DroidOperationGate.Lease lease = DroidOperationGate.acquire();
        if (lease == null) return null;
        activeLease = lease;
        host.busy(true);
        return lease;
    }

    private void finish(DroidOperationGate.Lease lease) {
        if (lease.release() && activeLease == lease) {
            activeLease = null;
            if (!closed) host.busy(false);
        }
    }

    private void post(DroidOperationGate.Lease lease, Runnable runnable) {
        activity.runOnUiThread(() -> { if (!closed) runnable.run(); else finish(lease); });
    }

    private void read(Uri selected) {
        read(selected, null);
    }

    private void read(Uri selected, String safToken) {
        final DroidOperationGate.Lease lease = begin(safToken);
        if (lease == null) { retrySafAction(safToken); return; }
        worker.execute(() -> {
            try {
                byte[] data = storage.read(selected);
                String text = PatchTextCodec.read(new ByteArrayInputStream(data), 4 * 1024 * 1024);
                post(lease, () -> {
                    finish(lease);
                    completeSafAction(safToken);
                    show(new AlertDialog.Builder(activity).setTitle("Import droid.ini from the card?")
                            .setMessage("File read: " + data.length + " bytes.\n\nThis file is the patch saved on the card, which may differ from a patch previously sent over MIDI. Importing replaces the document and its drafts; a private copy of the previous document will be retained.")
                            .setNegativeButton("Keep patch", null)
                            .setNeutralButton("Export first", (dialog, which) -> host.exportSource())
                            .setPositiveButton("Import", (dialog, which) -> host.importSource(text)).create());
                });
            } catch (Exception | LinkageError | OutOfMemoryError exception) {
                failSaf(lease, safToken, "Could not read the card", exception);
            } finally { if (closed) finish(lease); }
        });
    }

    void backups() {
        final DroidOperationGate.Lease lease = begin();
        if (lease == null) return;
        worker.execute(() -> {
            try {
                DroidSdStorage.BackupRecord[] records = storage.listBackups();
                String[] names = new String[records.length];
                java.text.DateFormat dates = java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.DEFAULT, java.text.DateFormat.DEFAULT, java.util.Locale.ENGLISH);
                for (int i = 0; i < names.length; i++)
                    names[i] = dates.format(new java.util.Date(records[i].timestampMillis)) + " · " + records[i].bytes + " bytes";
                post(lease, () -> {
                    finish(lease);
                    if (records.length == 0) {
                        host.error("DROID backups", "No card file has been replaced with a backup in this installation.");
                        return;
                    }
                    show(new AlertDialog.Builder(activity).setTitle("Original droid.ini backups")
                            .setItems(names, (dialog, which) -> show(new AlertDialog.Builder(activity)
                                    .setTitle("Recover a backup")
                                    .setMessage("Opening in the editor requires valid UTF-8 text. Export preserves every byte of the original.")
                                    .setNegativeButton("Cancel", null)
                                    .setNeutralButton("Export original", (choice, action) -> exportBackup(records[which]))
                                    .setPositiveButton("Open in editor", (choice, action) -> recover(records[which])).create()))
                            .setNegativeButton("Close", null).create());
                });
            } catch (Exception | OutOfMemoryError exception) { fail(lease, "Backups inaccessible", exception); }
            finally { if (closed) finish(lease); }
        });
    }

    private void recover(DroidSdStorage.BackupRecord record) {
        final DroidOperationGate.Lease lease = begin();
        if (lease == null) return;
        worker.execute(() -> {
            try {
                byte[] bytes = storage.readBackup(record);
                String source = PatchTextCodec.read(new ByteArrayInputStream(bytes), 4 * 1024 * 1024);
                post(lease, () -> {
                    finish(lease);
                    show(new AlertDialog.Builder(activity).setTitle("Recover the original in the editor?")
                            .setMessage("The card remains unchanged. The original will replace the editor document, and a private copy of that document will be retained. You can then export this file.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Recover", (dialog, which) -> host.importSource(source)).create());
                });
            } catch (Exception | OutOfMemoryError exception) { fail(lease, "Recovery failed", exception); }
            finally { if (closed) finish(lease); }
        });
    }

    private AtomicFile backupExport() {
        return new AtomicFile(new File(activity.getFilesDir(), "droid-original-export.bin"));
    }

    private void exportBackup(DroidSdStorage.BackupRecord record) {
        final DroidOperationGate.Lease lease = begin();
        if (lease == null) return;
        worker.execute(() -> {
            try {
                byte[] bytes = storage.readBackup(record);
                AtomicFile snapshotFile = backupExport();
                FileOutputStream output = snapshotFile.startWrite();
                try { output.write(bytes); snapshotFile.finishWrite(output); }
                catch (IOException | RuntimeException failure) { snapshotFile.failWrite(output); throw failure; }
                try (InputStream input = snapshotFile.openRead()) {
                    if (!java.util.Arrays.equals(bytes, DroidSdIo.readBounded(input, 4 * 1024 * 1024)))
                        throw new IOException("The backup snapshot could not be verified.");
                }
                post(lease, () -> {
                    finish(lease);
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/octet-stream");
                    intent.putExtra(Intent.EXTRA_TITLE, "droid-original.ini");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    try { activity.startActivityForResult(intent, EXPORT_BACKUP); }
                    catch (RuntimeException exception) { host.error("Export unavailable", message(exception)); }
                });
            } catch (Exception | OutOfMemoryError exception) { fail(lease, "Could not prepare the export", exception); }
            finally { if (closed) finish(lease); }
        });
    }

    private void exportBackupTo(Uri destination, String safToken) {
        final DroidOperationGate.Lease lease = begin(safToken);
        if (lease == null) { retrySafAction(safToken); return; }
        worker.execute(() -> {
            try {
                byte[] bytes;
                try (InputStream input = backupExport().openRead()) { bytes = DroidSdIo.readBounded(input, 4 * 1024 * 1024); }
                try (OutputStream output = activity.getContentResolver().openOutputStream(destination, "wt")) {
                    if (output == null) throw new IOException("The provider did not open the file for writing.");
                    output.write(bytes); output.flush();
                }
                try (InputStream input = activity.getContentResolver().openInputStream(destination)) {
                    if (!java.util.Arrays.equals(bytes, DroidSdIo.readBounded(input, 4 * 1024 * 1024)))
                        throw new IOException("The exported file read back does not match the backup.");
                }
                post(lease, () -> {
                    finish(lease);
                    completeSafAction(safToken);
                    backupExport().delete();
                    show(new AlertDialog.Builder(activity).setTitle("Original exported and verified")
                            .setMessage(bytes.length + " bytes preserved exactly.")
                            .setPositiveButton("Close", null).create());
                });
            } catch (Exception | OutOfMemoryError exception) { failSaf(lease, safToken, "Original export failed", exception); }
            finally { if (closed) finish(lease); }
        });
    }

    private void prepare(DroidMidiTransport.Endpoint endpoint, Uri selected) {
        prepare(endpoint, selected, null);
    }

    private void prepare(DroidMidiTransport.Endpoint endpoint, Uri selected, String safToken) {
        final DroidOperationGate.Lease lease = begin(safToken);
        if (lease == null) { retrySafAction(safToken); return; }
        worker.execute(() -> {
            try {
                String source = NativeBridge.source();
                String deployment = NativeBridge.prepareDeployment();
                byte[] bytes = deployment.getBytes(StandardCharsets.US_ASCII);
                byte[] frame = endpoint == null ? null : NativeBridge.activationMessage(deployment);
                post(lease, () -> {
                    finish(lease);
                    completeSafAction(safToken);
                    String target = endpoint == null ? "Replace droid.ini on the card" : "Activate the patch on " + endpoint.label;
                    String detail = endpoint == null
                            ? "The original will be backed up before writing. The written file will be read back and compared. Then eject the card before returning the X7 switch to the centre."
                            : "Sending replaces the patch running on the MASTER. The official protocol does not return an acknowledgement; check the MASTER lights afterwards.";
                    show(new AlertDialog.Builder(activity).setTitle(target + " ?")
                            .setMessage(bytes.length + " / 64,000 bytes.\n\nComments and labels remain in the editor document; they are removed from the copy sent to DROID.\n\n" + detail)
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton(endpoint == null ? "Save to SD" : "Activate!", (dialog, which) -> {
                                if (endpoint == null) write(selected, source, bytes); else send(endpoint, source, frame);
                            }).create());
                });
            } catch (Exception | LinkageError | OutOfMemoryError exception) {
                failSaf(lease, safToken, "Deployment refused", exception);
            } finally { if (closed) finish(lease); }
        });
    }

    private void write(Uri selected, String source, byte[] bytes) {
        final DroidOperationGate.Lease lease = begin();
        if (lease == null) return;
        worker.execute(() -> {
            try {
                if (!source.equals(NativeBridge.source())) throw new IllegalStateException("The patch has changed: prepare the deployment again.");
                DroidSdStorage.Result result = storage.save(selected, bytes);
                post(lease, () -> {
                    finish(lease);
                    show(new AlertDialog.Builder(activity).setTitle("droid.ini written and verified")
                            .setMessage(result.bytes + " bytes read back exactly." + (result.backupVerified ? " The previous file is backed up in private app storage." : "")
                                    + "\n\nEject the card before returning the X7 switch to the centre to load the patch.")
                            .setNegativeButton("Close", null)
                            .setPositiveButton("Eject SD", (dialog, which) -> eject()).create());
                });
            } catch (Exception | LinkageError | OutOfMemoryError exception) {
                fail(lease, "SD save interrupted", exception);
            } finally { if (closed) finish(lease); }
        });
    }

    private void send(DroidMidiTransport.Endpoint endpoint, String source, byte[] frame) {
        final DroidOperationGate.Lease lease = begin();
        if (lease == null) return;
        midiLease = lease;
        worker.execute(() -> {
            try {
                if (!source.equals(NativeBridge.source())) throw new IllegalStateException("The patch has changed: prepare the deployment again.");
                if (closed) { finish(lease); return; }
                midi.send(endpoint, frame, 15000, new DroidMidiTransport.Completion() {
                    @Override public void onSent() {
                        midiLease = null;
                        finish(lease);
                        if (!closed) show(new AlertDialog.Builder(activity).setTitle("MIDI transfer sent")
                                .setMessage("The bytes were handed to the Android MIDI port. Check activation and any errors on the MASTER: the official protocol does not provide an acknowledgement.")
                                .setPositiveButton("Close", null).create());
                    }
                    @Override public void onError(String error) {
                        midiLease = null;
                        finish(lease);
                        if (!closed) host.error("MIDI transfer interrupted", error);
                    }
                });
            } catch (Exception | LinkageError | OutOfMemoryError exception) {
                midiLease = null;
                fail(lease, "Activation failed", exception);
            }
        });
    }

    void eject() {
        if (!canStart()) return;
        Uri selected = tree();
        if (selected == null) { showSystemEject(); return; }
        final DroidOperationGate.Lease lease = begin();
        if (lease == null) return;
        worker.execute(() -> {
            try {
                if (!storage.canEject(selected)) {
                    post(lease, () -> { finish(lease); showSystemEject(); });
                    return;
                }
                storage.ejectRoot(selected);
                post(lease, () -> {
                    finish(lease);
                    show(new AlertDialog.Builder(activity).setTitle("Ejection requested from Android")
                            .setMessage("Wait for Android confirmation before returning the X7 switch to the centre. Do not remove the card while it is being written.")
                            .setPositiveButton("Close", null).create());
                });
            } catch (Exception exception) { fail(lease, "Ejection failed", exception); }
            finally { if (closed) finish(lease); }
        });
    }

    private void showSystemEject() {
        show(new AlertDialog.Builder(activity).setTitle("Eject the card with Android")
                .setMessage("In the Android picker, open the locations panel and tap the Eject icon next to DROID. Wait for confirmation or for the volume to disappear, then return without choosing a folder. Return the X7 switch to the centre. If the action is unavailable, use Android settings or the USB notification.")
                .setNegativeButton("Close", null)
                .setNeutralButton("Settings", (dialog, which) -> {
                    try { activity.startActivity(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)); }
                    catch (RuntimeException exception) { host.error("Settings unavailable", message(exception)); }
                })
                .setPositiveButton("Open picker", (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    intent.putExtra(DocumentsContract.EXTRA_PROMPT, "Tap Eject next to DROID, then return without choosing a folder.");
                    Uri selected = tree();
                    if (selected != null) {
                        try { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                                DocumentsContract.buildDocumentUriUsingTree(selected, DocumentsContract.getTreeDocumentId(selected))); }
                        catch (IllegalArgumentException ignored) { /* Let Android show its default location. */ }
                    }
                    try { activity.startActivityForResult(intent, EJECT_PICKER); }
                    catch (RuntimeException exception) { host.error("Picker unavailable", message(exception)); }
                }).create());
    }

    private void fail(DroidOperationGate.Lease lease, String title, Throwable exception) {
        post(lease, () -> { finish(lease); host.error(title, message(exception)); });
        if (closed) finish(lease);
    }

    private void failSaf(DroidOperationGate.Lease lease, String token, String title, Throwable exception) {
        post(lease, () -> { finish(lease); completeSafAction(token); host.error(title, message(exception)); });
        if (closed) finish(lease);
    }

    private static String message(Throwable exception) {
        String text = exception.getMessage();
        return text == null || text.isEmpty() ? exception.getClass().getSimpleName() : text;
    }

    private void show(AlertDialog dialog) { if (!closed) host.show(dialog); }

    @Override public void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        midi.close();
        DroidOperationGate.Lease pending = midiLease;
        if (pending != null) finish(pending);
        worker.shutdown();
        // A started SD write completes verification in the application context.
        // A completed picker/dialog is never replayed after activity recreation.
    }
}
