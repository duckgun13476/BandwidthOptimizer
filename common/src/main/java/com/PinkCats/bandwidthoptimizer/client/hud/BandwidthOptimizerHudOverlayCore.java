package com.PinkCats.bandwidthoptimizer.client.hud;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.client.config.ClientChunkCacheConfig;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkPersistentClientCache;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateClientController;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateMode;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateServerState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class BandwidthOptimizerHudOverlayCore {

    private static final long HUD_REFRESH_INTERVAL_MILLIS = 100L;
    private static final int HUD_DEFAULT_COLOR = 0xF2F2F2;
    private static final int HUD_MUTED_COLOR = 0xAAB2C0;
    private static final int HUD_BYPASS_COLOR = 0xFFD166;
    private static final int HUD_SERVER_TITLE_COLOR = 0x8BD5FF;
    private static final int HUD_SERVER_TOTAL_COLOR = 0xB8DFFF;
    private static final int HUD_SERVER_CACHE_COLOR = 0xA6E3A1;
    private static final int HUD_SERVER_FLOW_COLOR = 0xC6D3E1;

    private static boolean enabled;
    private static long nextHudRefreshAtMillis;
    private static CachedHud cachedHud = CachedHud.empty();

    private BandwidthOptimizerHudOverlayCore() {}

    static void onLoggingIn() {
        enabled = false;
        resetHudCache();
        BandwidthOptimizerHudStats.reset();
    }

    static void onLoggingOut(String reason) {
        ChunkPersistentClientCache.flushAsync(reason);
    }

    static boolean shouldRender(Minecraft minecraft) {
        return minecraft != null
                && enabled
                && !minecraft.options.hideGui
                && minecraft.player != null
                && minecraft.screen == null;
    }

    static String idleIndicator(Minecraft minecraft) {
        if (minecraft == null
                || !enabled
                || minecraft.options.hideGui
                || minecraft.player == null
                || shouldRender(minecraft)
                || !IdleGateClientController.currentMode().isIdle()) {
            return null;
        }
        return text("hud.bandwidthoptimizer.idle");
    }

    static CachedHud currentHud(Minecraft minecraft) {
        long nowMillis = System.currentTimeMillis();
        if (nowMillis < nextHudRefreshAtMillis && !cachedHud.lines().isEmpty()) {
            return cachedHud;
        }

        BandwidthOptimizerHudStats.Snapshot snapshot = BandwidthOptimizerHudStats.snapshot();
        List<String> lines = buildLines(snapshot);
        cachedHud = measureHud(minecraft.font, lines);
        nextHudRefreshAtMillis = nowMillis + HUD_REFRESH_INTERVAL_MILLIS;
        return cachedHud;
    }

    static int hudLineColor(String line) {
        if (line == null) {
            return HUD_DEFAULT_COLOR;
        }
        String trimmedLine = line.trim();
        if (trimmedLine.equals(text("hud.bandwidthoptimizer.server"))) {
            return HUD_SERVER_TITLE_COLOR;
        }
        if (trimmedLine.equals(text("hud.bandwidthoptimizer.summary"))) {
            return HUD_SERVER_TITLE_COLOR;
        }
        if (lineStartsWithText(trimmedLine, "hud.bandwidthoptimizer.metric.raw_flow")) {
            return HUD_SERVER_TOTAL_COLOR;
        }
        if (lineStartsWithText(trimmedLine, "hud.bandwidthoptimizer.metric.offline_cache")) {
            return HUD_SERVER_CACHE_COLOR;
        }
        if (lineStartsWithText(trimmedLine, "hud.bandwidthoptimizer.metric.create_gate")) {
            return HUD_SERVER_CACHE_COLOR;
        }
        if (lineStartsWithText(trimmedLine, "hud.bandwidthoptimizer.metric.server_memory")) {
            return HUD_SERVER_CACHE_COLOR;
        }
        if (lineStartsWithText(trimmedLine, "hud.bandwidthoptimizer.metric.server_jvm")) {
            return HUD_SERVER_CACHE_COLOR;
        }
        if (lineStartsWithText(trimmedLine, "hud.bandwidthoptimizer.metric.optimized_flow")) {
            return HUD_SERVER_FLOW_COLOR;
        }
        if (lineStartsWithText(trimmedLine, "hud.bandwidthoptimizer.metric.realtime_speed")) {
            return HUD_SERVER_FLOW_COLOR;
        }
        if (lineStartsWithText(trimmedLine, "hud.bandwidthoptimizer.metric.estimated_save")) {
            return HUD_SERVER_FLOW_COLOR;
        }
        if (lineStartsWithText(trimmedLine, "hud.bandwidthoptimizer.bypass")) {
            return HUD_BYPASS_COLOR;
        }
        if (lineStartsWithText(trimmedLine, "hud.bandwidthoptimizer.server.waiting")) {
            return HUD_MUTED_COLOR;
        }
        return HUD_DEFAULT_COLOR;
    }

    static boolean isEnabled() {
        return enabled;
    }

    static void setEnabled(boolean enabled) {
        BandwidthOptimizerHudOverlayCore.enabled = enabled;
        resetHudCache();
    }

    private static List<String> buildLines(BandwidthOptimizerHudStats.Snapshot snapshot) {
        List<String> lines = new ArrayList<>(12);
        lines.add(buildTitleLine());
        if (snapshot == null) {
            lines.add(text("hud.bandwidthoptimizer.client"));
            addIdleHintLines(lines, null);
            lines.add(text("hud.bandwidthoptimizer.server"));
            lines.add("  " + text("hud.bandwidthoptimizer.server.waiting"));
            return lines;
        }

        lines.add(text("hud.bandwidthoptimizer.client"));
        if (!snapshot.hasData()) {
            addIdleHintLines(lines, snapshot);
            lines.add(text("hud.bandwidthoptimizer.server"));
            addServerStatsLines(lines, snapshot);
            return lines;
        }

        lines.add("  " + text("hud.bandwidthoptimizer.total_save") + " " + formatTrafficRatioPercent(snapshot.effectiveTotalRawBytes(), snapshot.effectiveTotalSentBytes())
                + " | 2 min " + formatTrafficRatioPercent(snapshot.effectiveRecentRawBytes(), snapshot.effectiveRecentSentBytes())
                + "  (" + formatFlow(snapshot.effectiveTotalRawBytes(), snapshot.effectiveTotalSentBytes()) + ")");
        lines.add(buildChunkCacheLine(snapshot));
        lines.add(buildChunkCacheSourceLine(snapshot));
        lines.add("  " + text("hud.bandwidthoptimizer.local_cache") + " " + formatBytes(snapshot.localCacheBytes())
                + "/" + formatBytes(ClientChunkCacheConfig.chunkCacheMaxMemoryBytes())
                + " | " + text("hud.bandwidthoptimizer.metric.pkt") + " " + formatCount(snapshot.localCachePacketCount())
                + " | " + text("hud.bandwidthoptimizer.metric.chunk") + " " + formatCount(snapshot.localCacheChunkCount()));
        lines.add("  " + text("hud.bandwidthoptimizer.bypass")
                + " " + text("hud.bandwidthoptimizer.bypass_flow") + " " + formatDirectionalBytes(snapshot.inboundBypassPacketBytes(), snapshot.outboundBypassPacketBytes())
                + " | " + text("hud.bandwidthoptimizer.bypass_packets") + " " + formatDirectionalCounts(snapshot.inboundBypassPacketCount(), snapshot.outboundBypassPacketCount())
                + " | 2 min " + formatBytes(snapshot.recentBypassPacketBytes()));
        lines.add("  " + text("hud.bandwidthoptimizer.map") + " " + text("hud.bandwidthoptimizer.metric.lit") + " " + formatCount(snapshot.totalMapLiteralEntries())
                + " | " + text("hud.bandwidthoptimizer.metric.ex") + " " + formatCount(snapshot.totalMapExactReferences())
                + " | " + text("hud.bandwidthoptimizer.metric.tpl") + " " + formatCount(snapshot.totalMapTemplateReferences())
                + " | " + text("hud.bandwidthoptimizer.metric.add") + " " + formatCount(snapshot.totalMapExactAdditions() + snapshot.totalMapTemplateAdditions()));
        lines.add("  " + text("hud.bandwidthoptimizer.batch") + " " + formatCount(snapshot.totalBatchCount())
                + " | " + text("hud.bandwidthoptimizer.metric.pkt") + " " + formatCount(snapshot.totalPacketCount())
                + " | " + text("hud.bandwidthoptimizer.metric.algo") + " " + safeText(snapshot.algorithmDisplayName())
                + " | " + text("hud.bandwidthoptimizer.metric.win") + " " + snapshot.batchWindowMillis() + "ms");
        lines.add(text("hud.bandwidthoptimizer.server"));
        addServerStatsLines(lines, snapshot);
        addSummaryStatsLines(lines, snapshot);
        return lines;
    }

    private static CachedHud measureHud(Font font, List<String> lines) {
        List<String> safeLines = lines == null ? List.of() : List.copyOf(lines);
        int maxWidth = 0;
        for (String line : safeLines) {
            maxWidth = Math.max(maxWidth, font.width(line));
        }

        int lineHeight = font.lineHeight + 2;
        return new CachedHud(safeLines, lineHeight, maxWidth + 10, safeLines.size() * lineHeight + 8);
    }

    private static void addServerStatsLines(List<String> lines, BandwidthOptimizerHudStats.Snapshot snapshot) {
        if (snapshot == null || !snapshot.serverStatsFresh()) {
            lines.add("  " + text("hud.bandwidthoptimizer.server.waiting"));
            return;
        }
        long serverBaselineBytes = serverBaselineBytes(snapshot);
        lines.add("  " + text("hud.bandwidthoptimizer.metric.offline_cache") + " "
                + formatSavedShare(serverBaselineBytes, snapshot.serverOfflineReuseConfirmedSavedBytes())
                + " | " + text("hud.bandwidthoptimizer.metric.temporary_cache") + " "
                + formatSavedShare(serverBaselineBytes, snapshot.serverTemporaryReuseSavedBytes()));
        long createObservedBytes = Math.max(
                snapshot.serverCreateTransportRawBytes() + snapshot.serverCreateGateSavedBytes(),
                0L);
        long createSavedBytes = Math.max(
                snapshot.serverCreateTransportSavedBytes() + snapshot.serverCreateGateSavedBytes(),
                0L);
        long createInputPackets = Math.max(
                snapshot.serverCreateTransportPackets() + snapshot.serverCreateGateSavedPackets(),
                0L);
        lines.add("  " + text("hud.bandwidthoptimizer.metric.create_gate") + " "
                + formatSavedShare(serverBaselineBytes, createSavedBytes)
                + " | " + text("hud.bandwidthoptimizer.metric.compression_ratio") + " "
                + formatTrafficRatioPercent(
                        createObservedBytes,
                        snapshot.serverCreateTransportActualBytes()
                )
                + " | " + text("hud.bandwidthoptimizer.metric.pkt") + " "
                + formatCount(createInputPackets)
                + " | gate " + formatCount(snapshot.serverCreateGateSavedPackets())
                + " | " + text("hud.bandwidthoptimizer.metric.sent") + " "
                + formatCount(snapshot.serverCreateGateReleasedPackets()));
        lines.add("  " + text("hud.bandwidthoptimizer.metric.idle_gate") + " "
                + formatSavedShare(serverBaselineBytes, snapshot.serverIdleGateSavedBytes())
                + " | " + text("hud.bandwidthoptimizer.metric.pkt") + " "
                + formatCount(snapshot.serverIdleGateSavedPackets()));
        for (IdleGateServerState.IdlePlayerSnapshot idlePlayer : snapshot.idlePlayers()) {
            String modeKey = idlePlayer.mode() == IdleGateMode.BACKGROUND_IDLE
                    ? "hud.bandwidthoptimizer.idle_player.deep"
                    : "hud.bandwidthoptimizer.idle_player.light";
            lines.add("    " + idlePlayer.playerName() + ": " + text(modeKey));
        }
        lines.add("  " + text("hud.bandwidthoptimizer.metric.optimized_flow") + " "
                + formatByteShare(serverBaselineBytes, snapshot.serverOutboundTransportFrameBytes())
                + " | " + text("hud.bandwidthoptimizer.bypass") + " "
                + formatByteShare(serverBaselineBytes, snapshot.serverOutboundBypassBytes())
                + " | " + text("hud.bandwidthoptimizer.metric.packet_raw") + " " + formatBytes(snapshot.serverOutboundRawEncodedBytes())
                + " | " + text("hud.bandwidthoptimizer.metric.players") + " " + formatCount(snapshot.serverBoundPlayers()));
        lines.add("  " + text("hud.bandwidthoptimizer.metric.server_memory") + " "
                + formatBytes(snapshot.serverShadowRetainedOriginalBytes())
                + "/" + formatBytes(snapshot.serverShadowOriginalBytesBudget())
                + " | " + text("hud.bandwidthoptimizer.metric.chunk") + " "
                + formatCount(snapshot.serverShadowChunkCount())
                + " | " + text("hud.bandwidthoptimizer.metric.hash") + " "
                + formatCount(snapshot.serverShadowMetadataPacketCount())
                + " | " + text("hud.bandwidthoptimizer.metric.pkt") + " "
                + formatCount(snapshot.serverShadowRetainedOriginalPacketCount())
                + " | " + text("hud.bandwidthoptimizer.metric.trimmed") + " "
                + formatBytes(snapshot.serverShadowEvictedOriginalBytes())
                + "/" + formatCount(snapshot.serverShadowEvictedOriginalPackets()));
        lines.add("  " + text("hud.bandwidthoptimizer.metric.server_jvm") + " "
                + formatBytes(snapshot.serverJvmUsedBytes())
                + "/" + formatBytes(snapshot.serverJvmMaxBytes()));
    }

    private static void addSummaryStatsLines(List<String> lines, BandwidthOptimizerHudStats.Snapshot snapshot) {
        if (snapshot == null || !snapshot.serverStatsFresh()) {
            return;
        }
        long serverEffectiveRawBytes = serverEffectiveRawBytes(snapshot);
        lines.add(text("hud.bandwidthoptimizer.summary"));
        lines.add("  " + text("hud.bandwidthoptimizer.metric.raw_flow") + " " + formatBytes(serverEffectiveRawBytes)
                + " | " + text("hud.bandwidthoptimizer.metric.actual_flow") + " " + formatBytes(snapshot.serverOutboundWireBytes())
                + " | " + text("hud.bandwidthoptimizer.metric.save") + " " + formatBytes(Math.max(serverEffectiveRawBytes - snapshot.serverOutboundWireBytes(), 0L))
                + " | " + text("hud.bandwidthoptimizer.metric.total_ratio") + " " + formatTrafficRatioPercent(serverEffectiveRawBytes, snapshot.serverOutboundWireBytes())
                + " | 2 min " + formatTrafficRatioPercent(snapshot.serverRecentOutboundRawEncodedBytes(), snapshot.serverRecentOutboundWireBytes()));
        lines.add("  " + text("hud.bandwidthoptimizer.metric.realtime_speed") + " "
                + text("hud.bandwidthoptimizer.metric.client_side") + " "
                + formatDirectionalRate(snapshot.clientInboundWireBytesPerSecond(), snapshot.clientOutboundWireBytesPerSecond())
                + " | " + text("hud.bandwidthoptimizer.metric.server_side") + " "
                + formatDirectionalRate(snapshot.serverInboundWireBytesPerSecond(), snapshot.serverOutboundWireBytesPerSecond()));
        lines.add("  " + text("hud.bandwidthoptimizer.metric.estimated_save")
                + "(" + text("hud.bandwidthoptimizer.metric.high_cost") + ") "
                + text("hud.bandwidthoptimizer.metric.client_side") + " "
                + formatEstimatedSavedRatio(
                        snapshot.vanillaCompressionEstimateEnabled(),
                        snapshot.optimizeTotalVanillaBaselineBytes(),
                        snapshot.optimizeTotalVanillaActualBytes()
                )
                + " | " + text("hud.bandwidthoptimizer.metric.server_side") + " "
                + formatEstimatedSavedRatio(
                        snapshot.vanillaCompressionEstimateEnabled(),
                        snapshot.serverOutboundVanillaCompressedEstimateBytes(),
                        snapshot.serverOutboundVanillaEstimateWireBytes()
                ));
    }

    private static String buildChunkCacheLine(BandwidthOptimizerHudStats.Snapshot snapshot) {
        if (snapshot == null) {
            return "  " + text("hud.bandwidthoptimizer.chunk_cache")
                    + " " + text("hud.bandwidthoptimizer.metric.save") + " 0B"
                    + " | 2 min 0B"
                    + " | " + text("hud.bandwidthoptimizer.metric.reuse") + " 0"
                    + " | " + text("hud.bandwidthoptimizer.metric.full") + " 0"
                    + " | " + text("hud.bandwidthoptimizer.metric.reuse_wire") + " 0B"
                    + " | " + text("hud.bandwidthoptimizer.metric.avg") + " 0B";
        }
        if (!snapshot.chunkTransportEnabled()
                && snapshot.chunkCacheSavedTotalBytes() <= 0L
                && snapshot.chunkCacheReuseTotalPackets() <= 0L
                && snapshot.chunkCacheFullTotalPackets() <= 0L) {
            return "  " + text("hud.bandwidthoptimizer.chunk_cache.off");
        }
        long averageReuseWireBytes = snapshot.chunkCacheReuseTotalPackets() <= 0L
                ? 0L
                : snapshot.chunkCacheReuseWireTotalBytes() / snapshot.chunkCacheReuseTotalPackets();
        return "  " + text("hud.bandwidthoptimizer.chunk_cache")
                + " " + text("hud.bandwidthoptimizer.metric.save") + " " + formatBytes(snapshot.chunkCacheSavedTotalBytes())
                + " | 2 min " + formatBytes(snapshot.chunkCacheSavedRecentBytes())
                + " | " + text("hud.bandwidthoptimizer.metric.reuse") + " " + formatCount(snapshot.chunkCacheReuseTotalPackets())
                + " | " + text("hud.bandwidthoptimizer.metric.full") + " " + formatCount(snapshot.chunkCacheFullTotalPackets())
                + " | " + text("hud.bandwidthoptimizer.metric.reuse_wire") + " " + formatBytes(snapshot.chunkCacheReuseWireTotalBytes())
                + " | " + text("hud.bandwidthoptimizer.metric.avg") + " " + formatBytes(averageReuseWireBytes);
    }

    private static String buildChunkCacheSourceLine(BandwidthOptimizerHudStats.Snapshot snapshot) {
        if (snapshot == null) {
            return "  " + text("hud.bandwidthoptimizer.cache_source")
                    + " " + text("hud.bandwidthoptimizer.metric.temp") + " 0B/0"
                    + " | " + text("hud.bandwidthoptimizer.metric.offline") + " 0B/0";
        }
        return "  " + text("hud.bandwidthoptimizer.cache_source")
                + " " + text("hud.bandwidthoptimizer.metric.temp") + " " + formatBytes(snapshot.temporaryCacheSavedTotalBytes()) + "/" + formatCount(snapshot.temporaryCacheReuseTotalPackets())
                + " | " + text("hud.bandwidthoptimizer.metric.offline") + " " + formatBytes(snapshot.offlineCacheSavedTotalBytes()) + "/" + formatCount(snapshot.offlineCacheReuseTotalPackets());
    }

    private static void addIdleHintLines(List<String> lines, BandwidthOptimizerHudStats.Snapshot snapshot) {
        if (snapshot != null && !snapshot.transportAvailable()) {
            lines.add("  " + text("hud.bandwidthoptimizer.transport.unavailable"));
            lines.add("  " + shortenUnavailableReason(snapshot.transportUnavailableReason()));
            return;
        }

        lines.add("  " + text("hud.bandwidthoptimizer.waiting"));
    }


    private static String buildTitleLine() {
        String title = text("hud.bandwidthoptimizer.title") + " (" + Bandwidthoptimizer.displayVersion() + ")";
        return IdleGateClientController.currentMode() == IdleGateMode.FOREGROUND_STILL
                ? title + " | " + text("hud.bandwidthoptimizer.idle")
                : title;
    }

    private static boolean lineStartsWithText(String trimmedLine, String key) {
        return trimmedLine != null && trimmedLine.startsWith(text(key));
    }

    private static String text(String key) {
        return I18n.get(key);
    }

    private static String formatTrafficRatioPercent(long rawBytes, long sentBytes) {
        if (rawBytes <= 0L) {
            return "0.0%";
        }
        double sentRatioPercent = (double) sentBytes * 100.0D / (double) rawBytes;
        return String.format(Locale.ROOT, "%.1f%%", Math.max(sentRatioPercent, 0.0D));
    }

    private static String formatSavedRatioPercent(long rawBytes, long sentBytes) {
        if (rawBytes <= 0L) {
            return "0.0%";
        }
        double savedRatioPercent = 100.0D - (double) Math.max(sentBytes, 0L) * 100.0D / (double) rawBytes;
        return String.format(Locale.ROOT, "%.1f%%", Math.max(savedRatioPercent, 0.0D));
    }

    private static String formatEstimatedSavedRatio(boolean enabled, long vanillaBaselineBytes, long actualBytes) {
        if (!enabled) {
            return text("hud.bandwidthoptimizer.metric.disabled");
        }
        if (vanillaBaselineBytes <= 0L) {
            return text("hud.bandwidthoptimizer.metric.collecting");
        }
        return formatSavedRatioPercent(vanillaBaselineBytes, actualBytes);
    }

    private static String formatFlow(long rawBytes, long sentBytes) {
        return formatBytes(rawBytes) + " → " + formatBytes(sentBytes);
    }

    private static String formatByteShare(long rawBytes, long bytes) {
        long safeBytes = Math.max(bytes, 0L);
        return formatBytes(safeBytes) + "/" + formatSharePercent(rawBytes, safeBytes);
    }

    private static String formatSavedShare(long rawBytes, long savedBytes) {
        long safeSavedBytes = Math.max(savedBytes, 0L);
        return formatBytes(safeSavedBytes) + "/" + formatSharePercent(rawBytes, safeSavedBytes);
    }

    private static String formatSharePercent(long rawBytes, long bytes) {
        if (rawBytes <= 0L) {
            return "0.0%";
        }
        double ratioPercent = (double) Math.max(bytes, 0L) * 100.0D / (double) rawBytes;
        return String.format(Locale.ROOT, "%.1f%%", Math.max(ratioPercent, 0.0D));
    }

    private static String formatDirectionalBytes(long inboundBytes, long outboundBytes) {
        return text("hud.bandwidthoptimizer.metric.in_short") + ": " + formatBytes(inboundBytes)
                + " / " + text("hud.bandwidthoptimizer.metric.out_short") + ": " + formatBytes(outboundBytes);
    }

    private static String formatDirectionalCounts(long inboundCount, long outboundCount) {
        return text("hud.bandwidthoptimizer.metric.in_short") + ": " + formatCount(inboundCount)
                + " / " + text("hud.bandwidthoptimizer.metric.out_short") + ": " + formatCount(outboundCount);
    }

    private static long serverBaselineBytes(BandwidthOptimizerHudStats.Snapshot snapshot) {
        if (snapshot == null) {
            return 0L;
        }
        long preEncodeSavedBytes = Math.max(snapshot.serverCreateGateSavedBytes(), 0L)
                + Math.max(snapshot.serverIdleGateSavedBytes(), 0L);
        long baselineBytes = snapshot.serverOutboundVanillaCompressedEstimateBytes() + preEncodeSavedBytes;
        return baselineBytes > preEncodeSavedBytes ? baselineBytes : serverEffectiveRawBytes(snapshot);
    }

    private static long serverEffectiveRawBytes(BandwidthOptimizerHudStats.Snapshot snapshot) {
        if (snapshot == null) {
            return 0L;
        }
        return Math.max(snapshot.serverOutboundRawEncodedBytes()
                + Math.max(snapshot.serverCreateGateSavedBytes(), 0L)
                + Math.max(snapshot.serverIdleGateSavedBytes(), 0L), 0L);
    }

    private static String formatDirectionalRate(long inboundBytesPerSecond, long outboundBytesPerSecond) {
        return text("hud.bandwidthoptimizer.metric.in_short") + ": " + formatBytes(inboundBytesPerSecond) + "/s"
                + " / " + text("hud.bandwidthoptimizer.metric.out_short") + ": " + formatBytes(outboundBytesPerSecond) + "/s";
    }

    private static String formatCount(long count) {
        long absoluteCount = Math.abs(count);
        if (absoluteCount < 1000L) {
            return Long.toString(count);
        }

        double value = count;
        String suffix = "k";
        if (absoluteCount >= 1_000_000L) {
            value = (double) count / 1_000_000.0D;
            suffix = "m";
        } else {
            value = (double) count / 1_000.0D;
        }
        return trimFourDigitNumber(value) + suffix;
    }

    private static String trimFourDigitNumber(double value) {
        double absoluteValue = Math.abs(value);
        if (absoluteValue >= 100.0D) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        if (absoluteValue >= 10.0D) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String safeText(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String shortenUnavailableReason(String unavailableReason) {
        if (unavailableReason == null || unavailableReason.isBlank()) {
            return text("hud.bandwidthoptimizer.unknown_reason");
        }

        String singleLineReason = unavailableReason.replace('\r', ' ').replace('\n', ' ').trim();
        if (singleLineReason.length() <= 60) {
            return singleLineReason;
        }
        return singleLineReason.substring(0, 57) + "...";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + "B";
        }

        double value = bytes;
        String[] units = {"KB", "MB", "GB"};
        int unitIndex = -1;
        while (value >= 1024.0D && unitIndex + 1 < units.length) {
            value /= 1024.0D;
            unitIndex++;
        }
        return String.format(Locale.ROOT, "%.2f%s", value, units[unitIndex]);
    }

    private static void resetHudCache() {
        nextHudRefreshAtMillis = 0L;
        cachedHud = CachedHud.empty();
    }

    record CachedHud(List<String> lines, int lineHeight, int boxWidth, int boxHeight) {
        private static CachedHud empty() {
            return new CachedHud(List.of(), 0, 0, 0);
        }
    }
}
