package com.PinkCats.bandwidthoptimizer.gate;

public enum IdleGateMode {
    ACTIVE(0),
    FOREGROUND_STILL(1),
    BACKGROUND_IDLE(2);

    private final int id;

    IdleGateMode(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public boolean isIdle() {
        return this != ACTIVE;
    }

    public boolean suppressesWorldPresentation() {
        return this == BACKGROUND_IDLE;
    }

    public static IdleGateMode byId(int id) {
        for (IdleGateMode mode : values()) {
            if (mode.id == id) {
                return mode;
            }
        }
        return ACTIVE;
    }
}
