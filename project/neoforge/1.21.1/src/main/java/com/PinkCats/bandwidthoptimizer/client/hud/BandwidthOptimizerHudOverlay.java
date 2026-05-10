package com.PinkCats.bandwidthoptimizer.client.hud;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkPersistentClientCache;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@EventBusSubscriber(modid = Bandwidthoptimizer.MODID, value = Dist.CLIENT)
public final class BandwidthOptimizerHudOverlay {

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

    private BandwidthOptimizerHudOverlay() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        enabled = false;
        resetHudCache();
        BandwidthOptimizerHudStats.reset();
    }

    // logout write
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ChunkPersistentClientCache.flushNow("neoforge_client_logging_out");
    }

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!enabled || minecraft.options.hideGui || minecraft.player == null) {
            return;
        }

        CachedHud hud = currentHud(minecraft);
        if (hud.lines().isEmpty()) {
            return;
        }

        int x = 6;
        int y = 6;

        GuiGraphics guiGraphics = event.getGuiGraphics();
        RenderSystem.enableBlend();
        guiGraphics.fill(x, y, x + hud.boxWidth(), y + hud.boxHeight(), 0xA0101018);
        guiGraphics.fill(x, y, x + hud.boxWidth(), y + 1, 0xFF66D9EF);
        for (int index = 0; index < hud.lines().size(); index++) {
            String line = hud.lines().get(index);
            guiGraphics.drawString(minecraft.font, line, x + 5, y + 4 + index * hud.lineHeight(), hudLineColor(line), false);
        }
    }

    // Render hud
    private static CachedHud currentHud(Minecraft minecraft) {
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

    private static CachedHud measureHud(Font font, List<String> lines) {
        List<String> safeLines = lines == null ? List.of() : List.copyOf(lines);
        int maxWidth = 0;
        for (String line : safeLines) {
            maxWidth = Math.max(maxWidth, font.width(line));
        }

        int lineHeight = font.lineHeight + 2;
        return new CachedHud(safeLines, lineHeight, maxWidth + 10, safeLines.size() * lineHeight + 8);
    }

    // Hud central
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
        lines.add("  " + text("hud.bandwidthoptimizer.optimize") + " " + formatTrafficRatioPercent(snapshot.optimizeTotalRawBytes(), snapshot.optimizeTotalSentBytes())
                + " | 2 min " + formatTrafficRatioPercent(snapshot.optimizeRecentRawBytes(), snapshot.optimizeRecentSentBytes())
                + "  (" + formatFlow(snapshot.optimizeTotalRawBytes(), snapshot.optimizeTotalSentBytes()) + ")");
        lines.add(buildChunkCacheLine(snapshot));
        lines.add(buildChunkCacheSourceLine(snapshot));
        lines.add("  " + text("hud.bandwidthoptimizer.local_cache") + " " + formatBytes(snapshot.localCacheBytes())
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
        return lines;
    }

    // server hud
    private static void addServerStatsLines(List<String> lines, BandwidthOptimizerHudStats.Snapshot snapshot) {
        if (snapshot == null || !snapshot.serverStatsFresh()) {
            lines.add("  " + text("hud.bandwidthoptimizer.server.waiting"));
            return;
        }
        lines.add("  " + text("hud.bandwidthoptimizer.metric.raw_flow") + " " + formatBytes(snapshot.serverOutboundRawEncodedBytes())
                + " | " + text("hud.bandwidthoptimizer.metric.actual_flow") + " " + formatBytes(snapshot.serverOutboundWireBytes())
                + " | " + text("hud.bandwidthoptimizer.metric.save") + " " + formatBytes(snapshot.serverOutboundSavedBytes())
                + " | " + text("hud.bandwidthoptimizer.metric.total_ratio") + " " + formatTrafficRatioPercent(snapshot.serverOutboundRawEncodedBytes(), snapshot.serverOutboundWireBytes()));
        lines.add("  " + text("hud.bandwidthoptimizer.metric.offline_cache") + " "
                + formatSavedShare(snapshot.serverOutboundRawEncodedBytes(), snapshot.serverOfflineReuseConfirmedSavedBytes())
                + " | " + text("hud.bandwidthoptimizer.metric.temporary_cache") + " "
                + formatSavedShare(snapshot.serverOutboundRawEncodedBytes(), snapshot.serverTemporaryReuseSavedBytes()));
        lines.add("  " + text("hud.bandwidthoptimizer.metric.optimized_flow") + " "
                + formatByteShare(snapshot.serverOutboundRawEncodedBytes(), snapshot.serverOutboundTransportFrameBytes())
                + " | " + text("hud.bandwidthoptimizer.bypass") + " "
                + formatByteShare(snapshot.serverOutboundRawEncodedBytes(), snapshot.serverOutboundBypassBytes())
                + " | " + text("hud.bandwidthoptimizer.metric.players") + " " + formatCount(snapshot.serverBoundPlayers()));
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

    // Hud
    private static String buildTitleLine() {
        return text("hud.bandwidthoptimizer.title") + " (" + Bandwidthoptimizer.displayVersion() + " beta)";
    }

    private static int hudLineColor(String line) {
        if (line == null) {
            return HUD_DEFAULT_COLOR;
        }
        String trimmedLine = line.trim();
        if (trimmedLine.equals(text("hud.bandwidthoptimizer.server"))) {
            return HUD_SERVER_TITLE_COLOR;
        }
        if (lineStartsWithText(trimmedLine, "hud.bandwidthoptimizer.metric.raw_flow")) {
            return HUD_SERVER_TOTAL_COLOR;
        }
        if (lineStartsWithText(trimmedLine, "hud.bandwidthoptimizer.metric.offline_cache")) {
            return HUD_SERVER_CACHE_COLOR;
        }
        if (lineStartsWithText(trimmedLine, "hud.bandwidthoptimizer.metric.optimized_flow")) {
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

    private static String formatRatioPercent(double ratioPercent) {
        return String.format(Locale.ROOT, "%.1f%%", Math.max(ratioPercent, 0.0D));
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

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean enabled) {
        BandwidthOptimizerHudOverlay.enabled = enabled;
        resetHudCache();
    }

    private static void resetHudCache() {
        nextHudRefreshAtMillis = 0L;
        cachedHud = CachedHud.empty();
    }

    private record CachedHud(List<String> lines, int lineHeight, int boxWidth, int boxHeight) {
        private static CachedHud empty() {
            return new CachedHud(List.of(), 0, 0, 0);
        }
    }
}
