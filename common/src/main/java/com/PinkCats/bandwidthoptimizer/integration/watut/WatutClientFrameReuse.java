package com.PinkCats.bandwidthoptimizer.integration.watut;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

/** Fresh captures can be identical; explicit cached-frame replays must still reach the server. */
public final class WatutClientFrameReuse {
    public static final int MAX_BYTES = 1024 * 1024;
    public static final int REFRESH_TICKS = 200;
    private Object scope, screen, lastBuffer;
    private byte[] previous;
    private Set<UUID> viewers = Set.of();
    private long sentTick;
    private int width, height;

    public boolean suppress(Object scope, Object screen, ByteBuffer buffer, int width, int height,
                            Set<UUID> viewers, long tick) {
        if (scope == null || screen == null || buffer == null || buffer.remaining() == 0
                || buffer.remaining() > MAX_BYTES || viewers.size() > WatutFrameStore.MAX_RECIPIENTS) {
            reset(); return false;
        }
        ByteBuffer read = buffer.duplicate();
        byte[] current = new byte[read.remaining()];
        read.get(current);
        boolean same = this.scope == scope && this.screen == screen && this.width == width && this.height == height
                && this.viewers.equals(viewers) && Arrays.equals(previous, current);
        boolean explicitReplay = lastBuffer == buffer;
        boolean suppress = same && !explicitReplay && tick >= sentTick && tick - sentTick < REFRESH_TICKS;
        lastBuffer = buffer;
        if (!suppress) {
            this.scope = scope; this.screen = screen; this.width = width; this.height = height;
            this.viewers = Set.copyOf(viewers); previous = current; sentTick = tick;
        }
        return suppress;
    }

    public void reset() {
        scope = screen = lastBuffer = null; previous = null; viewers = Set.of(); sentTick = 0;
    }
}
