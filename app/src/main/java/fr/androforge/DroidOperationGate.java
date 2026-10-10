package fr.androforge;

import java.util.concurrent.atomic.AtomicReference;

/** One device transaction across Activity instances; old callbacks own only their lease. */
final class DroidOperationGate {
    private static final AtomicReference<Lease> ACTIVE = new AtomicReference<>();
    private DroidOperationGate() {}
    static boolean occupied() { return ACTIVE.get() != null; }
    static Lease acquire() {
        Lease lease = new Lease();
        return ACTIVE.compareAndSet(null, lease) ? lease : null;
    }
    static final class Lease {
        private Lease() {}
        boolean release() { return ACTIVE.compareAndSet(this, null); }
    }
}
