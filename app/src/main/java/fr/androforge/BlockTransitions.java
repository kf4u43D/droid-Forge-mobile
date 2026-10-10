package fr.androforge;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, process-local correspondence for circuit identities across native history. */
public final class BlockTransitions {
    private final int capacity;
    private final LinkedHashMap<Key, int[]> entries = new LinkedHashMap<>();

    public BlockTransitions(int capacityEntries) {
        if (capacityEntries < 2) throw new IllegalArgumentException("At least two entries are required");
        capacity = capacityEntries;
    }

    public synchronized void record(String beforeHash, String afterHash, int[] forward, int afterCount) {
        if (beforeHash == null || afterHash == null || forward == null || afterCount < 0)
            throw new IllegalArgumentException("Missing transition");
        int[] inverse = new int[afterCount];
        Arrays.fill(inverse, -1);
        for (int index = 0; index < forward.length; index++) {
            int target = forward[index];
            if (target < -1 || target >= afterCount || (target >= 0 && inverse[target] >= 0))
                throw new IllegalArgumentException("Invalid correspondence");
            if (target >= 0) inverse[target] = index;
        }
        Key direct = new Key(beforeHash, afterHash);
        Key reverse = new Key(afterHash, beforeHash);
        // Identical text can arise from deleting different identical clones. Once a
        // pair conflicts, neither direction is evidence of a circuit's identity.
        boolean ambiguous = conflicts(direct, forward) || conflicts(reverse, inverse);
        put(direct, ambiguous ? null : forward.clone());
        put(reverse, ambiguous ? null : inverse);
        while (entries.size() > capacity) entries.remove(entries.keySet().iterator().next());
    }

    private boolean conflicts(Key key, int[] value) {
        return entries.containsKey(key) && !Arrays.equals(entries.get(key), value);
    }

    private void put(Key key, int[] value) {
        entries.remove(key);
        entries.put(key, value);
    }

    public synchronized int[] lookup(String beforeHash, String afterHash) {
        int[] correspondence = entries.get(new Key(beforeHash, afterHash));
        return correspondence == null ? null : correspondence.clone();
    }

    public synchronized void clear() { entries.clear(); }
    public synchronized int entryCount() { return entries.size(); }

    public static int[] identity(int count) {
        if (count < 0) throw new IllegalArgumentException("Negative count");
        int[] result = new int[count];
        for (int index = 0; index < count; index++) result[index] = index;
        return result;
    }

    public static int[] insertion(int oldCount, int insertionIndex) {
        if (oldCount < 0 || insertionIndex < 0 || insertionIndex > oldCount)
            throw new IllegalArgumentException("Invalid insertion");
        int[] result = identity(oldCount);
        for (int index = insertionIndex; index < oldCount; index++) result[index]++;
        return result;
    }

    public static int[] deletion(int oldCount, int deletionIndex) {
        if (oldCount < 1 || deletionIndex < 0 || deletionIndex >= oldCount)
            throw new IllegalArgumentException("Invalid deletion");
        int[] result = identity(oldCount);
        result[deletionIndex] = -1;
        for (int index = deletionIndex + 1; index < oldCount; index++) result[index]--;
        return result;
    }

    /** Only unique, complete signatures establish identity without an operation record. */
    public static int[] inferUnique(List<String> before, List<String> after) {
        if (before == null || after == null) throw new IllegalArgumentException("Missing signatures");
        Map<String, Integer> oldIndices = uniqueIndices(before);
        Map<String, Integer> newIndices = uniqueIndices(after);
        int[] result = new int[before.size()];
        Arrays.fill(result, -1);
        for (Map.Entry<String, Integer> entry : oldIndices.entrySet()) {
            Integer target = newIndices.get(entry.getKey());
            if (entry.getValue() >= 0 && target != null && target >= 0)
                result[entry.getValue()] = target;
        }
        return result;
    }

    private static Map<String, Integer> uniqueIndices(List<String> signatures) {
        Map<String, Integer> result = new HashMap<>();
        for (int index = 0; index < signatures.size(); index++) {
            String signature = signatures.get(index);
            if (signature == null) continue;
            result.put(signature, result.containsKey(signature) ? -1 : index);
        }
        return result;
    }

    private static final class Key {
        final String before, after;
        Key(String before, String after) { this.before = before; this.after = after; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return before.equals(key.before) && after.equals(key.after);
        }
        @Override public int hashCode() { return 31 * before.hashCode() + after.hashCode(); }
    }
}
