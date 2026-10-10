package fr.androforge;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.DocumentsContract;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

/** Access only a user-granted SAF tree. Call all methods from the IO worker. */
public final class DroidSdStorage {
    public static final String PATCH_FILENAME = "droid.ini";
    public static final int MAX_READ_BYTES = DroidSdIo.MAX_READ_BYTES;
    private static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";
    private static final int MAX_DIRECTORY_ENTRIES = 4096;
    private static final int MAX_BACKUPS = 128;
    private static final long MAX_BACKUP_BYTES = 32L * 1024 * 1024;

    private final Context context;
    private final ContentResolver resolver;

    public DroidSdStorage(Context context) {
        if (context == null) throw new IllegalArgumentException("Missing context");
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
        this.resolver = this.context.getContentResolver();
    }

    public static final class Result {
        public final int bytes;
        public final boolean backupVerified;
        public final boolean changed;
        public final String name;
        public final String sha256;
        public final File backupFile;
        private Result(DroidSdIo.Outcome result) {
            bytes = result.bytes;
            backupVerified = result.backupVerified;
            changed = result.changed;
            name = PATCH_FILENAME;
            sha256 = result.sha256;
            backupFile = result.backupFile;
        }
    }

    public static final class SaveException extends IOException {
        public final File backupFile;
        public final boolean mayHaveChanged;
        private SaveException(DroidSdIo.Failure failure) {
            super(failure.getMessage(), failure);
            backupFile = failure.backupFile;
            mayHaveChanged = failure.mayHaveChanged;
        }
    }

    public static final class BackupRecord {
        public final File file;
        public final long bytes;
        public final long timestampMillis;
        public final String label;
        private BackupRecord(File file) {
            this.file = file;
            bytes = file.length();
            timestampMillis = file.lastModified();
            label = "Original droid.ini";
        }
    }

    /** Lists completed private backups, newest first; never deletes a backup. */
    public BackupRecord[] listBackups() throws IOException {
        File directory = backupDirectory(false);
        if (!directory.exists()) return new BackupRecord[0];
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Le dossier des sauvegardes privées n’est pas lisible.");
        ArrayList<BackupRecord> result = new ArrayList<>();
        for (File file : files) {
            if (!isBackupName(file.getName()) || !file.isFile()) continue;
            File canonical = file.getCanonicalFile();
            if (!directory.equals(canonical.getParentFile()))
                throw new IOException("Un chemin de sauvegarde sort du stockage privé autorisé.");
            result.add(new BackupRecord(canonical));
        }
        result.sort(Comparator.comparingLong((BackupRecord backup) -> backup.timestampMillis).reversed()
                .thenComparing(backup -> backup.file.getName()));
        return result.toArray(new BackupRecord[0]);
    }

    /** Raw bytes allow explicit UTF-8 validation by the editor or lossless binary export. */
    public byte[] readBackup(BackupRecord backup) throws IOException {
        if (backup == null || backup.file == null || !isBackupName(backup.file.getName()))
            throw new IOException("La sauvegarde sélectionnée est invalide.");
        File directory = backupDirectory(false);
        File file = backup.file.getCanonicalFile();
        if (!directory.equals(file.getParentFile()) || !file.isFile())
            throw new IOException("La sauvegarde n’est plus accessible dans le stockage privé autorisé.");
        try (InputStream input = new FileInputStream(file)) {
            return DroidSdIo.readBounded(input, MAX_READ_BYTES);
        }
    }

    /** Returns raw bytes, without UTF-8 conversion, BOM or newline normalization. */
    public byte[] read(Uri treeUri) throws IOException {
        try {
            Uri parent = directory(treeUri);
            Uri patch = findPatch(treeUri, parent);
            if (patch == null) throw new IOException("Aucun fichier droid.ini dans le dossier sélectionné.");
            return readFile(patch);
        } catch (RuntimeException exception) {
            throw accessFailure(exception);
        }
    }

