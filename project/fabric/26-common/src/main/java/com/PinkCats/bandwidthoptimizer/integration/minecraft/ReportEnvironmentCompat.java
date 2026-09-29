package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public final class ReportEnvironmentCompat {
    private ReportEnvironmentCompat() {}

    public static Path gameDirectory() {
        return FabricLoader.getInstance().getGameDir();
    }

    public static Path chunkBoundaryDirectory() {
        return gameDirectory().resolve("chunk-boundary-bandwidth");
    }

    public static Path packetRankDirectory() {
        return gameDirectory().resolve("transport-packet-rank");
    }

    public static Path compressionDirectory() {
        return gameDirectory().resolve("run").resolve("transport-report");
    }
}
