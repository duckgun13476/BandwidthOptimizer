package com.PinkCats.bandwidthoptimizer.integration.watut;

import com.PinkCats.bandwidthoptimizer.gate.IdleGateMode;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateStatePayload;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.Arrays;

/** Verify actual heartbeat wire compatibility and cumulative accounting, not only snapshot formatting. */
public final class WatutClientReportingRegressionMain {
    public static void run() throws Exception {
        wireCompatibility(); cumulativeReports(); resetIsolation(); reportMapping();
        System.out.println("WATUT client reporting PASS: bounded legacy wire, sequence/reconnect/reset accounting, coverage and saturation");
    }
    private static void wireCompatibility() throws Exception {
        IdleGateStatePayload old = new IdleGateStatePayload(5, IdleGateMode.BACKGROUND_IDLE, false, 100, 7, 8, 9);
        IdleGateStatePayload modern = new IdleGateStatePayload(5, IdleGateMode.BACKGROUND_IDLE, false, 100, 7, 8, 9,
                true, 1000, 2, 4000);
        byte[] bytes = modern.toBytes();
        check(bytes.length == 64 && old.toBytes().length == 39, "wire cap/prefix size");
        check(Arrays.equals(old.toBytes(), Arrays.copyOf(bytes, 39)), "legacy prefix unchanged");
        check(IdleGateStatePayload.fromBytes(bytes).equals(modern), "extension round trip");
        check(!IdleGateStatePayload.fromBytes(old.toBytes()).watutStatsSupported(), "legacy is missing coverage, not zero savings");
        // Reproduce the old v2 reader: exactly the same cap and prefix, no extension consumption.
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            check(input.readByte() == 2 && input.readInt() == 5, "old reader version/sequence");
            check(input.readByte() == IdleGateMode.BACKGROUND_IDLE.id() && !input.readBoolean(), "old reader mode/HUD retained");
            check(input.readLong() == 100 && input.readLong() == 7 && input.readLong() == 8 && input.readLong() == 9,
                    "old reader cadence fields retained");
        }
        check(!IdleGateStatePayload.fromBytes(Arrays.copyOf(bytes, 63)).watutStatsSupported(), "truncated extension never creates exact stats");
        bytes[39] = 0;
        check(!IdleGateStatePayload.fromBytes(bytes).watutStatsSupported(), "unknown extension ignored");
    }
    private static void cumulativeReports() {
        ServerWatutClientStats.clear(); Object a = new Object(), b = new Object();
        ServerWatutClientStats.accept(a, 1, 0, 0, 0);
        check(ServerWatutClientStats.snapshot().reportingConnections() == 1, "zero-valued capable client counts");
        ServerWatutClientStats.accept(a, 2, 100, 1, 300); expect(100, 1, 300);
        ServerWatutClientStats.accept(a, 2, 1000, 8, 900); expect(100, 1, 300);
        ServerWatutClientStats.accept(a, 1, 0, 0, 0); expect(100, 1, 300);
        ServerWatutClientStats.accept(a, 3, 150, 2, 400); expect(150, 2, 400);
        ServerWatutClientStats.accept(a, 4, 1, 0, 2); expect(150, 2, 400);
        ServerWatutClientStats.accept(a, 5, 11, 1, 12); expect(160, 3, 410);
        ServerWatutClientStats.reset();
        ServerWatutClientStats.accept(a, 6, 11, 1, 12); expect(0, 0, 0);
        ServerWatutClientStats.accept(a, 7, 21, 2, 32); expect(10, 1, 20);
        ServerWatutClientStats.remove(a);
        ServerWatutClientStats.accept(b, 1, 5, 1, 8); expect(15, 2, 28);
        ServerWatutClientStats.remove(a);
        check(ServerWatutClientStats.snapshot().reportingConnections() == 1, "late old connection cleanup cannot remove new connection");
        ServerWatutClientStats.accept(b, 2, -1, 2, 9); expect(15, 2, 28);
        ServerWatutClientStats.clear();
        ServerWatutClientStats.accept(a, Integer.MAX_VALUE, Long.MAX_VALUE - 1, 1, 0);
        ServerWatutClientStats.accept(a, Integer.MIN_VALUE, Long.MAX_VALUE, 2, 0);
        ServerWatutClientStats.accept(b, 1, 100, 1, 0);
        check(ServerWatutClientStats.snapshot().savedPayloadBytes() == Long.MAX_VALUE, "sequence wrap and saturated totals");
        ServerWatutClientStats.clear();
        for (int i = 0; i < ServerWatutClientStats.MAX_CLIENTS + 1; i++) ServerWatutClientStats.accept(new Object(), 1, 0, 0, 0);
        check(ServerWatutClientStats.snapshot().reportingConnections() == ServerWatutClientStats.MAX_CLIENTS, "bounded connection retention");
        ServerWatutClientStats.clear();
    }
    private static void resetIsolation() {
        WatutTrafficStats.reset(); WatutTrafficStats.clientSuppressed(10); WatutTrafficStats.clientSent(30);
        WatutTrafficStats.serverSuppressed(20, 2); WatutTrafficStats.serverSent(50);
        WatutTrafficStats.resetClient();
        check(WatutTrafficStats.snapshot().serverSavedPayloadBytes() == 40 && WatutTrafficStats.snapshot().serverSentPayloadBytes() == 50,
                "client disconnect cannot reset integrated server counters");
        WatutTrafficStats.clientSuppressed(7); WatutTrafficStats.resetServer();
        check(WatutTrafficStats.snapshot().clientSavedPayloadBytes() == 7, "server reset cannot reset client cumulative reports");
        WatutTrafficStats.reset();
    }
    private static void expect(long bytes, long frames, long sent) {
        var value = ServerWatutClientStats.snapshot();
        check(value.savedPayloadBytes() == bytes && value.suppressedFrames() == frames && value.sentPayloadBytes() == sent, "exact aggregates");
    }
    private static void reportMapping() throws Exception {
        ServerWatutClientStats.clear(); WatutTrafficStats.reset();
        ServerWatutClientStats.accept(new Object(), 1, 123, 2, 300);
        WatutTrafficStats.clientSuppressed(7); WatutTrafficStats.clientSent(17);
        WatutTrafficStats.serverSuppressed(19, 2); WatutTrafficStats.serverSent(71); WatutTrafficStats.nativeImage();
        var method = com.PinkCats.bandwidthoptimizer.report.unified.UnifiedBandwidthReportCollector.class.getDeclaredMethod("watutSection");
        method.setAccessible(true);
        var section = (com.PinkCats.bandwidthoptimizer.report.unified.UnifiedBandwidthReport.Section) method.invoke(null);
        java.util.Map<String, Long> values = new java.util.HashMap<>();
        for (var metric : section.metrics()) values.put(metric.id(), metric.value());
        check(values.get("reported_client_saved_payload_bytes") == 123 && values.get("client_saved_payload_bytes") == 7,
                "report separates server aggregation from local client counters");
        check(values.get("reported_client_sent_payload_bytes") == 300 && values.get("server_sent_payload_bytes") == 71,
                "matching sent denominators exported");
        check(values.get("server_saved_payload_bytes") == 38 && values.get("native_image_packets") == 1,
                "server exact savings and fallback coverage exported");
        check(values.get("reporting_client_connections") == 1, "report exports independent client coverage");
        ServerWatutClientStats.clear(); WatutTrafficStats.reset();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
