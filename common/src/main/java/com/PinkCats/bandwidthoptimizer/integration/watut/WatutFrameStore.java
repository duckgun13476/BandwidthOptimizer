package com.PinkCats.bandwidthoptimizer.integration.watut;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Server-thread owned, bounded complete-frame reuse. No hash-only equality or fragment drops. */
public final class WatutFrameStore<P> {
    public static final int MAX_FRAME_BYTES = 1024 * 1024;
    public static final int MAX_TOTAL_BYTES = 8 * 1024 * 1024;
    public static final int MAX_SOURCES = 128;
    public static final int MAX_RECIPIENTS = 256;
    public static final int MAX_PARTS = 64;
    public static final int ASSEMBLY_TICKS = 10;
    public static final int CACHE_TICKS = 600;
    private final Map<Object, State<P>> states = new HashMap<>();
    private int retainedBytes;

    public interface Delivery<P> {
        void nativePacket(Object source, P packet);
        void frame(Object source, Object recipient, List<P> packets);
        void suppressed(long payloadBytes, int recipients);
    }

    public record Header(int width, int height, int declaredSize, int count) {
        public boolean valid() {
            return width > 0 && width <= 4096 && height > 0 && height <= 4096
                    && declaredSize > 0 && declaredSize <= 64 * 1024 * 1024
                    && count > 0 && count <= MAX_PARTS;
        }
    }

    public boolean receive(Object source, Object scope, Header header, int index,
                           byte[] bytes, P packet, List<?> recipients, long tick, Delivery<P> delivery) {
        State<P> state = states.get(source);
        if (state != null && (state.scope != scope || tick < state.lastTick)) {
            remove(source, delivery);
            state = null;
        }
        if (!header.valid() || bytes.length == 0 || bytes.length > MAX_FRAME_BYTES
                || recipients.size() > MAX_RECIPIENTS) {
            remove(source, delivery);
            return false;
        }
        boolean first = header.count == 1 || index == 0;
        if (first && state != null && !state.pending.isEmpty()) flushPending(source, state, delivery);
        if (!first && (state == null || state.pending.isEmpty() || !header.equals(state.header)
                || index != state.pending.size() || tick - state.startedTick > ASSEMBLY_TICKS)) {
            remove(source, delivery);
            return false;
        }
        if (state == null) {
            if (!first || states.size() >= MAX_SOURCES) return false;
            state = new State<>();
            state.scope = scope;
            states.put(source, state);
        }
        if (first) { state.header = header; state.startedTick = tick; }
        // Include bounded NBT/packet bookkeeping, not only the large pixel array.
        int charge = bytes.length + 512;
        if (state.pendingBytes + charge > MAX_FRAME_BYTES || retainedBytes + charge > MAX_TOTAL_BYTES) {
            remove(source, delivery);
            return false;
        }
        state.lastTick = tick;
        state.pending.add(packet);
        state.pendingPixels.add(bytes);
        state.pendingBytes += charge;
        retainedBytes += charge;
        if (state.pending.size() != header.count) return true;

        boolean same = header.equals(state.cachedHeader) && equalPixels(state.pendingPixels, state.cachedPixels);
        Set<Object> current = new HashSet<>(recipients);
        int suppressed = 0;
        long payloadBytes = state.pendingPixels.stream().mapToLong(p -> p.length).sum();
        for (Object recipient : current) {
            if (same && state.delivered.contains(recipient)) suppressed++;
            else delivery.frame(source, recipient, state.pending);
        }
        if (suppressed > 0) delivery.suppressed(payloadBytes, suppressed);
        retainedBytes -= state.cachedBytes;
        state.cached = state.pending;
        state.cachedPixels = state.pendingPixels;
        state.cachedHeader = header;
        state.cachedBytes = state.pendingBytes;
        state.delivered = current;
        state.pending = new ArrayList<>();
        state.pendingPixels = new ArrayList<>();
        state.pendingBytes = 0;
        return true;
    }

    /** Replay uses connection objects as identities: reconnecting UUIDs never inherit delivery. */
    public void replay(Object source, Object scope, Object recipient, long tick, Delivery<P> delivery) {
        State<P> state = states.get(source);
        if (state == null || state.scope != scope || !state.pending.isEmpty() || state.cached.isEmpty()
                || tick < state.lastTick || tick - state.lastTick > CACHE_TICKS) return;
        if (!state.delivered.contains(recipient) && state.delivered.size() < MAX_RECIPIENTS) {
            delivery.frame(source, recipient, state.cached);
            state.delivered.add(recipient);
        }
    }

    public void expire(long tick, Set<?> liveSources, Delivery<P> delivery) {
        Iterator<Map.Entry<Object, State<P>>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Object, State<P>> entry = iterator.next();
            State<P> state = entry.getValue();
            if (!liveSources.contains(entry.getKey())) {
                retainedBytes -= state.cachedBytes + state.pendingBytes;
                iterator.remove();
                continue;
            }
            if (!state.pending.isEmpty() && tick - state.startedTick > ASSEMBLY_TICKS)
                flushPending(entry.getKey(), state, delivery);
            if (tick < state.lastTick || tick - state.lastTick > CACHE_TICKS) {
                retainedBytes -= state.cachedBytes;
                iterator.remove();
            }
        }
    }

    public void remove(Object source, Delivery<P> delivery) {
        State<P> state = states.remove(source);
        if (state != null) {
            flushPending(source, state, delivery);
            retainedBytes -= state.cachedBytes;
        }
    }

    public void flush(Object source, Delivery<P> delivery) {
        State<P> state = states.get(source);
        if (state != null && !state.pending.isEmpty()) flushPending(source, state, delivery);
    }

    public void clear() { states.clear(); retainedBytes = 0; }
    public int retainedBytes() { return retainedBytes; }
    public int sourceCount() { return states.size(); }

    private void flushPending(Object source, State<P> state, Delivery<P> delivery) {
        for (P packet : state.pending) delivery.nativePacket(source, packet);
        retainedBytes -= state.pendingBytes + state.cachedBytes;
        state.pending.clear(); state.pendingPixels.clear(); state.pendingBytes = 0;
        state.cached = List.of(); state.cachedPixels = List.of(); state.cachedBytes = 0;
        state.cachedHeader = null; state.delivered.clear();
    }

    private static boolean equalPixels(List<byte[]> left, List<byte[]> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) if (!Arrays.equals(left.get(i), right.get(i))) return false;
        return true;
    }

    private static final class State<P> {
        Object scope;
        long startedTick, lastTick;
        Header header, cachedHeader;
        List<P> pending = new ArrayList<>(), cached = List.of();
        List<byte[]> pendingPixels = new ArrayList<>(), cachedPixels = List.of();
        int pendingBytes, cachedBytes;
        Set<Object> delivered = new HashSet<>();
    }
}
