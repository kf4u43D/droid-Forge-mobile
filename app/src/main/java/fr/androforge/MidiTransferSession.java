package fr.androforge;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Single bounded, cancellable upload. Android supplies the port; JDK tests use fake ports. */
final class MidiTransferSession implements Closeable {
    interface Channel extends Closeable { void send(byte[] data, int offset, int count) throws IOException; }
    interface Opened {
        boolean isWanted();
        void onOpened(Channel channel);
        void onFailure(String message);
    }
    interface Connector { void open(Opened callback) throws Exception; }
    interface Completion {
        void onSent();
        void onFailure(String message);
        default void onProgress(int sent, int total) {}
    }

    private final ScheduledThreadPoolExecutor control = scheduler();
    private final java.util.concurrent.ExecutorService io = Executors.newSingleThreadExecutor(task -> daemon(task, "DROID MIDI write"));
    private final java.util.concurrent.ExecutorService cleanup = Executors.newSingleThreadExecutor(task -> daemon(task, "DROID MIDI close"));
    private final Executor callbacks;
    private final AtomicBoolean closed = new AtomicBoolean(), opening = new AtomicBoolean(), writing = new AtomicBoolean();
    private final AtomicInteger closing = new AtomicInteger();
    private volatile Operation active;

    MidiTransferSession(Executor callbacks) {
        if (callbacks == null) throw new IllegalArgumentException("Missing callback executor");
        this.callbacks = callbacks;
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name); thread.setDaemon(true); return thread;
    }
    private static ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> daemon(task, "DROID MIDI session"));
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    void send(Connector connector, byte[] frame, int timeoutMs, Completion completion) {
        if (completion == null || connector == null) throw new IllegalArgumentException("Missing MIDI callback or connector");
        String problem = MidiSysExFramer.activationProblem(frame);
        if (!problem.isEmpty()) { notifyFailure(completion, problem); return; }
        if (timeoutMs < 250 || timeoutMs > 120000) { notifyFailure(completion, "Délai MIDI invalide."); return; }
        if (closed.get()) return;
        final byte[] copy;
        try { copy = Arrays.copyOf(frame, frame.length); }
        catch (OutOfMemoryError insufficientMemory) { notifyFailure(completion, errorMessage(insufficientMemory)); return; }
        execute(() -> {
            if (closed.get()) return;
            if (active != null || opening.get() || writing.get() || closing.get() != 0) {
                notifyFailure(completion, "Un transfert ou une fermeture MIDI est encore en cours."); return;
            }
            try {
                Operation operation = new Operation(copy, completion);
                active = operation;
                operation.timeout = control.schedule(() -> finish(operation, "Le transfert MIDI a dépassé le délai prévu.", false), timeoutMs, TimeUnit.MILLISECONDS);
                opening.set(true);
                io.execute(() -> {
                try {
                    if (!operation.stopped) connector.open(new Opened() {
                        @Override public boolean isWanted() { return !closed.get() && !operation.stopped; }
                        @Override public void onOpened(Channel channel) {
                            if (channel == null) { onFailure("Le périphérique MIDI n’a pas pu être ouvert."); return; }
                            if (!isWanted()) { dispose(channel); opening.set(false); return; }
                            opening.set(false);
                            if (!execute(() -> opened(operation, channel))) dispose(channel);
                        }
                        @Override public void onFailure(String message) {
                            opening.set(false);
                            execute(() -> finish(operation, message, false));
                        }
                    });
                    else opening.set(false);
                } catch (Exception | LinkageError | OutOfMemoryError failure) {
                    opening.set(false);
                    execute(() -> finish(operation, errorMessage(failure), false));
                }
                });
            } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
                opening.set(false);
                Operation operation = active;
                if (operation != null) finish(operation, errorMessage(failure), false);
                else notifyFailure(completion, errorMessage(failure));
            }
        });
    }

    private void opened(Operation operation, Channel channel) {
        if (operation.stopped || active != operation || closed.get()) { dispose(channel); return; }
        if (operation.channel != null) { dispose(channel); return; }
        operation.channel = channel;
        writing.set(true);
        io.execute(() -> {
            try {
                int progress = 0;
                for (int offset = 0; offset < operation.frame.length && !operation.stopped; ) {
                    int count = Math.min(256, operation.frame.length - offset);
                    channel.send(operation.frame, offset, count);
                    offset += count; operation.sent = offset;
                    if (offset - progress >= 4096 || offset == operation.frame.length) {
                        int sent = offset;
                        execute(() -> {
                            if (active == operation && !operation.stopped)
                                dispatch(() -> operation.completion.onProgress(sent, operation.frame.length));
                        });
                        progress = offset;
                    }
                }
                if (!operation.stopped) {
                    // The public Android API exposes no physical delivery acknowledgement.
                    // Leave the MIDI service time to drain its final queued packet before close.
                    execute(() -> control.schedule(() -> finish(operation, "", true), 300, TimeUnit.MILLISECONDS));
                }
            } catch (Exception | LinkageError | OutOfMemoryError failure) {
                execute(() -> finish(operation, errorMessage(failure), false));
            } finally { writing.set(false); }
        });
    }

    void cancel() { stop("Transfert MIDI annulé."); }
    void disconnected() { stop("Le périphérique MIDI a été déconnecté."); }

    private void stop(String message) {
        Operation operation = active;
        if (operation != null) operation.stopped = true;
        // A send can still be queued when cancel is called. Resolve its operation
        // after that queued start, instead of accidentally cancelling nothing.
        execute(() -> {
            Operation target = operation == null ? active : operation;
            if (target != null) finish(target, message, false);
        });
    }

    private void finish(Operation operation, String message, boolean sent) {
        if (active != operation || !operation.terminal.compareAndSet(false, true)) return;
        operation.stopped = true;
        if (operation.timeout != null) operation.timeout.cancel(false);
        active = null;
        if (operation.channel != null) dispose(operation.channel);
        if (sent) dispatch(operation.completion::onSent);
        else {
            String detail = message == null || message.isEmpty() ? "Le transfert MIDI a échoué." : message;
            if (operation.sent > 0) detail += " " + operation.sent + " / " + operation.frame.length
                    + " octets ont été remis à Android ; l’activation du patch n’est pas confirmée.";
            notifyFailure(operation.completion, detail);
        }
    }

    boolean isBusy() { return active != null || opening.get() || writing.get() || closing.get() != 0; }

    private boolean execute(Runnable task) {
        try { control.execute(task); return true; }
        catch (RejectedExecutionException closedExecutor) { return false; }
    }

    private void dispatch(Runnable task) {
        if (closed.get()) return;
        callbacks.execute(() -> { if (!closed.get()) task.run(); });
    }

    private void notifyFailure(Completion completion, String message) { dispatch(() -> completion.onFailure(message)); }

    private void dispose(Channel channel) {
        closing.incrementAndGet();
        Runnable close = () -> {
            try { channel.close(); }
            catch (IOException | RuntimeException ignored) { /* No additional MIDI bytes are sent during cleanup. */ }
            finally { closing.decrementAndGet(); }
        };
        try { cleanup.execute(close); }
        catch (RejectedExecutionException alreadyClosed) { close.run(); }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Operation operation = active;
        if (operation != null) operation.stopped = true;
        execute(() -> {
            if (operation != null) finish(operation, "Session MIDI fermée.", false);
            control.shutdownNow(); io.shutdownNow(); cleanup.shutdown();
        });
    }

    static String errorMessage(Throwable failure) {
        if (failure instanceof OutOfMemoryError) return "Mémoire insuffisante pour le transfert MIDI.";
        String message = failure.getMessage();
        if (message == null || message.trim().isEmpty()) return "Le périphérique MIDI n’est pas disponible.";
        return message.length() > 512 ? message.substring(0, 512) : message;
    }

    private static final class Operation {
        final byte[] frame;
        final Completion completion;
        final AtomicBoolean terminal = new AtomicBoolean();
        volatile boolean stopped;
        volatile int sent;
        Channel channel;
        ScheduledFuture<?> timeout;
        Operation(byte[] frame, Completion completion) { this.frame = frame; this.completion = completion; }
    }
}
