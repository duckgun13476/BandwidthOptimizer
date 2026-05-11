package com.PinkCats.bandwidthoptimizer.report;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportLayerRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.batch.ChannelTransportBatchRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;
import net.minecraftforge.fml.loading.FMLPaths;

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

public final class ChannelTransportPacketRankReportWriter {

    private static final DateTimeFormatter REPORT_FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final DateTimeFormatter REPORT_DISPLAY_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private ChannelTransportPacketRankReportWriter() {}

    public static GeneratedReport writeReport(
            int captureDurationTicks,
            List<ChannelTransportPacketRankObservation> observations
    ) throws IOException {
        List<EnrichedObservation> enrichedObservations = enrichStandaloneReplay(observations);
        List<PacketClassAggregate> aggregates = aggregateByPacketClass(enrichedObservations);
        List<SourceAggregate> sourceAggregates = aggregateBySource(enrichedObservations);
        Summary summary = summarize(enrichedObservations, aggregates);

        Path reportDirectory = BandwidthOptimizerOutputPaths.resolve("transport-packet-rank");
        Files.createDirectories(reportDirectory);
        Path timestampedReportPath = reportDirectory.resolve(
                "packet-rank-" + REPORT_FILE_TIMESTAMP.format(LocalDateTime.now()) + ".txt"
        );
        Path latestReportPath = reportDirectory.resolve("latest-packet-rank.txt");
        String reportText = buildReportText(captureDurationTicks, summary, aggregates, sourceAggregates, enrichedObservations);
        Files.writeString(timestampedReportPath, reportText, StandardCharsets.UTF_8);
        Files.writeString(latestReportPath, reportText, StandardCharsets.UTF_8);

        return new GeneratedReport(
                timestampedReportPath,
                latestReportPath,
                reportText,
                summary.totalPacketCount(),
                summary.negativeActualClassCount()
        );
    }


    private static List<EnrichedObservation> enrichStandaloneReplay(
            List<ChannelTransportPacketRankObservation> observations
    ) {
        List<ChannelTransportPacketRankObservation> orderedObservations = new ArrayList<>(observations);
        orderedObservations.sort(Comparator
                .comparingLong(ChannelTransportPacketRankObservation::capturedAtMillis)
                .thenComparingLong(ChannelTransportPacketRankObservation::captureIndex));

        Map<String, ChannelTransportSession> sessionsByChannelId = new LinkedHashMap<>();
        List<EnrichedObservation> enrichedObservations = new ArrayList<>(orderedObservations.size());
        for (ChannelTransportPacketRankObservation observation : orderedObservations) {
            ChannelTransportSession transportSession = sessionsByChannelId.computeIfAbsent(
                    observation.channelId(),
                    ignored -> new ChannelTransportSession()
            );
            ChannelTransportPacketCodec.WrappedTransportFrame wrappedFrame =
                    ChannelTransportPacketCodec.wrapPacket(transportSession, observation.copyTransportInputPacketBytes());
            int standaloneFrameBytes = wrappedFrame == null
                    ? observation.transportInputBytes()
                    : wrappedFrame.transportFrameLength();
            enrichedObservations.add(new EnrichedObservation(observation, standaloneFrameBytes));
        }
        return List.copyOf(enrichedObservations);
    }


    private static List<PacketClassAggregate> aggregateByPacketClass(List<EnrichedObservation> enrichedObservations) {
        Map<String, PacketClassAggregateBuilder> builders = new LinkedHashMap<>();
        for (EnrichedObservation enrichedObservation : enrichedObservations) {
            builders.computeIfAbsent(
                    enrichedObservation.observation().packetClassName(),
                    ignored -> new PacketClassAggregateBuilder(enrichedObservation.observation().packetClassName())
            ).record(enrichedObservation);
        }

        List<PacketClassAggregate> aggregates = new ArrayList<>(builders.size());
        for (PacketClassAggregateBuilder builder : builders.values()) {
            aggregates.add(builder.build());
        }
        aggregates.sort(Comparator
                .comparingLong(PacketClassAggregate::actualSavedVsRawBytes)
                .thenComparing(PacketClassAggregate::packetClassName));
        return List.copyOf(aggregates);
    }

    private static List<SourceAggregate> aggregateBySource(List<EnrichedObservation> enrichedObservations) {
        Map<String, SourceAggregateBuilder> builders = new LinkedHashMap<>();
        for (EnrichedObservation enrichedObservation : enrichedObservations) {
            builders.computeIfAbsent(
                    enrichedObservation.observation().sourceKey(),
                    SourceAggregateBuilder::new
            ).record(enrichedObservation);
        }

        List<SourceAggregate> aggregates = new ArrayList<>(builders.size());
        for (SourceAggregateBuilder builder : builders.values()) {
            aggregates.add(builder.build());
        }
        return List.copyOf(aggregates);
    }

