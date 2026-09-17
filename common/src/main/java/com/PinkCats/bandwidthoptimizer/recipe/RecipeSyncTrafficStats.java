package com.PinkCats.bandwidthoptimizer.recipe;

import java.util.concurrent.atomic.LongAdder;

public final class RecipeSyncTrafficStats {

    private static final LongAdder FULL_FRAMES = new LongAdder();
    private static final LongAdder IDENTITY_FRAMES = new LongAdder();
    private static final LongAdder STRUCTURAL_DELTA_FRAMES = new LongAdder();
    private static final LongAdder BYTE_DELTA_FRAMES = new LongAdder();
    private static final LongAdder LOGICAL_BYTES = new LongAdder();
    private static final LongAdder PAYLOAD_BYTES = new LongAdder();
    private static final LongAdder FRAME_BYTES = new LongAdder();

    private RecipeSyncTrafficStats() {}

    public static void record(Mode mode, int logicalBytes, int payloadBytes, int frameBytes) {
        Mode safeMode = mode == null ? Mode.FULL : mode;
        switch (safeMode) {
            case FULL -> FULL_FRAMES.increment();
            case IDENTITY -> IDENTITY_FRAMES.increment();
            case STRUCTURAL_DELTA -> STRUCTURAL_DELTA_FRAMES.increment();
            case BYTE_DELTA -> BYTE_DELTA_FRAMES.increment();
        }
        LOGICAL_BYTES.add(Math.max(logicalBytes, 0));
        PAYLOAD_BYTES.add(Math.max(payloadBytes, 0));
        FRAME_BYTES.add(Math.max(frameBytes, 0));
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                FULL_FRAMES.sum(),
                IDENTITY_FRAMES.sum(),
                STRUCTURAL_DELTA_FRAMES.sum(),
                BYTE_DELTA_FRAMES.sum(),
                LOGICAL_BYTES.sum(),
                PAYLOAD_BYTES.sum(),
                FRAME_BYTES.sum()
        );
    }

    static void resetForTests() {
        FULL_FRAMES.reset();
        IDENTITY_FRAMES.reset();
        STRUCTURAL_DELTA_FRAMES.reset();
        BYTE_DELTA_FRAMES.reset();
        LOGICAL_BYTES.reset();
        PAYLOAD_BYTES.reset();
        FRAME_BYTES.reset();
    }

    public enum Mode {
        FULL,
        IDENTITY,
        STRUCTURAL_DELTA,
        BYTE_DELTA
    }

    public record Snapshot(
            long fullFrames,
            long identityFrames,
            long structuralDeltaFrames,
            long byteDeltaFrames,
            long logicalBytes,
            long payloadBytes,
            long frameBytes
    ) {
        public long savedBytes() {
            return Math.max(logicalBytes - frameBytes, 0L);
        }

        public long totalFrames() {
            return Math.max(fullFrames, 0L)
                    + Math.max(identityFrames, 0L)
                    + Math.max(structuralDeltaFrames, 0L)
                    + Math.max(byteDeltaFrames, 0L);
        }
    }
}
