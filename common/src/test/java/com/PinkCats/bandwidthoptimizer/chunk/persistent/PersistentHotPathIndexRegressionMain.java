package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public final class PersistentHotPathIndexRegressionMain {
    private PersistentHotPathIndexRegressionMain() {
    }

    public static void main(String[] args) {
        HashMap<String, Integer> initial = new HashMap<>();
        for (int entry = 0; entry < 34_000; entry++) {
            initial.put("scope.chunk." + entry + "." + (-entry), entry);
        }
        PersistentHotPathIndex<Integer> first = PersistentHotPathIndex.from(initial);
        require(first.size() == initial.size(), "initial hot-path index size changed");

        HashMap<String, Integer> upserts = new HashMap<>();
        HashSet<String> removals = new HashSet<>();
        for (int entry = 0; entry < 256; entry++) {
            String key = "scope.chunk." + entry + "." + (-entry);
            removals.add(key);
            upserts.put("scope.chunk.new." + entry, 100_000 + entry);
        }

        PersistentHotPathIndex<Integer> second = first.withChanges(upserts, removals);
        require(second.size() == first.size(), "incremental hot-path index size changed");
        require(first.get("scope.chunk.1.-1") == 1, "previous immutable snapshot was mutated");
        require(second.get("scope.chunk.1.-1") == null, "removed entry remained visible");
        require(second.get("scope.chunk.new.1") == 100_001, "upserted entry was not visible");

        Set<Integer> values = new HashSet<>();
        second.forEachValue(values::add);
        require(values.size() == second.size(), "hot-path value traversal lost entries");
        System.out.println("Persistent hot-path index regression passed entries=" + second.size());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