    private static Summary summarize(
            List<EnrichedObservation> enrichedObservations,
            List<PacketClassAggregate> aggregates
    ) {
        long totalRawPacketBytes = 0L;
        long totalTransportInputBytes = 0L;
        long totalActualFrameBytes = 0L;
        long totalStandaloneFrameBytes = 0L;
        long chunkWrappedPacketCount = 0L;
        long batchedPacketCount = 0L;
        long estimatedActualPacketCount = 0L;
        int negativeActualClassCount = 0;

        for (EnrichedObservation enrichedObservation : enrichedObservations) {
            ChannelTransportPacketRankObservation observation = enrichedObservation.observation();
            totalRawPacketBytes += observation.rawPacketBytes();
            totalTransportInputBytes += observation.transportInputBytes();
            totalActualFrameBytes += observation.actualFrameBytes();
            totalStandaloneFrameBytes += enrichedObservation.standaloneFrameBytes();
            if (observation.chunkProtocolApplied()) {
                chunkWrappedPacketCount++;
            }
            if (observation.batchPacketCount() > 1) {
                batchedPacketCount++;
            }
            if (observation.actualFrameBytesEstimated()) {
                estimatedActualPacketCount++;
            }
        }

        for (PacketClassAggregate aggregate : aggregates) {
            if (aggregate.actualSavedVsRawBytes() < 0L) {
                negativeActualClassCount++;
            }
        }

        return new Summary(
                enrichedObservations.size(),
                totalRawPacketBytes,
                totalTransportInputBytes,
                totalActualFrameBytes,
                totalStandaloneFrameBytes,
                chunkWrappedPacketCount,
                batchedPacketCount,
                estimatedActualPacketCount,
                negativeActualClassCount
        );
    }


    private static String buildReportText(
            int captureDurationTicks,
            Summary summary,
            List<PacketClassAggregate> aggregates,
            List<SourceAggregate> sourceAggregates,
            List<EnrichedObservation> enrichedObservations
    ) {
        StringBuilder builder = new StringBuilder(8192);
        builder.append("BandwidthOptimizer Transport Packet Rank Report").append('\n');
        builder.append("generatedAt=").append(REPORT_DISPLAY_TIMESTAMP.format(LocalDateTime.now())).append('\n');
        builder.append("gameDir=").append(FMLPaths.GAMEDIR.get().toAbsolutePath()).append('\n');
        builder.append("captureDurationTicks=").append(captureDurationTicks).append('\n');
        builder.append("captureDurationSeconds=").append(String.format(Locale.ROOT, "%.2f", captureDurationTicks / 20.0D)).append('\n');
        builder.append("algorithmId=").append(ChannelTransportLayerRuntimeConfig.algorithmId()).append('\n');
        builder.append("mappingEnabled=").append(ChannelTransportLayerRuntimeConfig.isMappingEnabled()).append('\n');
        builder.append("zstdEnabled=").append(ChannelTransportLayerRuntimeConfig.isZstdEnabled()).append('\n');
        builder.append("batchEnabled=").append(ChannelTransportBatchRuntimeConfig.isBatchEnabled()).append('\n');
        builder.append("packetIdMappingEnabled=").append(ChannelTransportLayerRuntimeConfig.isPacketIdMappingEnabled()).append('\n');
        builder.append("note.actualFrameBytes=single frame uses exact bytes, multi-packet batch uses proportional share estimate").append('\n');
        builder.append("note.standaloneFrameBytes=sequential no-batch replay on captured transport-input bytes").append('\n');
        builder.append('\n');

        builder.append("== Capture Summary ==").append('\n');
        builder.append("packets=").append(summary.totalPacketCount())
                .append(", raw=").append(formatBytes(summary.totalRawPacketBytes()))
                .append(", chunkInput=").append(formatBytes(summary.totalTransportInputBytes()))
                .append(", actualFrame=").append(formatBytes(summary.totalActualFrameBytes()))
                .append(", actualRatio=").append(ratioText(summary.totalActualFrameBytes(), summary.totalRawPacketBytes()))
                .append(", actualSavedVsRaw=").append(signed(summary.totalRawPacketBytes() - summary.totalActualFrameBytes()))
                .append('\n');
        builder.append("standaloneFrame=").append(formatBytes(summary.totalStandaloneFrameBytes()))
                .append(", standaloneRatio=").append(ratioText(summary.totalStandaloneFrameBytes(), summary.totalRawPacketBytes()))
                .append(", standaloneSavedVsRaw=").append(signed(summary.totalRawPacketBytes() - summary.totalStandaloneFrameBytes()))
                .append('\n');
        builder.append("chunkWrappedPackets=").append(summary.chunkWrappedPacketCount())
                .append(", batchedPackets=").append(summary.batchedPacketCount())
                .append(", estimatedActualPackets=").append(summary.estimatedActualPacketCount())
                .append(", negativeActualClasses=").append(summary.negativeActualClassCount())
                .append('\n');
        builder.append('\n');

        appendAggregateSection(
                builder,
                "== Negative Actual Packet Classes ==",
                aggregates.stream()
                        .filter(aggregate -> aggregate.actualSavedVsRawBytes() < 0L)
                        .toList()
        );
        appendAggregateSection(
                builder,
                "== Negative Standalone Packet Classes ==",
                aggregates.stream()
                        .filter(aggregate -> aggregate.standaloneSavedVsRawBytes() < 0L)
                        .toList()
        );
        appendAggregateSection(
                builder,
                "== Full Ranking By Actual SavedVsRaw ==",
                aggregates
        );
        appendSourceAggregateSection(
                builder,
                "== Full Ranking By Source Raw Bytes ==",
                sourceAggregates.stream()
                        .sorted(Comparator
                                .comparingLong(SourceAggregate::rawPacketBytes)
                                .reversed()
                                .thenComparing(SourceAggregate::sourceKey))
                        .limit(80)
                        .toList()
        );
        appendSourceAggregateSection(
                builder,
                "== Full Ranking By Source Actual Bytes ==",
                sourceAggregates.stream()
                        .sorted(Comparator
                                .comparingLong(SourceAggregate::actualFrameBytes)
                                .reversed()
                                .thenComparing(SourceAggregate::sourceKey))
                        .limit(80)
                        .toList()
        );
        appendWorstSampleSection(builder, enrichedObservations);
        return builder.toString();
    }

