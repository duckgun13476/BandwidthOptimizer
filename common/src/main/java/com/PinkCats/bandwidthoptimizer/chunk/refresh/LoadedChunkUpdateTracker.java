package com.PinkCats.bandwidthoptimizer.chunk.refresh;

import it.unimi.dsi.fastutil.longs.Long2BooleanLinkedOpenHashMap;

/** Client-thread metadata only. Never owns chunks, packets, listeners or world objects. */
public final class LoadedChunkUpdateTracker {
    private final int limit;
    private final int capturesPerTick;
    private final Long2BooleanLinkedOpenHashMap loaded = new Long2BooleanLinkedOpenHashMap();
    private long tick = Long.MIN_VALUE;
    private int captured;

    public LoadedChunkUpdateTracker(int limit, int capturesPerTick) {
        if (limit < 1 || capturesPerTick < 1) throw new IllegalArgumentException("Invalid bounds");
        this.limit = limit;
        this.capturesPerTick = capturesPerTick;
    }
    public void loaded(long key) {
        loaded.remove(key);
        loaded.put(key, false);
        if (loaded.size() > limit) loaded.removeFirstBoolean();
    }
    public void changed(long key) { if (loaded.containsKey(key)) loaded.put(key, true); }
    public boolean forget(long key, long nowTick) {
        boolean dirty = loaded.remove(key);
        if (tick != nowTick) { tick = nowTick; captured = 0; }
        if (!dirty || captured >= capturesPerTick) return false;
        captured++;
        return true;
    }
    public int size() { return loaded.size(); }
    public void captureDirty(long nowTick, java.util.function.LongConsumer capture) {
        captureDirtyWhenReady(nowTick, key -> { capture.accept(key); return true; });
    }
    /** Unready captures remain dirty until another view change or final forget.
     * Scan each entry at most once and share the final-forget attempt budget. */
    public void captureDirtyWhenReady(long nowTick, java.util.function.LongPredicate capture) {
        if (tick != nowTick) { tick = nowTick; captured = 0; }
        var iterator = loaded.long2BooleanEntrySet().fastIterator();
        while (iterator.hasNext() && captured < capturesPerTick) {
            var entry = iterator.next();
            if (!entry.getBooleanValue()) continue;
            captured++;
            if (capture.test(entry.getLongKey())) entry.setValue(false);
        }
    }
    public void clear() { loaded.clear(); tick = Long.MIN_VALUE; captured = 0; }
    public static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
