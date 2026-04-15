package com.PinkCats.bandwidthoptimizer.network.client;

import com.PinkCats.bandwidthoptimizer.network.ModNetwork;
import com.PinkCats.bandwidthoptimizer.network.message.ClientToServerOptimizationTelemetrySubscriptionPacket;
import com.PinkCats.bandwidthoptimizer.network.server.ServerPlayPacketBatchingManager;
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

@Mod.EventBusSubscriber(modid = "bandwidthoptimizer", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientOptimizationHudOverlay {
    private static boolean enabled;

    private ClientOptimizationHudOverlay() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        enabled = false;
        sendTelemetrySubscription(false);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        enabled = false;
        sendTelemetrySubscription(false);
    }

    @SubscribeEvent
    public static void render(RenderGuiOverlayEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!enabled || minecraft.options.hideGui || minecraft.player == null) {
            return;
        }

        ClientOptimizationStats.Snapshot localSnapshot = ClientOptimizationStats.snapshot();
        ClientServerOptimizationStats.Snapshot serverSnapshot = ClientServerOptimizationStats.snapshot();

        Font font = minecraft.font;
        List<String> lines = buildLines(localSnapshot, serverSnapshot);
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.width(line));
        }
        int lineHeight = font.lineHeight + 2;
        int boxWidth = width + 10;
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
            ClientOptimizationStats.Snapshot localSnapshot,
            ClientServerOptimizationStats.Snapshot serverSnapshot
    ) {
        List<String> lines = new ArrayList<>(12);
        ClientChunkCacheManager.Snapshot chunkCacheSnapshot = ClientChunkCacheManager.snapshot();
        lines.add("Bandwidth Optimizer");
        if (localSnapshot.hasData()) {
            addClientLines(lines, localSnapshot, chunkCacheSnapshot);
        }
        if (serverSnapshot.hasData()) {
            if (lines.size() > 1) {
                lines.add("");
            }
            addServerLines(lines, serverSnapshot);
        }
        if (!localSnapshot.hasData() && !serverSnapshot.hasData()) {
            lines.add("Waiting for traffic data");
            lines.add("  Move in-world or connect to a server.");
            if (chunkCacheSnapshot.totalEntries() > 0) {
                lines.add("  Cache " + formatBytes(chunkCacheSnapshot.totalBytes())
                        + " / entries " + chunkCacheSnapshot.totalEntries()
                        + " / dims " + chunkCacheSnapshot.dimensions());
            }
        }
        return lines;
    }

    private static void addClientLines(List<String> lines, ClientOptimizationStats.Snapshot snapshot, ClientChunkCacheManager.Snapshot chunkCacheSnapshot) {
        long optimizedTotalRaw = snapshot.totalRawBytes();
        long optimizedTotalSent = snapshot.totalBatchedBytes();
        long optimizedRecentRaw = snapshot.recentRawBytes();
        long optimizedRecentSent = snapshot.recentBatchedBytes();
        long bypassTotal = snapshot.bypassTotalBytes();
        long bypassRecent = snapshot.bypassRecentBytes();
        long chunkCacheSavedTotal = snapshot.chunkCacheSavedTotalBytes();
        long chunkCacheSavedRecent = snapshot.chunkCacheSavedRecentBytes();
        long effectiveTotalRaw = optimizedTotalRaw + bypassTotal + chunkCacheSavedTotal;
        long effectiveTotalSent = optimizedTotalSent + bypassTotal;
        long effectiveRecentRaw = optimizedRecentRaw + bypassRecent + chunkCacheSavedRecent;
        long effectiveRecentSent = optimizedRecentSent + bypassRecent;
        lines.add("Client");
        lines.add("  Total " + formatDelta(100.0D - ratio(effectiveTotalRaw, effectiveTotalSent))
                + " / 2 min " + formatDelta(100.0D - ratio(effectiveRecentRaw, effectiveRecentSent))
                + "  (" + formatBytes(effectiveTotalRaw) + " -> " + formatBytes(effectiveTotalSent) + ")");
        lines.add("  Optimize " + formatDelta(100.0D - ratio(optimizedTotalRaw, optimizedTotalSent))
                + " / 2 min " + formatDelta(100.0D - ratio(optimizedRecentRaw, optimizedRecentSent))
                + "  (" + formatBytes(optimizedTotalRaw) + " -> " + formatBytes(optimizedTotalSent) + ")");
        lines.add("  ChunkCache " + formatBytes(chunkCacheSavedTotal) + " / 2 min " + formatBytes(chunkCacheSavedRecent)
                + " / hit " + snapshot.chunkCacheHitTotalPackets() + " / ref " + snapshot.chunkCacheRefreshTotalPackets());
        lines.add("  CacheMem " + formatBytes(chunkCacheSnapshot.totalBytes())
                + " / entries " + chunkCacheSnapshot.totalEntries()
                + " / dims " + chunkCacheSnapshot.dimensions());
        lines.add("  Bypass " + formatBytes(bypassTotal) + " / 2 min " + formatBytes(bypassRecent));
        lines.add("  Batch " + snapshot.totalBatchCount() + " / Pkt " + snapshot.totalPacketCount()
                + " / Algo " + snapshot.algorithmId()
                + " / Win " + ServerPlayPacketBatchingManager.WINDOW_MILLIS + "ms");
    }

    private static void addServerLines(List<String> lines, ClientServerOptimizationStats.Snapshot snapshot) {
        long effectiveTotalRaw = snapshot.totalRawBytes() + snapshot.totalBypassBytes() + snapshot.totalChunkCacheSavedBytes();
        long effectiveTotalSent = snapshot.totalBatchedBytes() + snapshot.totalBypassBytes();
        long effectiveRecentRaw = snapshot.recentRawBytes() + snapshot.recentBypassBytes() + snapshot.recentChunkCacheSavedBytes();
        long effectiveRecentSent = snapshot.recentBatchedBytes() + snapshot.recentBypassBytes();
        lines.add("Server Overall");
        lines.add("  Total " + formatDelta(100.0D - ratio(effectiveTotalRaw, effectiveTotalSent))
                + " / 2 min " + formatDelta(100.0D - ratio(effectiveRecentRaw, effectiveRecentSent))
                + "  (" + formatBytes(effectiveTotalRaw) + " -> " + formatBytes(effectiveTotalSent) + ")");
        lines.add("  Optimize " + formatDelta(100.0D - ratio(snapshot.totalRawBytes(), snapshot.totalBatchedBytes()))
                + " / 2 min " + formatDelta(100.0D - ratio(snapshot.recentRawBytes(), snapshot.recentBatchedBytes()))
                + "  (" + formatBytes(snapshot.totalRawBytes()) + " -> " + formatBytes(snapshot.totalBatchedBytes()) + ")");
        lines.add("  Bypass " + formatBytes(snapshot.totalBypassBytes()) + " / ChunkCache " + formatBytes(snapshot.totalChunkCacheSavedBytes())
                + " / hit " + snapshot.totalChunkCacheHitPackets() + " / ref " + snapshot.totalChunkCacheRefreshPackets());
        lines.add("  Conn " + snapshot.activeConnections() + " / Batch " + snapshot.totalBatchCount()
                + " / Pkt " + snapshot.totalPacketCount() + " / Algo " + snapshot.algorithmId());
    }

    private static double ratio(long rawBytes, long batchedBytes) {
        if (rawBytes <= 0L) {
            return 100.0D;
        }
        return (double) batchedBytes * 100.0D / (double) rawBytes;
    }

    private static String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    private static String formatDelta(double savedPercent) {
        if (savedPercent >= 0.0D) {
            return "save " + formatPercent(savedPercent);
        }
        return "overhead " + formatPercent(-savedPercent);
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

    public static void setEnabled(boolean enabled) {
        ClientOptimizationHudOverlay.enabled = enabled;
        sendTelemetrySubscription(enabled);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    private static void sendTelemetrySubscription(boolean subscribed) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        ModNetwork.sendToServer(new ClientToServerOptimizationTelemetrySubscriptionPacket(subscribed));
    }
}
