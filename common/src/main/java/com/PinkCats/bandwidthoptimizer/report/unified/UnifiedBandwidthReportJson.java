package com.PinkCats.bandwidthoptimizer.report.unified;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;

public final class UnifiedBandwidthReportJson {
    public static final String MEDIA_TYPE = "application/vnd.bandwidthoptimizer.report+json;version=1";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private UnifiedBandwidthReportJson() {
    }

    public static byte[] encode(UnifiedBandwidthReport report) {
        return GSON.toJson(report).getBytes(StandardCharsets.UTF_8);
    }
}
