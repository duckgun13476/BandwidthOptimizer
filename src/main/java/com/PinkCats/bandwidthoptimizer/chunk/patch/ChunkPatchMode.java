package com.PinkCats.bandwidthoptimizer.chunk.patch;

public enum ChunkPatchMode {
    GENERIC_REPLACE(0, "generic_replace"),
    SECTION_SAME_POSITIONS(1, "section_same_positions");

    private final int codecId;
    private final String logName;

    ChunkPatchMode(int codecId, String logName) {
        this.codecId = codecId;
        this.logName = logName;
    }

    public int codecId() {
        return this.codecId;
    }

    public String logName() {
        return this.logName;
    }

    public static ChunkPatchMode fromCodecId(int codecId) {
        for (ChunkPatchMode patchMode : values()) {
            if (patchMode.codecId == codecId) {
                return patchMode;
            }
        }
        throw new IllegalArgumentException("Unsupported chunk patch mode: " + codecId);
    }
}
