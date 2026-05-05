package com.PinkCats.bandwidthoptimizer.client.hud;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Mod.EventBusSubscriber(modid = Bandwidthoptimizer.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BandwidthOptimizerHudOverlay {

    private static final long HUD_REFRESH_INTERVAL_MILLIS = 100L;

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

    @SubscribeEvent
    public static void render(RenderGuiOverlayEvent.Post event) {
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
            guiGraphics.drawString(minecraft.font, hud.lines().get(index), x + 5, y + 4 + index * hud.lineHeight(), 0xF2F2F2, false);
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
        lines.add(text("hud.bandwidthoptimizer.title"));
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
            lines.add(buildServerStatsLine(snapshot));
            return lines;
        }

        lines.add("  " + text("hud.bandwidthoptimizer.total_save") + " " + formatTrafficRatioPercent(snapshot.effectiveTotalRawBytes(), snapshot.effectiveTotalSentBytes())
                + " | 2 min " + formatTrafficRatioPercent(snapshot.effectiveRecentRawBytes(), snapshot.effectiveRecentSentBytes())
                + "  (" + formatFlow(snapshot.effectiveTotalRawBytes(), snapshot.effectiveTotalSentBytes()) + ")");
        lines.add("  " + text("hud.bandwidthoptimizer.optimize") + " " + formatTrafficRatioPercent(snapshot.optimizeTotalRawBytes(), snapshot.optimizeTotalSentBytes())
                + " | 2 min " + formatTrafficRatioPercent(snapshot.optimizeRecentRawBytes(), snapshot.optimizeRecentSentBytes())
                + "  (" + formatFlow(snapshot.optimizeTotalRawBytes(), snapshot.optimizeTotalSentBytes()) + ")");
        lines.add(buildChunkCacheLine(snapshot));
        lines.add("  " + text("hud.bandwidthoptimizer.local_cache") + " " + formatBytes(snapshot.localCacheBytes())
                + " | " + text("hud.bandwidthoptimizer.metric.pkt") + " " + formatCount(snapshot.localCachePacketCount())
                + " | " + text("hud.bandwidthoptimizer.metric.chunk") + " " + formatCount(snapshot.localCacheChunkCount()));
        lines.add("  " + text("hud.bandwidthoptimizer.bypass") + " " + text("hud.bandwidthoptimizer.metric.pkt") + " " + formatCount(snapshot.totalBypassPacketCount())
                + " | 2 min " + formatCount(snapshot.recentBypassPacketCount())
                + " | " + text("hud.bandwidthoptimizer.metric.bytes") + " " + formatBytes(snapshot.totalBypassPacketBytes())
                + " | " + text("hud.bandwidthoptimizer.metric.in") + " " + formatCount(snapshot.inboundBypassPacketCount())
                + " | " + text("hud.bandwidthoptimizer.metric.out") + " " + formatCount(snapshot.outboundBypassPacketCount()));
        lines.add("  " + text("hud.bandwidthoptimizer.map") + " " + text("hud.bandwidthoptimizer.metric.lit") + " " + formatCount(snapshot.totalMapLiteralEntries())
                + " | " + text("hud.bandwidthoptimizer.metric.ex") + " " + formatCount(snapshot.totalMapExactReferences())
                + " | " + text("hud.bandwidthoptimizer.metric.tpl") + " " + formatCount(snapshot.totalMapTemplateReferences())
                + " | " + text("hud.bandwidthoptimizer.metric.add") + " " + formatCount(snapshot.totalMapExactAdditions() + snapshot.totalMapTemplateAdditions()));
        lines.add("  " + text("hud.bandwidthoptimizer.batch") + " " + formatCount(snapshot.totalBatchCount())
                + " | " + text("hud.bandwidthoptimizer.metric.pkt") + " " + formatCount(snapshot.totalPacketCount())
                + " | " + text("hud.bandwidthoptimizer.metric.algo") + " " + safeText(snapshot.algorithmDisplayName())
                + " | " + text("hud.bandwidthoptimizer.metric.win") + " " + snapshot.batchWindowMillis() + "ms");
        lines.add(text("hud.bandwidthoptimizer.server"));
        lines.add(buildServerStatsLine(snapshot));
        return lines;
    }

    // server hud
    private static String buildServerStatsLine(BandwidthOptimizerHudStats.Snapshot snapshot) {
        if (snapshot == null || !snapshot.serverStatsFresh()) {
            return "  " + text("hud.bandwidthoptimizer.server.waiting");
        }
        return "  " + text("hud.bandwidthoptimizer.metric.total") + " " + text("hud.bandwidthoptimizer.metric.raw") + " " + formatBytes(snapshot.serverOutboundRawEncodedBytes())
                + " | " + text("hud.bandwidthoptimizer.metric.wire") + " " + formatBytes(snapshot.serverOutboundWireBytes())
                + " | " + text("hud.bandwidthoptimizer.metric.in") + " " + formatBytes(snapshot.serverInboundWireBytes())
                + " | " + text("hud.bandwidthoptimizer.metric.save") + " " + formatBytes(snapshot.serverOutboundSavedBytes())
                + " | " + text("hud.bandwidthoptimizer.metric.ratio") + " " + formatRatioPercent(snapshot.serverOutboundWireRatioPercent())
                + " | " + text("hud.bandwidthoptimizer.metric.players") + " " + formatCount(snapshot.serverBoundPlayers());
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

    private static void addIdleHintLines(List<String> lines, BandwidthOptimizerHudStats.Snapshot snapshot) {
        if (snapshot != null && !snapshot.transportEnabledByProperty()) {
            lines.add("  " + text("hud.bandwidthoptimizer.transport.unavailable"));
            lines.add("  " + text("hud.bandwidthoptimizer.transport.disabled"));
            lines.add("  " + text("hud.bandwidthoptimizer.transport.remove_disable_flag"));
            return;
        }

        if (snapshot != null && !snapshot.transportAvailable()) {
            lines.add("  " + text("hud.bandwidthoptimizer.transport.unavailable"));
            lines.add("  " + shortenUnavailableReason(snapshot.transportUnavailableReason()));
            return;
        }

        lines.add("  " + text("hud.bandwidthoptimizer.waiting"));
    }

    // Hud
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
