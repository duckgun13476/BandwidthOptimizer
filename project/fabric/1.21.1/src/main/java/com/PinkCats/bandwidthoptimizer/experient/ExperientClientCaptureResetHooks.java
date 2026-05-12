package com.PinkCats.bandwidthoptimizer.experient;

public final class ExperientClientCaptureResetHooks {

    private ExperientClientCaptureResetHooks() {}

    public static void onClientTick() {
        ExperientCaptureResetCoordinator.applyPendingResetIfNeeded("client");
    }
}

