package com.PinkCats.bandwidthoptimizer.gate;

import java.util.ArrayList;
import java.util.List;

public final class PendingEntityMovementsRegression {
    private PendingEntityMovementsRegression() {}

    public static void verify() {
        var queue = new PendingEntityMovements<Object, String>(2);
        Object firstEntity = new Object();
        Object secondEntity = new Object();
        queue.put(1, firstEntity, "first", 12);
        queue.put(2, secondEntity, "second", 14);
        require(queue.isFull(), "pending queue must be bounded");
        var order = new ArrayList<String>();
        queue.drainDue(11, (id, entry) -> order.add(entry.packet()));
        require(order.isEmpty(), "nothing is applied early");
        var held = queue.take(1);
        require(held.entity() == firstEntity && held.packet().equals("first"),
                "next movement must take the old packet first");
        require(queue.take(1) == null, "a flushed or discarded packet must not replay");
        queue.put(1, firstEntity, "replacement", 13);
        queue.drainDue(13, (id, entry) -> order.add(entry.packet()));
        require(order.equals(List.of("replacement")), "only the due entity is applied");
        require(queue.take(2).packet().equals("second"), "teleport/removal must discard the held packet");
        queue.put(3, firstEntity, "stale world", 15);
        queue.clear();
        queue.drainDue(16, (id, entry) -> order.add(entry.packet()));
        require(order.equals(List.of("replacement")), "world clear must prevent stale replay");
        queue.put(1, firstEntity, "a", 20);
        queue.put(2, secondEntity, "b", 20);
        boolean rejected = false;
        try {
            queue.put(3, new Object(), "overflow", 20);
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        require(rejected, "queue must reject overflow");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
