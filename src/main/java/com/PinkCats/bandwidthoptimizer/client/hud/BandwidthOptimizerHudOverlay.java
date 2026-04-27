package com.PinkCats.bandwidthoptimizer.client.hud;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
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

    private static boolean enabled;

    private BandwidthOptimizerHudOverlay() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        enabled = false;
        BandwidthOptimizerHudStats.reset();
    }

    @SubscribeEvent
    public static void render(RenderGuiOverlayEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!enabled || minecraft.options.hideGui || minecraft.player == null) {
            return;
        }

        BandwidthOptimizerHudStats.Snapshot snapshot = BandwidthOptimizerHudStats.snapshot();
        List<String> lines = buildLines(snapshot);
        if (lines.isEmpty()) {
            return;
        }

        Font font = minecraft.font;
        int maxWidth = 0;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, font.width(line));
        }

        int lineHeight = font.lineHeight + 2;
        int boxWidth = maxWidth + 10;
        int boxHeight = lines.size() * lineHeight + 8;
        int x = 6;
        int y = 6;

        GuiGraphics guiGraphics = event.getGuiGraphics();
        RenderSystem.enableBlend();
        guiGraphics.fill(x, y, x + boxWidth, y + boxHeight, 0xA0101018);
        guiGraphics.fill(x, y, x + boxWidth, y + 1, 0xFF66D9EF);
        for (int index = 0; index < lines.size(); index++) {
            guiGraphics.drawString(font, lines.get(index), x + 5, y + 4 + index * lineHeight, 0xF2F2F2, false);
        }
    }

    private static List<String> buildLines(BandwidthOptimizerHudStats.Snapshot snapshot) {
        List<String> lines = new ArrayList<>(8);
        lines.add("Bandwidth Optimizer");
        if (snapshot == null || !snapshot.hasData()) {
            addIdleHintLines(lines, snapshot);
            return lines;
        }

        lines.add("Total save " + formatSavedPercent(snapshot.effectiveTotalRawBytes(), snapshot.effectiveTotalSentBytes())
                + " / 2 min " + formatSavedPercent(snapshot.effectiveRecentRawBytes(), snapshot.effectiveRecentSentBytes())
                + "  (" + formatFlow(snapshot.effectiveTotalRawBytes(), snapshot.effectiveTotalSentBytes()) + ")");
        lines.add("Optimize " + formatSavedPercent(snapshot.optimizeTotalRawBytes(), snapshot.optimizeTotalSentBytes())
                + " / 2 min " + formatSavedPercent(snapshot.optimizeRecentRawBytes(), snapshot.optimizeRecentSentBytes())
                + "  (" + formatFlow(snapshot.optimizeTotalRawBytes(), snapshot.optimizeTotalSentBytes()) + ")");
        lines.add(buildChunkCacheLine(snapshot));
        lines.add("LocalCache " + formatBytes(snapshot.localCacheBytes())
                + " / pkt " + snapshot.localCachePacketCount()
                + " / chunk " + snapshot.localCacheChunkCount());
        lines.add("Map lit " + snapshot.totalMapLiteralEntries()
                + " / ex " + snapshot.totalMapExactReferences()
                + " / tpl " + snapshot.totalMapTemplateReferences()
                + " / add " + (snapshot.totalMapExactAdditions() + snapshot.totalMapTemplateAdditions()));
        lines.add("Batch " + snapshot.totalBatchCount()
                + " / Pkt " + snapshot.totalPacketCount()
                + " / Algo " + safeText(snapshot.algorithmDisplayName())
                + " / Win " + snapshot.batchWindowMillis() + "ms");
        return lines;
    }

    private static String buildChunkCacheLine(BandwidthOptimizerHudStats.Snapshot snapshot) {
        if (snapshot == null) {
            return "ChunkCache save 0B / 2 min 0B / reuse 0 / full 0 / reuseWire 0B / avg 0B";
        }
        if (!snapshot.chunkTransportEnabled()
                && snapshot.chunkCacheSavedTotalBytes() <= 0L
                && snapshot.chunkCacheReuseTotalPackets() <= 0L
                && snapshot.chunkCacheFullTotalPackets() <= 0L) {
            return "ChunkCache off / hotspot transport disabled";
        }
        long averageReuseWireBytes = snapshot.chunkCacheReuseTotalPackets() <= 0L
                ? 0L
                : snapshot.chunkCacheReuseWireTotalBytes() / snapshot.chunkCacheReuseTotalPackets();
        return "ChunkCache save " + formatBytes(snapshot.chunkCacheSavedTotalBytes())
                + " / 2 min " + formatBytes(snapshot.chunkCacheSavedRecentBytes())
                + " / reuse " + snapshot.chunkCacheReuseTotalPackets()
                + " / full " + snapshot.chunkCacheFullTotalPackets()
                + " / reuseWire " + formatBytes(snapshot.chunkCacheReuseWireTotalBytes())
                + " / avg " + formatBytes(averageReuseWireBytes);
    }

    private static void addIdleHintLines(List<String> lines, BandwidthOptimizerHudStats.Snapshot snapshot) {
        if (snapshot != null && !snapshot.transportEnabledByProperty()) {
            lines.add("Transport unavailable");
            lines.add("  Transport is disabled.");
            lines.add("  Remove the disable flag.");
            return;
        }

        if (snapshot != null && !snapshot.transportAvailable()) {
            lines.add("Transport unavailable");
            lines.add("  " + shortenUnavailableReason(snapshot.transportUnavailableReason()));
            return;
        }

        lines.add("Waiting for optimizer traffic");
        lines.add("  Trigger some network activity first.");
    }

    private static String formatSavedPercent(long rawBytes, long sentBytes) {
        if (rawBytes <= 0L) {
            return "0.0%";
        }
        double sentRatioPercent = (double) sentBytes * 100.0D / (double) rawBytes;
        return String.format(Locale.ROOT, "%.1f%%", 100.0D - sentRatioPercent);
    }


    private static String formatFlow(long rawBytes, long sentBytes) {
        return formatBytes(rawBytes) + " -> " + formatBytes(sentBytes);
    }

    private static String safeText(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }


    private static String shortenUnavailableReason(String unavailableReason) {
        if (unavailableReason == null || unavailableReason.isBlank()) {
            return "unknown reason";
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
    }
}
