package com.PinkCats.bandwidthoptimizer.report.traffic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

public final class TrafficPeriodReportJson {
    public static final String MEDIA_TYPE = "application/vnd.bandwidthoptimizer.traffic-period+json;version=1";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private TrafficPeriodReportJson() {
    }

    public static byte[] encode(TrafficPeriodReport report) {
        return GSON.toJson(report).getBytes(StandardCharsets.UTF_8);
    }

    public static TrafficPeriodReport decode(String json) {
        return GSON.fromJson(json, TrafficPeriodReport.class);
    }

    static byte[] encodeHourlyDay(LocalDate day, List<TrafficPeriodReport> hours) {
        return GSON.toJson(new HourlyDayArchive(1, day.toString(), List.copyOf(hours)))
                .getBytes(StandardCharsets.UTF_8);
    }

    static List<TrafficPeriodReport> decodeHourlyDay(String json) {
        HourlyDayArchive archive = GSON.fromJson(json, HourlyDayArchive.class);
        if (archive == null || archive.schemaVersion() != 1 || archive.hours() == null) {
            throw new IllegalArgumentException("Unsupported hourly day archive");
        }
        return List.copyOf(archive.hours());
    }

    private record HourlyDayArchive(
            int schemaVersion,
            String day,
            List<TrafficPeriodReport> hours
    ) {
    }
}
