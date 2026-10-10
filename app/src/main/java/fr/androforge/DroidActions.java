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
        String state = snapshot == null ? "Recherche des périphériques USB…" : snapshot.summary;
        String sd = tree() == null ? "Carte SD : aucun dossier choisi." : "Carte SD : dossier autorisé par Android.";
        show(new AlertDialog.Builder(activity).setTitle("Connexion DROID")
                .setMessage(state + "\n\n" + sd + "\n\nX7 : carré/gauche pour la carte SD ; droite pour MIDI ; milieu pour déconnecter USB et charger le fichier de la carte après éjection.\n\nLe catalogue de l’éditeur est blue-7. La version du firmware du matériel n’est pas lue par ce protocole.")
                .setNeutralButton("Choisir la carte SD", (dialog, which) -> chooseTree(Purpose.READ))
                .setPositiveButton("Actualiser", (dialog, which) -> connection())
                .setNegativeButton("Fermer", null).create());
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
            host.error("X7 MIDI absent", "Aucun port DROID X7 MIDI disponible. En mode carré, le X7 expose la carte SD. Éjectez-la avant de passer le commutateur à droite pour l’activation MIDI, puis actualisez la connexion.");
            return;
        }
        if (endpoints.size() == 1) prepare(endpoints.get(0), null);
        else {
            String[] names = new String[endpoints.size()];
            for (int i = 0; i < names.length; i++) names[i] = endpoints.get(i).label;
            show(new AlertDialog.Builder(activity).setTitle("Choisir le X7 MIDI")
                    .setItems(names, (dialog, which) -> prepare(endpoints.get(which), null))
                    .setNegativeButton("Annuler", null).create());
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
        host.error("Des saisies ne sont pas appliquées", "Appliquez ou récupérez vos brouillons avant le déploiement. Le DROID reçoit uniquement le patch appliqué.");
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
            host.error("Carte SD inaccessible", message(exception));
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
                        throw new SecurityException("Le fichier doit être autorisé en écriture et en lecture pour vérifier l’export.");
                    if ((flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0)
                        activity.getContentResolver().takePersistableUriPermission(destination,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    rememberSafAction(SafAction.EXPORT, destination);
                } catch (RuntimeException exception) {
                    host.error("Export de l’original impossible", message(exception));
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
                throw new SecurityException("L’accès en lecture n’a pas été autorisé.");
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
                activity.getContentResolver().takePersistableUriPermission(selected,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            else activity.getContentResolver().takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            preferences.edit().putString("sd-tree", selected.toString()).apply();
        } catch (SecurityException exception) {
            host.error("Autorisation de carte SD non conservée", "Choisissez à nouveau le dossier de la carte et autorisez son accès. " + message(exception));
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
            if (token.isEmpty() || uriText.isEmpty()) throw new IllegalArgumentException("Le résultat du sélecteur est incomplet. Choisissez à nouveau le fichier ou la carte.");
            Uri uri = Uri.parse(uriText);
            if (!"content".equals(uri.getScheme())) throw new IllegalArgumentException("Le sélecteur n’a pas fourni un document Android valide.");
            activeSafToken = token;
            switch (action) {
                case READ: read(uri, token); break;
                case SAVE:
                    if (requireApplied()) prepare(null, uri, token);
                    else completeSafAction(token);
                    break;
                case EXPORT: exportBackupTo(uri, token); break;
                default: throw new IllegalArgumentException("Action du sélecteur non reconnue.");
            }
        } catch (RuntimeException exception) {
            completeSafAction(token);
            host.error("Reprise du sélecteur impossible", message(exception));
        }
    }

    private void rememberSafAction(SafAction action, Uri uri) {
        if (!preferences.edit().putString("pending-saf-action", action.name())
                .putString("pending-saf-uri", uri.toString())
                .putString("pending-saf-token", java.util.UUID.randomUUID().toString()).commit()) {
            // commit() changes the in-memory preferences even when persistence fails.
            preferences.edit().remove("pending-saf-action").remove("pending-saf-uri")
                    .remove("pending-saf-token").apply();
            host.error("Résultat du sélecteur non conservé", "La reprise n’a pas pu être sauvegardée. Choisissez à nouveau le fichier ou la carte.");
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
            main.post(() -> { if (!closed) host.error("Reprise non mise à jour", "L’action est terminée, mais son état n’a pas pu être sauvegardé."); });
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
                    show(new AlertDialog.Builder(activity).setTitle("Importer droid.ini de la carte ?")
                            .setMessage("Fichier lu : " + data.length + " octets.\n\nCe fichier est le patch enregistré sur la carte, qui peut différer d’un patch envoyé précédemment en MIDI. L’import remplace le document et ses brouillons ; une copie privée du document précédent sera conservée.")
                            .setNegativeButton("Conserver le patch", null)
                            .setNeutralButton("Exporter d’abord", (dialog, which) -> host.exportSource())
                            .setPositiveButton("Importer", (dialog, which) -> host.importSource(text)).create());
                });
            } catch (Exception | LinkageError | OutOfMemoryError exception) {
                failSaf(lease, safToken, "Lecture de la carte impossible", exception);
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
                java.text.DateFormat dates = java.text.DateFormat.getDateTimeInstance();
                for (int i = 0; i < names.length; i++)
                    names[i] = dates.format(new java.util.Date(records[i].timestampMillis)) + " · " + records[i].bytes + " octets";
                post(lease, () -> {
                    finish(lease);
                    if (records.length == 0) {
                        host.error("Sauvegardes DROID", "Aucun fichier de la carte n’a été remplacé avec sauvegarde dans cette installation.");
                        return;
                    }
                    show(new AlertDialog.Builder(activity).setTitle("Sauvegardes originales droid.ini")
                            .setItems(names, (dialog, which) -> show(new AlertDialog.Builder(activity)
                                    .setTitle("Récupérer une sauvegarde")
                                    .setMessage("Ouvrir dans l’éditeur exige un texte UTF-8 valide. L’export conserve tous les octets de l’original.")
                                    .setNegativeButton("Annuler", null)
                                    .setNeutralButton("Exporter l’original", (choice, action) -> exportBackup(records[which]))
                                    .setPositiveButton("Ouvrir dans l’éditeur", (choice, action) -> recover(records[which])).create()))
                            .setNegativeButton("Fermer", null).create());
                });
            } catch (Exception | OutOfMemoryError exception) { fail(lease, "Sauvegardes inaccessibles", exception); }
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
                    show(new AlertDialog.Builder(activity).setTitle("Récupérer l’original dans l’éditeur ?")
                            .setMessage("La carte reste inchangée. L’original remplacera le document de l’éditeur, dont une copie privée sera conservée. Vous pourrez ensuite exporter ce fichier.")
                            .setNegativeButton("Annuler", null)
                            .setPositiveButton("Récupérer", (dialog, which) -> host.importSource(source)).create());
                });
            } catch (Exception | OutOfMemoryError exception) { fail(lease, "Récupération impossible", exception); }
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
                        throw new IOException("L’instantané de sauvegarde n’a pas pu être vérifié.");
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
                    catch (RuntimeException exception) { host.error("Export indisponible", message(exception)); }
                });
            } catch (Exception | OutOfMemoryError exception) { fail(lease, "Préparation de l’export impossible", exception); }
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
                    if (output == null) throw new IOException("Le fournisseur n’a pas ouvert le fichier en écriture.");
                    output.write(bytes); output.flush();
                }
                try (InputStream input = activity.getContentResolver().openInputStream(destination)) {
                    if (!java.util.Arrays.equals(bytes, DroidSdIo.readBounded(input, 4 * 1024 * 1024)))
                        throw new IOException("La relecture de l’export ne correspond pas à la sauvegarde.");
                }
                post(lease, () -> {
                    finish(lease);
                    completeSafAction(safToken);
                    backupExport().delete();
                    show(new AlertDialog.Builder(activity).setTitle("Original exporté et vérifié")
                            .setMessage(bytes.length + " octets conservés à l’identique.")
                            .setPositiveButton("Fermer", null).create());
                });
            } catch (Exception | OutOfMemoryError exception) { failSaf(lease, safToken, "Export de l’original impossible", exception); }
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
                    String target = endpoint == null ? "Remplacer droid.ini sur la carte" : "Activer le patch sur " + endpoint.label;
                    String detail = endpoint == null
                            ? "L’original sera sauvegardé avant écriture. Le fichier écrit sera relu et comparé. Éjectez ensuite la carte avant de remettre le X7 au milieu."
                            : "L’envoi remplace le patch exécuté par le MASTER. Le protocole officiel ne renvoie pas d’accusé de réception ; vérifiez ensuite les voyants du MASTER.";
                    show(new AlertDialog.Builder(activity).setTitle(target + " ?")
                            .setMessage(bytes.length + " / 64 000 octets.\n\nLes commentaires et labels restent dans le document de l’éditeur ; ils sont retirés de la copie destinée au DROID.\n\n" + detail)
                            .setNegativeButton("Annuler", null)
                            .setPositiveButton(endpoint == null ? "Save to SD" : "Activate!", (dialog, which) -> {
                                if (endpoint == null) write(selected, source, bytes); else send(endpoint, source, frame);
                            }).create());
                });
            } catch (Exception | LinkageError | OutOfMemoryError exception) {
                failSaf(lease, safToken, "Déploiement refusé", exception);
            } finally { if (closed) finish(lease); }
        });
    }

    private void write(Uri selected, String source, byte[] bytes) {
        final DroidOperationGate.Lease lease = begin();
        if (lease == null) return;
        worker.execute(() -> {
            try {
                if (!source.equals(NativeBridge.source())) throw new IllegalStateException("Le patch a changé : préparez à nouveau le déploiement.");
                DroidSdStorage.Result result = storage.save(selected, bytes);
                post(lease, () -> {
                    finish(lease);
                    show(new AlertDialog.Builder(activity).setTitle("droid.ini écrit et vérifié")
                            .setMessage(result.bytes + " octets relus à l’identique." + (result.backupVerified ? " L’ancien fichier est sauvegardé dans le stockage privé de l’application." : "")
                                    + "\n\nÉjectez la carte avant de remettre le X7 au milieu pour charger le patch.")
                            .setNegativeButton("Fermer", null)
                            .setPositiveButton("Eject SD", (dialog, which) -> eject()).create());
                });
            } catch (Exception | LinkageError | OutOfMemoryError exception) {
                fail(lease, "Sauvegarde SD interrompue", exception);
            } finally { if (closed) finish(lease); }
        });
    }

    private void send(DroidMidiTransport.Endpoint endpoint, String source, byte[] frame) {
        final DroidOperationGate.Lease lease = begin();
        if (lease == null) return;
        midiLease = lease;
        worker.execute(() -> {
            try {
                if (!source.equals(NativeBridge.source())) throw new IllegalStateException("Le patch a changé : préparez à nouveau le déploiement.");
                if (closed) { finish(lease); return; }
                midi.send(endpoint, frame, 15000, new DroidMidiTransport.Completion() {
                    @Override public void onSent() {
                        midiLease = null;
                        finish(lease);
                        if (!closed) show(new AlertDialog.Builder(activity).setTitle("Transfert MIDI envoyé")
                                .setMessage("Les octets ont été remis au port MIDI Android. Vérifiez l’activation et l’absence d’erreur sur le MASTER : le protocole officiel ne fournit pas d’accusé de réception.")
                                .setPositiveButton("Fermer", null).create());
                    }
                    @Override public void onError(String error) {
                        midiLease = null;
                        finish(lease);
                        if (!closed) host.error("Transfert MIDI interrompu", error);
                    }
                });
            } catch (Exception | LinkageError | OutOfMemoryError exception) {
                midiLease = null;
                fail(lease, "Activation impossible", exception);
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
                    show(new AlertDialog.Builder(activity).setTitle("Éjection demandée à Android")
                            .setMessage("Attendez la confirmation d’Android avant de remettre le X7 au milieu. Ne retirez pas la carte pendant une écriture.")
                            .setPositiveButton("Fermer", null).create());
                });
            } catch (Exception exception) { fail(lease, "Éjection impossible", exception); }
            finally { if (closed) finish(lease); }
        });
    }

    private void showSystemEject() {
        show(new AlertDialog.Builder(activity).setTitle("Éjecter la carte avec Android")
                .setMessage("Dans le sélecteur Android, ouvrez le volet des emplacements et touchez l’icône Éjecter à côté de DROID. Attendez la confirmation ou la disparition du volume, puis revenez sans choisir de dossier. Remettez ensuite le X7 au milieu. Si l’action n’est pas proposée, utilisez les réglages ou la notification USB Android.")
                .setNegativeButton("Fermer", null)
                .setNeutralButton("Réglages", (dialog, which) -> {
                    try { activity.startActivity(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)); }
                    catch (RuntimeException exception) { host.error("Réglages indisponibles", message(exception)); }
                })
                .setPositiveButton("Ouvrir le sélecteur", (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    intent.putExtra(DocumentsContract.EXTRA_PROMPT, "Touchez Éjecter à côté de DROID, puis revenez sans choisir de dossier.");
                    Uri selected = tree();
                    if (selected != null) {
                        try { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                                DocumentsContract.buildDocumentUriUsingTree(selected, DocumentsContract.getTreeDocumentId(selected))); }
                        catch (IllegalArgumentException ignored) { /* Let Android show its default location. */ }
                    }
                    try { activity.startActivityForResult(intent, EJECT_PICKER); }
                    catch (RuntimeException exception) { host.error("Sélecteur indisponible", message(exception)); }
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
