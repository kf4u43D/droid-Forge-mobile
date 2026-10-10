package fr.androforge;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Protects uncommitted values against reassignment between repeated circuits. */
public final class BlockTransitionsTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void mapping(int[] expected, int[] actual, String message) {
        check(Arrays.equals(expected, actual), message + ": expected " + Arrays.toString(expected) + ", got " + Arrays.toString(actual));
    }

    private static void knownStructuralOperationsAndHistory() {
        mapping(new int[]{0, 1, 2}, BlockTransitions.identity(3), "Unchanged structure preserves indices");
        mapping(new int[]{1, 2, 3}, BlockTransitions.insertion(3, 0), "Controller insertion before circuits shifts every draft");
        mapping(new int[]{0, 2, 3}, BlockTransitions.insertion(3, 1), "Middle insertion shifts only following drafts");
        mapping(new int[]{0, 1, 2}, BlockTransitions.insertion(3, 3), "Append preserves existing drafts");
        mapping(new int[]{0, -1, 1}, BlockTransitions.deletion(3, 1), "Deleted block receives no draft");
        mapping(new int[0], BlockTransitions.insertion(0, 0), "First block insertion has no previous draft to attribute");
        mapping(new int[]{-1}, BlockTransitions.deletion(1, 0), "Removing the only block cannot attach its draft elsewhere");

        BlockTransitions transitions = new BlockTransitions(16);
        transitions.record("three-COPY", "two-COPY", BlockTransitions.deletion(3, 1), 2);
        int originalLast = 2;
        int afterDeletion = transitions.lookup("three-COPY", "two-COPY")[originalLast];
        check(afterDeletion == 1, "Deleting middle COPY moved the last draft onto the wrong block");
        int afterUndo = transitions.lookup("two-COPY", "three-COPY")[afterDeletion];
        check(afterUndo == originalLast, "Undo must restore last COPY's draft to its exact original index");
        int afterRedo = transitions.lookup("three-COPY", "two-COPY")[afterUndo];
        check(afterRedo == 1, "Redo must keep the draft on last COPY");
        mapping(new int[]{0, 2}, transitions.lookup("two-COPY", "three-COPY"), "Inverse mapping must skip restored middle COPY");
        transitions.record("empty", "first-COPY", BlockTransitions.insertion(0, 0), 1);
        mapping(new int[0], transitions.lookup("empty", "first-COPY"), "Empty patch transition must remain valid");
        mapping(new int[]{-1}, transitions.lookup("first-COPY", "empty"), "Undoing first insertion must drop only the newly added block");
    }

    private static void identicalClonesAndConflictingHistory() {
        List<String> clones = List.of("COPY|input=I1", "COPY|input=I1", "COPY|input=I1");
        mapping(new int[]{-1, -1, -1}, BlockTransitions.inferUnique(clones, clones.subList(0, 2)), "Identical clones cannot be inferred by position or name");

        BlockTransitions transitions = new BlockTransitions(16);
        transitions.record("clones-3", "clones-2", BlockTransitions.deletion(3, 1), 2);
        mapping(new int[]{0, -1, 1}, transitions.lookup("clones-3", "clones-2"), "An explicitly known operation can preserve clone identity");
        mapping(new int[]{0, 2}, transitions.lookup("clones-2", "clones-3"), "Known clone deletion must reverse exactly");
        // Removing another identical clone produces the same before/after hashes
        // but a different identity map. Both directions must become uncertain.
        transitions.record("clones-3", "clones-2", BlockTransitions.deletion(3, 0), 2);
        check(transitions.lookup("clones-3", "clones-2") == null, "Conflicting clone mappings must not silently overwrite one another");
        check(transitions.lookup("clones-2", "clones-3") == null, "Conflicting clone mapping must also invalidate undo direction");
        transitions.record("clones-3", "clones-2", BlockTransitions.deletion(3, 1), 2);
        check(transitions.lookup("clones-3", "clones-2") == null, "A later repeated edge must not forget previously detected ambiguity");
        check(transitions.lookup("clones-2", "clones-3") == null, "Undo ambiguity must remain protected");
        transitions.clear();
        transitions.record("clones-3", "clones-2", BlockTransitions.deletion(3, 1), 2);
        mapping(new int[]{0, 2}, transitions.lookup("clones-2", "clones-3"), "Opening another document resets transition ambiguity");

        BlockTransitions reverseConflict = new BlockTransitions(16);
        reverseConflict.record("three", "two", BlockTransitions.deletion(3, 1), 2);
        reverseConflict.record("two", "three", BlockTransitions.insertion(2, 2), 3);
        check(reverseConflict.lookup("three", "two") == null && reverseConflict.lookup("two", "three") == null,
                "Conflicts recorded from inverse direction must invalidate both directions");
    }

    private static void fallbackRequiresUniqueFullSignatures() {
        String first = "COPY|controller=false|disabled=false|input=I1";
        String middle = "COPY|controller=false|disabled=false|input=I2";
        String last = "COPY|controller=false|disabled=false|input=I3";
        mapping(new int[]{0, -1, 1}, BlockTransitions.inferUnique(List.of(first, middle, last), List.of(first, last)), "Unique value signatures distinguish homonymous circuits");
        mapping(new int[]{1, 2, 0}, BlockTransitions.inferUnique(List.of(first, middle, last), List.of(last, first, middle)), "Unique signatures survive source reorder");
        mapping(new int[]{-1, -1, 1}, BlockTransitions.inferUnique(List.of(first, first, last), List.of(first, last)), "Duplicate source signature must remain unassigned");
        mapping(new int[]{-1, 2}, BlockTransitions.inferUnique(List.of(first, last), List.of(first, first, last)), "Duplicate destination signature must remain unassigned");
        mapping(new int[]{-1}, BlockTransitions.inferUnique(List.of(first), List.of(middle)), "Changed value cannot be assumed to identify the same circuit");
        mapping(new int[]{-1}, BlockTransitions.inferUnique(List.of(first), List.of(first.replace("disabled=false", "disabled=true"))), "Disabled state participates in fallback identity");
    }

    private static void defensiveCopiesAndBoundedHistory() {
        BlockTransitions transitions = new BlockTransitions(4);
        int[] supplied = BlockTransitions.deletion(3, 1);
        transitions.record("before", "after", supplied, 2);
        supplied[2] = -1;
        mapping(new int[]{0, -1, 1}, transitions.lookup("before", "after"), "Caller mutation must not corrupt saved transition");
        int[] returned = transitions.lookup("before", "after");
        returned[0] = 99;
        mapping(new int[]{0, -1, 1}, transitions.lookup("before", "after"), "Lookup must return a defensive copy");
        for (int i = 0; i < 20; i++) {
            transitions.record("before-" + i, "after-" + i, BlockTransitions.identity(3), 3);
            check(transitions.entryCount() <= 4, "Transition history exceeded its declared memory bound");
        }
        check(transitions.lookup("before", "after") == null, "Old transitions must eventually be evicted");
        mapping(new int[]{0, 1, 2}, transitions.lookup("before-19", "after-19"), "Newest mapping must remain usable");
        transitions.clear();
        check(transitions.entryCount() == 0, "Clearing a document must release transition metadata");
        check(transitions.lookup("before-19", "after-19") == null, "Cleared transition must not leak into another document");
    }

    private static void concurrentAccessKeepsCacheBounded() throws InterruptedException {
        BlockTransitions transitions = new BlockTransitions(64);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] workers = new Thread[3];
        for (int index = 0; index < workers.length; index++) {
            final int worker = index;
            workers[index] = new Thread(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        String prefix = worker + ":" + i;
                        transitions.record(prefix + "before", prefix + "after", BlockTransitions.identity(3), 3);
                        int[] found = transitions.lookup(prefix + "before", prefix + "after");
                        if (found != null && !Arrays.equals(found, new int[]{0, 1, 2})) throw new AssertionError("Concurrent lookup returned a mixed mapping");
                    }
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            });
            workers[index].start();
        }
        for (Thread worker : workers) worker.join();
        check(failure.get() == null, "Concurrent transition operations failed: " + failure.get());
        check(transitions.entryCount() <= 64, "Concurrent transitions exceeded capacity");
    }

    public static void main(String[] arguments) throws InterruptedException {
        knownStructuralOperationsAndHistory();
        identicalClonesAndConflictingHistory();
        fallbackRequiresUniqueFullSignatures();
        defensiveCopiesAndBoundedHistory();
        concurrentAccessKeepsCacheBounded();
        System.out.println("Block transitions: " + checks + " checks passed (homonymous circuits, clones, undo/redo, ambiguity, bounded copies)");
    }
}
