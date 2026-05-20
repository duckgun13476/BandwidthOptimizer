package com.PinkCats.bandwidthoptimizer.server.stat;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

public final class ServerBandwidthEvaluationProbe {

    private static final String ENABLED_PROPERTY = "bandwidthoptimizer.bandwidthEvaluation.enabled";
    private static final String FILE_NAME_PROPERTY = "bandwidthoptimizer.bandwidthEvaluation.fileName";
    private static final String LOG_CONSOLE_PROPERTY = "bandwidthoptimizer.bandwidthEvaluation.logConsole";
    private static final String DEFAULT_FILE_NAME = "bandwidth-evaluation/server-wire-rate.tsv";
    private static final long SAMPLE_INTERVAL_MILLIS = 1_000L;
    private static final Object LOCK = new Object();

    private static long lastSampleAtMillis;
    private static long lastOutboundWireBytes;
    private static long lastInboundWireBytes;
    private static long lastOutboundRawBytes;
    private static long lastInboundRawBytes;
    private static long lastOutboundVanillaBaselineBytes;

    private ServerBandwidthEvaluationProbe() {}

    public static void onServerTick() {
        if (!isEnabled()) {
            return;
        }
        long nowMillis = System.currentTimeMillis();
        synchronized (LOCK) {
            if (lastSampleAtMillis > 0L && nowMillis - lastSampleAtMillis < SAMPLE_INTERVAL_MILLIS) {
                return;
            }

            ServerBandwidthStatsRegistry.TotalsSnapshot totals = ServerBandwidthStatsRegistry.snapshotSessionTotals();
            long outboundWireBytes = Math.max(totals.outboundWireBytes(), 0L);
            long inboundWireBytes = Math.max(totals.inboundWireBytes(), 0L);
            long outboundRawBytes = Math.max(totals.outboundRawEncodedBytes(), 0L);
            long inboundRawBytes = Math.max(totals.inboundRawEncodedBytes(), 0L);
            long outboundVanillaBaselineBytes = Math.max(totals.outboundVanillaCompressedEstimateBytes(), 0L);
            if (lastSampleAtMillis <= 0L
                    || outboundWireBytes < lastOutboundWireBytes
                    || inboundWireBytes < lastInboundWireBytes
                    || outboundRawBytes < lastOutboundRawBytes
                    || inboundRawBytes < lastInboundRawBytes
                    || outboundVanillaBaselineBytes < lastOutboundVanillaBaselineBytes) {
                lastSampleAtMillis = nowMillis;
                lastOutboundWireBytes = outboundWireBytes;
                lastInboundWireBytes = inboundWireBytes;
                lastOutboundRawBytes = outboundRawBytes;
                lastInboundRawBytes = inboundRawBytes;
                lastOutboundVanillaBaselineBytes = outboundVanillaBaselineBytes;
                ensureHeader();
                return;
            }

            long elapsedMillis = Math.max(nowMillis - lastSampleAtMillis, 1L);
            long outboundBytesPerSecond = Math.round((double) (outboundWireBytes - lastOutboundWireBytes) * 1000.0D / elapsedMillis);
            long inboundBytesPerSecond = Math.round((double) (inboundWireBytes - lastInboundWireBytes) * 1000.0D / elapsedMillis);
            long outboundRawBytesPerSecond = Math.round((double) (outboundRawBytes - lastOutboundRawBytes) * 1000.0D / elapsedMillis);
            long inboundRawBytesPerSecond = Math.round((double) (inboundRawBytes - lastInboundRawBytes) * 1000.0D / elapsedMillis);
            long outboundVanillaBaselineBytesPerSecond = Math.round((double) (outboundVanillaBaselineBytes - lastOutboundVanillaBaselineBytes) * 1000.0D / elapsedMillis);
            appendSample(nowMillis, outboundBytesPerSecond, inboundBytesPerSecond, outboundRawBytesPerSecond, inboundRawBytesPerSecond, outboundVanillaBaselineBytesPerSecond, totals);
            if (isConsoleLogEnabled()) {
                Bandwidthoptimizer.LOGGER.info(
                        "[BandwidthEval][ServerWire] out={}/s, in={}/s, vanillaOut={}/s, rawOut={}/s, rawIn={}/s, outTotal={}, inTotal={}, channels={}, players={}",
                        formatKiB(outboundBytesPerSecond),
                        formatKiB(inboundBytesPerSecond),
                        formatKiB(outboundVanillaBaselineBytesPerSecond),
                        formatKiB(outboundRawBytesPerSecond),
                        formatKiB(inboundRawBytesPerSecond),
                        formatKiB(outboundWireBytes),
                        formatKiB(inboundWireBytes),
                        totals.activeChannels(),
                        totals.boundPlayers()
                );
            }
            lastSampleAtMillis = nowMillis;
            lastOutboundWireBytes = outboundWireBytes;
            lastInboundWireBytes = inboundWireBytes;
            lastOutboundRawBytes = outboundRawBytes;
            lastInboundRawBytes = inboundRawBytes;
            lastOutboundVanillaBaselineBytes = outboundVanillaBaselineBytes;
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            lastSampleAtMillis = 0L;
            lastOutboundWireBytes = 0L;
            lastInboundWireBytes = 0L;
            lastOutboundRawBytes = 0L;
            lastInboundRawBytes = 0L;
            lastOutboundVanillaBaselineBytes = 0L;
        }
    }