    private static void appendAggregateSection(
            StringBuilder builder,
            String title,
            List<PacketClassAggregate> aggregates
    ) {
        builder.append(title).append('\n');
        if (aggregates.isEmpty()) {
            builder.append("none").append('\n').append('\n');
            return;
        }

        for (PacketClassAggregate aggregate : aggregates) {
            builder.append("- ").append(simpleClassName(aggregate.packetClassName()))
                    .append(": count=").append(aggregate.packetCount())
                    .append(", raw=").append(formatBytes(aggregate.rawPacketBytes()))
                    .append(", chunkInput=").append(formatBytes(aggregate.transportInputBytes()))
                    .append(", chunkSavedVsRaw=").append(signed(aggregate.rawPacketBytes() - aggregate.transportInputBytes()))
                    .append(", actualFrame=").append(formatBytes(aggregate.actualFrameBytes()))
                    .append(", actualRatio=").append(ratioText(aggregate.actualFrameBytes(), aggregate.rawPacketBytes()))
                    .append(", actualSavedVsRaw=").append(signed(aggregate.actualSavedVsRawBytes()))
                    .append(", standaloneFrame=").append(formatBytes(aggregate.standaloneFrameBytes()))
                    .append(", standaloneRatio=").append(ratioText(aggregate.standaloneFrameBytes(), aggregate.rawPacketBytes()))
                    .append(", standaloneSavedVsRaw=").append(signed(aggregate.standaloneSavedVsRawBytes()))
                    .append(", actualNegativeCount=").append(aggregate.actualNegativeCount())
                    .append(", standaloneNegativeCount=").append(aggregate.standaloneNegativeCount())
                    .append(", chunkWrapped=").append(aggregate.chunkWrappedCount())
                    .append(", batched=").append(aggregate.batchedCount())
                    .append(", estimatedActual=").append(aggregate.estimatedActualCount())
                    .append('\n');
        }
        builder.append('\n');
    }

