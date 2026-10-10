package fr.androforge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Hardware-free framing, exact upload, cancellation and late-callback regression tests. */
public final class MidiTransportTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        check(latch.await(4, TimeUnit.SECONDS), message);
    }
    private static void waitIdle(MidiTransferSession session) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (session.isBusy() && System.nanoTime() < deadline) Thread.sleep(1);
        check(!session.isBusy(), "MIDI cleanup did not finish");
    }
    private static byte[] frame(int size) {
        byte[] frame = new byte[size];
        frame[0] = (byte) 0xf0; frame[1] = 0; frame[2] = frame[3] = 0x66; frame[4] = 'P';
        for (int index = 5; index < size - 1; index++) frame[index] = (byte) ('a' + index % 26);
        frame[size - 1] = (byte) 0xf7;
        return frame;
    }

    private static final class Frames implements MidiSysExFramer.Listener {
        final List<byte[]> values = new ArrayList<>();
        int discarded;
        @Override public void onFrame(byte[] frame) { values.add(frame); }
        @Override public void onDiscarded() { discarded++; }
    }
    private static void fragmentedFrames() {
        byte[] original = frame(1024);
        for (int split = 0; split <= original.length; split++) {
            Frames frames = new Frames();
            MidiSysExFramer parser = new MidiSysExFramer(original.length, frames);
            parser.accept(original, 0, split); parser.accept(original, split, original.length - split);
            check(frames.values.size() == 1 && Arrays.equals(original, frames.values.get(0)), "Every fragment boundary must preserve exact SysEx");
            check(parser.bufferedBytes() == 0 && frames.discarded == 0, "Completed fragment must release bounded staging bytes");
        }
        Random random = new Random(0x5847);
        byte[] maximum = frame(MidiSysExFramer.MAX_ACTIVATION_BYTES);
        Frames frames = new Frames();
        MidiSysExFramer parser = new MidiSysExFramer(maximum.length, frames);
        for (int offset = 0; offset < maximum.length; ) {
            int count = Math.min(1 + random.nextInt(1015), maximum.length - offset);
            parser.accept(maximum, offset, count); offset += count;
        }
        check(frames.values.size() == 1 && Arrays.equals(maximum, frames.values.get(0)), "Maximum official upload must survive Android-sized fragments");

        ByteArrayOutputStream mixed = new ByteArrayOutputStream();
        mixed.write(0x90); mixed.write(60); mixed.write(127);
        for (int index = 0; index < original.length; index++) {
            mixed.write(original[index]);
            if (index % 11 == 0) { mixed.write(0xf8); mixed.write(0xfe); mixed.write(0xff); }
        }
        mixed.writeBytes(original);
        frames = new Frames(); parser = new MidiSysExFramer(2048, frames);
        byte[] stream = mixed.toByteArray(); parser.accept(stream, 0, stream.length);
        check(frames.values.size() == 2, "Concatenated SysEx and MIDI channel messages must be separated");
        check(Arrays.equals(original, frames.values.get(0)) && Arrays.equals(original, frames.values.get(1)), "Real-time bytes inside SysEx must not alter its payload");
        stream[0] = 0;
        check(frames.values.get(0)[0] == (byte) 0xf0, "Delivered frames must not retain the mutable input buffer");
    }

    private static void malformedAndBudgets() {
        Frames frames = new Frames(); MidiSysExFramer parser = new MidiSysExFramer(8, frames);
        byte[] stream = {(byte) 0xf0, 1, 2, (byte) 0xf0, 3, (byte) 0xf7, (byte) 0xf0, 4, (byte) 0x91, 5, (byte) 0xf7};
        parser.accept(stream, 0, stream.length);
        check(frames.discarded == 2 && frames.values.size() == 1, "Nested starts and non-real-time statuses must discard the interrupted frame");
        check(Arrays.equals(new byte[]{(byte) 0xf0, 3, (byte) 0xf7}, frames.values.get(0)), "Recovery must begin at the fresh SysEx start");
        byte[] overflow = frame(9); parser.accept(overflow, 0, overflow.length);
        check(frames.discarded == 3 && parser.bufferedBytes() == 0, "Oversized frame must never publish a truncated payload");
        parser.accept(new byte[]{(byte) 0xf0, 1}, 0, 2); parser.reset();
        parser.accept(new byte[]{2, (byte) 0xf7}, 0, 2);
        check(frames.values.size() == 1 && parser.bufferedBytes() == 0, "Flush/reset must not complete an old partial frame");
        try { parser.accept(new byte[2], Integer.MAX_VALUE, 1); check(false, "Overflowing range accepted"); }
        catch (IllegalArgumentException expected) { check(true, "Range checked without integer overflow"); }
        check(MidiSysExFramer.activationProblem(frame(64006)).isEmpty(), "64 000-byte payload is permitted");
        check(!MidiSysExFramer.activationProblem(frame(64007)).isEmpty(), "Payload larger than PC limit must be refused");
        byte[] invalid = frame(8); invalid[5] = (byte) 0x80;
        check(!MidiSysExFramer.activationProblem(invalid).isEmpty(), "High-bit data must be refused before hardware I/O");
        invalid = frame(8); invalid[4] = 'R';
        check(!MidiSysExFramer.activationProblem(invalid).isEmpty(), "An invented read command must not be accepted as an upload");
    }

    private static final class Result implements MidiTransferSession.Completion {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger sent = new AtomicInteger(), errors = new AtomicInteger(), progress = new AtomicInteger();
        volatile String message = "";
        @Override public void onSent() { sent.incrementAndGet(); done.countDown(); }
        @Override public void onFailure(String message) { this.message = message; errors.incrementAndGet(); done.countDown(); }
        @Override public void onProgress(int sent, int total) { if (sent > 0 && sent <= total) progress.incrementAndGet(); }
    }
    private static class Port implements MidiTransferSession.Channel {
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        final AtomicInteger closes = new AtomicInteger(), sends = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1), wrote = new CountDownLatch(1);
        volatile boolean isClosed;
        @Override public synchronized void send(byte[] data, int offset, int count) throws IOException {
            if (isClosed) throw new IOException("Closed fake port");
            if (count > 256) throw new IOException("Unbounded write");
            received.write(data, offset, count); sends.incrementAndGet(); wrote.countDown();
        }
        @Override public void close() { isClosed = true; closes.incrementAndGet(); closed.countDown(); }
        synchronized byte[] bytes() { return received.toByteArray(); }
    }

    private static void exactSendAndDefensiveCopy() throws InterruptedException {
        try (MidiTransferSession session = new MidiTransferSession(Runnable::run)) {
            Result result = new Result(); Port port = new Port();
            byte[] upload = frame(64006), expected = upload.clone();
            session.send(callback -> callback.onOpened(port), upload, 3000, result);
            upload[7] = 'Z';
            await(result.done, "Maximum transfer did not complete"); await(port.closed, "Completed port was not closed");
            waitIdle(session);
            check(result.sent.get() == 1 && result.errors.get() == 0, "Successful send must have exactly one terminal result");
            check(Arrays.equals(expected, port.bytes()), "Upload must be exact without Mac padding, chunk loss or caller mutation");
            check(result.progress.get() > 0 && port.sends.get() == (64006 + 255) / 256, "Bounded writes and progress must cover the whole frame");
            check(!session.isBusy() && port.closes.get() == 1, "Completed session must release its connection");
        }
    }

    private static void lateOpenAndTimeout() throws InterruptedException {
        try (MidiTransferSession session = new MidiTransferSession(Runnable::run)) {
            AtomicReference<MidiTransferSession.Opened> opener = new AtomicReference<>(); CountDownLatch requested = new CountDownLatch(1);
            Result timed = new Result();
            session.send(callback -> { opener.set(callback); requested.countDown(); }, frame(10), 250, timed);
            await(requested, "Open was never requested"); await(timed.done, "Open timeout was not delivered");
            check(timed.errors.get() == 1 && timed.sent.get() == 0 && !opener.get().isWanted(), "Timeout must abandon pending open without success");
            Result refused = new Result();
            session.send(callback -> { throw new AssertionError("A second pending platform open was allowed"); }, frame(10), 500, refused);
            await(refused.done, "Pending platform open did not block a second request");
            check(refused.errors.get() == 1, "Unbounded concurrent opens must be prevented");
            Port late = new Port(); opener.get().onOpened(late); await(late.closed, "Late opened port leaked");
            waitIdle(session);
            check(late.sends.get() == 0 && timed.errors.get() == 1, "Late callback must neither write nor complete twice");
            Port fresh = new Port(); Result retried = new Result();
            session.send(callback -> callback.onOpened(fresh), frame(10), 1000, retried);
            await(retried.done, "A new transfer after late-close could not complete");
            check(retried.sent.get() == 1, "A released timed-out open must permit a fresh transfer");
        }
    }

    private static void cancelDisconnectAndDuplicateCallback() throws InterruptedException {
        try (MidiTransferSession session = new MidiTransferSession(Runnable::run)) {
            AtomicReference<MidiTransferSession.Opened> opener = new AtomicReference<>(); CountDownLatch requested = new CountDownLatch(1);
            Result result = new Result();
            session.send(callback -> { opener.set(callback); requested.countDown(); }, frame(9), 1000, result);
            await(requested, "Cancel setup failed"); session.cancel(); session.cancel(); await(result.done, "Cancellation not reported");
            check(result.errors.get() == 1 && !opener.get().isWanted(), "Cancellation must be terminal and idempotent");
            Port late = new Port(); opener.get().onOpened(late); await(late.closed, "Cancelled late-open connection leaked");
            waitIdle(session);
            Port main = new Port(), duplicate = new Port(); Result sent = new Result();
            session.send(callback -> { callback.onOpened(main); callback.onOpened(duplicate); }, frame(20), 1000, sent);
            await(sent.done, "Fresh upload after cancellation failed"); await(duplicate.closed, "Duplicate opened channel leaked");
            check(sent.sent.get() == 1 && duplicate.sends.get() == 0, "Duplicate callbacks must not cause retransmission");
        }
        try (MidiTransferSession session = new MidiTransferSession(Runnable::run)) {
            Port port = new Port(); Result result = new Result();
            session.send(callback -> callback.onOpened(port), frame(100), 1000, result);
            await(port.wrote, "Disconnect setup failed"); session.disconnected(); await(result.done, "Disconnect not reported");
            check(result.errors.get() == 1 && result.sent.get() == 0, "Disconnect during drain must never claim activation");
            check(result.message.contains("n’est pas confirmée"), "Possibly delivered bytes must be reported honestly");
        }
    }

    private static void blockedWriteAndClose() throws InterruptedException {
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        Port port = new Port() {
            @Override public void send(byte[] data, int offset, int count) throws IOException {
                blocked.countDown();
                try { if (!release.await(4, TimeUnit.SECONDS)) throw new IOException("Test write never released"); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IOException("Interrupted write", stopped); }
                super.send(data, offset, count);
            }
            @Override public void close() { super.close(); release.countDown(); }
        };
        try (MidiTransferSession session = new MidiTransferSession(Runnable::run)) {
            Result result = new Result(); session.send(callback -> callback.onOpened(port), frame(4096), 250, result);
            await(blocked, "Blocking write did not start"); await(result.done, "Blocked write prevented independent timeout"); await(port.closed, "Timeout did not close stalled port");
            check(result.errors.get() == 1 && result.sent.get() == 0 && port.sends.get() == 0, "Stalled I/O must stop without retry or success");
        }
        MidiTransferSession session = new MidiTransferSession(Runnable::run);
        AtomicReference<MidiTransferSession.Opened> opener = new AtomicReference<>(); CountDownLatch requested = new CountDownLatch(1); Result suppressed = new Result();
        session.send(callback -> { opener.set(callback); requested.countDown(); }, frame(9), 1000, suppressed);
        await(requested, "Close setup failed"); session.close(); session.close();
        Port late = new Port(); opener.get().onOpened(late); await(late.closed, "Lifecycle close leaked a late device");
        check(suppressed.sent.get() == 0 && suppressed.errors.get() == 0 && late.sends.get() == 0, "Closed lifecycle must suppress UI callbacks and hardware writes");
    }

    private static void refusalsBeforeOpen() throws InterruptedException {
        try (MidiTransferSession session = new MidiTransferSession(Runnable::run)) {
            AtomicInteger opens = new AtomicInteger();
            Result invalid = new Result(); session.send(callback -> opens.incrementAndGet(), new byte[]{1}, 1000, invalid); await(invalid.done, "Invalid frame not rejected");
            Result wrongTimeout = new Result(); session.send(callback -> opens.incrementAndGet(), frame(10), 1, wrongTimeout); await(wrongTimeout.done, "Invalid timeout not rejected");
            check(opens.get() == 0 && invalid.errors.get() == 1 && wrongTimeout.errors.get() == 1, "Validation must happen before opening hardware");
            Result failure = new Result(); session.send(callback -> { throw new IOException("Unavailable"); }, frame(10), 1000, failure); await(failure.done, "Open exception not reported");
            check(failure.errors.get() == 1 && !session.isBusy(), "Failed open must release operation state");
            Result memory = new Result(); session.send(callback -> { throw new OutOfMemoryError("fake"); }, frame(10), 1000, memory); await(memory.done, "Memory refusal not reported");
            check(memory.errors.get() == 1 && memory.message.contains("Mémoire"), "Explicit memory error must keep transport recoverable");
        }
    }

    private static void queuedCancelAndClosingGate() throws InterruptedException {
        for (int attempt = 0; attempt < 30; attempt++) {
            try (MidiTransferSession session = new MidiTransferSession(Runnable::run)) {
                Result cancelled = new Result();
                session.send(callback -> callback.onOpened(new Port()), frame(4096), 1000, cancelled);
                session.cancel(); await(cancelled.done, "Immediate cancellation was lost");
                check(cancelled.errors.get() == 1 && cancelled.sent.get() == 0 && cancelled.message.startsWith("Transfert MIDI annulé"),
                        "Cancel immediately after queued send must cancel that operation");
            }
        }
        CountDownLatch closing = new CountDownLatch(1), release = new CountDownLatch(1);
        Port slowClose = new Port() {
            @Override public void close() {
                closing.countDown();
                try { release.await(4, TimeUnit.SECONDS); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                super.close();
            }
        };
        try (MidiTransferSession session = new MidiTransferSession(Runnable::run)) {
            Result sent = new Result(); session.send(callback -> callback.onOpened(slowClose), frame(9), 1000, sent);
            await(sent.done, "Slow-close setup did not send"); await(closing, "Slow close never began");
            Result blocked = new Result();
            session.send(callback -> { throw new AssertionError("Port reopened before previous close"); }, frame(9), 1000, blocked);
            await(blocked.done, "Closing port did not refuse another upload");
            check(session.isBusy() && blocked.errors.get() == 1, "Cleanup must participate in the busy gate");
            release.countDown(); await(slowClose.closed, "Slow close did not release"); waitIdle(session);
            Result retried = new Result(); session.send(callback -> callback.onOpened(new Port()), frame(9), 1000, retried);
            await(retried.done, "Upload after slow close did not work"); check(retried.sent.get() == 1, "Closed previous port must permit a new transfer");
        } finally { release.countDown(); }
    }

    public static void main(String[] arguments) throws Exception {
        fragmentedFrames(); malformedAndBudgets(); exactSendAndDefensiveCopy(); lateOpenAndTimeout();
        cancelDisconnectAndDuplicateCallback(); blockedWriteAndClose(); refusalsBeforeOpen();
        queuedCancelAndClosingGate();
        System.out.println("MIDI transport: " + checks + " checks passed (SysEx fragmentation, exact upload, timeout, cancellation, disconnect, late opens, lifecycle)");
    }
}
