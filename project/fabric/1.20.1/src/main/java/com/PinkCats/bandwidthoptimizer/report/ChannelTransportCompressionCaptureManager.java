package com.PinkCats.bandwidthoptimizer.report;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportLayerRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCapturedFrame;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class ChannelTransportCompressionCaptureManager {

    private static final AtomicReference<ActiveCaptureSession> ACTIVE_CAPTURE_SESSION = new AtomicReference<>();
    private static final ExecutorService REPORT_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bo-transport-report");
        thread.setDaemon(true);
        thread.setContextClassLoader(ChannelTransportCompressionCaptureManager.class.getClassLoader());
        return thread;
    });

    private ChannelTransportCompressionCaptureManager() {}

    public static boolean isCaptureActive() {
        return ACTIVE_CAPTURE_SESSION.get() != null;
    }

    public static StartResult startCapture(MinecraftServer server, CommandSourceStack source, int durationTicks) {
        int safeDurationTicks = Math.max(durationTicks, 20);
        ActiveCaptureSession newCaptureSession = new ActiveCaptureSession(server, source, safeDurationTicks);
        if (!ACTIVE_CAPTURE_SESSION.compareAndSet(null, newCaptureSession)) {
            return new StartResult(false, snapshotCurrentStatus(server));
        }
        return new StartResult(true, snapshotCurrentStatus(server));
    }


    public static StatusSnapshot snapshotCurrentStatus(MinecraftServer server) {
        ActiveCaptureSession activeCaptureSession = ACTIVE_CAPTURE_SESSION.get();
        if (activeCaptureSession == null || activeCaptureSession.server() != server)
            return StatusSnapshot.idle();
        return activeCaptureSession.snapshotStatus(server.getTickCount());
    }


    public static void recordCapturedFrame(ChannelCapturedFrame capturedFrame) {
        ActiveCaptureSession activeCaptureSession = ACTIVE_CAPTURE_SESSION.get();
        if (activeCaptureSession == null || capturedFrame == null)
            return;
        activeCaptureSession.recordFrame(capturedFrame);
    }


    public static void onServerTick() {
        ActiveCaptureSession currentCaptureSession = ACTIVE_CAPTURE_SESSION.get();
        MinecraftServer server = currentCaptureSession == null ? null : currentCaptureSession.server();
        ActiveCaptureSession activeCaptureSession = ACTIVE_CAPTURE_SESSION.get();
        if (server == null || activeCaptureSession == null || activeCaptureSession.server() != server) {
            return;
        }
        if (server.getTickCount() < activeCaptureSession.endTick()) {
            return;
        }
        if (ACTIVE_CAPTURE_SESSION.compareAndSet(activeCaptureSession, null)) {
            finishCapture(activeCaptureSession);
        }
    }

    private static void finishCapture(ActiveCaptureSession activeCaptureSession) {
        List<ChannelTransportCapturedPacketSample> capturedPacketSamples = activeCaptureSession.snapshotCapturedPackets();
        prewarmZstdRuntimeForReport();
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return ChannelTransportCompressionReportWriter.writeReport(
                                activeCaptureSession.durationTicks(),
                                capturedPacketSamples
                        );
                    } catch (Exception exception) {
                        throw new IllegalStateException("Failed to write in-game transport report", exception);
                    }
                }, REPORT_EXECUTOR)
                .whenComplete((generatedReport, throwable) -> activeCaptureSession.server().execute(() -> {
                    if (throwable != null) {
                        Bandwidthoptimizer.LOGGER.error("[TransportTest] Failed to generate in-game report", throwable);
                        Throwable rootCause = throwable.getCause() == null ? throwable : throwable.getCause();
                        activeCaptureSession.source().sendFailure(Component.literal(
                                "Transport report failed: " + rootCause.getMessage()
                        ));
                        return;
                    }
                    notifyCaptureCompleted(activeCaptureSession, generatedReport);
                }));
    }


    private static void prewarmZstdRuntimeForReport() {
        try {
            ChannelTransportLayerRuntimeConfig.runWithTemporaryOverride(
                    new ChannelTransportLayerRuntimeConfig.RuntimeOverride(false, true, false),
                    () -> {
                        try (ChannelTransportSession transportSession = new ChannelTransportSession()) {
                            byte[] encodedBytes = transportSession.encodeSinglePacket(new byte[] {0});
                            transportSession.decodeSinglePacket(encodedBytes);
                        }
                    }
            );
        } catch (Throwable throwable) {
            Bandwidthoptimizer.LOGGER.warn("[TransportTest] Failed to prewarm zstd runtime before async report", throwable);
        }
    }

    private static void notifyCaptureCompleted(
            ActiveCaptureSession activeCaptureSession,
            ChannelTransportCompressionReportWriter.GeneratedReport generatedReport
    ) {
        Path reportPath = generatedReport.reportPath().toAbsolutePath();
        Bandwidthoptimizer.LOGGER.info(
                "[TransportTest] In-game report completed. packets={}, rawBytes={}, bestScenario={}, path={}",
                generatedReport.capturedPacketCount(),
                generatedReport.capturedRawPacketBytes(),
                generatedReport.bestScenarioName(),
                reportPath
        );
        activeCaptureSession.source().sendSuccess(() -> Component.literal(
                "Transport report completed. packets="
                        + generatedReport.capturedPacketCount()
                        + ", rawBytes="
                        + generatedReport.capturedRawPacketBytes()
                        + ", best="
                        + generatedReport.bestScenarioName()
                        + ", path="
                        + reportPath
        ), true);
    }

    public record StartResult(boolean started, StatusSnapshot statusSnapshot) {
    }

    public record StatusSnapshot(
            boolean running,
            long remainingTicks,
            long capturedPacketCount,
            long capturedRawPacketBytes,
            long startTick,
            long endTick
    ) {
        private static StatusSnapshot idle() {
            return new StatusSnapshot(false, 0L, 0L, 0L, 0L, 0L);
        }
    }

    private record ActiveCaptureSession(
            MinecraftServer server,
            CommandSourceStack source,
            int durationTicks,
            long startTick,
            long endTick,
            AtomicLong nextCaptureIndex,
            Object captureLock,
            List<ChannelTransportCapturedPacketSample> capturedPacketSamples
    ) {


        private ActiveCaptureSession(MinecraftServer server, CommandSourceStack source, int durationTicks) {
            this(
                    server,
                    source,
                    durationTicks,
                    server.getTickCount(),
                    server.getTickCount() + durationTicks,
                    new AtomicLong(),
                    new Object(),
                    new ArrayList<>()
            );
        }

        private void recordFrame(ChannelCapturedFrame capturedFrame) {
            if (!"PLAY".equalsIgnoreCase(capturedFrame.protocolName()) || capturedFrame.encodedBytes().length == 0) {
                return;
            }

            ChannelTransportCapturedPacketSample capturedPacketSample = new ChannelTransportCapturedPacketSample(
                    this.nextCaptureIndex.incrementAndGet(),
                    capturedFrame.channelId(),
                    capturedFrame.direction(),
                    capturedFrame.protocolName(),
                    capturedFrame.packetClassName(),
                    capturedFrame.packetId(),
                    capturedFrame.copyEncodedBytes(),
                    capturedFrame.capturedAtMillis()
            );
            synchronized (this.captureLock) {
                this.capturedPacketSamples.add(capturedPacketSample);
            }
        }

        private List<ChannelTransportCapturedPacketSample> snapshotCapturedPackets() {
            synchronized (this.captureLock) {
                return List.copyOf(this.capturedPacketSamples);
            }
        }

        private StatusSnapshot snapshotStatus(long currentTick) {
            synchronized (this.captureLock) {
                long rawPacketBytes = 0L;
                for (ChannelTransportCapturedPacketSample capturedPacketSample : this.capturedPacketSamples) {
                    rawPacketBytes += capturedPacketSample.packetBytes().length;
                }
                return new StatusSnapshot(
                        true,
                        Math.max(this.endTick - currentTick, 0L),
                        this.capturedPacketSamples.size(),
                        rawPacketBytes,
                        this.startTick,
                        this.endTick
                );
            }
        }
    }
}

