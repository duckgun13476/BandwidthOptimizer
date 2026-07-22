package com.PinkCats.bandwidthoptimizer.experimental.runall;

public final class ExperientClientCaptureResetHooks {

    private ExperientClientCaptureResetHooks() {}

    public static void onClientTick() {
        ExperientCaptureResetCoordinator.applyPendingResetIfNeeded("client");
    }
}

