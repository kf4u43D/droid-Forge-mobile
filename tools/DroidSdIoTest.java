package fr.androforge;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Host-side transaction/fault tests; never opens Android storage or hardware. */
public final class DroidSdIoTest {
    private static int checks;
    private static final byte[] OLD = "\ufeff# Original préservé\r\n[copy]\r\noutput=O1\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEW = ascii("[copy]\ninput=1\noutput=O1\n");

    private interface Attempt { void run() throws Exception; }

    private static final class Fake implements DroidSdIo.Backend {
        byte[] current;
        byte[] backup;
        int reads, backups, writes;
        boolean backupFailure, backupCorruption, writeFailure, writeRuntime;
        int readFailureAt;
        int changeAt;
        byte[] changedSource;
        int corruption;
        byte[] mutateCaller;
        Fake(byte[] current) { this.current = current == null ? null : current.clone(); }
        @Override public byte[] read() throws IOException {
            reads++;
            if (reads == readFailureAt) throw new IOException("Read disconnected");
            if (reads == changeAt) current = changedSource == null ? null : changedSource.clone();
            if (reads == 1 && mutateCaller != null) Arrays.fill(mutateCaller, (byte) 'Z');
            if (current == null) return null;
            byte[] result = current.clone();
            if (writes > 0 && corruption == 1) result = Arrays.copyOf(result, result.length - 1);
            if (writes > 0 && corruption == 2) result = Arrays.copyOf(result, result.length + 1);
            if (writes > 0 && corruption == 3) result[0] ^= 1;
            return result;
        }
        @Override public DroidSdIo.Backup backup(byte[] original) throws IOException {
            backups++;
            if (backupFailure) throw new IOException("Backup full");
            backup = original.clone();
            byte[] reread = backup.clone();
            if (backupCorruption) reread[0] ^= 1;
            return new DroidSdIo.Backup(new File("private-original.ini"), reread);
        }
        @Override public void write(byte[] deployed) throws IOException {
            writes++;
            if (writeRuntime) throw new SecurityException("Permission lost");
            if (writeFailure) {
                current = Arrays.copyOf(deployed, 3);
                throw new IOException("USB disconnected during write");
            }
            current = deployed.clone();
            Arrays.fill(deployed, (byte) 'X'); // Caller must own an independent snapshot.
        }
    }

    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Check " + checks);
    }
    private static void rejects(Attempt attempt) throws Exception {
        boolean rejected = false;
        try { attempt.run(); } catch (IOException | IllegalArgumentException expected) { rejected = true; }
        check(rejected);
    }
    private static DroidSdIo.Failure fail(Fake backend) throws Exception {
        try { DroidSdIo.save(backend, NEW); }
        catch (DroidSdIo.Failure expected) { checks++; return expected; }
        throw new AssertionError("Expected a failed SD transaction");
    }

    public static void main(String[] args) throws Exception {
        Fake existing = new Fake(OLD);
        DroidSdIo.Outcome saved = DroidSdIo.save(existing, NEW);
        check(saved.changed && saved.backupVerified && saved.backupFile != null);
        check(saved.bytes == NEW.length && Arrays.equals(existing.current, NEW));
        check(Arrays.equals(existing.backup, OLD));
        check(existing.backups == 1 && existing.writes == 1 && existing.reads == 3);
        check(saved.sha256.equals(DroidSdIo.sha256(NEW)));
        check(DroidSdIo.sha256(ascii("abc")).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));

        Fake absent = new Fake(null);
        DroidSdIo.Outcome created = DroidSdIo.save(absent, NEW);
        check(created.changed && !created.backupVerified && created.backupFile == null);
        check(absent.backups == 0 && absent.writes == 1 && Arrays.equals(absent.current, NEW));
        Fake empty = new Fake(new byte[0]);
        check(DroidSdIo.save(empty, NEW).backupVerified && empty.backup.length == 0);

        Fake same = new Fake(NEW);
        DroidSdIo.Outcome unchanged = DroidSdIo.save(same, NEW);
        check(!unchanged.changed && !unchanged.backupVerified && unchanged.backupFile == null);
        check(same.reads == 1 && same.writes == 0 && same.backups == 0);

        for (int character = 0; character < 256; character++) {
            byte[] value = {(byte) character};
            boolean accepted = character == '\n' || character == '\t' || character >= 32 && character <= 126;
            boolean rejected = false;
            try { DroidSdIo.validateDeployment(value); } catch (IOException expected) { rejected = true; }
            check(accepted != rejected);
        }
        rejects(() -> DroidSdIo.validateDeployment(null));
        rejects(() -> DroidSdIo.validateDeployment(new byte[0]));
        byte[] max = new byte[64_000]; Arrays.fill(max, (byte) 'A');
        DroidSdIo.validateDeployment(max);
        check(DroidSdIo.save(new Fake(null), max).bytes == 64_000);
        rejects(() -> DroidSdIo.validateDeployment(Arrays.copyOf(max, 64_001)));

        Fake invalid = new Fake(OLD);
        rejects(() -> DroidSdIo.save(invalid, new byte[]{(byte) 0xff}));
        check(invalid.reads == 0 && invalid.writes == 0 && invalid.backups == 0);
        Fake tooLarge = new Fake(new byte[DroidSdIo.MAX_READ_BYTES + 1]);
        DroidSdIo.Failure oversized = fail(tooLarge);
        check(!oversized.mayHaveChanged && tooLarge.writes == 0 && tooLarge.backups == 0);

        Fake noBackup = new Fake(OLD); noBackup.backupFailure = true;
        check(!fail(noBackup).mayHaveChanged && noBackup.writes == 0 && Arrays.equals(noBackup.current, OLD));
        Fake wrongBackup = new Fake(OLD); wrongBackup.backupCorruption = true;
        DroidSdIo.Failure badBackup = fail(wrongBackup);
        check(!badBackup.mayHaveChanged && badBackup.backupFile != null && wrongBackup.writes == 0);

        Fake changed = new Fake(OLD); changed.changeAt = 2; changed.changedSource = ascii("external change");
        DroidSdIo.Failure conflict = fail(changed);
        check(!conflict.mayHaveChanged && conflict.backupFile != null && changed.writes == 0);
        check(Arrays.equals(changed.backup, OLD) && Arrays.equals(changed.current, changed.changedSource));
        Fake removed = new Fake(OLD); removed.changeAt = 2;
        check(!fail(removed).mayHaveChanged && removed.writes == 0);
        Fake appeared = new Fake(null); appeared.changeAt = 2; appeared.changedSource = OLD;
        check(!fail(appeared).mayHaveChanged && appeared.writes == 0 && appeared.backups == 0);

        Fake partial = new Fake(OLD); partial.writeFailure = true;
        DroidSdIo.Failure partialFailure = fail(partial);
        check(partialFailure.mayHaveChanged && partialFailure.backupFile != null);
        check(Arrays.equals(partial.backup, OLD) && partial.current.length == 3 && partial.writes == 1);
        Fake denied = new Fake(OLD); denied.writeRuntime = true;
        check(fail(denied).mayHaveChanged && Arrays.equals(denied.backup, OLD));
        Fake lostRead = new Fake(OLD); lostRead.readFailureAt = 3;
        check(fail(lostRead).mayHaveChanged && Arrays.equals(lostRead.backup, OLD));
        Fake initialReadFail = new Fake(OLD); initialReadFail.readFailureAt = 1;
        check(!fail(initialReadFail).mayHaveChanged && initialReadFail.writes == 0);
        for (int corruption = 1; corruption <= 3; corruption++) {
            Fake altered = new Fake(OLD); altered.corruption = corruption;
            DroidSdIo.Failure failure = fail(altered);
            check(failure.mayHaveChanged && failure.backupFile != null && Arrays.equals(altered.backup, OLD));
        }
        byte[] caller = NEW.clone();
        Fake callerChanges = new Fake(OLD); callerChanges.mutateCaller = caller;
        DroidSdIo.save(callerChanges, caller);
        check(Arrays.equals(callerChanges.current, NEW) && !Arrays.equals(caller, NEW));

        byte[] raw = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '\r', '\n', (byte) 0xff, 0};
        check(Arrays.equals(raw, DroidSdIo.readBounded(new ByteArrayInputStream(raw), raw.length)));
        rejects(() -> DroidSdIo.readBounded(new ByteArrayInputStream(raw), raw.length - 1));
        check(DroidSdIo.readBounded(new ByteArrayInputStream(new byte[0]), 0).length == 0);
        rejects(() -> DroidSdIo.readBounded(new ByteArrayInputStream(new byte[]{1}), 0));
        rejects(() -> DroidSdIo.readBounded(null, 1));
        rejects(() -> DroidSdIo.readBounded(new ByteArrayInputStream(raw), -1));
        byte[] fourMiB = new byte[DroidSdIo.MAX_READ_BYTES];
        check(DroidSdIo.readBounded(new ByteArrayInputStream(fourMiB), fourMiB.length).length == fourMiB.length);
        rejects(() -> DroidSdIo.readBounded(new ByteArrayInputStream(Arrays.copyOf(fourMiB, fourMiB.length + 1)), fourMiB.length));
        InputStream zeroChunks = new ByteArrayInputStream(raw) {
            @Override public int read(byte[] bytes, int offset, int length) { return available() == 0 ? -1 : 0; }
        };
        check(Arrays.equals(raw, DroidSdIo.readBounded(zeroChunks, raw.length)));
        System.out.println("DroidSdIo " + checks + " checks passed");
    }
}
