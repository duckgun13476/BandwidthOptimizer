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
        ConnectionDisconnectClassifier.onChannelInactive(channel);
        ConnectionDisconnectClassifier.onChannelInactive(channel);
        channel.finishAndReleaseAll();
        Path integration = integrationRoot.resolve("diagnostics/connection/failures.jsonl");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while ((!Files.exists(integration) || Files.size(integration) == 0) && System.nanoTime() < deadline) Thread.sleep(10);
        var actual = JsonParser.parseString(Files.readAllLines(integration).get(0)).getAsJsonObject();
        require(actual.getAsJsonObject("reasons").getAsJsonObject("CONNECTION_RESET")
                .get("process_count").getAsLong() == 1, "inactive hook double counted channel");
        System.out.println("Connection failure summary regression passed");
    }

    private static Decision decision(Category category) {
        return new Decision(category, RecoveryPolicy.MANUAL_REVIEW, "test", "", "example.Failure", "fixture", 0);
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
