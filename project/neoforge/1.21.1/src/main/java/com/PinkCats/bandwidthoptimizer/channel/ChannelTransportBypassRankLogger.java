package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.debug.DebugRuntimeConfig;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ChannelTransportBypassRankLogger {

    private static final Object LOCK = new Object();
    private static final Map<BypassKey, BypassCounter> COUNTERS = new LinkedHashMap<>();
    private static long windowBypassCount;
    private static long windowBypassBytes;
    private static long totalBypassCount;
    private static long totalBypassBytes;
    private static long nextPeriodicDumpAtMillis;
    private static long nextReportFailureLogAtMillis;
    private static final DateTimeFormatter REPORT_DISPLAY_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final String LATEST_REPORT_FILE_NAME = "latest-bypass-report.txt";

    private ChannelTransportBypassRankLogger() {}


    // 记录完整包对象的 bypass 流量，入口保持轻量，只做聚合计数。
    public static void recordPacket(
            ChannelHandlerContext context,
            String reason,
            String protocolName,
            Packet<?> packet,
            PacketFlow packetFlow,
            byte[] packetBytes
    ) {
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

    // 记录已经编码后的 bypass 流量，避免依赖诊断开关导致运行期看不到旁路排行。
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


    // 手动刷新当前窗口的 bypass 报告，用于停服或调试命令收口。
    public static void dumpNow(String reason) {
        synchronized (LOCK) {
            if (windowBypassCount <= 0L && windowBypassBytes <= 0L) {
                return;
            }
            dumpLocked(textOrFallback(reason, "manual"));
            nextPeriodicDumpAtMillis = System.currentTimeMillis() + readIntervalMillis();
        }
    }

    // 输出当前窗口的 bypass 排行，并在写出后清空窗口计数但保留总计。
    private static void dumpLocked(String reason) {
        List<Map.Entry<BypassKey, BypassCounter>> entries = new ArrayList<>(COUNTERS.entrySet());
        entries.sort(Comparator
                .<Map.Entry<BypassKey, BypassCounter>>comparingLong(entry -> entry.getValue().windowBytes())
                .reversed()
                .thenComparing(entry -> entry.getKey().packetClassName())
                .thenComparing(entry -> entry.getKey().reason()));

        int topN = Math.max(readTopN(), 1);
        if (isLogEnabled()) {
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
            logEntries(entries, topN);
        }
        if (isReportEnabled()) {
            writeReportLocked(reason, entries, topN);
        }

        for (BypassCounter counter : COUNTERS.values()) {
            counter.resetWindow();
        }
        windowBypassCount = 0L;
        windowBypassBytes = 0L;
    }

    // 把窗口排行写入日志，日志开关只影响这里，不影响内存统计和报告文件。
    private static void logEntries(List<Map.Entry<BypassKey, BypassCounter>> entries, int topN) {
        int emitted = 0;
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
    }

    // 解析 custom payload 的原始通道，方便报告直接定位被旁路的模组通道。
    private static ResourceLocation customPayloadChannel(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket clientboundCustomPayloadPacket) {
            return clientboundCustomPayloadPacket.payload().type().id();
        }
        if (packet instanceof ServerboundCustomPayloadPacket serverboundCustomPayloadPacket) {
            return serverboundCustomPayloadPacket.payload().type().id();
        }
        return null;
    }

    // 尝试从编码后的包头读取原始 packet id，失败时使用 -1 表示未知。
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

    // 获取 Netty channel 的短 id，避免报告里只能看到包类型而无法关联连接。
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

    // 统一处理空文本，保证统计 key 不会因为 null 破坏聚合。
    private static String textOrFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // 日志仍然受旧诊断开关控制，避免默认情况下刷屏。
    private static boolean isLogEnabled() {
        if (!DebugRuntimeConfig.isAnalysisEnabled()) {
            return false;
        }
        return readBoolean(
                Config.RuntimeProperty.Transport.BYPASS_RANK_LOG_ENABLED,
                Config.RuntimeProperty.Transport.DEFAULT_BYPASS_RANK_LOG_ENABLED
        );
    }

    // 报告默认自动开启，只写周期聚合结果，不做逐包落盘。
    private static boolean isReportEnabled() {
        return readBoolean(
                Config.RuntimeProperty.Transport.BYPASS_RANK_REPORT_ENABLED,
                Config.RuntimeProperty.Transport.DEFAULT_BYPASS_RANK_REPORT_ENABLED
        );
    }

    // 写出 latest 报告文件，使用覆盖式快照避免长期运行堆积文件。
    private static void writeReportLocked(String reason, List<Map.Entry<BypassKey, BypassCounter>> entries, int topN) {
        Path reportDirectory = resolveReportDirectory();
        LocalDateTime now = LocalDateTime.now();
        String reportTimestamp = REPORT_DISPLAY_TIMESTAMP.format(now);
        Path latestReport = reportDirectory.resolve(LATEST_REPORT_FILE_NAME);
        StringBuilder builder = new StringBuilder(4096);
        builder.append("BandwidthOptimizer Transport Bypass Report").append(System.lineSeparator());
        builder.append("generatedAt=").append(reportTimestamp).append(System.lineSeparator());
        builder.append("reason=").append(reason).append(System.lineSeparator());
        builder.append("windowCount=").append(windowBypassCount).append(System.lineSeparator());
        builder.append("windowBytes=").append(windowBypassBytes).append(" (").append(formatBytes(windowBypassBytes)).append(")").append(System.lineSeparator());
        builder.append("totalCount=").append(totalBypassCount).append(System.lineSeparator());
        builder.append("totalBytes=").append(totalBypassBytes).append(" (").append(formatBytes(totalBypassBytes)).append(")").append(System.lineSeparator());
        builder.append("keys=").append(entries.size()).append(System.lineSeparator());
        builder.append("topN=").append(topN).append(System.lineSeparator());
        builder.append("note=Only aggregated counters are stored; payload bytes are not retained.").append(System.lineSeparator());
        builder.append(System.lineSeparator());
        builder.append("rank\twindowCount\twindowBytes\twindowBytesHuman\ttotalCount\ttotalBytes\ttotalBytesHuman\tavgBytes\treason\tprotocol\tflow\tpacketClass\tpayloadChannel\trawPacketId\tlastChannel").append(System.lineSeparator());

        int emitted = 0;
        for (Map.Entry<BypassKey, BypassCounter> entry : entries) {
            if (emitted >= topN) {
                break;
            }
            BypassCounter counter = entry.getValue();
            if (counter.windowCount() <= 0L && counter.windowBytes() <= 0L) {
                continue;
            }
            emitted++;
            appendReportEntry(builder, emitted, entry.getKey(), counter);
        }

        try {
            Files.createDirectories(reportDirectory);
            String report = builder.toString();
            Files.writeString(latestReport, report, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            logReportFailureThrottled(exception);
        }
    }

    // 追加一行 TSV 统计，字段内制表符会被替换，避免破坏列结构。
    private static void appendReportEntry(StringBuilder builder, int rank, BypassKey key, BypassCounter counter) {
        builder.append(rank).append('\t')
                .append(counter.windowCount()).append('\t')
                .append(counter.windowBytes()).append('\t')
                .append(formatBytes(counter.windowBytes())).append('\t')
                .append(counter.totalCount()).append('\t')
                .append(counter.totalBytes()).append('\t')
                .append(formatBytes(counter.totalBytes())).append('\t')
                .append(counter.averageBytes()).append('\t')
                .append(sanitizeReportValue(key.reason())).append('\t')
                .append(sanitizeReportValue(key.protocolName())).append('\t')
                .append(sanitizeReportValue(key.packetFlow())).append('\t')
                .append(sanitizeReportValue(key.packetClassName())).append('\t')
                .append(key.payloadChannel().isEmpty() ? "<none>" : sanitizeReportValue(key.payloadChannel())).append('\t')
                .append(key.rawPacketId()).append('\t')
                .append(sanitizeReportValue(counter.lastChannelId()))
                .append(System.lineSeparator());
    }

    // 报告目录使用游戏目录下的相对路径，避免 common 层依赖具体加载器 API。
    private static Path resolveReportDirectory() {
        String directory = readString(
                Config.RuntimeProperty.Transport.BYPASS_RANK_REPORT_DIRECTORY,
                Config.RuntimeProperty.Transport.DEFAULT_BYPASS_RANK_REPORT_DIRECTORY
        );
        return Path.of(directory);
    }

    // 限频记录报告写入失败，避免磁盘不可写时每个周期刷屏。
    private static void logReportFailureThrottled(IOException exception) {
        long nowMillis = System.currentTimeMillis();
        if (nowMillis < nextReportFailureLogAtMillis) {
            return;
        }
        nextReportFailureLogAtMillis = nowMillis + 60_000L;
        Bandwidthoptimizer.LOGGER.warn("[Transport][BypassRank] Failed to write bypass report", exception);
    }

    // 清理报告字段中的换行和制表符，保证文件可以直接按 TSV 查看。
    private static String sanitizeReportValue(String value) {
        return textOrFallback(value, "").replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static boolean readBoolean(String propertyName, boolean fallback) {
        String rawValue = System.getProperty(propertyName);
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(rawValue);
    }

    // 读取运行期字符串属性，属性缺失时使用默认值。
    private static String readString(String propertyName, String fallback) {
        String rawValue = System.getProperty(propertyName);
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        return rawValue;
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