    private static void appendSourceAggregateSection(
            StringBuilder builder,
            String title,
            List<SourceAggregate> aggregates
    ) {
        builder.append(title).append('\n');
        if (aggregates.isEmpty()) {
            builder.append("none").append('\n').append('\n');
            return;
        }

        for (SourceAggregate aggregate : aggregates) {
            builder.append("- ").append(aggregate.sourceKey())
                    .append(": count=").append(aggregate.packetCount())
                    .append(", raw=").append(formatBytes(aggregate.rawPacketBytes()))
                    .append(", chunkInput=").append(formatBytes(aggregate.transportInputBytes()))
                    .append(", chunkSavedVsRaw=").append(signed(aggregate.rawPacketBytes() - aggregate.transportInputBytes()))
                    .append(", actualFrame=").append(formatBytes(aggregate.actualFrameBytes()))
                    .append(", actualRatio=").append(ratioText(aggregate.actualFrameBytes(), aggregate.rawPacketBytes()))
                    .append(", actualSavedVsRaw=").append(signed(aggregate.actualSavedVsRawBytes()))
                    .append(", standaloneFrame=").append(formatBytes(aggregate.standaloneFrameBytes()))
                    .append(", standaloneRatio=").append(ratioText(aggregate.standaloneFrameBytes(), aggregate.rawPacketBytes()))
                    .append(", standaloneSavedVsRaw=").append(signed(aggregate.standaloneSavedVsRawBytes()))
                    .append(", classes=").append(aggregate.packetClassCount())
                    .append(", topClass=").append(simpleClassName(aggregate.topPacketClassName()))
                    .append('\n');
        }
        builder.append('\n');
    }

    private static void appendWorstSampleSection(
            StringBuilder builder,
            List<EnrichedObservation> enrichedObservations
    ) {
        builder.append("== Worst Actual Samples ==").append('\n');
        List<EnrichedObservation> negativeSamples = enrichedObservations.stream()
                .filter(observation -> observation.actualSavedVsRawBytes() < 0L)
                .sorted(Comparator
                        .comparingLong(EnrichedObservation::actualSavedVsRawBytes)
                        .thenComparingLong(observation -> observation.observation().captureIndex()))
                .limit(20)
                .toList();
        if (negativeSamples.isEmpty()) {
            builder.append("none").append('\n').append('\n');
            return;
        }

        for (EnrichedObservation enrichedObservation : negativeSamples) {
            ChannelTransportPacketRankObservation observation = enrichedObservation.observation();
            builder.append("- ").append(simpleClassName(observation.packetClassName()))
                    .append('#').append(observation.captureIndex())
                    .append(": raw=").append(observation.rawPacketBytes())
                    .append(", source=").append(observation.sourceKey())
                    .append(", chunkInput=").append(observation.transportInputBytes())
                    .append(", actualFrame=").append(observation.actualFrameBytes())
                    .append(", actualSavedVsRaw=").append(signed(enrichedObservation.actualSavedVsRawBytes()))
                    .append(", standaloneFrame=").append(enrichedObservation.standaloneFrameBytes())
                    .append(", standaloneSavedVsRaw=").append(signed(enrichedObservation.standaloneSavedVsRawBytes()))
                    .append(", path=").append(observation.actualPath())
                    .append(", frameKind=").append(observation.actualFrameKind())
                    .append(", actualEstimated=").append(observation.actualFrameBytesEstimated())
                    .append(", chunkWrapped=").append(observation.chunkProtocolApplied())
                    .append(", packetId=").append(observation.packetId())
                    .append('\n');
        }
        builder.append('\n');
    }

