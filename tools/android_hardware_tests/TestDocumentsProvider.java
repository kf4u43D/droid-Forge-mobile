package fr.androforge.validation.hardwaretests;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Test-owned files only. Custom controls accept only the same-signed validation UID. */
public final class TestDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "fr.androforge.validation.hardwaretests.documents";
    private static final String TARGET = "fr.androforge.validation";
    private static final String FIXTURE_MARKER = ".ui-fixture";
    private static final byte[] FIXTURE_SIGNATURE = "droid-ui-fixture-v1\n".getBytes(StandardCharsets.US_ASCII);
    private static final String[] DOCUMENT_COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    private static final class State {
        final String id;
        final File directory;
        volatile boolean offline;
        volatile boolean uiFixture;
        boolean wrongName, disconnectAfterWrite;
        int creates, reads, writes, ejects;
        State(String id, File directory) { this.id = id; this.directory = directory; }
    }

    @Override public boolean onCreate() {
        // Fixtures belong to this provider process, never to the validation Activity.
        // Persist just the exact private UUID marker so a provider restart is safe too.
        try {
            File root = getContext().getFilesDir().getCanonicalFile();
            File[] candidates = root.listFiles();
            if (candidates != null) for (File candidate : candidates) {
                String name = candidate.getName();
                if (!name.startsWith("hardwaretests-")) continue;
                String id = name.substring("hardwaretests-".length());
                try { validId(id); } catch (IllegalArgumentException invalid) { continue; }
                File directory = candidate.getCanonicalFile();
                if (!directory.equals(candidate) || !root.equals(directory.getParentFile()) || !directory.isDirectory()) continue;
                File marker = new File(directory, FIXTURE_MARKER);
                if (!marker.isFile() || marker.length() != FIXTURE_SIGNATURE.length
                        || !directory.equals(marker.getCanonicalFile().getParentFile())) continue;
                byte[] signature = new byte[FIXTURE_SIGNATURE.length];
                try (FileInputStream input = new FileInputStream(marker)) {
                    int read = 0;
                    while (read < signature.length) {
                        int count = input.read(signature, read, signature.length - read);
                        if (count <= 0) break;
                        read += count;
                    }
                    if (read != signature.length || input.read() != -1 || !Arrays.equals(signature, FIXTURE_SIGNATURE)) continue;
                }
                State state = new State(id, directory); state.uiFixture = true; states.put(id, state);
            }
        } catch (IOException failure) { throw new IllegalStateException("Cannot restore private test fixtures", failure); }
        return true;
    }

    private static String title(State state) {
        return state.uiFixture ? "Validation DROID " + state.id.substring(0, 8) : "Private instrumentation test";
    }

    private void rootsChanged() {
        getContext().getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null);
    }

    private void enforceValidationCaller() {
        int caller = Binder.getCallingUid();
        // Our control service already validates the external caller before dropping identity.
        if (caller == getContext().getApplicationInfo().uid) return;
        PackageManager packages = getContext().getPackageManager();
        try {
            int target = packages.getApplicationInfo(TARGET, 0).uid;
            if (caller != target || packages.checkSignatures(caller, getContext().getApplicationInfo().uid)
                    != PackageManager.SIGNATURE_MATCH)
                throw new SecurityException("Only the signed validation process owns these tests");
        } catch (PackageManager.NameNotFoundException missing) { throw new SecurityException("Validation target not installed", missing); }
    }

    private static String validId(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value))
            throw new IllegalArgumentException("Canonical test UUID required");
        return value;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!method.startsWith("hardwaretests.")) return super.call(method, arg, extras);
        enforceValidationCaller();
        String id = validId(arg);
        long identity = Binder.clearCallingIdentity();
        try {
            String action = method.substring("hardwaretests.".length());
            State state = states.get(id);
            Bundle result = new Bundle();
            if (action.equals("prepare")) {
                if (state != null) throw new IllegalArgumentException("Test UUID already in use");
                File root = getContext().getFilesDir().getCanonicalFile();
                File directory = new File(root, "hardwaretests-" + id).getCanonicalFile();
                if (!root.equals(directory.getParentFile()) || !directory.mkdir())
                    throw new IOException("Cannot create the private test directory");
                state = new State(id, directory);
                states.put(id, state);
                result.putString("tree", DocumentsContract.buildTreeDocumentUri(AUTHORITY, id).toString());
                return result;
            }
            if (state == null) throw new IllegalArgumentException("Unknown test UUID");
            synchronized (state) {
                Uri tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, id);
                int read = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                int write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                if (action.equals("grantRead") || action.equals("grantReadWrite")) {
                    getContext().grantUriPermission(TARGET, tree, read | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                            | (action.equals("grantReadWrite") ? write : 0));
                } else if (action.equals("revoke")) {
                    getContext().revokeUriPermission(TARGET, tree, read | write);
                } else if (action.equals("seed")) {
                    byte[] bytes = extras == null ? null : extras.getByteArray("bytes");
                    if (bytes == null || bytes.length > 65536) throw new IllegalArgumentException("Bounded test bytes required");
                    try (FileOutputStream output = new FileOutputStream(new File(state.directory, "droid.ini"))) {
                        output.write(bytes); output.flush(); output.getFD().sync();
                    }
                } else if (action.equals("fault")) {
                    state.offline = extras != null && extras.getBoolean("offline");
                    state.wrongName = extras != null && extras.getBoolean("wrongName");
                    state.disconnectAfterWrite = extras != null && extras.getBoolean("disconnectAfterWrite");
                } else if (action.equals("fixture")) {
                    try (FileOutputStream output = new FileOutputStream(new File(state.directory, FIXTURE_MARKER))) {
                        output.write(FIXTURE_SIGNATURE); output.flush(); output.getFD().sync();
                    }
                    state.uiFixture = true;
                    result.putString("id", id); result.putString("title", title(state)); result.putString("tree", tree.toString());
                    rootsChanged();
                } else if (action.equals("stats")) {
                    result.putInt("creates", state.creates); result.putInt("reads", state.reads);
                    result.putInt("writes", state.writes); result.putInt("ejects", state.ejects);
                    File patch = new File(state.directory, "droid.ini");
                    result.putLong("patchBytes", patch.exists() ? patch.length() : -1);
                    result.putLong("wrongNameBytes", new File(state.directory, "renamed.ini").length());
                } else if (action.equals("cleanup") || action.equals("cleanupUiFixture")) {
                    if (action.equals("cleanupUiFixture") && !state.uiFixture)
                        throw new IllegalArgumentException("UUID does not designate a kept UI fixture");
                    getContext().revokeUriPermission(TARGET, tree, read | write);
                    for (String name : new String[]{"droid.ini", "renamed.ini", "droid-original.ini", FIXTURE_MARKER}) {
                        File file = new File(state.directory, name);
                        if (!state.directory.equals(file.getCanonicalFile().getParentFile()))
                            throw new IOException("Refusing a file outside the exact private fixture directory");
                        if (file.exists() && !file.delete()) throw new IOException("Cannot remove own test file");
                    }
                    if (!state.directory.delete()) throw new IOException("Cannot remove own empty test directory");
                    states.remove(id); result.putBoolean("cleaned", true);
                    rootsChanged();
                } else throw new IllegalArgumentException("Unknown private test control");
            }
            return result;
        } catch (IOException failure) { throw new IllegalStateException(failure); }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    private State state(String documentId) throws FileNotFoundException {
        int slash = documentId.indexOf('/');
        String id = slash < 0 ? documentId : documentId.substring(0, slash);
        State state = states.get(id);
        if (state == null || state.offline) throw new FileNotFoundException("Simulated test provider disconnection");
        if (slash >= 0 && !documentId.equals(id + "/droid.ini") && !documentId.equals(id + "/renamed.ini")
                && !(state.uiFixture && documentId.equals(id + "/droid-original.ini")))
            throw new FileNotFoundException("Outside the exact test file set");
        return state;
    }

    private File file(State state, String documentId) throws FileNotFoundException {
        if (documentId.equals(state.id)) return state.directory;
        return new File(state.directory, documentId.substring(state.id.length() + 1));
    }

    private void addDocument(MatrixCursor cursor, State state, String id) throws FileNotFoundException {
        File file = file(state, id);
        if (!file.exists()) throw new FileNotFoundException("Private test document missing");
        boolean directory = id.equals(state.id);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            if (column.equals(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) value = id;
            else if (column.equals(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) value = directory ? title(state) : file.getName();
            else if (column.equals(DocumentsContract.Document.COLUMN_MIME_TYPE)) value = directory ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream";
            else if (column.equals(DocumentsContract.Document.COLUMN_FLAGS)) value = directory ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
            else if (column.equals(DocumentsContract.Document.COLUMN_SIZE)) value = file.length();
            else if (column.equals(DocumentsContract.Document.COLUMN_LAST_MODIFIED)) value = file.lastModified();
            row.add(value);
        }
    }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection == null ? new String[]{DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        for (State state : states.values()) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) {
                Object value = null;
                if (column.equals(DocumentsContract.Root.COLUMN_ROOT_ID) || column.equals(DocumentsContract.Root.COLUMN_DOCUMENT_ID)) value = state.id;
                else if (column.equals(DocumentsContract.Root.COLUMN_TITLE)) value = title(state);
                else if (column.equals(DocumentsContract.Root.COLUMN_SUMMARY)) value = "Private test files; no DROID hardware";
                else if (column.equals(DocumentsContract.Root.COLUMN_MIME_TYPES)) value = "*/*";
                else if (column.equals(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)) value = state.directory.getUsableSpace();
                else if (column.equals(DocumentsContract.Root.COLUMN_FLAGS)) value = DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                        | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD | DocumentsContract.Root.FLAG_LOCAL_ONLY;
                row.add(value);
            }
        }
        return cursor;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        State state = state(documentId);
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        synchronized (state) { addDocument(cursor, state, documentId); }
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder) throws FileNotFoundException {
        State state = state(parentId);
        if (!parentId.equals(state.id)) throw new FileNotFoundException("Only our UUID root has children");
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        synchronized (state) {
            for (String name : new String[]{"droid.ini", "renamed.ini", "droid-original.ini"})
                if (new File(state.directory, name).exists()) addDocument(cursor, state, state.id + "/" + name);
        }
        return cursor;
    }

    @Override public boolean isChildDocument(String parentId, String documentId) {
        State state = states.get(parentId);
        return state != null && (documentId.equals(parentId + "/droid.ini") || documentId.equals(parentId + "/renamed.ini")
                || state.uiFixture && documentId.equals(parentId + "/droid-original.ini"));
    }

    @Override public String createDocument(String parentId, String mimeType, String displayName) throws FileNotFoundException {
        State state = state(parentId);
        if (!parentId.equals(state.id) || !("droid.ini".equals(displayName)
                || state.uiFixture && "droid-original.ini".equals(displayName)))
            throw new FileNotFoundException("Unexpected test filename");
        synchronized (state) {
            String name = state.wrongName ? "renamed.ini" : displayName;
            try {
                if (!new File(state.directory, name).createNewFile()) throw new IOException("Test document already exists");
            } catch (IOException failure) { throw new FileNotFoundException(failure.toString()); }
            state.creates++;
            return state.id + "/" + name;
        }
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal cancellation) throws FileNotFoundException {
        State state = state(documentId);
        synchronized (state) {
            if (documentId.equals(state.id)) throw new FileNotFoundException("Cannot open directory");
            ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file(state, documentId), ParcelFileDescriptor.parseMode(mode));
            if (mode.contains("w")) { state.writes++; if (state.disconnectAfterWrite) state.offline = true; }
            else state.reads++;
            return descriptor;
        }
    }

    @Override public void ejectRoot(String rootId) { throw new UnsupportedOperationException("Test provider cannot eject a device"); }
}
