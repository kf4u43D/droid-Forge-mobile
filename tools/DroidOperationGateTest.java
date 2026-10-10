package fr.androforge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** Regression tests for transactions that outlive their Activity or deliver duplicate callbacks. */
public final class DroidOperationGateTest {
    private static final AtomicInteger checks = new AtomicInteger();

    private static void check(boolean condition, String message) {
        checks.incrementAndGet();
        if (!condition) throw new AssertionError(message);
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        check(latch.await(10, TimeUnit.SECONDS), message);
    }

    private static ExecutorService workers(int count) {
        return Executors.newFixedThreadPool(count, task -> {
            Thread thread = new Thread(task, "DROID lease regression");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void finish(List<Future<?>> pending) throws Exception {
        for (Future<?> task : pending) task.get(20, TimeUnit.SECONDS);
    }

    private static void duplicateAndLateCallbacks() throws Exception {
        check(!DroidOperationGate.occupied(), "New process must have no owner");
        DroidOperationGate.Lease oldActivity = DroidOperationGate.acquire();
        check(oldActivity != null, "First Activity could not start its operation");
        check(DroidOperationGate.acquire() == null, "Another Activity entered while a transaction was pending");
        check(oldActivity.release(), "Current owner could not finish");
        check(!oldActivity.release(), "Duplicate completion released the operation twice");

        DroidOperationGate.Lease newActivity = DroidOperationGate.acquire();
        check(newActivity != null && newActivity != oldActivity, "New operation reused an obsolete lease");
        ExecutorService callbacks = workers(16);
        try {
            List<Future<?>> pending = new ArrayList<>();
            for (int index = 0; index < 128; index++) pending.add(callbacks.submit(() -> {
                check(!oldActivity.release(), "Late callback from destroyed Activity released the new owner");
                check(DroidOperationGate.occupied(), "Late callback made the new operation invisible");
                check(DroidOperationGate.acquire() == null, "A competing operation entered during stale callback delivery");
            }));
            finish(pending);
            check(DroidOperationGate.occupied(), "Current Activity lost its lease");
            check(newActivity.release(), "Current Activity could no longer release its own transaction");
            check(!newActivity.release(), "New Activity completion was not idempotent");
            DroidOperationGate.Lease nextOperation = DroidOperationGate.acquire();
            check(nextOperation != null, "A finished transaction left the gate occupied");
            check(!oldActivity.release() && !newActivity.release(), "An older generation released a subsequent transaction");
            check(nextOperation.release(), "Subsequent transaction lost ownership");
        } finally {
            callbacks.shutdownNow();
            // Release only the exact lease owned here, even on test failure.
            newActivity.release(); oldActivity.release();
        }
        check(!DroidOperationGate.occupied(), "Completed callback test retained a lease");
    }

    private static void simultaneousAcquisitionHasOneWinner() throws Exception {
        final int count = 24;
        ExecutorService pool = workers(count);
        try {
            for (int round = 0; round < 24; round++) {
                CountDownLatch ready = new CountDownLatch(count), start = new CountDownLatch(1);
                CountDownLatch attempted = new CountDownLatch(count), releaseWinner = new CountDownLatch(1);
                AtomicInteger winners = new AtomicInteger();
                AtomicReference<DroidOperationGate.Lease> owner = new AtomicReference<>();
                List<Future<?>> pending = new ArrayList<>();
                for (int index = 0; index < count; index++) pending.add(pool.submit(() -> {
                    ready.countDown();
                    DroidOperationGate.Lease lease = null;
                    try {
                        await(start, "Concurrent acquisition did not start");
                        lease = DroidOperationGate.acquire();
                        if (lease != null) { winners.incrementAndGet(); owner.set(lease); }
                        attempted.countDown();
                        if (lease != null) {
                            await(releaseWinner, "The owner was not allowed to finish");
                            check(lease.release(), "Simultaneous candidates replaced the winner's lease");
                        }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt(); throw new AssertionError(failure);
                    } finally { if (lease != null) lease.release(); }
                }));
                try {
                    await(ready, "Not all concurrent contenders reached the starting barrier");
                    start.countDown();
                    await(attempted, "Not all contenders attempted acquisition");
                    check(winners.get() == 1 && owner.get() != null, "Concurrent acquisition produced zero or several owners");
                    check(DroidOperationGate.occupied(), "Winning lease was not held until completion");
                    check(DroidOperationGate.acquire() == null, "A late contender bypassed the winning transaction");
                } finally { start.countDown(); releaseWinner.countDown(); }
                finish(pending);
                check(!DroidOperationGate.occupied(), "Concurrent winner left a stale active lease");
            }
        } finally { pool.shutdownNow(); }
    }

    private static void repeatedConcurrentTransactionsRemainExclusive() throws Exception {
        final int threads = 32, iterations = 600;
        ExecutorService pool = workers(threads);
        AtomicInteger inside = new AtomicInteger(), completed = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(threads), start = new CountDownLatch(1);
        List<Future<?>> pending = new ArrayList<>();
        try {
            for (int index = 0; index < threads; index++) pending.add(pool.submit(() -> {
                DroidOperationGate.Lease previous = null;
                ready.countDown();
                try {
                    await(start, "Repeated acquisition did not start");
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        DroidOperationGate.Lease lease;
                        while ((lease = DroidOperationGate.acquire()) == null) {
                            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted())
                                throw new AssertionError("Concurrent transactions did not finish within their budget");
                            LockSupport.parkNanos(1000);
                        }
                        try {
                            check(inside.incrementAndGet() == 1, "Two device transactions entered their critical section");
                            check(DroidOperationGate.occupied(), "Active transaction was reported as available");
                            if (previous != null)
                                check(previous != lease && !previous.release(), "An obsolete completion released the current transaction");
                            check(DroidOperationGate.acquire() == null, "Reentrant acquisition admitted another device operation");
                            check(inside.decrementAndGet() == 0, "Critical sections overlapped");
                            check(lease.release(), "Current transaction lost its release right");
                            check(!lease.release(), "Duplicate completion released a newer transaction");
                            previous = lease;
                            completed.incrementAndGet();
                        } finally { lease.release(); }
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt(); throw new AssertionError(failure);
                }
            }));
            await(ready, "Not all stress threads started");
            start.countDown();
            finish(pending);
            check(completed.get() == threads * iterations, "Stress run skipped device transactions");
            check(inside.get() == 0, "Stress run retained a critical-section owner");
            check(!DroidOperationGate.occupied(), "Stress run left the gate locked");
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    public static void main(String[] arguments) throws Exception {
        duplicateAndLateCallbacks();
        simultaneousAcquisitionHasOneWinner();
        repeatedConcurrentTransactionsRemainExclusive();
        System.out.println("DROID operation gate: " + checks.get() + " checks passed (32 threads, 19 200 transactions, stale Activity leases)");
    }
}
