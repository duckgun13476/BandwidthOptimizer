package com.PinkCats.bandwidthoptimizer.report.traffic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;

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
}
