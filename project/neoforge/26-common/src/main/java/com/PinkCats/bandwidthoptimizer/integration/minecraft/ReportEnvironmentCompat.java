package com.PinkCats.bandwidthoptimizer.integration.minecraft;

import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

public final class ReportEnvironmentCompat {
    private ReportEnvironmentCompat() {}

    public static Path gameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    public static Path chunkBoundaryDirectory() {
        return BandwidthOptimizerOutputPaths.resolve("chunk-boundary-bandwidth");
    }

    public static Path packetRankDirectory() {
        return BandwidthOptimizerOutputPaths.resolve("transport-packet-rank");
    }

    public static Path compressionDirectory() {
        return BandwidthOptimizerOutputPaths.resolve("transport-report");
    }
}
