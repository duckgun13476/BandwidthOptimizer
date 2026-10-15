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
        verifyRotation();
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

    private static void verifyRotation() throws Exception {
        Path directory = Files.createTempDirectory("bo-diagnostic-rotation");
        Path output = directory.resolve("failures.jsonl");
        try (BoundedDiagnosticFileWriter writer = new BoundedDiagnosticFileWriter(output, "rotate", 64, 8192, 64, 3)) {
            for (int i = 0; i < 20; i++) require(writer.offer("测试-" + i + "-xxxxxxxxxxxxxxxx"), "rotation offer rejected");
        }
        try (var files = Files.list(directory)) {
            var retained = files.toList();
            require(retained.size() == 4, "rotation retained wrong file count");
            for (Path file : retained) require(Files.size(file) <= 64, "UTF-8 disk cap exceeded");
        }
        String previous = Files.readString(output);
        try (BoundedDiagnosticFileWriter writer = new BoundedDiagnosticFileWriter(output, "restart", 8, 4096, 64, 3)) {
            require(writer.offer("new-process"), "restart offer rejected");
        }
        require(Files.readString(directory.resolve("failures.jsonl.1")).equals(previous), "restart lost previous file");
        require(Files.readString(output).strip().equals("new-process"), "restart did not write new file");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
