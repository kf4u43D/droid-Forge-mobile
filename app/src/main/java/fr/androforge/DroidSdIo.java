package fr.androforge;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Byte-level policy shared by the SAF adapter and its host-side fault tests. */
final class DroidSdIo {
    static final int MAX_READ_BYTES = 4 * 1024 * 1024;
    static final int MAX_DEPLOY_BYTES = 64_000;

    interface Backend {
        /** Null denotes an absent droid.ini; an empty array denotes an empty file. */
        byte[] read() throws IOException;
        Backup backup(byte[] original) throws IOException;
        void write(byte[] deployed) throws IOException;
    }

    static final class Backup {
        final File file;
        final byte[] reread;
        Backup(File file, byte[] reread) { this.file = file; this.reread = reread; }
    }

    static final class Outcome {
        final int bytes;
        final String sha256;
        final File backupFile;
        final boolean backupVerified;
        final boolean changed;
        Outcome(byte[] deployed, File backupFile, boolean backupVerified, boolean changed) {
            this.bytes = deployed.length;
            this.sha256 = sha256(deployed);
            this.backupFile = backupFile;
            this.backupVerified = backupVerified;
            this.changed = changed;
        }
    }

    static final class Failure extends IOException {
        final File backupFile;
        final boolean mayHaveChanged;
        Failure(String message, Throwable cause, File backupFile, boolean mayHaveChanged) {
            super(message, cause);
            this.backupFile = backupFile;
            this.mayHaveChanged = mayHaveChanged;
        }
    }

    private DroidSdIo() {}

    static Outcome save(Backend backend, byte[] input) throws Failure {
        File backupFile = null;
        boolean mayHaveChanged = false;
        try {
            if (input == null || input.length == 0 || input.length > MAX_DEPLOY_BYTES)
                validateDeployment(input);
            byte[] deployed = input.clone();
            validateDeployment(deployed);
            byte[] original = backend.read();
            if (original != null) {
                if (original.length > MAX_READ_BYTES)
                    throw new IOException("The droid.ini file exceeds the read limit (4 MiB).");
                original = original.clone();
                if (Arrays.equals(original, deployed))
                    return new Outcome(deployed, null, false, false);
                Backup backup = backend.backup(original.clone());
                if (backup == null || backup.file == null)
                    throw new IOException("The safety backup of droid.ini is unavailable.");
                backupFile = backup.file;
                if (!Arrays.equals(original, backup.reread))
                    throw new IOException("The safety backup read back does not match the original.");
            }
            if (!Arrays.equals(original, backend.read()))
                throw new IOException("droid.ini changed during preparation. Read the card again before saving.");
            mayHaveChanged = true;
            backend.write(deployed.clone());
            if (!Arrays.equals(deployed, backend.read()))
                throw new IOException("The droid.ini file read back does not match the bytes written.");
            return new Outcome(deployed, backupFile, original != null, true);
        } catch (IOException | RuntimeException exception) {
            String message = exception.getMessage();
            if (message == null || message.isEmpty()) message = "SD card access failed.";
            if (mayHaveChanged) message += backupFile == null
                    ? " The file may be incomplete; do not reload the MASTER before checking it."
                    : " The file may be incomplete; the original is retained in the private backup.";
            throw new Failure(message, exception, backupFile, mayHaveChanged);
        }
    }

    static void validateDeployment(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0)
            throw new IOException("The deployment patch is empty.");
        if (bytes.length > MAX_DEPLOY_BYTES)
            throw new IOException("The deployment patch exceeds 64,000 bytes.");
        for (byte value : bytes) {
            int character = value & 255;
            if (character != '\n' && character != '\t' && (character < 32 || character > 126))
                throw new IOException("The deployment patch must be ASCII text prepared by the engine.");
        }
    }

    static byte[] readBounded(InputStream input, int limit) throws IOException {
        if (input == null) throw new IOException("Le fournisseur n’a pas ouvert le fichier.");
        if (limit < 0) throw new IllegalArgumentException("Negative limit");
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        for (;;) {
            int count = input.read(chunk);
            if (count == -1) break;
            if (count == 0) {
                int single = input.read();
                if (single == -1) break;
                chunk[0] = (byte) single;
                count = 1;
            }
            if (count > limit - total)
                throw new IOException("The file exceeds the allowed read limit.");
            total += count;
            result.write(chunk, 0, count);
        }
        return result.toByteArray();
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] alphabet = "0123456789abcdef".toCharArray();
            char[] result = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                result[2 * i] = alphabet[(digest[i] & 255) >>> 4];
                result[2 * i + 1] = alphabet[digest[i] & 15];
            }
            return new String(result);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
