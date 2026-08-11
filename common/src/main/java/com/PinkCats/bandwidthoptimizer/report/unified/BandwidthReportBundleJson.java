package com.PinkCats.bandwidthoptimizer.report.unified;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;

public final class BandwidthReportBundleJson {
    public static final String MEDIA_TYPE = "application/vnd.bandwidthoptimizer.report-bundle+json;version=1";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private BandwidthReportBundleJson() {
    }

    public static byte[] encode(BandwidthReportBundle report) {
        return GSON.toJson(report).getBytes(StandardCharsets.UTF_8);
    }
}