    /** Explicit save only: verified private backup before an existing file is overwritten. */
    public Result save(Uri treeUri, byte[] deployed) throws IOException {
        // Validation precedes any document creation or access to private backups.
        DroidSdIo.validateDeployment(deployed);
        final Uri parent;
        try { parent = directory(treeUri); }
        catch (RuntimeException exception) { throw accessFailure(exception); }
        try {
            DroidSdIo.Outcome result = DroidSdIo.save(new DroidSdIo.Backend() {
                private Uri expectedPatch;
                private boolean inspected;

                @Override public byte[] read() throws IOException {
                    Uri current = findPatch(treeUri, parent);
                    if (!inspected) {
                        expectedPatch = current;
                        inspected = true;
                    } else if (expectedPatch == null ? current != null : !expectedPatch.equals(current)) {
                        throw new IOException("Le fichier droid.ini a été remplacé pendant la sauvegarde.");
                    }
                    return current == null ? null : readFile(current);
                }

                @Override public DroidSdIo.Backup backup(byte[] original) throws IOException {
                    File file = createBackup(original);
                    try (InputStream input = new FileInputStream(file)) {
                        return new DroidSdIo.Backup(file, DroidSdIo.readBounded(input, MAX_READ_BYTES));
                    }
                }

                @Override public void write(byte[] bytes) throws IOException {
                    if (expectedPatch == null) {
                        expectedPatch = DocumentsContract.createDocument(resolver, parent,
                                "application/octet-stream", PATCH_FILENAME);
                        if (expectedPatch == null) throw new IOException("La création de droid.ini a échoué.");
                        ensurePatchName(expectedPatch);
                    }
                    ParcelFileDescriptor descriptor = resolver.openFileDescriptor(expectedPatch, "wt");
                    if (descriptor == null) throw new IOException("L’ouverture de droid.ini en écriture a échoué.");
                    try (ParcelFileDescriptor.AutoCloseOutputStream output =
                                 new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                        output.write(bytes);
                        output.flush();
                        descriptor.getFileDescriptor().sync();
                    }
                }
            }, deployed);
            return new Result(result);
        } catch (DroidSdIo.Failure failure) { throw new SaveException(failure); }
    }

    /** Tree permission does not imply permission to eject its separate root URI. */
    public boolean canEject(Uri treeUri) {
        try {
            Uri root = ejectableRoot(treeUri);
            if (root == null || context.checkUriPermission(root, Process.myPid(), Process.myUid(),
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) return false;
            try (Cursor cursor = resolver.query(root,
                    new String[]{DocumentsContract.Root.COLUMN_FLAGS}, null, null, null)) {
                return cursor != null && cursor.moveToFirst()
                        && (cursor.getInt(0) & DocumentsContract.Root.FLAG_SUPPORTS_EJECT) != 0;
            }
        } catch (RuntimeException exception) { return false; }
    }

    /** Never treats an unsupported or denied request as an ejection success. */
    public void ejectRoot(Uri treeUri) throws IOException {
        if (!canEject(treeUri))
            throw new IOException("L’éjection n’est pas autorisée par le fournisseur. Utilisez l’éjection du stockage dans Android.");
        try { DocumentsContract.ejectRoot(resolver, ejectableRoot(treeUri)); }
        catch (RuntimeException exception) { throw accessFailure(exception); }
    }

    private Uri directory(Uri treeUri) throws IOException {
        if (treeUri == null || !"content".equals(treeUri.getScheme())
                || !DocumentsContract.isTreeUri(treeUri) || treeUri.getAuthority() == null)
            throw new IOException("Sélectionnez un dossier avec le sélecteur Android.");
        String id = DocumentsContract.getTreeDocumentId(treeUri);
        if (id == null || id.isEmpty() || id.length() > 4096)
            throw new IOException("L’identifiant du dossier sélectionné est invalide.");
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
        try (Cursor cursor = resolver.query(parent,
                new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()
                    || !DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(0)))
                throw new IOException("Le dossier sélectionné n’est pas accessible.");
        }
        return parent;
    }