    private static String simpleClassName(String className) {
        if (className == null || className.isBlank()) {
            return "<unknown>";
        }
        int lastDotIndex = className.lastIndexOf('.');
        return lastDotIndex < 0 ? className : className.substring(lastDotIndex + 1);
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

    private static String ratioText(long currentBytes, long baselineBytes) {
        if (baselineBytes <= 0L) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.3fx", (double) currentBytes / (double) baselineBytes);
    }

    private static String signed(long value) {
        return value > 0L ? "+" + value : Long.toString(value);
    }

    private record EnrichedObservation(
            ChannelTransportPacketRankObservation observation,
            int standaloneFrameBytes
    ) {
        private long actualSavedVsRawBytes() {
            return (long) this.observation.rawPacketBytes() - this.observation.actualFrameBytes();
        }

        private long standaloneSavedVsRawBytes() {
            return (long) this.observation.rawPacketBytes() - this.standaloneFrameBytes;
        }
    }

    private record PacketClassAggregate(
            String packetClassName,
            long packetCount,
            long rawPacketBytes,
            long transportInputBytes,
            long actualFrameBytes,
            long standaloneFrameBytes,
            long chunkWrappedCount,
            long batchedCount,
            long estimatedActualCount,
            long actualNegativeCount,
            long standaloneNegativeCount
    ) {
        private long actualSavedVsRawBytes() {
            return this.rawPacketBytes - this.actualFrameBytes;
        }

        private long standaloneSavedVsRawBytes() {
            return this.rawPacketBytes - this.standaloneFrameBytes;
        }
    }

    private record SourceAggregate(
            String sourceKey,
            long packetCount,
            long rawPacketBytes,
            long transportInputBytes,
            long actualFrameBytes,
            long standaloneFrameBytes,
            int packetClassCount,
            String topPacketClassName
    ) {
        private long actualSavedVsRawBytes() {
            return this.rawPacketBytes - this.actualFrameBytes;
        }

        private long standaloneSavedVsRawBytes() {
            return this.rawPacketBytes - this.standaloneFrameBytes;
        }
    }

    private static final class PacketClassAggregateBuilder {
        private final String packetClassName;
        private long packetCount;
        private long rawPacketBytes;
        private long transportInputBytes;
        private long actualFrameBytes;
        private long standaloneFrameBytes;
        private long chunkWrappedCount;
        private long batchedCount;
        private long estimatedActualCount;
        private long actualNegativeCount;
        private long standaloneNegativeCount;

        private PacketClassAggregateBuilder(String packetClassName) {
            this.packetClassName = packetClassName;
        }

        private void record(EnrichedObservation enrichedObservation) {
            ChannelTransportPacketRankObservation observation = enrichedObservation.observation();
            this.packetCount++;
            this.rawPacketBytes += observation.rawPacketBytes();
            this.transportInputBytes += observation.transportInputBytes();
            this.actualFrameBytes += observation.actualFrameBytes();
            this.standaloneFrameBytes += enrichedObservation.standaloneFrameBytes();
            if (observation.chunkProtocolApplied()) {
                this.chunkWrappedCount++;
            }
            if (observation.batchPacketCount() > 1) {
                this.batchedCount++;
            }
            if (observation.actualFrameBytesEstimated()) {
                this.estimatedActualCount++;
            }
            if (observation.actualFrameBytes() > observation.rawPacketBytes()) {
                this.actualNegativeCount++;
            }
            if (enrichedObservation.standaloneFrameBytes() > observation.rawPacketBytes()) {
                this.standaloneNegativeCount++;
            }
        }

        private PacketClassAggregate build() {
            return new PacketClassAggregate(
                    this.packetClassName,
                    this.packetCount,
                    this.rawPacketBytes,
                    this.transportInputBytes,
                    this.actualFrameBytes,
                    this.standaloneFrameBytes,
                    this.chunkWrappedCount,
                    this.batchedCount,
                    this.estimatedActualCount,
                    this.actualNegativeCount,
                    this.standaloneNegativeCount
            );
        }
    }

    private static final class SourceAggregateBuilder {
        private final String sourceKey;
        private final Map<String, Long> classBytes = new LinkedHashMap<>();
        private long packetCount;
        private long rawPacketBytes;
        private long transportInputBytes;
        private long actualFrameBytes;
        private long standaloneFrameBytes;

        private SourceAggregateBuilder(String sourceKey) {
            this.sourceKey = sourceKey;
        }

        private void record(EnrichedObservation enrichedObservation) {
            ChannelTransportPacketRankObservation observation = enrichedObservation.observation();
            this.packetCount++;
            this.rawPacketBytes += observation.rawPacketBytes();
            this.transportInputBytes += observation.transportInputBytes();
            this.actualFrameBytes += observation.actualFrameBytes();
            this.standaloneFrameBytes += enrichedObservation.standaloneFrameBytes();
            this.classBytes.merge(observation.packetClassName(), (long) observation.rawPacketBytes(), Long::sum);
        }

        private SourceAggregate build() {
            String topPacketClassName = this.classBytes.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("<unknown>");
            return new SourceAggregate(
                    this.sourceKey,
                    this.packetCount,
                    this.rawPacketBytes,
                    this.transportInputBytes,
                    this.actualFrameBytes,
                    this.standaloneFrameBytes,
                    this.classBytes.size(),
                    topPacketClassName
            );
        }
    }

    private record Summary(
            int totalPacketCount,
            long totalRawPacketBytes,
            long totalTransportInputBytes,
            long totalActualFrameBytes,
            long totalStandaloneFrameBytes,
            long chunkWrappedPacketCount,
            long batchedPacketCount,
            long estimatedActualPacketCount,
            int negativeActualClassCount
    ) {
    }

    public record GeneratedReport(
            Path reportPath,
            Path latestReportPath,
            String reportText,
            long capturedPacketCount,
            int negativeActualClassCount
    ) {
    }
}
