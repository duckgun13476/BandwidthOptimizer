package com.PinkCats.bandwidthoptimizer.gate;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.BiConsumer;

/** Client-thread-only bounded storage for the second half of movement packets. */
final class PendingEntityMovements<E, P> {
    private final int limit;
    private final Map<Integer, Entry<E, P>> entries = new HashMap<>();

    PendingEntityMovements(int limit) {
        this.limit = limit;
    }

    boolean isFull() {
        return entries.size() >= limit;
    }

    void put(int id, E entity, P packet, long dueTick) {
        if (isFull() && !entries.containsKey(id)) {
            throw new IllegalStateException("Pending movement limit reached");
        }
        entries.put(id, new Entry<>(entity, packet, dueTick));
    }

    Entry<E, P> take(int id) {
        return entries.remove(id);
    }

    void clear() {
        entries.clear();
    }

    void drainDue(long tick, BiConsumer<Integer, Entry<E, P>> consumer) {
        Iterator<Map.Entry<Integer, Entry<E, P>>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Entry<E, P>> entry = iterator.next();
            if (entry.getValue().dueTick() <= tick) {
                iterator.remove();
                consumer.accept(entry.getKey(), entry.getValue());
            }
        }
    }

    record Entry<E, P>(E entity, P packet, long dueTick) {}
}
