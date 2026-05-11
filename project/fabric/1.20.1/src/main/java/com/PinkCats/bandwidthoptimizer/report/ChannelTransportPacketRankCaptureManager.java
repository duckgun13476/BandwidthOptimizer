package com.PinkCats.bandwidthoptimizer.report;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportLayerRuntimeConfig;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class ChannelTransportPacketRankCaptureManager {

    private static final AtomicReference<ActiveCaptureSession> ACTIVE_CAPTURE_SESSION = new AtomicReference<>();
    private static final ExecutorService REPORT_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bo-transport-packet-rank");
        thread.setDaemon(true);
        thread.setContextClassLoader(ChannelTransportPacketRankCaptureManager.class.getClassLoader());
        return thread;
    });

    private ChannelTransportPacketRankCaptureManager() {}


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
        if (activeCaptureSession == null || activeCaptureSession.server() != server) {
            return StatusSnapshot.idle();
        }
        return activeCaptureSession.snapshotStatus(server.getTickCount());
    }


    public static OutboundPacketCapture beginOutboundPacketCapture(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet,
            byte[] rawPacketBytes,
            byte[] transportInputPacketBytes,
            boolean chunkProtocolApplied
    ) {
        ActiveCaptureSession activeCaptureSession = ACTIVE_CAPTURE_SESSION.get();
        if (activeCaptureSession == null
                || context == null
                || packet == null
                || rawPacketBytes == null
                || transportInputPacketBytes == null
                || !"PLAY".equalsIgnoreCase(protocolName)) {
            return null;
        }

        return new OutboundPacketCapture(
                activeCaptureSession.sessionId(),
                activeCaptureSession.nextCaptureIndex().incrementAndGet(),
                System.currentTimeMillis(),
                context.channel().id().asLongText(),
                packet.getClass().getName(),
                ChannelTransportPacketRankSourceResolver.resolveSourceKey(packet),
                tryReadLeadingVarInt(rawPacketBytes),
                rawPacketBytes.length,
                transportInputPacketBytes,
                chunkProtocolApplied
        );
    }


    public static void recordDirectPassthrough(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet,
            byte[] rawPacketBytes
    ) {
        OutboundPacketCapture capture = beginOutboundPacketCapture(
                context,
                protocolName,
                packet,
                rawPacketBytes,
                rawPacketBytes,
                false
        );
        if (capture == null) {
            return;
        }
        completeCapture(
                capture,
                "DIRECT_PASSTHROUGH",
                "DIRECT",
                rawPacketBytes == null ? 0 : rawPacketBytes.length,
                false,
                1
        );
    }

    public static void completeSingleTransportCapture(
            OutboundPacketCapture capture,
            ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame
    ) {
        if (capture == null || wrappedFrame == null) {
            return;
        }
        completeCapture(
                capture,
                "SINGLE_TRANSPORT",
                wrappedFrame.frameKind().name(),
                wrappedFrame.transportFrameLength(),
                false,
                Math.max(wrappedFrame.originalPacketCount(), 1)
        );
    }


    public static void completeBatchTransportCapture(
            List<OutboundPacketCapture> captures,
            ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame
    ) {
        if (captures == null || captures.isEmpty() || wrappedFrame == null) {
            return;
        }

        if (captures.size() == 1) {
            completeCapture(
                    captures.get(0),
                    "BATCH_TRANSPORT_SINGLE_ENTRY",
                    wrappedFrame.frameKind().name(),
                    wrappedFrame.transportFrameLength(),
                    false,
                    1
            );
            return;
        }

        long totalWeight = 0L;
        for (OutboundPacketCapture capture : captures) {
            totalWeight += Math.max(capture.transportInputBytes().length, 1);
        }

        long remainingFrameBytes = wrappedFrame.transportFrameLength();
        long remainingWeight = Math.max(totalWeight, captures.size());
        for (int index = 0; index < captures.size(); index++) {
            OutboundPacketCapture capture = captures.get(index);
            int allocatedFrameBytes;
            if (index == captures.size() - 1) {
                allocatedFrameBytes = (int) Math.max(remainingFrameBytes, 0L);
            } else {
                long weight = Math.max(capture.transportInputBytes().length, 1);
                allocatedFrameBytes = (int) Math.max((remainingFrameBytes * weight) / Math.max(remainingWeight, 1L), 0L);
                remainingFrameBytes -= allocatedFrameBytes;
                remainingWeight -= weight;
            }
            completeCapture(
                    capture,
                    "BATCH_TRANSPORT_SHARE",
                    wrappedFrame.frameKind().name(),
                    allocatedFrameBytes,
                    true,
                    captures.size()
            );
        }
    }

    public static void completeSingleDirectFallbackCapture(OutboundPacketCapture capture, int actualFrameBytes) {
        if (capture == null) {
            return;
        }

        completeCapture(
                capture,
                "SINGLE_DIRECT_FALLBACK",
                "DIRECT",
                actualFrameBytes,
                false,
                1
        );
    }


    public static void completeDirectFallbackCapture(List<OutboundPacketCapture> captures) {
        if (captures == null || captures.isEmpty()) {
            return;
        }

        for (OutboundPacketCapture capture : captures) {
            completeCapture(
                    capture,
                    "BATCH_DIRECT_FALLBACK",
                    "DIRECT",
                    capture.transportInputBytes().length,
                    false,
                    1
            );
        }
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
        List<ChannelTransportPacketRankObservation> observations = activeCaptureSession.snapshotObservations();
        prewarmZstdRuntimeForReport();
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return ChannelTransportPacketRankReportWriter.writeReport(
                                activeCaptureSession.durationTicks(),
                                observations
                        );
                    } catch (Exception exception) {
                        throw new IllegalStateException("Failed to write transport packet rank report", exception);
                    }
                }, REPORT_EXECUTOR)
                .whenComplete((generatedReport, throwable) -> activeCaptureSession.server().execute(() -> {
                    if (throwable != null) {
                        Throwable rootCause = throwable.getCause() == null ? throwable : throwable.getCause();
                        Bandwidthoptimizer.LOGGER.error("[PacketRank] Failed to generate packet rank report", throwable);
                        activeCaptureSession.source().sendFailure(
                                net.minecraft.network.chat.Component.literal("Packet rank report failed: " + rootCause.getMessage())
                        );
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
                        ChannelTransportSession transportSession = new ChannelTransportSession();
                        byte[] encodedBytes = transportSession.encodeSinglePacket(new byte[] {0});
                        transportSession.decodeSinglePacket(encodedBytes);
                    }
            );
        } catch (Throwable throwable) {
            Bandwidthoptimizer.LOGGER.warn("[PacketRank] Failed to prewarm zstd runtime before async report", throwable);
        }
    }

    private static void notifyCaptureCompleted(
            ActiveCaptureSession activeCaptureSession,
            ChannelTransportPacketRankReportWriter.GeneratedReport generatedReport
    ) {
        Path reportPath = generatedReport.reportPath().toAbsolutePath();
        Path latestReportPath = generatedReport.latestReportPath().toAbsolutePath();
        Bandwidthoptimizer.LOGGER.info(
                "[PacketRank] Report completed. packets={}, negativeActualClasses={}, path={}, latest={}",
                generatedReport.capturedPacketCount(),
                generatedReport.negativeActualClassCount(),
                reportPath,
                latestReportPath
        );
        activeCaptureSession.source().sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                "Packet rank report completed. packets="
                        + generatedReport.capturedPacketCount()
                        + ", negativeActualClasses="
                        + generatedReport.negativeActualClassCount()
                        + ", path="
                        + reportPath
        ), true);
    }

    private static void completeCapture(
            OutboundPacketCapture capture,
            String actualPath,
            String actualFrameKind,
            int actualFrameBytes,
            boolean actualFrameBytesEstimated,
            int batchPacketCount
    ) {
        if (capture == null) {
            return;
        }

        ActiveCaptureSession activeCaptureSession = ACTIVE_CAPTURE_SESSION.get();
        if (activeCaptureSession == null || activeCaptureSession.sessionId() != capture.sessionId()) {
            return;
        }

        activeCaptureSession.recordObservation(new ChannelTransportPacketRankObservation(
                capture.captureIndex(),
                capture.capturedAtMillis(),
                capture.channelId(),
                capture.packetClassName(),
                capture.sourceKey(),
                capture.packetId(),
                capture.rawPacketBytes(),
                capture.transportInputBytes(),
                capture.chunkProtocolApplied(),
                actualPath,
                actualFrameKind,
                Math.max(actualFrameBytes, 0),
                actualFrameBytesEstimated,
                Math.max(batchPacketCount, 1)
        ));
    }

    private static int tryReadLeadingVarInt(byte[] encodedBytes) {
        int value = 0;
        int position = 0;
        for (int index = 0; index < encodedBytes.length && index < 5; index++) {
            int current = encodedBytes[index] & 0xFF;
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return value;
            }
            position += 7;
        }
        return -1;
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

    public record OutboundPacketCapture(
            long sessionId,
            long captureIndex,
            long capturedAtMillis,
            String channelId,
            String packetClassName,
            String sourceKey,
            int packetId,
            int rawPacketBytes,
            byte[] transportInputBytes,
            boolean chunkProtocolApplied
    ) {
        public OutboundPacketCapture {
            transportInputBytes = transportInputBytes == null ? new byte[0] : Arrays.copyOf(transportInputBytes, transportInputBytes.length);
        }
    }

    private record ActiveCaptureSession(
            MinecraftServer server,
            CommandSourceStack source,
            int durationTicks,
            long startTick,
            long endTick,
            long sessionId,
            AtomicLong nextCaptureIndex,
            Object observationLock,
            List<ChannelTransportPacketRankObservation> observations
    ) {

        private ActiveCaptureSession(MinecraftServer server, CommandSourceStack source, int durationTicks) {
            this(
                    server,
                    source,
                    durationTicks,
                    server.getTickCount(),
                    server.getTickCount() + durationTicks,
                    System.nanoTime(),
                    new AtomicLong(),
                    new Object(),
                    new ArrayList<>()
            );
        }

        private void recordObservation(ChannelTransportPacketRankObservation observation) {
            synchronized (this.observationLock) {
                this.observations.add(observation);
            }
        }

        private List<ChannelTransportPacketRankObservation> snapshotObservations() {
            synchronized (this.observationLock) {
                return List.copyOf(this.observations);
            }
        }

        private StatusSnapshot snapshotStatus(long currentTick) {
            synchronized (this.observationLock) {
                long rawPacketBytes = 0L;
                for (ChannelTransportPacketRankObservation observation : this.observations) {
                    rawPacketBytes += observation.rawPacketBytes();
                }
                return new StatusSnapshot(
                        true,
                        Math.max(this.endTick - currentTick, 0L),
                        this.observations.size(),
                        rawPacketBytes,
                        this.startTick,
                        this.endTick
                );
            }
        }
    }
}

