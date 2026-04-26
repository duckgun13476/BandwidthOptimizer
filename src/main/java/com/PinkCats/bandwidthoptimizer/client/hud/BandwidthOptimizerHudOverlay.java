package com.PinkCats.bandwidthoptimizer.client.hud;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportRuntimeGuard;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelTransportTelemetry;
import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotReport;
import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotStats;
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

    private BandwidthOptimizerHudOverlay() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        enabled = false;
    }

    @SubscribeEvent
    public static void render(RenderGuiOverlayEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!enabled || minecraft.options.hideGui || minecraft.player == null) {
            return;
        }

        ChannelTransportTelemetry.Snapshot transportSnapshot = ChannelTransportTelemetry.snapshot();
        ChunkHotspotReport hotspotReport = ChunkHotspotStats.snapshotReport();
        List<String> lines = buildLines(transportSnapshot, hotspotReport);
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

    private static List<String> buildLines(
            ChannelTransportTelemetry.Snapshot transportSnapshot,
            ChunkHotspotReport hotspotReport
    ) {
        List<String> lines = new ArrayList<>(8);
        lines.add("Bandwidth Optimizer");
        addTransportLines(lines, transportSnapshot);
        addChunkLines(lines, hotspotReport);
        if (lines.size() == 1) {
            addIdleHintLines(lines);
        }
        return lines;
    }

    private static void addIdleHintLines(List<String> lines) {
        if (!ChannelTransportRuntimeGuard.isTransportAvailable()) {
            lines.add("Transport unavailable");
            if (!ChannelTransportRuntimeGuard.isExperimentalTransportEnabled()) {
                lines.add("  Transport is disabled.");
                lines.add("  Remove the disable flag.");
                return;
            }

            lines.add("  " + shortenUnavailableReason(ChannelTransportRuntimeGuard.unavailableReason()));
            return;
        }

        lines.add("Waiting for optimizer traffic");
        lines.add("  Trigger some network activity first.");
    }

    private static void addTransportLines(
            List<String> lines,
            ChannelTransportTelemetry.Snapshot transportSnapshot
    ) {
        ChannelTransportTelemetry.DirectionSnapshot outbound = transportSnapshot.outbound();
        ChannelTransportTelemetry.DirectionSnapshot inbound = transportSnapshot.inbound();
        if (outbound.frameCount() <= 0L && inbound.frameCount() <= 0L) {
            return;
        }

        lines.add("Transport");
        lines.add("  Out " + formatSavedText(outbound.baselineBytes(), outbound.transportFrameBytes())
                + " / frame " + outbound.frameCount()
                + " / pkt " + outbound.packetCount());
        lines.add("  In  " + formatSavedText(inbound.baselineBytes(), inbound.transportFrameBytes())
                + " / frame " + inbound.frameCount()
                + " / pkt " + inbound.packetCount());
        lines.add("  Algo " + transportSnapshot.algorithmId()
                + " / map " + onOffText(transportSnapshot.mappingEnabled())
                + " / zstd " + onOffText(transportSnapshot.zstdEnabled())
                + " / pid " + onOffText(transportSnapshot.packetIdMappingEnabled()));
    }

    private static void addChunkLines(List<String> lines, ChunkHotspotReport hotspotReport) {
        if (hotspotReport == null) {
            return;
        }

        ChunkHotspotReport.DirectionTotals outbound = hotspotReport.outboundTotals();
        ChunkHotspotReport.DirectionTotals inbound = hotspotReport.inboundTotals();
        long outboundFrames = outbound == null ? 0L : outbound.totalFrames();
        long inboundFrames = inbound == null ? 0L : inbound.totalFrames();
        if (outboundFrames <= 0L && inboundFrames <= 0L) {
            return;
        }

        lines.add("Chunk Hotspot");
        lines.add("  Out " + formatSavedText(
                outbound == null ? 0L : outbound.totalLogicalPacketBytes(),
                outbound == null ? 0L : outbound.totalWireFrameBytes()
        ) + " / frame " + outboundFrames);
        lines.add("  In  " + formatSavedText(
                inbound == null ? 0L : inbound.totalLogicalPacketBytes(),
                inbound == null ? 0L : inbound.totalWireFrameBytes()
        ) + " / frame " + inboundFrames);
    }

    private static String formatSavedText(long baselineBytes, long actualBytes) {
        if (baselineBytes <= 0L) {
            return formatBytes(actualBytes) + " (n/a)";
        }

        long savedBytes = baselineBytes - actualBytes;
        return formatBytes(baselineBytes) + " -> " + formatBytes(actualBytes)
                + " (" + formatPercent(savedBytes, baselineBytes) + ", " + signedBytes(savedBytes) + ")";
    }

    private static String formatPercent(long deltaBytes, long baselineBytes) {
        double percent = baselineBytes <= 0L
                ? 0.0D
                : (double) deltaBytes * 100.0D / (double) baselineBytes;
        return String.format(Locale.ROOT, "%+.1f%%", percent);
    }

    private static String signedBytes(long bytes) {
        if (bytes == 0L) {
            return "0B";
        }
        return (bytes > 0L ? "+" : "-") + formatBytes(Math.abs(bytes));
    }

    private static String onOffText(boolean enabled) {
        return enabled ? "on" : "off";
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
