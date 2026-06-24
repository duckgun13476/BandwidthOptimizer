package com.PinkCats.bandwidthoptimizer.chunk.debug;

import com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity;
import com.PinkCats.bandwidthoptimizer.chunk.plan.ChunkPlanDecision;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkLoadDelayProbe {

    private static final String MAX_EVENTS_PROPERTY = "bandwidthoptimizer.chunk.loadDelayProbeMaxEvents";
    private static final String SLOW_STAGE_MILLIS_PROPERTY = "bandwidthoptimizer.chunk.loadDelayProbeSlowStageMillis";
    private static final long DEFAULT_MAX_EVENTS = 4096L;
    private static final long DEFAULT_SLOW_STAGE_MILLIS = 25L;
    private static final long SAMPLE_INTERVAL = 64L;
    private static final long MAX_EVENTS = readLong(MAX_EVENTS_PROPERTY, DEFAULT_MAX_EVENTS, 0L, Long.MAX_VALUE);
    private static final long SLOW_STAGE_MILLIS =
            readLong(SLOW_STAGE_MILLIS_PROPERTY, DEFAULT_SLOW_STAGE_MILLIS, 1L, 60_000L);
    private static final AtomicLong SERVER_ENCODE_EVENTS = new AtomicLong();
    private static final AtomicLong CLIENT_DECODE_EVENTS = new AtomicLong();
    private static final AtomicLong CLIENT_MISS_EVENTS = new AtomicLong();
    private static final AtomicLong SERVER_NACK_EVENTS = new AtomicLong();
    private static final AtomicLong CACHE_BUDGET_EVENTS = new AtomicLong();
    private static final AtomicLong STAGE_EVENTS = new AtomicLong();
    private static final AtomicLong VANILLA_CHUNK_PACKET_EVENTS = new AtomicLong();
    private static final Map<Packet<?>, ConstructedChunkPacketTiming> CONSTRUCTED_CHUNK_PACKETS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ChunkLoadDelayProbe() {}

    public static boolean isEnabled() {
        return DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.CHUNK_DELAY_TIMELINE);
    }

    // Marks vanilla chunk packet construction timing.
    public static void recordVanillaLevelChunkPacketConstructed(
            Packet<?> packet,
            int chunkX,
            int chunkZ,
            long constructStartNanos
    ) {
        if (!isEnabled() || packet == null) {
            return;
        }
        long nowMillis = System.currentTimeMillis();
        long elapsedMillis = elapsedMillisSince(constructStartNanos);
        ConstructedChunkPacketTiming timing =
                new ConstructedChunkPacketTiming(chunkX, chunkZ, nowMillis, elapsedMillis, Thread.currentThread().getName());
        CONSTRUCTED_CHUNK_PACKETS.put(packet, timing);
        long eventIndex = VANILLA_CHUNK_PACKET_EVENTS.incrementAndGet();
        if (!shouldLogVanillaChunkPacket(eventIndex, elapsedMillis)) {
            return;
        }
        DiagnosticLog.info(
                DiagnosticToolRegistry.Tool.CHUNK_DELAY_TIMELINE,
                "event=vanilla_construct, index={}, nowMs={}, stage=server_vanilla_chunk_packet_construct, chunk=({}, {}), constructElapsedMs={}, thread={}",
                eventIndex,
                nowMillis,
                chunkX,
                chunkZ,
                Math.max(elapsedMillis, 0L),
                timing.threadName()
        );
    }

    // Marks BO outbound chunk processing timing.
    public static void logVanillaLevelChunkPacketOutbound(
            ChannelHandlerContext context,
            Packet<?> packet,
            int packetBytes
    ) {
        if (!isEnabled() || !(packet instanceof ClientboundLevelChunkWithLightPacket levelChunkPacket)) {
            return;
        }
        ConstructedChunkPacketTiming timing = CONSTRUCTED_CHUNK_PACKETS.remove(packet);
        long nowMillis = System.currentTimeMillis();
        int chunkX = timing == null ? levelChunkPacket.getX() : timing.chunkX();
        int chunkZ = timing == null ? levelChunkPacket.getZ() : timing.chunkZ();
        long constructToOutboundMillis = timing == null ? -1L : Math.max(nowMillis - timing.constructedAtMillis(), 0L);
        long constructElapsedMillis = timing == null ? -1L : timing.constructElapsedMillis();
        long eventIndex = VANILLA_CHUNK_PACKET_EVENTS.incrementAndGet();
        if (!shouldLogVanillaChunkPacket(eventIndex, Math.max(constructToOutboundMillis, constructElapsedMillis))) {
            return;
        }
        DiagnosticLog.info(
                DiagnosticToolRegistry.Tool.CHUNK_DELAY_TIMELINE,
                "event=vanilla_outbound, index={}, nowMs={}, stage=server_vanilla_chunk_packet_outbound, channel={}, chunk=({}, {}), packetBytes={}, constructElapsedMs={}, constructToOutboundMs={}, constructThread={}, outboundThread={}",
                eventIndex,
                nowMillis,
                channelText(context),
                chunkX,
                chunkZ,
                Math.max(packetBytes, 0),
                constructElapsedMillis,
                constructToOutboundMillis,
                timing == null ? "<missing>" : timing.threadName(),
                Thread.currentThread().getName()
        );
    }

    // Logs server-side chunk frame encoding so TP timelines can be compared with client receive events.
    public static void logServerEncode(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            int originalBytes,
            int envelopeBytes,
            ChunkPlanDecision decision
    ) {
        if (!isEnabled()) {
            return;
        }
        long eventIndex = SERVER_ENCODE_EVENTS.incrementAndGet();
        if (!shouldLogDataFrame(frame, eventIndex)) {
            return;
        }
        DiagnosticLog.info(
                DiagnosticToolRegistry.Tool.CHUNK_DELAY_TIMELINE,
                "event=server_encode, index={}, nowMs={}, channel={}, op={}, kind={}, lane={}, epoch={}, observed={}, chunk={}, originalBytes={}, envelopeBytes={}, fullVersion={}, laneVersion={}, baseHash={}, payloadHash={}, reason={}, decisionReason={}, selectedBytes={}",
                eventIndex,
                System.currentTimeMillis(),
                channelText(context),
                frame.operation().logName(),
                frame.hotspotKind() == null ? "<unknown>" : frame.hotspotKind().logName(),
                frame.laneKind() == null ? "<unknown>" : frame.laneKind().logName(),
                frame.epoch(),
                frame.observedPacketCount(),
                frame.coordinate().logText(),
                Math.max(originalBytes, 0),
                Math.max(envelopeBytes, 0),
                frame.fullSnapshotVersion(),
                frame.laneVersion(),
                shortenHash(frame.baseSnapshotHash()),
                shortenHash(frame.payloadHash()),
                frame.reason(),
                decision == null ? "<none>" : decision.reason(),
                decision == null ? -1 : decision.selectedTransportBytes()
        );
    }

    public static void logServerEncode(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            int originalBytes,
            int envelopeBytes
    ) {
        logServerEncode(context, frame, originalBytes, envelopeBytes, null);
    }

    // Logs the first point where the client sees a BO chunk envelope.
    public static void logClientEnvelopeDecode(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            int envelopeBytes
    ) {
        if (!isEnabled()) {
            return;
        }
        long eventIndex = CLIENT_DECODE_EVENTS.incrementAndGet();
        if (!shouldLogDataFrame(frame, eventIndex)) {
            return;
        }
        DiagnosticLog.info(
                DiagnosticToolRegistry.Tool.CHUNK_DELAY_TIMELINE,
                "event=client_envelope_decode, index={}, nowMs={}, channel={}, op={}, kind={}, lane={}, epoch={}, observed={}, chunk={}, envelopeBytes={}, fullVersion={}, laneVersion={}, baseHash={}, payloadHash={}, reason={}",
                eventIndex,
                System.currentTimeMillis(),
                channelText(context),
                frame.operation().logName(),
                frame.hotspotKind() == null ? "<unknown>" : frame.hotspotKind().logName(),
                frame.laneKind() == null ? "<unknown>" : frame.laneKind().logName(),
                frame.epoch(),
                frame.observedPacketCount(),
                frame.coordinate().logText(),
                Math.max(envelopeBytes, 0),
                frame.fullSnapshotVersion(),
                frame.laneVersion(),
                shortenHash(frame.baseSnapshotHash()),
                shortenHash(frame.payloadHash()),
                frame.reason()
        );
    }

    // Logs successful restore of a ref, patch, or full frame back into vanilla packet bytes.
    public static void logClientRestored(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            int restoredBytes,
            String source
    ) {
        if (!isEnabled()) {
            return;
        }
        long eventIndex = CLIENT_DECODE_EVENTS.incrementAndGet();
        if (!shouldLogDataFrame(frame, eventIndex)) {
            return;
        }
        DiagnosticLog.info(
                DiagnosticToolRegistry.Tool.CHUNK_DELAY_TIMELINE,
                "event=client_restored, index={}, nowMs={}, channel={}, source={}, op={}, kind={}, lane={}, epoch={}, observed={}, chunk={}, restoredBytes={}, fullVersion={}, laneVersion={}, baseHash={}, payloadHash={}, reason={}",
                eventIndex,
                System.currentTimeMillis(),
                channelText(context),
                source == null || source.isBlank() ? "<unknown>" : source,
                frame.operation().logName(),
                frame.hotspotKind() == null ? "<unknown>" : frame.hotspotKind().logName(),
                frame.laneKind() == null ? "<unknown>" : frame.laneKind().logName(),
                frame.epoch(),
                frame.observedPacketCount(),
                frame.coordinate().logText(),
                Math.max(restoredBytes, 0),
                frame.fullSnapshotVersion(),
                frame.laneVersion(),
                shortenHash(frame.baseSnapshotHash()),
                shortenHash(frame.payloadHash()),
                frame.reason()
        );
    }

    // Logs ref or patch restore misses that can trigger NACK or fallback behavior.
    public static void logClientRestoreMiss(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            String reason,
            boolean nackSent
    ) {
        if (!isEnabled()) {
            return;
        }
        long eventIndex = CLIENT_MISS_EVENTS.incrementAndGet();
        if (!shouldLogImportantFrame(frame, eventIndex)) {
            return;
        }
        DiagnosticLog.info(
                DiagnosticToolRegistry.Tool.CHUNK_DELAY_TIMELINE,
                "event=client_restore_miss, index={}, nowMs={}, channel={}, op={}, kind={}, lane={}, epoch={}, observed={}, chunk={}, fullVersion={}, laneVersion={}, baseHash={}, payloadHash={}, missingReason={}, nackSent={}, frameReason={}",
                eventIndex,
                System.currentTimeMillis(),
                channelText(context),
                frame == null || frame.operation() == null ? "<unknown>" : frame.operation().logName(),
                frame == null || frame.hotspotKind() == null ? "<unknown>" : frame.hotspotKind().logName(),
                frame == null || frame.laneKind() == null ? "<unknown>" : frame.laneKind().logName(),
                frame == null ? 0L : frame.epoch(),
                frame == null ? 0L : frame.observedPacketCount(),
                frame == null || frame.coordinate() == null ? "<unknown>" : frame.coordinate().logText(),
                frame == null ? 0L : frame.fullSnapshotVersion(),
                frame == null ? 0L : frame.laneVersion(),
                frame == null ? "<none>" : shortenHash(frame.baseSnapshotHash()),
                frame == null ? "<none>" : shortenHash(frame.payloadHash()),
                reason == null ? "" : reason,
                nackSent,
                frame == null ? "" : frame.reason()
        );
    }

    // Logs how the server reacts after a client-side restore miss.
    public static void logServerNackRecovery(
            ChannelHandlerContext context,
            ChunkHotspotFrame nackFrame,
            boolean replaySent,
            int replayBytes,
            String recoveryReason
    ) {
        if (!isEnabled()) {
            return;
        }
        long eventIndex = SERVER_NACK_EVENTS.incrementAndGet();
        if (!shouldLogImportantFrame(nackFrame, eventIndex)) {
            return;
        }
        DiagnosticLog.info(
                DiagnosticToolRegistry.Tool.CHUNK_DELAY_TIMELINE,
                "event=server_nack_recovery, index={}, nowMs={}, channel={}, nackReason={}, replaySent={}, replayBytes={}, recoveryReason={}, epoch={}, observed={}, chunk={}, fullVersion={}, baseHash={}, payloadHash={}",
                eventIndex,
                System.currentTimeMillis(),
                channelText(context),
                nackFrame == null ? "" : nackFrame.reason(),
                replaySent,
                Math.max(replayBytes, 0),
                recoveryReason == null ? "" : recoveryReason,
                nackFrame == null ? 0L : nackFrame.epoch(),
                nackFrame == null ? 0L : nackFrame.observedPacketCount(),
                nackFrame == null || nackFrame.coordinate() == null ? "<unknown>" : nackFrame.coordinate().logText(),
                nackFrame == null ? 0L : nackFrame.fullSnapshotVersion(),
                nackFrame == null ? "<none>" : shortenHash(nackFrame.baseSnapshotHash()),
                nackFrame == null ? "<none>" : shortenHash(nackFrame.payloadHash())
        );
    }

    // Logs client cache budget invalidations that may remove ref bases.
    public static void logClientBudgetInvalidate(
            String channelId,
            long scopeId,
            String coordinateText,
            long fullSnapshotVersion,
            String fullSnapshotHash,
            String reason,
            boolean queued
    ) {
        if (!isEnabled()) {
            return;
        }
        long eventIndex = CACHE_BUDGET_EVENTS.incrementAndGet();
        if (!shouldLogImportant(eventIndex)) {
            return;
        }
        DiagnosticLog.info(
                DiagnosticToolRegistry.Tool.CHUNK_DELAY_TIMELINE,
                "event=client_budget_invalidate, index={}, nowMs={}, channel={}, queued={}, epoch={}, chunk={}, fullVersion={}, fullHash={}, reason={}",
                eventIndex,
                System.currentTimeMillis(),
                channelId == null || channelId.isBlank() ? "<unknown>" : channelId,
                queued,
                Math.max(scopeId, 0L),
                coordinateText == null || coordinateText.isBlank() ? "<unknown>" : coordinateText,
                Math.max(fullSnapshotVersion, 0L),
                shortenHash(fullSnapshotHash),
                reason == null ? "" : reason
        );
    }

    public static void logStage(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            String side,
            String stage,
            long elapsedMillis,
            int bytes,
            String detail
    ) {
        if (!isEnabled()) {
            return;
        }
        logStage(channelText(context), frame, side, stage, elapsedMillis, bytes, detail);
    }

    public static void logStage(
            Channel channel,
            ChunkHotspotFrame frame,
            String side,
            String stage,
            long elapsedMillis,
            int bytes,
            String detail
    ) {
        if (!isEnabled()) {
            return;
        }
        logStage(ChannelIdentity.longText(channel), frame, side, stage, elapsedMillis, bytes, detail);
    }

    public static long elapsedMillisSince(long startNanos) {
        if (!isEnabled() || startNanos <= 0L) {
            return 0L;
        }
        return TimeUnit.NANOSECONDS.toMillis(Math.max(System.nanoTime() - startNanos, 0L));
    }

    private static void logStage(
            String channelText,
            ChunkHotspotFrame frame,
            String side,
            String stage,
            long elapsedMillis,
            int bytes,
            String detail
    ) {
        long eventIndex = STAGE_EVENTS.incrementAndGet();
        if (!shouldLogStage(frame, eventIndex, elapsedMillis)) {
            return;
        }
        DiagnosticLog.info(
                DiagnosticToolRegistry.Tool.CHUNK_DELAY_TIMELINE,
                "event=stage, index={}, nowMs={}, side={}, stage={}, elapsedMs={}, channel={}, op={}, kind={}, lane={}, epoch={}, observed={}, chunk={}, bytes={}, fullVersion={}, laneVersion={}, baseHash={}, payloadHash={}, reason={}, detail={}",
                eventIndex,
                System.currentTimeMillis(),
                safeText(side, "<unknown>"),
                safeText(stage, "<unknown>"),
                Math.max(elapsedMillis, 0L),
                channelText == null || channelText.isBlank() ? "<unknown>" : channelText,
                frame == null || frame.operation() == null ? "<none>" : frame.operation().logName(),
                frame == null || frame.hotspotKind() == null ? "<none>" : frame.hotspotKind().logName(),
                frame == null || frame.laneKind() == null ? "<none>" : frame.laneKind().logName(),
                frame == null ? 0L : frame.epoch(),
                frame == null ? 0L : frame.observedPacketCount(),
                frame == null || frame.coordinate() == null ? "<unknown>" : frame.coordinate().logText(),
                Math.max(bytes, 0),
                frame == null ? 0L : frame.fullSnapshotVersion(),
                frame == null ? 0L : frame.laneVersion(),
                frame == null ? "<none>" : shortenHash(frame.baseSnapshotHash()),
                frame == null ? "<none>" : shortenHash(frame.payloadHash()),
                frame == null ? "" : frame.reason(),
                safeText(detail, "")
        );
    }

    private static boolean shouldLogStage(ChunkHotspotFrame frame, long eventIndex, long elapsedMillis) {
        return elapsedMillis >= SLOW_STAGE_MILLIS
                || eventIndex <= 64L
                || (frame != null
                && frame.operation() == ChunkHotspotFrameOp.CLIENT_CACHE_MANIFEST
                && shouldLogImportant(eventIndex));
    }

    private static boolean shouldLogVanillaChunkPacket(long eventIndex, long elapsedMillis) {
        return elapsedMillis >= SLOW_STAGE_MILLIS || eventIndex <= 128L || shouldLogSample(eventIndex);
    }

    private static boolean shouldLogDataFrame(ChunkHotspotFrame frame, long eventIndex) {
        return shouldLogImportantFrame(frame, eventIndex)
                || (frame != null
                && frame.operation() != null
                && frame.coordinate() != null
                && frame.coordinate().present()
                && (frame.operation() == ChunkHotspotFrameOp.PUBLISH_FULL
                || frame.operation() == ChunkHotspotFrameOp.PUBLISH_REF
                || frame.operation() == ChunkHotspotFrameOp.PUBLISH_PATCH)
                && shouldLogSample(eventIndex));
    }

    private static boolean shouldLogImportantFrame(ChunkHotspotFrame frame, long eventIndex) {
        return frame != null
                && frame.operation() != null
                && frame.coordinate() != null
                && frame.coordinate().present()
                && shouldLogImportant(eventIndex);
    }

    private static boolean shouldLogImportant(long eventIndex) {
        return MAX_EVENTS <= 0L || eventIndex <= MAX_EVENTS || eventIndex % SAMPLE_INTERVAL == 0L;
    }

    private static boolean shouldLogSample(long eventIndex) {
        return eventIndex <= 128L
                || eventIndex % SAMPLE_INTERVAL == 0L
                || (MAX_EVENTS > 0L && eventIndex <= MAX_EVENTS);
    }

    private static long readLong(String property, long defaultValue, long minValue, long maxValue) {
        String rawValue = System.getProperty(property);
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        try {
            long parsedValue = Long.parseLong(rawValue.trim());
            return Math.max(minValue, Math.min(maxValue, parsedValue));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String channelText(ChannelHandlerContext context) {
        return context == null ? "<null>" : ChannelIdentity.longText(context.channel());
    }

    private static String shortenHash(String hashHex) {
        if (hashHex == null || hashHex.isBlank()) {
            return "<none>";
        }
        return hashHex.length() <= 12 ? hashHex : hashHex.substring(0, 12);
    }

    private static String safeText(String text, String fallback) {
        if (text == null || text.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        return text.length() <= 180 ? text : text.substring(0, 180);
    }

    private record ConstructedChunkPacketTiming(
            int chunkX,
            int chunkZ,
            long constructedAtMillis,
            long constructElapsedMillis,
            String threadName
    ) {}
}
