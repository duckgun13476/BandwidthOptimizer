package com.PinkCats.bandwidthoptimizer.report;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportDispatcher.OutboundChunkEncodeResult;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.Envelope.ChunkTransportEnvelope;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.Envelope.ChunkTransportEnvelopeCodec;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ChunkBoundaryBandwidthRecorder {

    private static final Object LOCK = new Object();
    private static final int MAX_WINDOWS_PER_CHANNEL = 32;
    private static final int MAX_TOP_ENTRIES = 6;
    private static final int MAX_REASON_ENTRIES = 6;
    private static final long FLUSH_INTERVAL_MILLIS = 1_000L;
    private static final DateTimeFormatter REPORT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private static final Map<String, ChannelTraceState> CHANNEL_TRACE_STATES = new LinkedHashMap<>();

    private static boolean initialized;
    private static boolean shutdownHookInstalled;
    private static long lastFlushAtMillis;
    private static Path latestReportPath;

    private ChunkBoundaryBandwidthRecorder() {}

    public static OutboundPacketTrace beginOutboundTrace(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet,
            byte[] rawPacketBytes,
            byte[] transportInputPacketBytes,
            OutboundChunkEncodeResult encodeResult
    ) {
        if (context == null
                || packet == null
                || rawPacketBytes == null
                || protocolName == null
                || !"PLAY".equalsIgnoreCase(protocolName)) {
            return null;
        }

        String packetClassName = packet.getClass().getName();
        if (!shouldTrackPacketClass(packetClassName)) {
            return null;
        }

        ensureInitialized();
        boolean centerPacket = packet instanceof ClientboundSetChunkCacheCenterPacket;
        int centerChunkX = centerPacket ? ((ClientboundSetChunkCacheCenterPacket) packet).getX() : 0;
        int centerChunkZ = centerPacket ? ((ClientboundSetChunkCacheCenterPacket) packet).getZ() : 0;
        OutboundChunkEncodeResult safeEncodeResult = encodeResult == null
                ? new OutboundChunkEncodeResult(false, false, null, "", null)
                : encodeResult;

        return new OutboundPacketTrace(
                System.currentTimeMillis(),
                context.channel().id().asLongText(),
                packetClassName,
                safeEncodeResult.chunkPacketCandidate(),
                safeEncodeResult.chunkProtocolApplied(),
                safeEncodeResult.traceReason(),
                rawPacketBytes.length,
                copyBytesOrEmpty(transportInputPacketBytes == null ? rawPacketBytes : transportInputPacketBytes),
                centerPacket,
                centerChunkX,
                centerChunkZ
        );
    }


    public static void completeOutboundTrace(
            OutboundPacketTrace trace,
            String actualPath,
            String actualFrameKind,
            int actualFrameBytes,
            boolean actualFrameBytesEstimated,
            int batchPacketCount
    ) {
        if (trace == null) {
            return;
        }

        CompletedPacketTrace completedTrace = new CompletedPacketTrace(
                trace.capturedAtMillis(),
                trace.channelId(),
                trace.packetClassName(),
                trace.chunkPacketCandidate(),
                trace.chunkProtocolApplied(),
                trace.chunkTraceReason(),
                trace.rawPacketBytes(),
                trace.copyTransportInputPacketBytes(),
                trace.centerPacket(),
                trace.centerChunkX(),
                trace.centerChunkZ(),
                actualPath,
                actualFrameKind,
                Math.max(actualFrameBytes, 0),
                actualFrameBytesEstimated,
                Math.max(batchPacketCount, 1)
        );

        synchronized (LOCK) {
            ensureInitialized();
            recordCompletedTraceUnsafe(completedTrace);
            long now = System.currentTimeMillis();
            if (completedTrace.centerPacket()
                    || now - lastFlushAtMillis >= FLUSH_INTERVAL_MILLIS) {
                flushReportUnsafe(now);
            }
        }
    }


    private static boolean shouldTrackPacketClass(String packetClassName) {
        String simpleClassName = simpleClassName(packetClassName);
        return simpleClassName.startsWith("Clientbound") || "BundleDelimiterPacket".equals(simpleClassName);
    }

    private static void ensureInitialized() {
        if (initialized) {
            return;
        }

        try {
            Path reportDirectory = FMLPaths.GAMEDIR.get().resolve("chunk-boundary-bandwidth");
            Files.createDirectories(reportDirectory);
            latestReportPath = reportDirectory.resolve("latest-boundary-bandwidth.txt");
            Files.writeString(
                    latestReportPath,
                    "Chunk boundary bandwidth report is waiting for the first center packet.\n",
                    StandardCharsets.UTF_8
            );
            installShutdownHookIfNeeded();
            initialized = true;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to initialize chunk boundary bandwidth report output", exception);
        }
    }

    private static void installShutdownHookIfNeeded() {
        if (shutdownHookInstalled) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(ChunkBoundaryBandwidthRecorder::flushReportSafely, "bo-boundary-bandwidth-close"));
        shutdownHookInstalled = true;
    }

    private static void flushReportSafely() {
        synchronized (LOCK) {
            if (!initialized) {
                return;
            }
            flushReportUnsafe(System.currentTimeMillis());
        }
    }

    private static void recordCompletedTraceUnsafe(CompletedPacketTrace completedTrace) {
        ChannelTraceState channelTraceState = CHANNEL_TRACE_STATES.computeIfAbsent(
                completedTrace.channelId(),
                ChannelTraceState::new
        );
        if (completedTrace.centerPacket()) {
            channelTraceState.rotateWindow(completedTrace);
            return;
        }
        channelTraceState.record(completedTrace);
    }

    private static void flushReportUnsafe(long now) {
        if (latestReportPath == null) {
            return;
        }
        try {
            Files.writeString(latestReportPath, buildReportTextUnsafe(), StandardCharsets.UTF_8);
            lastFlushAtMillis = now;
        } catch (IOException exception) {
            Bandwidthoptimizer.LOGGER.warn("[BoundaryBandwidth] Failed to flush report: {}", latestReportPath, exception);
        }
    }

    private static String buildReportTextUnsafe() {
        StringBuilder builder = new StringBuilder(16_384);
        builder.append("BandwidthOptimizer Chunk Boundary Bandwidth Report").append('\n');
        builder.append("generatedAt=").append(REPORT_TIME_FORMAT.format(LocalDateTime.now())).append('\n');
        builder.append("gameDir=").append(FMLPaths.GAMEDIR.get().toAbsolutePath()).append('\n');
        builder.append("retainedWindowsPerChannel=").append(MAX_WINDOWS_PER_CHANNEL).append('\n');
        builder.append("note.window=every outbound ClientboundSetChunkCacheCenterPacket starts a new window; packets are counted until the next center packet").append('\n');
        builder.append("note.raw=vanilla packet bytes before chunk/ref transport").append('\n');
        builder.append("note.chunkInput=bytes that entered the generic transparent transport after chunk full/ref/patch or direct fallback").append('\n');
        builder.append("note.actualFrame=estimated real wire bytes after transparent transport; multi-packet batch uses proportional share").append('\n');
        builder.append('\n');

        appendOverallSummary(builder);
        appendRecentWindows(builder);
        return builder.toString();
    }

    private static void appendOverallSummary(StringBuilder builder) {
        builder.append("== Overall Summary ==").append('\n');
        OverallAggregate overallAggregate = new OverallAggregate();
        for (ChannelTraceState channelTraceState : CHANNEL_TRACE_STATES.values()) {
            overallAggregate.recordAll(channelTraceState.snapshotWindows());
        }
        if (overallAggregate.windowCount <= 0L) {
            builder.append("windows=0").append('\n').append('\n');
            return;
        }

        builder.append("windows=").append(overallAggregate.windowCount)
                .append(", packets=").append(overallAggregate.packetCount)
                .append(", raw=").append(formatBytes(overallAggregate.rawBytes))
                .append(", chunkInput=").append(formatBytes(overallAggregate.chunkInputBytes))
                .append(", actualFrame=").append(formatBytes(overallAggregate.actualBytes))
                .append(", actualRatio=").append(ratioText(overallAggregate.actualBytes, overallAggregate.rawBytes))
                .append(", actualSavedVsRaw=").append(signed(overallAggregate.rawBytes - overallAggregate.actualBytes))
                .append('\n');

        for (CategoryTotals categoryTotals : overallAggregate.sortedCategories()) {
            builder.append("- ").append(categoryTotals.category())
                    .append(": count=").append(categoryTotals.packetCount())
                    .append(", raw=").append(formatBytes(categoryTotals.rawBytes()))
                    .append(", chunkInput=").append(formatBytes(categoryTotals.chunkInputBytes()))
                    .append(", actualFrame=").append(formatBytes(categoryTotals.actualBytes()))
                    .append(", actualRatio=").append(ratioText(categoryTotals.actualBytes(), categoryTotals.rawBytes()))
                    .append(", actualSavedVsRaw=").append(signed(categoryTotals.rawBytes() - categoryTotals.actualBytes()))
                    .append('\n');
        }
        builder.append('\n');
    }

    private static void appendRecentWindows(StringBuilder builder) {
        builder.append("== Recent Windows ==").append('\n');
        boolean wroteWindow = false;
        for (ChannelTraceState channelTraceState : CHANNEL_TRACE_STATES.values()) {
            for (BoundaryWindow boundaryWindow : channelTraceState.snapshotWindows()) {
                wroteWindow = true;
                appendWindow(builder, channelTraceState.channelId(), boundaryWindow);
            }
        }
        if (!wroteWindow) {
            builder.append("none").append('\n');
        }
    }

    private static void appendWindow(StringBuilder builder, String channelId, BoundaryWindow boundaryWindow) {
        builder.append("- channel=").append(channelId)
                .append(", window=").append(boundaryWindow.windowIndex())
                .append(", center=(").append(boundaryWindow.centerChunkX()).append(", ").append(boundaryWindow.centerChunkZ()).append(')')
                .append(", state=").append(boundaryWindow.closedAtMillis() > 0L ? "closed" : "open")
                .append(", packets=").append(boundaryWindow.packetCount())
                .append(", estimatedActualPackets=").append(boundaryWindow.estimatedActualPacketCount())
                .append(", raw=").append(formatBytes(boundaryWindow.rawBytes()))
                .append(", chunkInput=").append(formatBytes(boundaryWindow.chunkInputBytes()))
                .append(", actualFrame=").append(formatBytes(boundaryWindow.actualBytes()))
                .append(", actualRatio=").append(ratioText(boundaryWindow.actualBytes(), boundaryWindow.rawBytes()))
                .append(", actualSavedVsRaw=").append(signed(boundaryWindow.rawBytes() - boundaryWindow.actualBytes()))
                .append('\n');

        for (CategoryTotals categoryTotals : boundaryWindow.sortedCategoryTotals()) {
            builder.append("  category ").append(categoryTotals.category())
                    .append(": count=").append(categoryTotals.packetCount())
                    .append(", raw=").append(formatBytes(categoryTotals.rawBytes()))
                    .append(", chunkInput=").append(formatBytes(categoryTotals.chunkInputBytes()))
                    .append(", actualFrame=").append(formatBytes(categoryTotals.actualBytes()))
                    .append(", actualRatio=").append(ratioText(categoryTotals.actualBytes(), categoryTotals.rawBytes()))
                    .append(", actualSavedVsRaw=").append(signed(categoryTotals.rawBytes() - categoryTotals.actualBytes()))
                    .append('\n');
        }

        builder.append("  topActual=");
        appendTopEntries(builder, boundaryWindow.sortedTopEntries());
        builder.append('\n');

        builder.append("  topReasons=");
        appendTopEntries(builder, boundaryWindow.sortedReasonEntries());
        builder.append('\n');
    }

    private static void appendTopEntries(StringBuilder builder, List<CategoryTotals> entries) {
        if (entries.isEmpty()) {
            builder.append("none");
            return;
        }

        for (int index = 0; index < entries.size(); index++) {
            CategoryTotals entry = entries.get(index);
            if (index > 0) {
                builder.append(" | ");
            }
            builder.append(entry.category())
                    .append(" count=").append(entry.packetCount())
                    .append(", raw=").append(formatBytes(entry.rawBytes()))
                    .append(", chunkInput=").append(formatBytes(entry.chunkInputBytes()))
                    .append(", actualFrame=").append(formatBytes(entry.actualBytes()));
        }
    }

    private static PacketContribution classifyPacket(CompletedPacketTrace completedTrace) {
        String simpleClassName = simpleClassName(completedTrace.packetClassName());
        if (completedTrace.chunkProtocolApplied()) {
            DecodedChunkTrace decodedChunkTrace = decodeChunkTrace(completedTrace.copyTransportInputPacketBytes());
            String category = categoryForChunkOperation(decodedChunkTrace.operation());
            String reason = normalizedReason(
                    decodedChunkTrace.reason(),
                    completedTrace.chunkTraceReason(),
                    category + "_no_reason"
            );
            return new PacketContribution(category, category + ":" + simpleClassName, category + ":" + reason);
        }
        if ("ClientboundForgetLevelChunkPacket".equals(simpleClassName)) {
            return new PacketContribution(
                    "forget_chunk",
                    "forget_chunk:" + simpleClassName,
                    "forget_chunk:" + normalizedReason(completedTrace.chunkTraceReason(), "", "direct_forget_chunk")
            );
        }
        if (completedTrace.chunkPacketCandidate()) {
            return new PacketContribution(
                    "chunk_direct",
                    "chunk_direct:" + simpleClassName,
                    "chunk_direct:" + normalizedReason(completedTrace.chunkTraceReason(), "", "chunk_transport_not_applied")
            );
        }
        if (isEntityTrackingPacket(simpleClassName)) {
            return new PacketContribution("entity_tracking", "entity_tracking:" + simpleClassName, "");
        }
        return new PacketContribution("other", "other:" + simpleClassName, "");
    }

    private static DecodedChunkTrace decodeChunkTrace(byte[] transportInputPacketBytes) {
        if (transportInputPacketBytes == null || !ChunkTransportEnvelopeCodec.looksLikeEnvelope(transportInputPacketBytes)) {
            return new DecodedChunkTrace(null, "chunk_envelope_missing");
        }
        try {
            ChunkTransportEnvelope envelope = ChunkTransportEnvelopeCodec.decodeEnvelope(transportInputPacketBytes);
            return new DecodedChunkTrace(
                    envelope.frame().operation(),
                    envelope.frame().reason()
            );
        } catch (RuntimeException exception) {
            return new DecodedChunkTrace(null, "chunk_envelope_decode_failed");
        }
    }

    private static String categoryForChunkOperation(ChunkHotspotFrameOp operation) {
        if (operation == ChunkHotspotFrameOp.PUBLISH_REF) {
            return "chunk_ref";
        }
        if (operation == ChunkHotspotFrameOp.PUBLISH_PATCH) {
            return "chunk_patch";
        }
        if (operation == ChunkHotspotFrameOp.PUBLISH_FULL) {
            return "chunk_full";
        }
        return "chunk_wrapped_other";
    }

    private static boolean isEntityTrackingPacket(String simpleClassName) {
        return "ClientboundMoveEntityPacket$Pos".equals(simpleClassName)
                || "ClientboundMoveEntityPacket$PosRot".equals(simpleClassName)
                || "ClientboundRotateHeadPacket".equals(simpleClassName)
                || "ClientboundTeleportEntityPacket".equals(simpleClassName)
                || "ClientboundAddEntityPacket".equals(simpleClassName)
                || "ClientboundRemoveEntitiesPacket".equals(simpleClassName)
                || "ClientboundSetEntityMotionPacket".equals(simpleClassName)
                || "ClientboundSetEntityDataPacket".equals(simpleClassName)
                || "ClientboundUpdateAttributesPacket".equals(simpleClassName)
                || "ClientboundEntityEventPacket".equals(simpleClassName);
    }

    private static String normalizedReason(String firstChoice, String secondChoice, String fallback) {
        if (firstChoice != null && !firstChoice.isBlank()) {
            return firstChoice;
        }
        if (secondChoice != null && !secondChoice.isBlank()) {
            return secondChoice;
        }
        return fallback;
    }

    private static String simpleClassName(String className) {
        if (className == null || className.isBlank()) {
            return "<unknown>";
        }
        int packageSeparator = className.lastIndexOf('.');
        return packageSeparator < 0 ? className : className.substring(packageSeparator + 1);
    }

    private static byte[] copyBytesOrEmpty(byte[] sourceBytes) {
        return sourceBytes == null ? new byte[0] : sourceBytes.clone();
    }

    private static String formatBytes(long bytes) {
        long safeBytes = Math.max(bytes, 0L);
        if (safeBytes < 1024L) {
            return safeBytes + " B";
        }
        double kib = safeBytes / 1024.0D;
        if (kib < 1024.0D) {
            return String.format(Locale.ROOT, "%.2f KiB", kib);
        }
        double mib = kib / 1024.0D;
        return String.format(Locale.ROOT, "%.2f MiB", mib);
    }

    private static String ratioText(long numerator, long denominator) {
        if (denominator <= 0L) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.3fx", numerator / (double) denominator);
    }

    private static String signed(long value) {
        return value >= 0L ? "+" + formatBytes(value) : "-" + formatBytes(-value);
    }

    public record OutboundPacketTrace(
            long capturedAtMillis,
            String channelId,
            String packetClassName,
            boolean chunkPacketCandidate,
            boolean chunkProtocolApplied,
            String chunkTraceReason,
            int rawPacketBytes,
            byte[] transportInputPacketBytes,
            boolean centerPacket,
            int centerChunkX,
            int centerChunkZ
    ) {
        public OutboundPacketTrace {
            channelId = channelId == null ? "<unknown-channel>" : channelId;
            packetClassName = packetClassName == null ? "<unknown-packet>" : packetClassName;
            chunkTraceReason = chunkTraceReason == null ? "" : chunkTraceReason;
            rawPacketBytes = Math.max(rawPacketBytes, 0);
            transportInputPacketBytes = copyBytesOrEmpty(transportInputPacketBytes);
        }

        public byte[] copyTransportInputPacketBytes() {
            return copyBytesOrEmpty(this.transportInputPacketBytes);
        }
    }

    private record CompletedPacketTrace(
            long capturedAtMillis,
            String channelId,
            String packetClassName,
            boolean chunkPacketCandidate,
            boolean chunkProtocolApplied,
            String chunkTraceReason,
            int rawPacketBytes,
            byte[] transportInputPacketBytes,
            boolean centerPacket,
            int centerChunkX,
            int centerChunkZ,
            String actualPath,
            String actualFrameKind,
            int actualFrameBytes,
            boolean actualFrameBytesEstimated,
            int batchPacketCount
    ) {
        private CompletedPacketTrace {
            channelId = channelId == null ? "<unknown-channel>" : channelId;
            packetClassName = packetClassName == null ? "<unknown-packet>" : packetClassName;
            chunkTraceReason = chunkTraceReason == null ? "" : chunkTraceReason;
            actualPath = actualPath == null ? "" : actualPath;
            actualFrameKind = actualFrameKind == null ? "" : actualFrameKind;
            rawPacketBytes = Math.max(rawPacketBytes, 0);
            actualFrameBytes = Math.max(actualFrameBytes, 0);
            batchPacketCount = Math.max(batchPacketCount, 1);
            transportInputPacketBytes = copyBytesOrEmpty(transportInputPacketBytes);
        }

        private int chunkInputBytes() {
            return this.transportInputPacketBytes.length;
        }

        private byte[] copyTransportInputPacketBytes() {
            return copyBytesOrEmpty(this.transportInputPacketBytes);
        }
    }

    private record PacketContribution(
            String category,
            String topEntryKey,
            String reasonKey
    ) {
    }

    private record DecodedChunkTrace(
            ChunkHotspotFrameOp operation,
            String reason
    ) {
    }

    private record CategoryTotals(
            String category,
            long packetCount,
            long rawBytes,
            long chunkInputBytes,
            long actualBytes
    ) {
    }

    private static final class ChannelTraceState {
        private final String channelId;
        private final ArrayDeque<BoundaryWindow> recentWindows = new ArrayDeque<>();
        private long nextWindowIndex;
        private BoundaryWindow currentWindow;

        private ChannelTraceState(String channelId) {
            this.channelId = channelId == null ? "<unknown-channel>" : channelId;
        }

        private String channelId() {
            return this.channelId;
        }

        private void rotateWindow(CompletedPacketTrace completedTrace) {
            if (this.currentWindow != null) {
                this.currentWindow.close(completedTrace.capturedAtMillis());
                this.recentWindows.addFirst(this.currentWindow);
                while (this.recentWindows.size() > MAX_WINDOWS_PER_CHANNEL - 1) {
                    this.recentWindows.removeLast();
                }
            }
            this.currentWindow = new BoundaryWindow(
                    ++this.nextWindowIndex,
                    completedTrace.centerChunkX(),
                    completedTrace.centerChunkZ(),
                    completedTrace.capturedAtMillis()
            );
        }

        private void record(CompletedPacketTrace completedTrace) {
            if (this.currentWindow == null) {
                return;
            }
            this.currentWindow.record(completedTrace);
        }

        private List<BoundaryWindow> snapshotWindows() {
            List<BoundaryWindow> windows = new ArrayList<>(this.recentWindows.size() + 1);
            if (this.currentWindow != null) {
                windows.add(this.currentWindow.copyForReport());
            }
            for (BoundaryWindow boundaryWindow : this.recentWindows) {
                windows.add(boundaryWindow.copyForReport());
            }
            return List.copyOf(windows);
        }
    }

    private static final class BoundaryWindow {
        private final long windowIndex;
        private final int centerChunkX;
        private final int centerChunkZ;
        private final long openedAtMillis;
        private long closedAtMillis;
        private long packetCount;
        private long estimatedActualPacketCount;
        private long rawBytes;
        private long chunkInputBytes;
        private long actualBytes;
        private final Map<String, MutableTotals> categoryTotals = new LinkedHashMap<>();
        private final Map<String, MutableTotals> topEntryTotals = new LinkedHashMap<>();
        private final Map<String, MutableTotals> reasonTotals = new LinkedHashMap<>();

        private BoundaryWindow(long windowIndex, int centerChunkX, int centerChunkZ, long openedAtMillis) {
            this.windowIndex = windowIndex;
            this.centerChunkX = centerChunkX;
            this.centerChunkZ = centerChunkZ;
            this.openedAtMillis = openedAtMillis;
        }

        private void record(CompletedPacketTrace completedTrace) {
            PacketContribution contribution = classifyPacket(completedTrace);
            long safeRawBytes = completedTrace.rawPacketBytes();
            long safeChunkInputBytes = completedTrace.chunkInputBytes();
            long safeActualBytes = completedTrace.actualFrameBytes();

            this.packetCount++;
            this.rawBytes += safeRawBytes;
            this.chunkInputBytes += safeChunkInputBytes;
            this.actualBytes += safeActualBytes;
            if (completedTrace.actualFrameBytesEstimated()) {
                this.estimatedActualPacketCount++;
            }
            accumulate(this.categoryTotals, contribution.category(), 1L, safeRawBytes, safeChunkInputBytes, safeActualBytes);
            accumulate(this.topEntryTotals, contribution.topEntryKey(), 1L, safeRawBytes, safeChunkInputBytes, safeActualBytes);
            if (contribution.reasonKey() != null && !contribution.reasonKey().isBlank()) {
                accumulate(this.reasonTotals, contribution.reasonKey(), 1L, safeRawBytes, safeChunkInputBytes, safeActualBytes);
            }
        }

        private void close(long closedAtMillis) {
            this.closedAtMillis = Math.max(closedAtMillis, this.openedAtMillis);
        }

        private BoundaryWindow copyForReport() {
            BoundaryWindow copy = new BoundaryWindow(this.windowIndex, this.centerChunkX, this.centerChunkZ, this.openedAtMillis);
            copy.closedAtMillis = this.closedAtMillis;
            copy.packetCount = this.packetCount;
            copy.estimatedActualPacketCount = this.estimatedActualPacketCount;
            copy.rawBytes = this.rawBytes;
            copy.chunkInputBytes = this.chunkInputBytes;
            copy.actualBytes = this.actualBytes;
            copy.categoryTotals.putAll(copyMutableTotals(this.categoryTotals));
            copy.topEntryTotals.putAll(copyMutableTotals(this.topEntryTotals));
            copy.reasonTotals.putAll(copyMutableTotals(this.reasonTotals));
            return copy;
        }

        private long windowIndex() {
            return this.windowIndex;
        }

        private int centerChunkX() {
            return this.centerChunkX;
        }

        private int centerChunkZ() {
            return this.centerChunkZ;
        }

        private long closedAtMillis() {
            return this.closedAtMillis;
        }

        private long packetCount() {
            return this.packetCount;
        }

        private long estimatedActualPacketCount() {
            return this.estimatedActualPacketCount;
        }

        private long rawBytes() {
            return this.rawBytes;
        }

        private long chunkInputBytes() {
            return this.chunkInputBytes;
        }

        private long actualBytes() {
            return this.actualBytes;
        }

        private List<CategoryTotals> sortedCategoryTotals() {
            return sortedTotals(this.categoryTotals, Integer.MAX_VALUE);
        }

        private List<CategoryTotals> sortedTopEntries() {
            return sortedTotals(this.topEntryTotals, MAX_TOP_ENTRIES);
        }

        private List<CategoryTotals> sortedReasonEntries() {
            return sortedTotals(this.reasonTotals, MAX_REASON_ENTRIES);
        }
    }

    private static final class MutableTotals {
        private final String category;
        private long packetCount;
        private long rawBytes;
        private long chunkInputBytes;
        private long actualBytes;

        private MutableTotals(String category) {
            this.category = category;
        }

        private void add(long packetCount, long rawBytes, long chunkInputBytes, long actualBytes) {
            this.packetCount += Math.max(packetCount, 0L);
            this.rawBytes += rawBytes;
            this.chunkInputBytes += chunkInputBytes;
            this.actualBytes += actualBytes;
        }

        private MutableTotals copy() {
            MutableTotals copy = new MutableTotals(this.category);
            copy.packetCount = this.packetCount;
            copy.rawBytes = this.rawBytes;
            copy.chunkInputBytes = this.chunkInputBytes;
            copy.actualBytes = this.actualBytes;
            return copy;
        }

        private CategoryTotals toTotals() {
            return new CategoryTotals(this.category, this.packetCount, this.rawBytes, this.chunkInputBytes, this.actualBytes);
        }
    }

    private static final class OverallAggregate {
        private long windowCount;
        private long packetCount;
        private long rawBytes;
        private long chunkInputBytes;
        private long actualBytes;
        private final Map<String, MutableTotals> categoryTotals = new LinkedHashMap<>();

        private void recordAll(List<BoundaryWindow> windows) {
            for (BoundaryWindow boundaryWindow : windows) {
                this.windowCount++;
                this.packetCount += boundaryWindow.packetCount();
                this.rawBytes += boundaryWindow.rawBytes();
                this.chunkInputBytes += boundaryWindow.chunkInputBytes();
                this.actualBytes += boundaryWindow.actualBytes();
                for (CategoryTotals categoryTotals : boundaryWindow.sortedCategoryTotals()) {
                    accumulate(
                            this.categoryTotals,
                            categoryTotals.category(),
                            categoryTotals.packetCount(),
                            categoryTotals.rawBytes(),
                            categoryTotals.chunkInputBytes(),
                            categoryTotals.actualBytes()
                    );
                }
            }
        }

        private List<CategoryTotals> sortedCategories() {
            return sortedTotals(this.categoryTotals, Integer.MAX_VALUE);
        }
    }

    private static void accumulate(
            Map<String, MutableTotals> totalsByKey,
            String key,
            long packetCount,
            long rawBytes,
            long chunkInputBytes,
            long actualBytes
    ) {
        totalsByKey.computeIfAbsent(key, MutableTotals::new).add(packetCount, rawBytes, chunkInputBytes, actualBytes);
    }

    private static Map<String, MutableTotals> copyMutableTotals(Map<String, MutableTotals> source) {
        Map<String, MutableTotals> copy = new LinkedHashMap<>(source.size());
        for (Map.Entry<String, MutableTotals> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().copy());
        }
        return copy;
    }

    private static List<CategoryTotals> sortedTotals(Map<String, MutableTotals> totalsByKey, int limit) {
        return totalsByKey.values().stream()
                .map(MutableTotals::toTotals)
                .sorted(Comparator
                        .comparingLong(CategoryTotals::actualBytes)
                        .reversed()
                        .thenComparing(CategoryTotals::category))
                .limit(Math.max(limit, 0))
                .toList();
    }
}
