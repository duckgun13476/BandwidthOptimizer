package com.PinkCats.bandwidthoptimizer.test;

import com.PinkCats.bandwidthoptimizer.report.ChannelTransportReportScenarioCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ChannelTransportReportScenarioListMain {

    private ChannelTransportReportScenarioListMain() {}

    public static void main(String[] args) throws IOException {
        Arguments arguments = Arguments.parse(args);
        Path outputParent = arguments.outputPath().getParent();
        if (outputParent != null)
            Files.createDirectories(outputParent);
        Files.writeString(arguments.outputPath(), buildScenarioListText(), StandardCharsets.UTF_8);
    }

    private static String buildScenarioListText() {
        StringBuilder builder = new StringBuilder(512);
        builder.append("id\tdisplayName\tbaseline\tmappingEnabled\tbatchEnabled\tpacketIdMappingEnabled").append('\n');
        for (ChannelTransportReportScenarioCatalog.ScenarioPreset scenarioPreset : ChannelTransportReportScenarioCatalog.defaultScenarios()) {
            appendScenarioLine(builder, scenarioPreset);
        }
        return builder.toString();
    }

    private static void appendScenarioLine(
            StringBuilder builder,
            ChannelTransportReportScenarioCatalog.ScenarioPreset scenarioPreset
    ) {
        builder.append(scenarioPreset.id()).append('\t')
                .append(scenarioPreset.displayName()).append('\t')
                .append(scenarioPreset.baseline()).append('\t')
                .append(scenarioPreset.mappingEnabled()).append('\t')
                .append(scenarioPreset.batchEnabled()).append('\t')
                .append(scenarioPreset.packetIdMappingEnabled()).append('\n');
    }

    private record Arguments(Path outputPath) {

        private static Arguments parse(String[] args) {
            Path outputPath = null;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--output".equals(argument) && index + 1 < args.length) {
                    outputPath = Path.of(args[++index]);
                }
            }

            if (outputPath == null)
                throw new IllegalArgumentException("Usage: --output <path>");
            return new Arguments(outputPath);
        }
    }
}