    private Uri findPatch(Uri treeUri, Uri parent) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
                DocumentsContract.getDocumentId(parent));
        Uri match = null;
        try (Cursor cursor = resolver.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS}, null, null, null)) {
            if (cursor == null) throw new IOException("Le contenu du dossier n’est pas accessible.");
            int count = 0;
            while (cursor.moveToNext()) {
                if (++count > MAX_DIRECTORY_ENTRIES)
                    throw new IOException("Le dossier contient trop d’éléments pour une recherche fiable de droid.ini.");
                String name = cursor.getString(1);
                if (!isPatchName(name)) continue;
                if (match != null) throw new IOException("Plusieurs fichiers correspondent à droid.ini. Choisissez un dossier sans ambiguïté.");
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))
                        || (cursor.getInt(3) & DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0)
                    throw new IOException("droid.ini n’est pas un fichier directement lisible.");
                String id = cursor.getString(0);
                if (id == null || id.isEmpty()) throw new IOException("L’identifiant de droid.ini est invalide.");
                match = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
            }
        }
        return match;
    }

    private byte[] readFile(Uri file) throws IOException {
        try (InputStream input = resolver.openInputStream(file)) {
            return DroidSdIo.readBounded(input, MAX_READ_BYTES);
        }
    }

    private void ensurePatchName(Uri file) throws IOException {
        try (Cursor cursor = resolver.query(file,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || !isPatchName(cursor.getString(0)))
                throw new IOException("Le fournisseur n’a pas conservé le nom droid.ini ; aucun texte n’a été écrit.");
        }
    }

    private File createBackup(byte[] original) throws IOException {
        File directory = backupDirectory(true);
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Le dossier des sauvegardes privées n’est pas lisible.");
        long total = 0;
        int count = 0;
        for (File file : files) {
            if (!file.isFile()) continue;
            total += file.length();
            count++;
        }
        if (count >= MAX_BACKUPS || original.length > MAX_BACKUP_BYTES - total)
            throw new IOException("Le budget des sauvegardes SD privées est atteint. Exportez et libérez vos sauvegardes avant de remplacer droid.ini.");
        File file = new File(directory, "droid-sd-" + UUID.randomUUID() + ".ini");
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            output.write(original);
            output.flush();
            output.getFD().sync();
            atomic.finishWrite(output);
            output = null;
            return file;
        } catch (IOException | RuntimeException exception) {
            if (output != null) atomic.failWrite(output);
            if (exception instanceof IOException) throw (IOException) exception;
            throw accessFailure((RuntimeException) exception);
        }
    }

    private File backupDirectory(boolean create) throws IOException {
        File privateRoot = context.getFilesDir().getCanonicalFile();
        File directory = new File(privateRoot, "droid-sd-backups").getCanonicalFile();
        if (!privateRoot.equals(directory.getParentFile()))
            throw new IOException("Le dossier de sauvegarde sort du stockage privé autorisé.");
        if (create && !directory.isDirectory() && !directory.mkdirs())
            throw new IOException("La sauvegarde privée de droid.ini ne peut pas être créée.");
        return directory;
    }

    private static boolean isBackupName(String name) {
        if (name == null || !name.startsWith("droid-sd-") || !name.endsWith(".ini")) return false;
        String identifier = name.substring(9, name.length() - 4);
        try { return identifier.length() == 36 && UUID.fromString(identifier).toString().equals(identifier); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private static boolean isPatchName(String name) {
        return name != null && PATCH_FILENAME.equals(name.toLowerCase(Locale.ROOT));
    }

    private static Uri ejectableRoot(Uri treeUri) {
        if (treeUri == null || !DocumentsContract.isTreeUri(treeUri)
                || !EXTERNAL_STORAGE_AUTHORITY.equals(treeUri.getAuthority())) return null;
        String id = DocumentsContract.getTreeDocumentId(treeUri);
        int colon = id.indexOf(':');
        // Only an explicitly selected volume root can be considered for whole-volume eject.
        if (colon <= 0 || colon != id.length() - 1 || "primary".equals(id.substring(0, colon))) return null;
        return DocumentsContract.buildRootUri(treeUri.getAuthority(), id.substring(0, colon));
    }

    private static IOException accessFailure(RuntimeException exception) {
        return new IOException("Le stockage n’est plus accessible ou l’autorisation Android a été refusée.", exception);
    }
}
