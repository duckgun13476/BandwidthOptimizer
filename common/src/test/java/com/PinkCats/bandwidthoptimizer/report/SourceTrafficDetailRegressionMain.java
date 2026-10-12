package com.PinkCats.bandwidthoptimizer.report;

import com.google.gson.Gson;
import java.lang.reflect.Method;
import java.util.List;

public final class SourceTrafficDetailRegressionMain {
    public static void main(String[] args) throws Exception {
        System.setProperty("bandwidthoptimizer.transport.sourceRankReportIntervalMillis", "0");
        ChannelTransportSourceRankCore.recordOutboundPacket("test:raw", "Raw", 1,
                1000, 900, 10, true, false, "SINGLE", "FULL", 1, "private-channel");
        ChannelTransportSourceRankCore.recordOutboundPacket("test:wire", "Wire", 2,
                100, 100, 90, false, false, "BATCH_SHARE", "BATCH", 2, "private-channel");
        var live = ChannelTransportSourceRankCore.snapshotForReport(1);
        require(live.entries().size() == 2, "raw and actual leaders must both survive truncation");
        require(live.actualBytes() == 100 && live.rawBytes() == 1100, "live totals");
        require(live.windowStartedAtMillis() == 0, "no completed window yet");
        require(live.countersStartedAtMillis() > 0 && live.observedAtMillis() >= live.countersStartedAtMillis(), "cumulative observation period");
        Method snapshot = ChannelTransportSourceRankCore.class.getDeclaredMethod("snapshotEntries");
        snapshot.setAccessible(true);
        List<?> drained = (List<?>) snapshot.invoke(null);
        Method writer = ChannelTransportSourceRankCore.class.getDeclaredMethod("writeReport", String.class, List.class, int.class);
        writer.setAccessible(true);
        writer.invoke(null, "regression", drained, 1);
        var completed = ChannelTransportSourceRankCore.snapshotForReport(1);
        require(completed.windowPackets() == 2 && completed.windowActualBytes() == 100, "completed window");
        require(completed.observedAtMillis() >= completed.windowStartedAtMillis(), "window timestamps");
        var wire = completed.entries().stream().filter(e -> e.sourceKey().equals("test:wire")).findFirst().orElseThrow();
        require(wire.estimatedPackets() == 1 && wire.lastPath().equals("BATCH_SHARE"), "batch estimate attribution");
        require(!completed.toString().contains("private-channel"), "channel identity must stay private");
        require(ChannelTransportSourceRankCore.snapshotForReport(0).entries().isEmpty(), "zero limit");
        require(ChannelTransportSourceRankCore.snapshotForReport(1).windowPackets() == 2, "read must not drain window");
        writer.invoke(null, "regression-next", snapshot.invoke(null), 1);
        require(ChannelTransportSourceRankCore.snapshotForReport(1).windowPackets() == 0, "next periodic window drains once");
        require(ChannelTransportSourceRankCore.snapshotForReport(1).packets() == 2, "cumulative totals survive reset");
        for (int i = 0; i < 501; i++) {
            ChannelTransportSourceRankCore.recordOutboundPacket("test:key-" + i, "Packet", i,
                    i + 1, i + 1, 502 - i, false, true, "BATCH_SHARE", "BATCH", 2, "private-channel");
        }
        writer.invoke(null, "regression-bounded", snapshot.invoke(null), 1);
        var bounded = ChannelTransportSourceRankCore.snapshotForReport(Integer.MAX_VALUE);
        require(bounded.distinctKeys() == 503 && bounded.entries().size() <= 400, "bounded four-rank union");
        require(bounded.rankingLimit() == 100, "hard export limit");
        String json = new Gson().toJson(bounded);
        var roundTrip = new Gson().fromJson(json, ChannelTransportSourceRankCore.SourceTrafficSnapshot.class);
        require(roundTrip.equals(bounded), "upload JSON must retain all detail fields");
        require(!json.contains("private-channel"), "JSON channel privacy");
        var telemetryStart = com.PinkCats.bandwidthoptimizer.channel.capture.ChannelTransportTelemetry.class.getDeclaredField("countersStartedAtMillis");
        telemetryStart.setAccessible(true);
        telemetryStart.setLong(null, 1L);
        com.PinkCats.bandwidthoptimizer.channel.capture.ChannelTransportTelemetry.reset();
        require(com.PinkCats.bandwidthoptimizer.channel.capture.ChannelTransportTelemetry.countersStartedAtMillis() > 1L, "transport reset renews period");
        var sessionStart = com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry.class.getDeclaredField("countersStartedAtMillis");
        sessionStart.setAccessible(true);
        sessionStart.setLong(null, 1L);
        com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry.resetAll();
        require(com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry.countersStartedAtMillis() > 1L, "server reset renews period");
        var bypass = com.PinkCats.bandwidthoptimizer.channel.ChannelTransportBypassRankCore.snapshot(100);
        require(bypass.countersStartedAtMillis() > 0 && bypass.observedAtMillis() >= bypass.windowStartedAtMillis(), "bypass periods");
        System.out.println("Source traffic detail regression passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
