package com.PinkCats.bandwidthoptimizer.chunk.debug;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity;
import com.PinkCats.bandwidthoptimizer.chunk.plan.ChunkPlanDecision;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkLoadDelayProbe {

    private static final String ENABLED_PROPERTY = "bandwidthoptimizer.chunk.loadDelayProbe";
    private static final String MAX_EVENTS_PROPERTY = "bandwidthoptimizer.chunk.loadDelayProbeMaxEvents";
    private static final String SLOW_STAGE_MILLIS_PROPERTY = "bandwidthoptimizer.chunk.loadDelayProbeSlowStageMillis";
    private static final boolean DEFAULT_ENABLED = false;
    private static final long DEFAULT_MAX_EVENTS = 4096L;
    private static final long DEFAULT_SLOW_STAGE_MILLIS = 25L;
    private static final long SAMPLE_INTERVAL = 64L;
    private static final boolean ENABLED = readBoolean(ENABLED_PROPERTY, DEFAULT_ENABLED);
    private static final long MAX_EVENTS = readLong(MAX_EVENTS_PROPERTY, DEFAULT_MAX_EVENTS, 0L, Long.MAX_VALUE);
    private static final long SLOW_STAGE_MILLIS =
            readLong(SLOW_STAGE_MILLIS_PROPERTY, DEFAULT_SLOW_STAGE_MILLIS, 1L, 60_000L);
    private static final AtomicLong SERVER_ENCODE_EVENTS = new AtomicLong();
    private static final AtomicLong CLIENT_DECODE_EVENTS = new AtomicLong();
    private static final AtomicLong CLIENT_MISS_EVENTS = new AtomicLong();
    private static final AtomicLong SERVER_NACK_EVENTS = new AtomicLong();
    private static final AtomicLong CACHE_BUDGET_EVENTS = new AtomicLong();
    private static final AtomicLong STAGE_EVENTS = new AtomicLong();

    private ChunkLoadDelayProbe() {}

    public static boolean isEnabled() {
        return ENABLED;
    }

    // Logs server-side chunk frame encoding so TP timelines can be compared with client receive events.
    public static void logServerEncode(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            int originalBytes,
            int envelopeBytes,
            ChunkPlanDecision decision
    ) {
        if (!ENABLED) {
            return;
        }
        long eventIndex = SERVER_ENCODE_EVENTS.incrementAndGet();
        if (!shouldLogDataFrame(frame, eventIndex)) {
            return;
        }
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkDelayProbe] server_encode event={}, nowMs={}, channel={}, op={}, kind={}, lane={}, epoch={}, observed={}, chunk={}, originalBytes={}, envelopeBytes={}, fullVersion={}, laneVersion={}, baseHash={}, payloadHash={}, reason={}, decisionReason={}, selectedBytes={}",
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
        if (!ENABLED) {
            return;
        }
        long eventIndex = CLIENT_DECODE_EVENTS.incrementAndGet();
        if (!shouldLogDataFrame(frame, eventIndex)) {
            return;
        }
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkDelayProbe] client_envelope_decode event={}, nowMs={}, channel={}, op={}, kind={}, lane={}, epoch={}, observed={}, chunk={}, envelopeBytes={}, fullVersion={}, laneVersion={}, baseHash={}, payloadHash={}, reason={}",
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
        if (!ENABLED) {
            return;
        }
        long eventIndex = CLIENT_DECODE_EVENTS.incrementAndGet();
        if (!shouldLogDataFrame(frame, eventIndex)) {
            return;
        }
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkDelayProbe] client_restored event={}, nowMs={}, channel={}, source={}, op={}, kind={}, lane={}, epoch={}, observed={}, chunk={}, restoredBytes={}, fullVersion={}, laneVersion={}, baseHash={}, payloadHash={}, reason={}",
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
        if (!ENABLED) {
            return;
        }
        long eventIndex = CLIENT_MISS_EVENTS.incrementAndGet();
        if (!shouldLogImportantFrame(frame, eventIndex)) {
            return;
        }
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkDelayProbe] client_restore_miss event={}, nowMs={}, channel={}, op={}, kind={}, lane={}, epoch={}, observed={}, chunk={}, fullVersion={}, laneVersion={}, baseHash={}, payloadHash={}, missingReason={}, nackSent={}, frameReason={}",
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
        if (!ENABLED) {
            return;
        }
        long eventIndex = SERVER_NACK_EVENTS.incrementAndGet();
        if (!shouldLogImportantFrame(nackFrame, eventIndex)) {
            return;
        }
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkDelayProbe] server_nack_recovery event={}, nowMs={}, channel={}, nackReason={}, replaySent={}, replayBytes={}, recoveryReason={}, epoch={}, observed={}, chunk={}, fullVersion={}, baseHash={}, payloadHash={}",
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
        if (!ENABLED) {
            return;
        }
        long eventIndex = CACHE_BUDGET_EVENTS.incrementAndGet();
        if (!shouldLogImportant(eventIndex)) {
            return;
        }
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkDelayProbe] client_budget_invalidate event={}, nowMs={}, channel={}, queued={}, epoch={}, chunk={}, fullVersion={}, fullHash={}, reason={}",
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
        if (!ENABLED) {
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
        if (!ENABLED) {
            return;
        }
        logStage(ChannelIdentity.longText(channel), frame, side, stage, elapsedMillis, bytes, detail);
    }

    public static long elapsedMillisSince(long startNanos) {
        if (!ENABLED || startNanos <= 0L) {
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
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkStageProbe] event={}, nowMs={}, side={}, stage={}, elapsedMs={}, channel={}, op={}, kind={}, lane={}, epoch={}, observed={}, chunk={}, bytes={}, fullVersion={}, laneVersion={}, baseHash={}, payloadHash={}, reason={}, detail={}",
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

    private static boolean readBoolean(String property, boolean defaultValue) {
        String rawValue = System.getProperty(property);
        return rawValue == null || rawValue.isBlank() ? defaultValue : Boolean.parseBoolean(rawValue);
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
}