    private static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"));
    }

    private static boolean isConsoleLogEnabled() {
        return Boolean.parseBoolean(System.getProperty(LOG_CONSOLE_PROPERTY, "true"));
    }

    // 初始化输出文件表头，方便和转发代理的 TSV 按时间对齐。
    private static void ensureHeader() {
        Path outputPath = outputPath();
        try {
            Path parentPath = outputPath.getParent();
            if (parentPath != null) {
                Files.createDirectories(parentPath);
            }
            if (!Files.exists(outputPath) || Files.size(outputPath) <= 0L) {
                Files.writeString(
                        outputPath,
                        "timeMillis\toutBytesPerSec\tinBytesPerSec\toutRawBytesPerSec\tinRawBytesPerSec\toutVanillaBaselineBytesPerSec\toutKiBPerSec\tinKiBPerSec\toutRawKiBPerSec\tinRawKiBPerSec\toutVanillaBaselineKiBPerSec\toutWireTotal\tinWireTotal\tactiveChannels\tboundPlayers\toutRawTotal\tinRawTotal\toutVanillaBaselineTotal\toutTransportTotal\toutBypassTotal\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            }
        } catch (IOException exception) {
            Bandwidthoptimizer.LOGGER.warn("[BandwidthEval] Failed to initialize server wire rate file {}", outputPath, exception);
        }
    }

    // 追加单秒采样，记录线速、累计线速和当前活跃连接数量。
    private static void appendSample(
            long nowMillis,
            long outboundBytesPerSecond,
            long inboundBytesPerSecond,
            long outboundRawBytesPerSecond,
            long inboundRawBytesPerSecond,
            long outboundVanillaBaselineBytesPerSecond,
            ServerBandwidthStatsRegistry.TotalsSnapshot totals
    ) {
        Path outputPath = outputPath();
        ensureHeader();
        String line = nowMillis
                + "\t" + outboundBytesPerSecond
                + "\t" + inboundBytesPerSecond
                + "\t" + outboundRawBytesPerSecond
                + "\t" + inboundRawBytesPerSecond
                + "\t" + outboundVanillaBaselineBytesPerSecond
                + "\t" + formatDecimalKiB(outboundBytesPerSecond)
                + "\t" + formatDecimalKiB(inboundBytesPerSecond)
                + "\t" + formatDecimalKiB(outboundRawBytesPerSecond)
                + "\t" + formatDecimalKiB(inboundRawBytesPerSecond)
                + "\t" + formatDecimalKiB(outboundVanillaBaselineBytesPerSecond)
                + "\t" + totals.outboundWireBytes()
                + "\t" + totals.inboundWireBytes()
                + "\t" + totals.activeChannels()
                + "\t" + totals.boundPlayers()
                + "\t" + totals.outboundRawEncodedBytes()
                + "\t" + totals.inboundRawEncodedBytes()
                + "\t" + totals.outboundVanillaCompressedEstimateBytes()
                + "\t" + totals.outboundTransportFrameBytes()
                + "\t" + totals.outboundBypassBytes()
                + "\n";
        try {
            Files.writeString(
                    outputPath,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            Bandwidthoptimizer.LOGGER.warn("[BandwidthEval] Failed to append server wire rate file {}", outputPath, exception);
        }
    }

    // 解析评估输出路径，默认写入 bandwidthoptimizer-native 下的评估目录。
    private static Path outputPath() {
        String configuredName = System.getProperty(FILE_NAME_PROPERTY, DEFAULT_FILE_NAME);
        if (configuredName == null || configuredName.isBlank()) {
            configuredName = DEFAULT_FILE_NAME;
        }
        return BandwidthOptimizerOutputPaths.resolve(configuredName);
    }

    private static String formatKiB(long bytes) {
        return formatDecimalKiB(bytes) + " KiB";
    }

    private static String formatDecimalKiB(long bytes) {
        return String.format(Locale.ROOT, "%.2f", (double) bytes / 1024.0D);
    }
}
