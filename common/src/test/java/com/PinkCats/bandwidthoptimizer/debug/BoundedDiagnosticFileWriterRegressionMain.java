package com.PinkCats.bandwidthoptimizer.debug;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class BoundedDiagnosticFileWriterRegressionMain {

    private BoundedDiagnosticFileWriterRegressionMain() {
    }

    public static void main(String[] args) throws Exception {
        verifyOrderedAsyncWrite();
        verifyBoundedDrop();
        verifyDiskFailureIsContained();
        System.out.println("BoundedDiagnosticFileWriter regression passed");
    }

    private static void verifyOrderedAsyncWrite() throws Exception {
        Path directory = Files.createTempDirectory("bo-diagnostic-writer-order");
        Path output = directory.resolve("capture.jsonl");
        BoundedDiagnosticFileWriter writer = new BoundedDiagnosticFileWriter(output, "test-order", 8, 4_096L);
        require(writer.offer("first"), "first line was rejected");
        require(writer.offer("second"), "second line was rejected");
        writer.close();

        List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
        require(lines.equals(List.of("first", "second")), "async writer changed line order or content");
    }

    private static void verifyBoundedDrop() {
        BoundedDiagnosticFileWriter writer = new BoundedDiagnosticFileWriter(
                Path.of("unused-overflow.jsonl"),
                "test-overflow",
                1,
                65L
        );
        require(!writer.offer("xx"), "line exceeding byte budget was accepted");
        require(writer.droppedLines() == 1L, "bounded drop was not counted");
        writer.close();
    }

    private static void verifyDiskFailureIsContained() throws Exception {
        Path regularFile = Files.createTempFile("bo-diagnostic-writer-parent", ".tmp");
        Path impossibleOutput = regularFile.resolve("capture.jsonl");
        BoundedDiagnosticFileWriter writer = new BoundedDiagnosticFileWriter(
                impossibleOutput,
                "test-failure",
                8,
                4_096L
        );
        require(writer.offer("must-not-reach-network-code"), "initial asynchronous offer was rejected");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (!writer.failed() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        require(writer.failed(), "disk open failure did not disable diagnostic output");
        require(!writer.offer("after-failure"), "disabled writer accepted another line");
        writer.close();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
