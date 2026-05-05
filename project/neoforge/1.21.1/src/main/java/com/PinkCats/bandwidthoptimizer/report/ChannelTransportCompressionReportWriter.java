package com.PinkCats.bandwidthoptimizer.report;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ChannelTransportCompressionReportWriter {

    private static final DateTimeFormatter REPORT_FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final DateTimeFormatter REPORT_DISPLAY_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private ChannelTransportCompressionReportWriter() {}

    public static GeneratedReport writeReport(
            int captureDurationTicks,
            List<ChannelTransportCapturedPacketSample> capturedPacketSamples
    ) throws IOException {
        CaptureSummary captureSummary = summarizeCapture(capturedPacketSamples);
        List<ChannelTransportCompressionReplaySimulator.ScenarioSimulationResult> scenarioResults =
                ChannelTransportReportScenarioCatalog.defaultScenarios().stream()
                        .map(scenarioPreset -> ChannelTransportCompressionReplaySimulator.simulate(capturedPacketSamples, scenarioPreset))
                        .toList();
        ChannelTransportCompressionReplaySimulator.ScenarioSimulationResult baselineScenario = findBaselineScenario(scenarioResults);
        ChannelTransportCompressionReplaySimulator.ScenarioSimulationResult bestScenario = findBestScenario(scenarioResults);

        Path reportDirectory = FMLPaths.GAMEDIR.get().resolve("run").resolve("transport-report");
        Files.createDirectories(reportDirectory);
        Path reportPath = reportDirectory.resolve("in-game-transport-report-" + REPORT_FILE_TIMESTAMP.format(LocalDateTime.now()) + ".txt");
        String reportText = buildReportText(captureDurationTicks, captureSummary, scenarioResults, baselineScenario, bestScenario);
        Files.writeString(reportPath, reportText, StandardCharsets.UTF_8);

        return new GeneratedReport(
                reportPath,
                reportText,
                captureSummary.totalPacketCount(),
                captureSummary.totalRawPacketBytes(),
                bestScenario == null ? "<none>" : bestScenario.scenarioPreset().displayName()
        );
    }

    private static CaptureSummary summarizeCapture(List<ChannelTransportCapturedPacketSample> capturedPacketSamples) {
        long outboundPacketCount = 0L;
        long inboundPacketCount = 0L;
        long outboundRawPacketBytes = 0L;
        long inboundRawPacketBytes = 0L;
        java.util.Set<String> channelIds = new java.util.HashSet<>();

        for (ChannelTransportCapturedPacketSample capturedPacketSample : capturedPacketSamples) {
            channelIds.add(capturedPacketSample.channelId());
            if (capturedPacketSample.isOutbound()) {
                outboundPacketCount++;
                outboundRawPacketBytes += capturedPacketSample.packetBytes().length;
            } else {
                inboundPacketCount++;
                inboundRawPacketBytes += capturedPacketSample.packetBytes().length;
            }
        }

        return new CaptureSummary(
                channelIds.size(),
                outboundPacketCount,
                inboundPacketCount,
                outboundRawPacketBytes,
                inboundRawPacketBytes
        );
    }

    private static String buildReportText(
            int captureDurationTicks,
            CaptureSummary captureSummary,
            List<ChannelTransportCompressionReplaySimulator.ScenarioSimulationResult> scenarioResults,
            ChannelTransportCompressionReplaySimulator.ScenarioSimulationResult baselineScenario,
            ChannelTransportCompressionReplaySimulator.ScenarioSimulationResult bestScenario
    ) {
        StringBuilder builder = new StringBuilder(4096);
        builder.append("BandwidthOptimizer In-Game Transport Compression Report").append('\n');
        builder.append("generatedAt=").append(REPORT_DISPLAY_TIMESTAMP.format(LocalDateTime.now())).append('\n');
        builder.append("gameDir=").append(FMLPaths.GAMEDIR.get().toAbsolutePath()).append('\n');
        builder.append("captureDurationTicks=").append(captureDurationTicks).append('\n');
        builder.append("captureDurationSeconds=").append(String.format(Locale.ROOT, "%.2f", captureDurationTicks / 20.0D)).append('\n');
        builder.append("capturedChannels=").append(captureSummary.channelCount()).append('\n');
        builder.append("capturedPackets=").append(captureSummary.totalPacketCount()).append('\n');
        builder.append("capturedRawPacketBytes=").append(formatBytes(captureSummary.totalRawPacketBytes())).append('\n');
        builder.append("bestScenario=").append(bestScenario == null ? "<none>" : bestScenario.scenarioPreset().displayName()).append('\n');
        builder.append("note=Only packet handled by BandwidthOptimizer is used").append('\n');
        builder.append('\n');

        builder.append("== Sampling Flow Rate  ==").append('\n');
        builder.append("outboundPackets=").append(captureSummary.outboundPacketCount())
                .append(", outboundRawBytes=").append(formatBytes(captureSummary.outboundRawPacketBytes()))
                .append('\n');
        builder.append("inboundPackets=").append(captureSummary.inboundPacketCount())
                .append(", inboundRawBytes=").append(formatBytes(captureSummary.inboundRawPacketBytes()))
                .append('\n');
        builder.append("totalPackets=").append(captureSummary.totalPacketCount())
                .append(", totalRawBytes=").append(formatBytes(captureSummary.totalRawPacketBytes()))
                .append('\n');
        builder.append('\n');

        builder.append("== Simulate Summary ==").append('\n');
        for (ChannelTransportCompressionReplaySimulator.ScenarioSimulationResult scenarioResult : scenarioResults) {
            builder.append("- ").append(scenarioResult.scenarioPreset().displayName())
                    .append(": raw=").append(formatBytes(scenarioResult.totalRawPacketBytes()))
                    .append(", frame=").append(formatBytes(scenarioResult.totalTransportFrameBytes()))
                    .append(", ratio=").append(ratioText(scenarioResult.totalTransportFrameBytes(), scenarioResult.totalRawPacketBytes()))
                    .append(", savedVsRaw=").append(signedBytes(scenarioResult.savedVsRawBytes()))
                    .append(", frames=").append(scenarioResult.totalTransportFrameCount());
            if (baselineScenario == null || baselineScenario == scenarioResult) {
                builder.append(", vsBaseline=baseline");
            } else {
                long deltaBytes = scenarioResult.totalTransportFrameBytes() - baselineScenario.totalTransportFrameBytes();
                builder.append(", vsBaselineFrameBytes=").append(signedBytes(deltaBytes))
                        .append(" (").append(percentText(deltaBytes, baselineScenario.totalTransportFrameBytes())).append(')');
            }
            builder.append('\n');
        }
        builder.append('\n');

        builder.append("== Scene Detail ==").append('\n');
        for (ChannelTransportCompressionReplaySimulator.ScenarioSimulationResult scenarioResult : scenarioResults) {
            builder.append('[').append(scenarioResult.scenarioPreset().displayName()).append(']').append('\n');
            builder.append("channels=").append(scenarioResult.channelCount())
                    .append(", mapping=").append(scenarioResult.scenarioPreset().mappingEnabled())
                    .append(", batch=").append(scenarioResult.scenarioPreset().batchEnabled())
                    .append(", packetIdMapping=").append(scenarioResult.scenarioPreset().packetIdMappingEnabled())
                    .append('\n');
            builder.append("outbound=").append(formatDirectionMetrics(scenarioResult.outboundMetrics())).append('\n');
            builder.append("inbound=").append(formatDirectionMetrics(scenarioResult.inboundMetrics())).append('\n');
            builder.append("totalPackets=").append(scenarioResult.totalPacketCount())
                    .append(", totalFrames=").append(scenarioResult.totalTransportFrameCount())
                    .append(", totalRaw=").append(formatBytes(scenarioResult.totalRawPacketBytes()))
                    .append(", totalFrame=").append(formatBytes(scenarioResult.totalTransportFrameBytes()))
                    .append(", totalRatio=").append(ratioText(scenarioResult.totalTransportFrameBytes(), scenarioResult.totalRawPacketBytes()))
                    .append(", savedVsRaw=").append(signedBytes(scenarioResult.savedVsRawBytes()))
                    .append('\n');
            builder.append('\n');
        }

        return builder.toString();
    }

    private static ChannelTransportCompressionReplaySimulator.ScenarioSimulationResult findBaselineScenario(
            List<ChannelTransportCompressionReplaySimulator.ScenarioSimulationResult> scenarioResults
    ) {
        return scenarioResults.stream()
                .filter(result -> result.scenarioPreset().baseline())
                .findFirst()
                .orElse(scenarioResults.isEmpty() ? null : scenarioResults.get(0));
    }

    private static ChannelTransportCompressionReplaySimulator.ScenarioSimulationResult findBestScenario(
            List<ChannelTransportCompressionReplaySimulator.ScenarioSimulationResult> scenarioResults
    ) {
        return scenarioResults.stream()
                .min(Comparator.comparingLong(ChannelTransportCompressionReplaySimulator.ScenarioSimulationResult::totalTransportFrameBytes))
                .orElse(null);
    }

    private static String formatDirectionMetrics(ChannelTransportCompressionReplaySimulator.DirectionMetrics directionMetrics) {
        return "packets=" + directionMetrics.packetCount()
                + ", frames=" + directionMetrics.transportFrameCount()
                + ", raw=" + formatBytes(directionMetrics.rawPacketBytes())
                + ", frame=" + formatBytes(directionMetrics.transportFrameBytes())
                + ", ratio=" + ratioText(directionMetrics.transportFrameBytes(), directionMetrics.rawPacketBytes());
    }

    private static String formatBytes(long bytes) {
        return bytes + " (" + humanReadableBytes(bytes) + ")";
    }


    private static String humanReadableBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kib = bytes / 1024.0D;
        if (kib < 1024.0D) {
            return String.format(Locale.ROOT, "%.2f KiB", kib);
        }
        double mib = kib / 1024.0D;
        if (mib < 1024.0D) {
            return String.format(Locale.ROOT, "%.2f MiB", mib);
        }
        return String.format(Locale.ROOT, "%.2f GiB", mib / 1024.0D);
    }

    private static String ratioText(long numerator, long denominator) {
        if (denominator <= 0L) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.3fx", numerator / (double) denominator);
    }

    private static String percentText(long deltaBytes, long baselineBytes) {
        if (baselineBytes <= 0L) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%+.3f%%", deltaBytes * 100.0D / baselineBytes);
    }

    private static String signedBytes(long value) {
        return (value > 0L ? "+" : "") + value;
    }

    private record CaptureSummary(
            int channelCount,
            long outboundPacketCount,
            long inboundPacketCount,
            long outboundRawPacketBytes,
            long inboundRawPacketBytes
    ) {

        private long totalPacketCount() {
            return this.outboundPacketCount + this.inboundPacketCount;
        }

        private long totalRawPacketBytes() {
            return this.outboundRawPacketBytes + this.inboundRawPacketBytes;
        }
    }

    public record GeneratedReport(
            Path reportPath,
            String reportText,
            long capturedPacketCount,
            long capturedRawPacketBytes,
            String bestScenarioName
    ) {
    }
}
