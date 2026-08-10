package com.PinkCats.bandwidthoptimizer.test;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.batch.KineticBatchLayer;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.mapping.KineticMapTableLayer;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd.KineticStreamingLayer;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/** Offline comparison only; it never changes a live transport session. */
public final class ZstdReplayComparisonMain {

    private static final String REPLAY_PATH_PROPERTY = "bandwidthoptimizer.replayRawPath";
    private static final long BATCH_WINDOW_MILLIS = 20L;
    private static final int COMPRESSION_LEVEL = 4;

    private ZstdReplayComparisonMain() {}

    public static void main(String[] args) throws IOException {
        Path replayPath = requireReplayPath();
        long startedNanos = System.nanoTime();
        long startHeapBytes = usedHeapBytes();
        ReplayMetrics metrics = replay(replayPath);
        long endHeapBytes = usedHeapBytes();
        forceGc();
        metrics.print(
                replayPath,
                System.nanoTime() - startedNanos,
                startHeapBytes,
                endHeapBytes,
                usedHeapBytes()
        );
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void forceGc() {
        for (int attempt = 0; attempt < 3; attempt++) {
            System.gc();
            System.runFinalization();
            LockSupport.parkNanos(50_000_000L);
        }
    }

    private static ReplayMetrics replay(Path replayPath) throws IOException {
        KineticBatchLayer batchLayer = new KineticBatchLayer();
        KineticMapTableLayer mappingEncoder = new KineticMapTableLayer();
        KineticMapTableLayer mappingEndDecoder = new KineticMapTableLayer();
        KineticMapTableLayer mappingFlushDecoder = new KineticMapTableLayer();
        try (KineticStreamingLayer endSender = new KineticStreamingLayer(COMPRESSION_LEVEL);
             KineticStreamingLayer endReceiver = new KineticStreamingLayer(COMPRESSION_LEVEL);
             KineticStreamingLayer flushSender = new KineticStreamingLayer(
                     COMPRESSION_LEVEL,
                     KineticStreamingLayer.FrameTermination.FLUSH
             );
             KineticStreamingLayer flushReceiver = new KineticStreamingLayer(
                     COMPRESSION_LEVEL,
                     KineticStreamingLayer.FrameTermination.FLUSH
             );
             BufferedReader reader = Files.newBufferedReader(replayPath)) {
            ReplayMetrics metrics = new ReplayMetrics();
            List<byte[]> pendingPackets = new ArrayList<>();
            long batchStartMillis = Long.MIN_VALUE;
            String line;
            while ((line = reader.readLine()) != null) {
                CapturedFrame frame = CapturedFrame.parse(line);
                if (frame == null) {
                    continue;
                }
                if (pendingPackets.isEmpty()) {
                    batchStartMillis = frame.capturedAtMillis();
                } else if (frame.capturedAtMillis() - batchStartMillis >= BATCH_WINDOW_MILLIS) {
                    flushBatch(
                            pendingPackets,
                            batchLayer,
                            mappingEncoder,
                            mappingEndDecoder,
                            mappingFlushDecoder,
                            endSender,
                            endReceiver,
                            flushSender,
                            flushReceiver,
                            metrics
                    );
                    pendingPackets = new ArrayList<>();
                    batchStartMillis = frame.capturedAtMillis();
                }
                pendingPackets.add(frame.packetBytes());
            }
            flushBatch(
                    pendingPackets,
                    batchLayer,
                    mappingEncoder,
                    mappingEndDecoder,
                    mappingFlushDecoder,
                    endSender,
                    endReceiver,
                    flushSender,
                    flushReceiver,
                    metrics
            );
            return metrics;
        }
    }

    private static void flushBatch(
            List<byte[]> packetBytes,
            KineticBatchLayer batchLayer,
            KineticMapTableLayer mappingEncoder,
            KineticMapTableLayer mappingEndDecoder,
            KineticMapTableLayer mappingFlushDecoder,
            KineticStreamingLayer endSender,
            KineticStreamingLayer endReceiver,
            KineticStreamingLayer flushSender,
            KineticStreamingLayer flushReceiver,
            ReplayMetrics metrics
    ) {
        if (packetBytes == null || packetBytes.isEmpty()) {
            return;
        }
        byte[] batchBytes = batchLayer.encodePacketBatch(packetBytes);
        byte[] mappingBytes = mappingEncoder.encodeLiteralWithTelemetry(batchBytes).bytes();
        byte[] endBytes = endSender.encode(mappingBytes);
        byte[] flushBytes = flushSender.encode(mappingBytes);

        verifyRestoredBatch(packetBytes, batchLayer, mappingEndDecoder.decodeWithTelemetry(endReceiver.decode(endBytes)).bytes(), "END");
        verifyRestoredBatch(packetBytes, batchLayer, mappingFlushDecoder.decodeWithTelemetry(flushReceiver.decode(flushBytes)).bytes(), "FLUSH");
        metrics.record(packetBytes, batchBytes.length, mappingBytes.length, endBytes.length, flushBytes.length);
    }

    private static void verifyRestoredBatch(
            List<byte[]> expectedPackets,
            KineticBatchLayer batchLayer,
            byte[] restoredBatchBytes,
            String profile
    ) {
        List<byte[]> restoredPackets = batchLayer.decodePacketBatch(restoredBatchBytes);
        if (restoredPackets.size() != expectedPackets.size()) {
            throw new IllegalStateException(profile + " replay changed packet count");
        }
        for (int index = 0; index < expectedPackets.size(); index++) {
            if (!Arrays.equals(expectedPackets.get(index), restoredPackets.get(index))) {
                throw new IllegalStateException(profile + " replay changed packet bytes at index " + index);
            }
        }
    }

    private static Path requireReplayPath() {
        String value = System.getProperty(REPLAY_PATH_PROPERTY, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing -D" + REPLAY_PATH_PROPERTY + "=<raw-frames.jsonl>");
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Replay input is not a file: " + path);
        }
        return path;
    }

    private record CapturedFrame(long capturedAtMillis, byte[] packetBytes) {
        private static CapturedFrame parse(String line) {
            if (line == null || line.isBlank()) {
                return null;
            }
            String timestamp = jsonString(line, "captured_at_ms");
            String payloadHex = jsonString(line, "payload_hex");
            if (timestamp == null || payloadHex == null) {
                throw new IllegalArgumentException("Replay row is missing required fields");
            }
            return new CapturedFrame(Long.parseLong(timestamp), decodeHex(payloadHex));
        }
    }

    private static String jsonString(String line, String fieldName) {
        String key = "\"" + fieldName + "\":";
        int start = line.indexOf(key);
        if (start < 0) {
            return null;
        }
        start += key.length();
        if (start >= line.length()) {
            return null;
        }
        if (line.charAt(start) == '\"') {
            int end = line.indexOf('\"', start + 1);
            return end < 0 ? null : line.substring(start + 1, end);
        }
        int end = line.indexOf(',', start);
        if (end < 0) {
            end = line.indexOf('}', start);
        }
        return end < 0 ? null : line.substring(start, end);
    }

    private static byte[] decodeHex(String value) {
        if ((value.length() & 1) != 0) {
            throw new IllegalArgumentException("Odd-length replay payload hex");
        }
        byte[] bytes = new byte[value.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Invalid replay payload hex");
            }
            bytes[index] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    private static final class ReplayMetrics {
        private long packetCount;
        private long batchCount;
        private long rawBytes;
        private long batchBytes;
        private long mappingBytes;
        private long endBytes;
        private long flushBytes;
        private long peakHeapBytes;

        private void record(List<byte[]> packets, int batchLength, int mappingLength, int endLength, int flushLength) {
            this.batchCount++;
            this.packetCount += packets.size();
            this.batchBytes += batchLength;
            this.mappingBytes += mappingLength;
            this.endBytes += endLength;
            this.flushBytes += flushLength;
            for (byte[] packet : packets) {
                this.rawBytes += packet.length;
            }
            this.peakHeapBytes = Math.max(this.peakHeapBytes, usedHeapBytes());
        }

        private void print(
                Path replayPath,
                long elapsedNanos,
                long startHeapBytes,
                long endHeapBytes,
                long afterGcHeapBytes
        ) {
            System.out.printf(Locale.ROOT, "zstd-replay path=%s%n", replayPath);
            System.out.printf(Locale.ROOT, "packets=%d batches=%d raw=%d batch=%d mapping=%d%n",
                    this.packetCount, this.batchCount, this.rawBytes, this.batchBytes, this.mappingBytes);
            System.out.printf(Locale.ROOT, "end=%d flush=%d flush_saved_vs_end=%d (%.3f%%)%n",
                    this.endBytes,
                    this.flushBytes,
                    this.endBytes - this.flushBytes,
                    percent(this.endBytes - this.flushBytes, this.endBytes));
            System.out.printf(Locale.ROOT, "end_saved_vs_raw=%.3f%% flush_saved_vs_raw=%.3f%%%n",
                    percent(this.rawBytes - this.endBytes, this.rawBytes),
                    percent(this.rawBytes - this.flushBytes, this.rawBytes));
            double elapsedSeconds = elapsedNanos / 1_000_000_000.0D;
            System.out.printf(Locale.ROOT,
                    "elapsed=%.3fs throughput=%.0f packets/s heap_start=%.2fMiB heap_peak=%.2fMiB heap_end=%.2fMiB heap_after_gc=%.2fMiB%n",
                    elapsedSeconds,
                    elapsedSeconds <= 0.0D ? 0.0D : this.packetCount / elapsedSeconds,
                    startHeapBytes / 1048576.0D,
                    this.peakHeapBytes / 1048576.0D,
                    endHeapBytes / 1048576.0D,
                    afterGcHeapBytes / 1048576.0D);
        }

        private static double percent(long numerator, long denominator) {
            return denominator <= 0L ? 0.0D : 100.0D * numerator / denominator;
        }
    }
}
