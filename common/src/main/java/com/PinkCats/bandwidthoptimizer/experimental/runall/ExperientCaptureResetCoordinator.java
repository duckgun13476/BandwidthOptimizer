package com.PinkCats.bandwidthoptimizer.experimental.runall;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.channel.mes.ChannelFrameJsonlLogger;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.shadow.ChunkShadowSnapshotManager;
import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotVerifyHooks;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ExperientCaptureResetCoordinator {

    private static final Path SHARED_RESET_REQUEST_PATH =
            BandwidthOptimizerOutputPaths.resolveShared("bo-runall-capture-reset.request");
    private static int lastAppliedAttemptCount;

    private ExperientCaptureResetCoordinator() {}

    public static void requestResetForRetry(int attemptCount) {
        if (!com.PinkCats.bandwidthoptimizer.experimental.runtime.ExperientRuntimeFlags.isEnabled() || attemptCount <= 0) {
            return;
        }

        String markerText = "attempt=" + attemptCount
                + System.lineSeparator()
                + "requested_at_ms=" + System.currentTimeMillis()
                + System.lineSeparator();
        try {
            Files.writeString(SHARED_RESET_REQUEST_PATH, markerText, StandardCharsets.UTF_8);
            Bandwidthoptimizer.LOGGER.info(
                    "[ExperientCaptureReset] Requested capture reset for retry attempt {} at {}",
                    attemptCount,
                    SHARED_RESET_REQUEST_PATH.toAbsolutePath()
            );
        } catch (IOException exception) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[ExperientCaptureReset] Failed to request capture reset for attempt {} at {}",
                    attemptCount,
                    SHARED_RESET_REQUEST_PATH.toAbsolutePath(),
                    exception
            );
        }
    }

    public static boolean applyPendingResetIfNeeded(String sideName) {
        if (!com.PinkCats.bandwidthoptimizer.experimental.runtime.ExperientRuntimeFlags.isEnabled())
            return false;

        Integer requestedAttemptCount = readRequestedAttemptCount();
        if (requestedAttemptCount == null || requestedAttemptCount <= lastAppliedAttemptCount)
            return false;

        ChannelFrameJsonlLogger.initializeOutputFiles();
        ChunkHotspotVerifyHooks.resetOutputFiles();
        ChunkShadowSnapshotManager.clearAll();
        lastAppliedAttemptCount = requestedAttemptCount;
        Bandwidthoptimizer.LOGGER.info(
                "[ExperientCaptureReset] Applied capture reset on {} side for retry attempt {}",
                sideName,
                requestedAttemptCount
        );
        return true;
    }

    public static Path sharedResetRequestPath() {
        return SHARED_RESET_REQUEST_PATH;
    }

    private static Integer readRequestedAttemptCount() {
        if (!Files.exists(SHARED_RESET_REQUEST_PATH)) {
            return null;
        }

        try {
            for (String line : Files.readAllLines(SHARED_RESET_REQUEST_PATH, StandardCharsets.UTF_8)) {
                if (line.startsWith("attempt=")) {
                    return Integer.parseInt(line.substring("attempt=".length()).trim());
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return null;
    }
}
