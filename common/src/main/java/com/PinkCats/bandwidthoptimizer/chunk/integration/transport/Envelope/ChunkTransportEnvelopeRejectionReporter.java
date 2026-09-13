package com.PinkCats.bandwidthoptimizer.chunk.integration.transport.Envelope;

import com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportRuntimeGuard;
import com.PinkCats.bandwidthoptimizer.server.stat.ChannelBandwidthStats;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

import java.net.SocketAddress;
import java.util.UUID;

public final class ChunkTransportEnvelopeRejectionReporter {

    private static final AttributeKey<Boolean> REPORTED_ON_CONNECTION_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:malformed_chunk_envelope_reported");
    private static final int MAX_REPORTS_PER_WINDOW = 10;
    private static final long REPORT_WINDOW_MILLIS = 60_000L;
    private static final Object RATE_LIMIT_LOCK = new Object();
    private static long windowStartedAtMillis;
    private static int reportsInWindow;
    private static long suppressedReports;

    private ChunkTransportEnvelopeRejectionReporter() {}

    public static void report(
            ChannelHandlerContext context,
            byte[] encodedEnvelopeBytes,
            RuntimeException failure
    ) {
        Channel channel = context == null ? null : context.channel();
        long nowMillis = System.currentTimeMillis();
        if (!shouldReport(channel, nowMillis)) {
            return;
        }

        ChannelBandwidthStats stats = ServerBandwidthStatsRegistry.getOrCreate(channel);
        ChannelBandwidthStats.Snapshot snapshot = stats == null ? null : stats.snapshot();
        UUID playerId = snapshot == null ? null : snapshot.playerId();
        long suppressed = takeSuppressedCount();
        String detail = "channel=" + ChannelIdentity.shortText(channel)
                + ", remote=" + remoteAddress(channel)
                + ", playerName=" + (snapshot == null || snapshot.playerName() == null
                ? "<unbound>"
                : snapshot.playerName())
                + ", playerUuid=" + (playerId == null ? "<unbound>" : playerId)
                + ", encodedBytes=" + (encodedEnvelopeBytes == null ? 0 : encodedEnvelopeBytes.length)
                + ", rejection=" + failureReason(failure)
                + ", suppressedSinceLastReport=" + suppressed;
        ChannelTransportRuntimeGuard.reportRuntimeFailure(
                "chunk-envelope-security-reject",
                new IllegalArgumentException(detail, failure)
        );
    }

    static boolean shouldReportForTest(Channel channel, long nowMillis) {
        return shouldReport(channel, nowMillis);
    }

    static void resetForTest() {
        synchronized (RATE_LIMIT_LOCK) {
            windowStartedAtMillis = 0L;
            reportsInWindow = 0;
            suppressedReports = 0L;
        }
    }

    private static boolean shouldReport(Channel channel, long nowMillis) {
        if (channel != null && channel.attr(REPORTED_ON_CONNECTION_KEY).setIfAbsent(Boolean.TRUE) != null) {
            return false;
        }
        synchronized (RATE_LIMIT_LOCK) {
            if (windowStartedAtMillis == 0L
                    || nowMillis < windowStartedAtMillis
                    || nowMillis - windowStartedAtMillis >= REPORT_WINDOW_MILLIS) {
                windowStartedAtMillis = nowMillis;
                reportsInWindow = 0;
            }
            if (reportsInWindow >= MAX_REPORTS_PER_WINDOW) {
                suppressedReports++;
                return false;
            }
            reportsInWindow++;
            return true;
        }
    }

    private static long takeSuppressedCount() {
        synchronized (RATE_LIMIT_LOCK) {
            long suppressed = suppressedReports;
            suppressedReports = 0L;
            return suppressed;
        }
    }

    private static String remoteAddress(Channel channel) {
        if (channel == null) {
            return "<unknown-remote>";
        }
        try {
            SocketAddress address = channel.remoteAddress();
            return address == null ? "<unknown-remote>" : address.toString();
        } catch (RuntimeException | LinkageError ignored) {
            return "<unknown-remote>";
        }
    }

    private static String failureReason(RuntimeException failure) {
        if (failure == null) {
            return "unknown";
        }
        String message = failure.getMessage();
        String reason = failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return reason.replace('\r', ' ').replace('\n', ' ');
    }
}
