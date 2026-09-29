package com.PinkCats.bandwidthoptimizer.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public final class ChannelTransportCompressionReportMain {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private ChannelTransportCompressionReportMain() {}

    public static void main(String[] args) throws IOException {
        Arguments arguments = Arguments.parse(args);
        List<ScenarioMetrics> scenarios = loadScenarioMetrics(arguments.manifestPath());
        String reportText = buildReportText(arguments.manifestPath(), scenarios);
        Path outputParent = arguments.outputPath().getParent();
        if (outputParent != null)
            Files.createDirectories(outputParent);
        Files.writeString(arguments.outputPath(), reportText, StandardCharsets.UTF_8);
        System.out.println(reportText);
    }

    private static List<ScenarioMetrics> loadScenarioMetrics(Path manifestPath) throws IOException {
        List<String> lines = Files.readAllLines(manifestPath, StandardCharsets.UTF_8);
        List<ScenarioMetrics> scenarios = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (line.isEmpty())
                continue;
            String[] columns = line.split("\t", -1);
            if (columns.length < 11)
                throw new IllegalArgumentException("Invalid manifest line: " + line);
            ScenarioDefinition definition = new ScenarioDefinition(
                    columns[0],
                    columns[1],
                    Boolean.parseBoolean(columns[2]),
                    Boolean.parseBoolean(columns[3]),
                    Boolean.parseBoolean(columns[4]),
                    Boolean.parseBoolean(columns[5]),
                    toOptionalPath(columns[6]),
                    toOptionalPath(columns[7]),
                    toOptionalPath(columns[8]),
                    Integer.parseInt(columns[9]),
                    Boolean.parseBoolean(columns[10])
            );
            scenarios.add(new ScenarioMetrics(
                    definition,
                    loadDumpSummary(definition.serverDumpPath()),
                    loadDumpSummary(definition.clientDumpPath())
            ));
        }
        return List.copyOf(scenarios);
    }

    private static String buildReportText(Path manifestPath, List<ScenarioMetrics> scenarios) {
        StringBuilder builder = new StringBuilder(4096);
        ScenarioMetrics baselineScenario = findBaselineScenario(scenarios);

        builder.append("BandwidthOptimizer Channel Transport Compression Report").append('\n');
        builder.append("generatedAt=").append(TIME_FORMATTER.format(LocalDateTime.now())).append('\n');
        builder.append("manifest=").append(manifestPath.toAbsolutePath()).append('\n');
        builder.append("baseline=").append(baselineScenario == null ? "none" : baselineScenario.definition().displayName()).append('\n');
        builder.append("note=totalRawPacketBytes 和 totalTransportFrameBytes 使用 server/client 两端 outbound 汇总").append('\n');
        builder.append('\n');

        builder.append("== Summary ==").append('\n');
        for (ScenarioMetrics scenario : scenarios) {
            builder.append("- ").append(scenario.definition().displayName())
                    .append(": status=").append(scenario.statusText())
                    .append(", totalRaw=").append(formatBytesWithHumanReadable(scenario.totalRawPacketBytes()))
                    .append(", totalFrame=").append(formatBytesWithHumanReadable(scenario.totalTransportFrameBytes()))
                    .append(", frameRatio=").append(ratioText(scenario.totalTransportFrameBytes(), scenario.totalRawPacketBytes()))
                    .append(", savedVsRaw=").append(scenario.totalSavedBytes());

            if (baselineScenario == null || baselineScenario == scenario || !baselineScenario.hasBothDumps()) {
                builder.append(", vsBaseline=baseline");
            } else {
                long deltaFrameBytes = scenario.totalTransportFrameBytes() - baselineScenario.totalTransportFrameBytes();
                builder.append(", vsBaselineFrameBytes=").append(signed(deltaFrameBytes))
                        .append(" (").append(percentText(deltaFrameBytes, baselineScenario.totalTransportFrameBytes())).append(')');
            }
            builder.append('\n');
        }
        builder.append('\n');

        builder.append("== Algorithm Total ==").append('\n');
        for (ScenarioMetrics scenario : scenarios) {
            builder.append('[').append(scenario.definition().displayName()).append(']').append('\n');
            builder.append("status=").append(scenario.statusText())
                    .append(", exitCode=").append(scenario.definition().exitCode())
                    .append(", timedOut=").append(scenario.definition().timedOut())
                    .append(", mapping=").append(scenario.definition().mappingEnabled())
                    .append(", batch=").append(scenario.definition().batchEnabled())
                    .append(", packetIdMapping=").append(scenario.definition().packetIdMappingEnabled())
                    .append('\n');
            builder.append("server=").append(formatDumpSummary(scenario.serverDump())).append('\n');
            builder.append("client=").append(formatDumpSummary(scenario.clientDump())).append('\n');
            builder.append("log=").append(scenario.definition().logPath() == null ? "" : scenario.definition().logPath().toAbsolutePath()).append('\n');
            builder.append('\n');
        }

        return builder.toString();
    }

    private static ScenarioMetrics findBaselineScenario(List<ScenarioMetrics> scenarios) {
        for (ScenarioMetrics scenario : scenarios) {
            if (scenario.definition().baseline() && scenario.hasBothDumps())
                return scenario;
        }
        for (ScenarioMetrics scenario : scenarios) {
            if (scenario.hasBothDumps())
                return scenario;
        }
        return scenarios.isEmpty() ? null : scenarios.get(0);
    }


    private static DumpSummary loadDumpSummary(Path dumpPath) throws IOException {
        if (dumpPath == null || !Files.exists(dumpPath))
            return null;
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(dumpPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }

        return new DumpSummary(
                properties.getProperty("algorithmId", ""),
                Boolean.parseBoolean(properties.getProperty("mappingEnabled", "false")),
                Boolean.parseBoolean(properties.getProperty("zstdEnabled", "false")),
                Boolean.parseBoolean(properties.getProperty("packetIdMappingEnabled", "false")),
                parseLong(properties, "outbound.frames"),
                parseLong(properties, "outbound.rawPackets"),
                parseLong(properties, "outbound.rawPacketBytes"),
                parseLong(properties, "outbound.transportFrameBytes"),
                parseLong(properties, "inbound.frames"),
                parseLong(properties, "inbound.restoredPackets"),
                parseLong(properties, "inbound.restoredPacketBytes")
        );
    }

    private static String formatDumpSummary(DumpSummary dumpSummary) {
        if (dumpSummary == null) {
            return "missing";
        }
        return "algorithm=" + dumpSummary.algorithmId()
                + ", rawPackets=" + dumpSummary.outboundRawPackets()
                + ", rawBytes=" + formatBytesWithHumanReadable(dumpSummary.outboundRawPacketBytes())
                + ", frameBytes=" + formatBytesWithHumanReadable(dumpSummary.outboundTransportFrameBytes())
                + ", frameRatio=" + ratioText(dumpSummary.outboundTransportFrameBytes(), dumpSummary.outboundRawPacketBytes())
                + ", restoredPackets=" + dumpSummary.inboundRestoredPackets()
                + ", restoredBytes=" + formatBytesWithHumanReadable(dumpSummary.inboundRestoredPacketBytes());
    }

    private static Path toOptionalPath(String rawPath) {
        return rawPath == null || rawPath.isBlank() ? null : Path.of(rawPath);
    }

    private static long parseLong(Properties properties, String key) {
        String rawValue = properties.getProperty(key, "0").trim();
        return rawValue.isEmpty() ? 0L : Long.parseLong(rawValue);
    }

    private static String formatBytesWithHumanReadable(long bytes) {
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

    private static String percentText(long deltaBytes, long baselineBytes) {
        if (baselineBytes <= 0L) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%+.3f%%", deltaBytes * 100.0D / baselineBytes);
    }

    private static String signed(long value) {
        return value > 0L ? "+" + value : Long.toString(value);
    }

    private record Arguments(Path manifestPath, Path outputPath) {

        private static Arguments parse(String[] args) {
            Path manifestPath = null;
            Path outputPath = null;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--manifest".equals(argument) && index + 1 < args.length) {
                    manifestPath = Path.of(args[++index]);
                    continue;
                }
                if ("--output".equals(argument) && index + 1 < args.length) {
                    outputPath = Path.of(args[++index]);
                }
            }

            if (manifestPath == null || outputPath == null) {
                throw new IllegalArgumentException("Usage: --manifest <path> --output <path>");
            }
            return new Arguments(manifestPath, outputPath);
        }
    }

    private record ScenarioDefinition(
            String id,
            String displayName,
            boolean baseline,
            boolean mappingEnabled,
            boolean batchEnabled,
            boolean packetIdMappingEnabled,
            Path logPath,
            Path serverDumpPath,
            Path clientDumpPath,
            int exitCode,
            boolean timedOut
    ) {
    }

    private record DumpSummary(
            String algorithmId,
            boolean mappingEnabled,
            boolean zstdEnabled,
            boolean packetIdMappingEnabled,
            long outboundFrames,
            long outboundRawPackets,
            long outboundRawPacketBytes,
            long outboundTransportFrameBytes,
            long inboundFrames,
            long inboundRestoredPackets,
            long inboundRestoredPacketBytes
    ) {
    }

    private record ScenarioMetrics(
            ScenarioDefinition definition,
            DumpSummary serverDump,
            DumpSummary clientDump
    ) {

        private boolean hasBothDumps() {
            return this.serverDump != null && this.clientDump != null;}

        private long totalRawPacketBytes() {
            return outboundRawPacketBytes(this.serverDump) + outboundRawPacketBytes(this.clientDump);
        }

        private long totalTransportFrameBytes() {
            return outboundTransportFrameBytes(this.serverDump) + outboundTransportFrameBytes(this.clientDump);
        }

        private long totalSavedBytes() {
            return totalRawPacketBytes() - totalTransportFrameBytes();
        }

        private String statusText() {
            if (this.definition.timedOut()) {
                return "TIMEOUT";
            }
            if (!hasBothDumps()) {
                return "MISSING_DUMP";
            }
            if (this.definition.exitCode() != 0) {
                return "EXIT_" + this.definition.exitCode();
            }
            return "OK";
        }

        private static long outboundRawPacketBytes(DumpSummary dumpSummary) {
            return dumpSummary == null ? 0L : dumpSummary.outboundRawPacketBytes();
        }

        private static long outboundTransportFrameBytes(DumpSummary dumpSummary) {
            return dumpSummary == null ? 0L : dumpSummary.outboundTransportFrameBytes();
        }
    }
}
