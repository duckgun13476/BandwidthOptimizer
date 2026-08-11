package com.PinkCats.bandwidthoptimizer.report.traffic;

import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

public final class TrafficPeriodReportStoreRegressionMain {
    private TrafficPeriodReportStoreRegressionMain() {
    }

    public static void main(String[] args) throws Exception {
        Path output = Files.createTempDirectory("bo-traffic-period-regression-");
        String previousOutput = System.getProperty(BandwidthOptimizerOutputPaths.OUTPUT_DIRECTORY_PROPERTY);
        System.setProperty(BandwidthOptimizerOutputPaths.OUTPUT_DIRECTORY_PROPERTY, output.toString());
        try {
            ZoneId zone = ZoneId.of("UTC");
            ZonedDateTime previousHour = ZonedDateTime.of(2026, 8, 14, 10, 0, 0, 0, zone);
            ZonedDateTime currentHour = ZonedDateTime.of(2026, 8, 15, 11, 0, 0, 0, zone);
            TrafficPeriodReport archived = report(previousHour, zone, true, 100L);
            TrafficPeriodReport live = report(currentHour, zone, false, 25L);

            TrafficPeriodReportStore.saveBlocking(archived);
            check(Files.isRegularFile(TrafficPeriodReportStore.dailyPath(previousHour.toLocalDate())),
                    "daily report was not rebuilt from the hourly archive");

            Files.write(
                    TrafficPeriodReportStore.hourlyPath(archived),
                    TrafficPeriodReportJson.encode(report(previousHour, zone, true, 900L))
            );
            TrafficHistoryReport history = TrafficPeriodReportStore.loadCurrentMonth(live);

            check(history != null, "month history was not produced");
            check(history.totals().outboundWireBytes() == 125L,
                    "month totals did not use the daily archive plus the live day");
            check(history.hours().size() == 2, "hourly detail was not retained");
            check(history.hours().get(0).totals().outboundWireBytes() == 900L,
                    "hourly detail did not remain independent from month aggregation");
            check(history.players().size() == 1 && "TestPlayer".equals(history.players().get(0).playerName()),
                    "player identity was not aggregated by UUID");
            System.out.println("Traffic period report store regression passed.");
        } finally {
            if (previousOutput == null) {
                System.clearProperty(BandwidthOptimizerOutputPaths.OUTPUT_DIRECTORY_PROPERTY);
            } else {
                System.setProperty(BandwidthOptimizerOutputPaths.OUTPUT_DIRECTORY_PROPERTY, previousOutput);
            }
            try (var paths = Files.walk(output)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                });
            }
        }
    }

    private static TrafficPeriodReport report(
            ZonedDateTime start,
            ZoneId zone,
            boolean complete,
            long outboundWireBytes
    ) {
        TrafficPeriodReport.TrafficCounters counters = new TrafficPeriodReport.TrafficCounters(
                1L, outboundWireBytes * 2L, outboundWireBytes * 2L, outboundWireBytes * 2L,
                1L, outboundWireBytes, 0L, 0L, outboundWireBytes,
                0L, 0L, 0L, 0L, 0L, 0L, 0L
        );
        long startMillis = start.toInstant().toEpochMilli();
        return new TrafficPeriodReport(
                1,
                "report-" + startMillis,
                "hour",
                startMillis,
                start.plusHours(1L).toInstant().toEpochMilli(),
                startMillis,
                zone.getId(),
                complete,
                "server-admin-player-identifiable",
                counters,
                List.of(new TrafficPeriodReport.PlayerTraffic(
                        "00000000-0000-0000-0000-000000000001",
                        "TestPlayer",
                        counters
                ))
        );
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
