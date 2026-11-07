package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.connection.ConnectionDisconnectClassifier;
import com.PinkCats.bandwidthoptimizer.connection.ConnectionDisconnectClassifier.Category;
import com.PinkCats.bandwidthoptimizer.connection.ConnectionDisconnectClassifier.Decision;
import com.PinkCats.bandwidthoptimizer.connection.ConnectionDisconnectClassifier.RecoveryPolicy;
import com.google.gson.JsonParser;
import io.netty.channel.embedded.EmbeddedChannel;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ConnectionFailureReportRegressionMain {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("bo-failure-summary");
        Path output = directory.resolve("failures.jsonl");
        try (ConnectionFailureReport.Recorder recorder = new ConnectionFailureReport.Recorder(output, false)) {
            for (int i = 0; i < 150; i++) recorder.record(decision(Category.LOCAL_DISCONNECT), 1000);
            recorder.flush();
            require(!Files.exists(output), "normal close created a file");
            // Independently exhaust the detailed diagnostic lifetime budget.
            DiagnosticLog.resetLimitersForTest();
            int emitted = 0;
            for (int i = 0; i < 150; i++) if (DiagnosticLog.shouldEmitForTest(
                    DiagnosticToolRegistry.Tool.CONNECTION_CLOSE, "event=connection_close", i * 1000L)) emitted++;
            require(emitted == 100, "detailed diagnostic budget was not exhausted");
            for (int i = 0; i < 10000; i++) recorder.record(decision(Category.CONNECTION_RESET), 2000 + i);
            recorder.flush();
            recorder.flush(); // idle window must produce nothing
            recorder.record(decision(Category.READ_TIMEOUT), 80000);
            recorder.flush();
        }
        var lines = Files.readAllLines(output);
        require(lines.size() == 2, "flood or idle window created extra summaries");
        var first = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        require(first.getAsJsonObject("reasons").getAsJsonObject("CONNECTION_RESET")
                .get("window_count").getAsLong() == 10000, "lost aggregate flood counts after diagnostic exhaustion");
        var second = JsonParser.parseString(lines.get(1)).getAsJsonObject();
        require(second.getAsJsonObject("reasons").getAsJsonObject("CONNECTION_RESET")
                .get("process_count").getAsLong() == 10000, "lost cumulative counts");
        require(second.getAsJsonObject("reasons").getAsJsonObject("READ_TIMEOUT")
                .get("window_count").getAsLong() == 1, "new incident after idle was not recorded");

        // Exercise the actual inactive hook twice: one closed channel counts once.
        Path integrationRoot = directory.resolve("integration");
        System.setProperty("bandwidthoptimizer.outputDirectory", integrationRoot.toString());
        EmbeddedChannel channel = new EmbeddedChannel();
        ConnectionDisconnectClassifier.observeException(channel, new java.net.SocketException("Connection reset"));
        for (int i = 0; i < 1000; i++) ConnectionFailureReport.observe(channel, "fixture", "event-" + i);
        ConnectionDisconnectClassifier.observeException(channel, new io.netty.handler.codec.DecoderException(
                "decode failed", new java.util.zip.DataFormatException("uncompressed size is inaccurate")));
        ConnectionDisconnectClassifier.onChannelInactive(channel);
        ConnectionDisconnectClassifier.onChannelInactive(channel);
        channel.finishAndReleaseAll();
        Path integration = integrationRoot.resolve("diagnostics/connection/failures.jsonl");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while ((!Files.exists(integration) || Files.size(integration) == 0) && System.nanoTime() < deadline) Thread.sleep(10);
        HolderClose.close();
        var records = Files.readAllLines(integration).stream().map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
        var actual = records.stream().filter(line -> line.get("event").getAsString().equals("connection_failure_summary")).findFirst().orElseThrow();
        require(actual.getAsJsonObject("reasons").getAsJsonObject("PROTOCOL_FAILURE")
                .get("process_count").getAsLong() == 1, "inactive hook double counted channel");
        var detail = records.stream().filter(line -> line.get("event").getAsString().equals("connection_failure_detail")).findFirst().orElseThrow();
        require(detail.getAsJsonArray("recent_events").size() == 16, "event ring unbounded");
        require(detail.getAsJsonArray("first_exception").get(0).getAsJsonObject().get("message").getAsString().equals("Connection reset"), "first cause overwritten");
        require(detail.getAsJsonArray("last_exception").get(1).getAsJsonObject().get("message").getAsString().equals("uncompressed size is inaccurate"), "root cause missing");
        System.out.println("Connection failure summary regression passed");
    }

    private static Decision decision(Category category) {
        return new Decision(category, RecoveryPolicy.MANUAL_REVIEW, "test", "", "example.Failure", "fixture", 0);
    }
    private static final class HolderClose {
        static void close() throws Exception {
            var holder = Class.forName(ConnectionFailureReport.class.getName() + "$Holder");
            var field = holder.getDeclaredField("RECORDER");
            field.setAccessible(true);
            ((AutoCloseable) field.get(null)).close();
        }
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
