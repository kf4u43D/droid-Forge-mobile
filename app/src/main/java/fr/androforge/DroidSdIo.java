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
                    throw new IOException("Le fichier droid.ini dépasse la limite de lecture (4 Mio).");
                original = original.clone();
                if (Arrays.equals(original, deployed))
                    return new Outcome(deployed, null, false, false);
                Backup backup = backend.backup(original.clone());
                if (backup == null || backup.file == null)
                    throw new IOException("La sauvegarde de sécurité de droid.ini n’est pas disponible.");
                backupFile = backup.file;
                if (!Arrays.equals(original, backup.reread))
                    throw new IOException("La relecture de la sauvegarde de sécurité ne correspond pas à l’original.");
            }
            if (!Arrays.equals(original, backend.read()))
                throw new IOException("droid.ini a changé pendant la préparation. Relisez la carte avant de sauvegarder.");
            mayHaveChanged = true;
            backend.write(deployed.clone());
            if (!Arrays.equals(deployed, backend.read()))
                throw new IOException("La relecture de droid.ini ne correspond pas aux octets écrits.");
            return new Outcome(deployed, backupFile, original != null, true);
        } catch (IOException | RuntimeException exception) {
            String message = exception.getMessage();
            if (message == null || message.isEmpty()) message = "L’accès à la carte SD a échoué.";
            if (mayHaveChanged) message += backupFile == null
                    ? " Le fichier peut être incomplet ; ne rechargez pas le master avant de le vérifier."
                    : " Le fichier peut être incomplet ; l’original est conservé dans la sauvegarde privée.";
            throw new Failure(message, exception, backupFile, mayHaveChanged);
        }
    }

    static void validateDeployment(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0)
            throw new IOException("Le patch de déploiement est vide.");
        if (bytes.length > MAX_DEPLOY_BYTES)
            throw new IOException("Le patch de déploiement dépasse 64 000 octets.");
        for (byte value : bytes) {
            int character = value & 255;
            if (character != '\n' && character != '\t' && (character < 32 || character > 126))
                throw new IOException("Le patch de déploiement doit être un texte ASCII préparé par le moteur.");
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
                throw new IOException("Le fichier dépasse la limite de lecture autorisée.");
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
