package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.debug.DebugRuntimeConfig;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChannelTransportBypassRankLogger {

    private static final Object LOCK = new Object();
    private static final Map<BypassKey, BypassCounter> COUNTERS = new LinkedHashMap<>();
    private static long windowBypassCount;
    private static long windowBypassBytes;
    private static long totalBypassCount;
    private static long totalBypassBytes;
    private static long nextPeriodicDumpAtMillis;

    private ChannelTransportBypassRankLogger() {}


    // Bypass record
    public static void recordPacket(
            ChannelHandlerContext context,
            String reason,
            String protocolName,
            Packet<?> packet,
            PacketFlow packetFlow,
            byte[] packetBytes
    ) {
        if (!isEnabled()) {
            return;
        }
        if (packet == null) {
            recordEncodedPacket(
                    context,
                    reason,
                    protocolName,
                    packetFlow,
                    "<unknown-packet>",
                    null,
                    tryReadLeadingVarInt(packetBytes),
                    lengthOf(packetBytes)
            );
            return;
        }

        recordEncodedPacket(
                context,
                reason,
                protocolName,
                packetFlow,
                packet.getClass().getName(),
                customPayloadChannel(packet),
                tryReadLeadingVarInt(packetBytes),
                lengthOf(packetBytes)
        );
    }

    public static void recordEncodedPacket(
            ChannelHandlerContext context,
            String reason,
            String protocolName,
            PacketFlow packetFlow,
            String packetClassName,
            ResourceLocation payloadChannel,
            int rawPacketId,
            int packetBytes
    ) {
        if (!isEnabled()) {
            return;
        }

        long nowMillis = System.currentTimeMillis();
        String channelId = channelIdText(context);
        BypassKey key = new BypassKey(
                textOrFallback(reason, "<unknown-reason>"),
                textOrFallback(protocolName, "<unknown-protocol>"),
                packetFlow == null ? "<unknown-flow>" : packetFlow.name(),
                textOrFallback(packetClassName, "<unknown-packet>"),
                payloadChannel == null ? "" : payloadChannel.toString(),
                rawPacketId
        );
        synchronized (LOCK) {
            if (nextPeriodicDumpAtMillis <= 0L) {
                nextPeriodicDumpAtMillis = nowMillis + readIntervalMillis();
            }

            BypassCounter counter = COUNTERS.computeIfAbsent(key, ignored -> new BypassCounter());
            counter.record(packetBytes, channelId);
            windowBypassCount++;
            windowBypassBytes += Math.max(packetBytes, 0);
            totalBypassCount++;
            totalBypassBytes += Math.max(packetBytes, 0);

            long intervalMillis = readIntervalMillis();
            if (intervalMillis > 0L && nowMillis >= nextPeriodicDumpAtMillis) {
                dumpLocked("periodic");
                nextPeriodicDumpAtMillis = nowMillis + intervalMillis;
            }
        }
    }


    public static void dumpNow(String reason) {
        if (!isEnabled()) {
            return;
        }

        synchronized (LOCK) {
            if (windowBypassCount <= 0L && windowBypassBytes <= 0L) {
                return;
            }
            dumpLocked(textOrFallback(reason, "manual"));
            nextPeriodicDumpAtMillis = System.currentTimeMillis() + readIntervalMillis();
        }
    }

    private static void dumpLocked(String reason) {
        List<Map.Entry<BypassKey, BypassCounter>> entries = new ArrayList<>(COUNTERS.entrySet());
        entries.sort(Comparator
                .<Map.Entry<BypassKey, BypassCounter>>comparingLong(entry -> entry.getValue().windowBytes())
                .reversed()
                .thenComparing(entry -> entry.getKey().packetClassName())
                .thenComparing(entry -> entry.getKey().reason()));

        int topN = Math.max(readTopN(), 1);
        int emitted = 0;
        Bandwidthoptimizer.LOGGER.info(
                "[Transport][BypassRank] reason={}, windowCount={}, windowBytes={}({}), totalCount={}, totalBytes={}({}), keys={}, topN={}",
                reason,
                windowBypassCount,
                windowBypassBytes,
                formatBytes(windowBypassBytes),
                totalBypassCount,
                totalBypassBytes,
                formatBytes(totalBypassBytes),
                entries.size(),
                topN
        );
        for (Map.Entry<BypassKey, BypassCounter> entry : entries) {
            if (emitted >= topN) {
                break;
            }
            BypassCounter counter = entry.getValue();
            if (counter.windowCount() <= 0L && counter.windowBytes() <= 0L) {
                continue;
            }
            emitted++;
            BypassKey key = entry.getKey();
            Bandwidthoptimizer.LOGGER.info(
                    "[Transport][BypassRank][Entry] rank={}, windowCount={}, windowBytes={}({}), totalCount={}, totalBytes={}({}), avgBytes={}, reason={}, protocol={}, flow={}, packetClass={}, payloadChannel={}, rawPacketId={}, lastChannel={}",
                    emitted,
                    counter.windowCount(),
                    counter.windowBytes(),
                    formatBytes(counter.windowBytes()),
                    counter.totalCount(),
                    counter.totalBytes(),
                    formatBytes(counter.totalBytes()),
                    counter.averageBytes(),
                    key.reason(),
                    key.protocolName(),
                    key.packetFlow(),
                    key.packetClassName(),
                    key.payloadChannel().isEmpty() ? "<none>" : key.payloadChannel(),
                    key.rawPacketId(),
                    counter.lastChannelId()
            );
        }

        for (BypassCounter counter : COUNTERS.values()) {
            counter.resetWindow();
        }
        windowBypassCount = 0L;
        windowBypassBytes = 0L;
    }

    private static ResourceLocation customPayloadChannel(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket clientboundCustomPayloadPacket) {
            return clientboundCustomPayloadPacket.getIdentifier();
        }
        if (packet instanceof ServerboundCustomPayloadPacket serverboundCustomPayloadPacket) {
            return serverboundCustomPayloadPacket.getIdentifier();
        }
        return null;
    }

    private static int tryReadLeadingVarInt(byte[] packetBytes) {
        if (packetBytes == null || packetBytes.length == 0) {
            return -1;
        }
        int value = 0;
        int position = 0;
        for (int index = 0; index < packetBytes.length && index < 5; index++) {
            int current = packetBytes[index] & 0xFF;
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return value;
            }
            position += 7;
        }
        return -1;
    }

    private static String channelIdText(ChannelHandlerContext context) {
        if (context == null || context.channel() == null) {
            return "<no-channel>";
        }
        try {
            return context.channel().id().asShortText();
        } catch (Throwable ignored) {
            return "<unknown-channel>";
        }
    }

    private static int lengthOf(byte[] packetBytes) {
        return packetBytes == null ? 0 : packetBytes.length;
    }

    private static String textOrFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean isEnabled() {
        if (!DebugRuntimeConfig.isAnalysisEnabled()) {
            return false;
        }
        return readBoolean(
                Config.RuntimeProperty.Transport.BYPASS_RANK_LOG_ENABLED,
                Config.RuntimeProperty.Transport.DEFAULT_BYPASS_RANK_LOG_ENABLED
        );
    }

    private static boolean readBoolean(String propertyName, boolean fallback) {
        String rawValue = System.getProperty(propertyName);
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(rawValue);
    }

    private static long readIntervalMillis() {
        String rawValue = System.getProperty(Config.RuntimeProperty.Transport.BYPASS_RANK_LOG_INTERVAL_MILLIS);
        if (rawValue == null || rawValue.isBlank()) {
            return Config.RuntimeProperty.Transport.DEFAULT_BYPASS_RANK_LOG_INTERVAL_MILLIS;
        }
        try {
            return Math.max(Long.parseLong(rawValue), 0L);
        } catch (NumberFormatException ignored) {
            return Config.RuntimeProperty.Transport.DEFAULT_BYPASS_RANK_LOG_INTERVAL_MILLIS;
        }
    }

    private static int readTopN() {
        String rawValue = System.getProperty(Config.RuntimeProperty.Transport.BYPASS_RANK_LOG_TOP_N);
        if (rawValue == null || rawValue.isBlank()) {
            return Config.RuntimeProperty.Transport.DEFAULT_BYPASS_RANK_LOG_TOP_N;
        }
        try {
            return Math.max(Integer.parseInt(rawValue), 1);
        } catch (NumberFormatException ignored) {
            return Config.RuntimeProperty.Transport.DEFAULT_BYPASS_RANK_LOG_TOP_N;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.2fMiB", bytes / 1024.0D / 1024.0D);
        }
        if (bytes >= 1024L) {
            return String.format(java.util.Locale.ROOT, "%.2fKiB", bytes / 1024.0D);
        }
        return bytes + "B";
    }

    private record BypassKey(
            String reason,
            String protocolName,
            String packetFlow,
            String packetClassName,
            String payloadChannel,
            int rawPacketId
    ) {
    }

    private static final class BypassCounter {

        private long windowCount;
        private long windowBytes;
        private long totalCount;
        private long totalBytes;
        private String lastChannelId = "<no-channel>";

        private void record(int packetBytes, String channelId) {
            int safePacketBytes = Math.max(packetBytes, 0);
            this.windowCount++;
            this.windowBytes += safePacketBytes;
            this.totalCount++;
            this.totalBytes += safePacketBytes;
            this.lastChannelId = channelId;
        }

        private void resetWindow() {
            this.windowCount = 0L;
            this.windowBytes = 0L;
        }

        private long averageBytes() {
            return this.totalCount <= 0L ? 0L : this.totalBytes / this.totalCount;
        }

        private long windowCount() {
            return this.windowCount;
        }

        private long windowBytes() {
            return this.windowBytes;
        }

        private long totalCount() {
            return this.totalCount;
        }

        private long totalBytes() {
            return this.totalBytes;
        }

        private String lastChannelId() {
            return this.lastChannelId;
        }
    }
}
