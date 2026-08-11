package com.PinkCats.bandwidthoptimizer.report.traffic;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class TrafficPeriodReportStore {
    private static final String ROOT_DIRECTORY = "reports/traffic";
    private static final Object IO_LOCK = new Object();
    private static final DateTimeFormatter HOUR_FILE = DateTimeFormatter.ofPattern("HHxx");
    private static final AtomicReference<TrafficPeriodReport> PENDING = new AtomicReference<>();
    private static final AtomicBoolean WRITER_RUNNING = new AtomicBoolean();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bo-traffic-report");
        thread.setDaemon(true);
        thread.setContextClassLoader(TrafficPeriodReportStore.class.getClassLoader());
        return thread;
    });

    private TrafficPeriodReportStore() {
    }

    public static Path hourlyPath(TrafficPeriodReport report) {
        ZoneId zone = zone(report.zoneId());
        ZonedDateTime start = Instant.ofEpochMilli(report.periodStartMillis()).atZone(zone);
        return root().resolve("hourly")
                .resolve(start.toLocalDate().toString())
                .resolve(HOUR_FILE.format(start) + ".json");
    }

    public static Path dailyPath(LocalDate day) {
        return root().resolve("daily").resolve(day + ".json");
    }

    public static TrafficPeriodReport loadHour(long startMillis, ZoneId zone) {
        TrafficPeriodReport probe = new TrafficPeriodReport(
                1, "probe", "hour", startMillis, startMillis, startMillis,
                zone.getId(), false, "server-admin-player-identifiable",
                TrafficPeriodReport.TrafficCounters.empty(), List.of()
        );
        return read(hourlyPath(probe));
    }

    public static TrafficHistoryReport loadCurrentMonth(TrafficPeriodReport currentHour) {
        if (currentHour == null) {
            return null;
        }
        ZoneId zone = zone(currentHour.zoneId());
        ZonedDateTime currentStart = Instant.ofEpochMilli(currentHour.periodStartMillis()).atZone(zone);
        YearMonth month = YearMonth.from(currentStart);
        long monthStart = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long monthEnd = month.plusMonths(1L).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        LocalDate currentDay = currentStart.toLocalDate();
        Map<Long, TrafficPeriodReport> byHour = new LinkedHashMap<>();
        List<TrafficPeriodReport> monthSources = new ArrayList<>();
        synchronized (IO_LOCK) {
            for (int day = 1; day <= month.lengthOfMonth(); day++) {
                LocalDate date = month.atDay(day);
                if (date.isBefore(currentDay)) {
                    TrafficPeriodReport daily = read(dailyPath(date));
                    if (daily != null
                            && "day".equals(daily.periodType())
                            && daily.periodStartMillis() >= monthStart
                            && daily.periodStartMillis() < monthEnd) {
                        monthSources.add(daily);
                    }
                }
                Path directory = root().resolve("hourly").resolve(date.toString());
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                try (var paths = Files.list(directory)) {
                    paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .map(TrafficPeriodReportStore::read)
                            .filter(report -> report != null
                                    && "hour".equals(report.periodType())
                                    && report.periodStartMillis() >= monthStart
                                    && report.periodStartMillis() < monthEnd)
                            .forEach(report -> byHour.put(report.periodStartMillis(), report));
                } catch (IOException exception) {
                    Bandwidthoptimizer.LOGGER.warn("[TrafficReport] Failed to read hourly report directory {}", directory, exception);
                }
            }
        }
        byHour.put(currentHour.periodStartMillis(), currentHour);
        List<TrafficPeriodReport> hours = byHour.values().stream()
                .sorted(Comparator.comparingLong(TrafficPeriodReport::periodStartMillis))
                .toList();
        List<TrafficPeriodReport> currentDayHours = hours.stream()
                .filter(report -> Instant.ofEpochMilli(report.periodStartMillis()).atZone(zone).toLocalDate().equals(currentDay))
                .toList();
        if (!currentDayHours.isEmpty()) {
            monthSources.add(aggregate(
                    "day",
                    currentDay.atStartOfDay(zone).toInstant().toEpochMilli(),
                    currentDay.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli(),
                    zone,
                    false,
                    currentDayHours
            ));
        }
        TrafficPeriodReport monthReport = aggregate(
                "month",
                monthStart,
                monthEnd,
                zone,
                month.isBefore(YearMonth.now(zone)) && monthSources.stream().allMatch(TrafficPeriodReport::complete),
                monthSources
        );
        List<TrafficHistoryReport.HourlyTraffic> hourlyTraffic = hours.stream()
                .map(TrafficPeriodReportStore::hourlyTraffic)
                .toList();
        return new TrafficHistoryReport(
                1,
                monthStart,
                monthEnd,
                System.currentTimeMillis(),
                zone.getId(),
                monthReport.complete(),
                monthReport.totals(),
                monthReport.players(),
                hourlyTraffic
        );
    }

    public static void saveAsync(TrafficPeriodReport report) {
        if (report == null) {
            return;
        }
        PENDING.set(report);
        if (WRITER_RUNNING.compareAndSet(false, true)) {
            WRITER.execute(TrafficPeriodReportStore::drain);
        }
    }

    public static Path saveBlocking(TrafficPeriodReport report) {
        try {
            return writeHourAndDaily(report);
        } catch (IOException exception) {
            Bandwidthoptimizer.LOGGER.warn("[TrafficReport] Failed to persist traffic period", exception);
            return null;
        }
    }

    private static void drain() {
        try {
            TrafficPeriodReport report;
            while ((report = PENDING.getAndSet(null)) != null) {
                saveBlocking(report);
            }
        } finally {
            WRITER_RUNNING.set(false);
            if (PENDING.get() != null && WRITER_RUNNING.compareAndSet(false, true)) {
                WRITER.execute(TrafficPeriodReportStore::drain);
            }
        }
    }

    private static Path writeHourAndDaily(TrafficPeriodReport report) throws IOException {
        synchronized (IO_LOCK) {
            Path hourly = hourlyPath(report);
            TrafficPeriodReport existing = read(hourly);
            if (existing == null || existing.generatedAtMillis() <= report.generatedAtMillis()) {
                writeAtomic(hourly, TrafficPeriodReportJson.encode(report));
            }
            refreshDaily(Instant.ofEpochMilli(report.periodStartMillis()).atZone(zone(report.zoneId())).toLocalDate(), zone(report.zoneId()));
            return hourly.toAbsolutePath().normalize();
        }
    }

    private static void refreshDaily(LocalDate day, ZoneId zone) throws IOException {
        Path directory = root().resolve("hourly").resolve(day.toString());
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<TrafficPeriodReport> hours = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(TrafficPeriodReportStore::read)
                    .filter(report -> report != null && "hour".equals(report.periodType()))
                    .sorted(Comparator.comparingLong(TrafficPeriodReport::periodStartMillis))
                    .forEach(hours::add);
        }
        if (hours.isEmpty()) {
            return;
        }
        long start = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = day.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli();
        boolean complete = day.isBefore(LocalDate.now(zone)) && hours.stream().allMatch(TrafficPeriodReport::complete);
        TrafficPeriodReport daily = aggregate("day", start, end, zone, complete, hours);
        writeAtomic(dailyPath(day), TrafficPeriodReportJson.encode(daily));
    }

    private static TrafficPeriodReport aggregate(
            String type,
            long start,
            long end,
            ZoneId zone,
            boolean complete,
            List<TrafficPeriodReport> sources
    ) {
        Map<String, MutablePlayer> players = new HashMap<>();
        for (TrafficPeriodReport source : sources) {
            for (TrafficPeriodReport.PlayerTraffic player : source.players()) {
                players.computeIfAbsent(player.playerUuid(), ignored -> new MutablePlayer(player.playerUuid(), player.playerName()))
                        .add(player.playerName(), player.traffic());
            }
        }
        return report(type, start, end, zone, complete, players);
    }

    private static TrafficHistoryReport.HourlyTraffic hourlyTraffic(TrafficPeriodReport report) {
        List<TrafficHistoryReport.PlayerWireTraffic> players = report.players().stream()
                .map(player -> new TrafficHistoryReport.PlayerWireTraffic(
                        player.playerUuid(),
                        player.playerName(),
                        player.traffic().outboundRawBytes(),
                        player.traffic().outboundWireBytes(),
                        player.traffic().inboundRawBytes(),
                        player.traffic().inboundWireBytes()
                ))
                .toList();
        return new TrafficHistoryReport.HourlyTraffic(
                report.periodStartMillis(),
                report.periodEndMillis(),
                report.complete(),
                report.totals(),
                players
        );
    }

    static TrafficPeriodReport report(
            String type,
            long start,
            long end,
            ZoneId zone,
            boolean complete,
            Map<String, MutablePlayer> players
    ) {
        List<TrafficPeriodReport.PlayerTraffic> entries = players.values().stream()
                .map(MutablePlayer::snapshot)
                .filter(entry -> !entry.traffic().isEmpty())
                .sorted(Comparator.comparingLong((TrafficPeriodReport.PlayerTraffic entry) -> entry.traffic().totalWireBytes())
                        .reversed().thenComparing(TrafficPeriodReport.PlayerTraffic::playerName))
                .toList();
        TrafficPeriodReport.TrafficCounters totals = TrafficPeriodReport.TrafficCounters.empty();
        for (TrafficPeriodReport.PlayerTraffic entry : entries) {
            totals = totals.plus(entry.traffic());
        }
        return new TrafficPeriodReport(
                1,
                UUID.randomUUID().toString(),
                type,
                start,
                end,
                System.currentTimeMillis(),
                zone.getId(),
                complete,
                "server-admin-player-identifiable",
                totals,
                List.copyOf(entries)
        );
    }

    private static TrafficPeriodReport read(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return TrafficPeriodReportJson.decode(Files.readString(path, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            Bandwidthoptimizer.LOGGER.warn("[TrafficReport] Ignoring unreadable report {}", path, exception);
            return null;
        }
    }

    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path root() {
        return BandwidthOptimizerOutputPaths.resolveDirectory(ROOT_DIRECTORY);
    }

    private static ZoneId zone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            return ZoneId.systemDefault();
        }
    }

    static final class MutablePlayer {
        private final String uuid;
        private String name;
        private TrafficPeriodReport.TrafficCounters traffic = TrafficPeriodReport.TrafficCounters.empty();

        MutablePlayer(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        void add(String latestName, TrafficPeriodReport.TrafficCounters delta) {
            if (latestName != null && !latestName.isBlank()) {
                this.name = latestName;
            }
            this.traffic = this.traffic.plus(delta);
        }

        TrafficPeriodReport.PlayerTraffic snapshot() {
            return new TrafficPeriodReport.PlayerTraffic(uuid, name == null ? "<unknown-player>" : name, traffic);
        }
    }
}
