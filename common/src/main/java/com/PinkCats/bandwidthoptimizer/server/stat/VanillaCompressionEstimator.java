package com.PinkCats.bandwidthoptimizer.server.stat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.zip.Deflater;

public final class VanillaCompressionEstimator {

    private static final String THRESHOLD_PROPERTY = "bandwidthoptimizer.vanillaCompressionThreshold";
    private static final int DEFAULT_COMPRESSION_THRESHOLD = 256;
    private static final ThreadLocal<Deflater> DEFLATER = ThreadLocal.withInitial(Deflater::new);
    private static final ThreadLocal<byte[]> BUFFER = ThreadLocal.withInitial(() -> new byte[16 * 1024]);

    private static volatile int cachedThreshold = Integer.MIN_VALUE;

    private VanillaCompressionEstimator() {
    }

    // 按 MC 外层压缩和长度前缀估算原版真实线速，保留 raw 之外更接近公网流量的基线。
    public static int estimateOutboundFrameBytes(byte[] packetBytes, int fallbackPacketLength) {
        int packetLength = packetBytes == null ? Math.max(fallbackPacketLength, 0) : packetBytes.length;
        if (packetLength <= 0) {
            return 0;
        }
        int threshold = compressionThreshold();
        if (threshold < 0) {
            return varIntSize(packetLength) + packetLength;
        }
        if (packetBytes == null || packetLength < threshold) {
            int bodyLength = 1 + packetLength;
            return varIntSize(bodyLength) + bodyLength;
        }

        int compressedLength = compressedLength(packetBytes);
        int bodyLength = varIntSize(packetLength) + compressedLength;
        return varIntSize(bodyLength) + bodyLength;
    }

    public static int compressionThreshold() {
        int threshold = cachedThreshold;
        if (threshold != Integer.MIN_VALUE) {
            return threshold;
        }
        threshold = readConfiguredThreshold();
        cachedThreshold = threshold;
        return threshold;
    }

    public static void resetCachedThreshold() {
        cachedThreshold = Integer.MIN_VALUE;
    }

    private static int readConfiguredThreshold() {
        String propertyValue = System.getProperty(THRESHOLD_PROPERTY);
        Integer explicitThreshold = parseInteger(propertyValue);
        if (explicitThreshold != null) {
            return explicitThreshold;
        }

        Path serverPropertiesPath = Path.of("server.properties");
        if (Files.isRegularFile(serverPropertiesPath)) {
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(serverPropertiesPath, StandardCharsets.UTF_8)) {
                properties.load(reader);
                Integer fileThreshold = parseInteger(properties.getProperty("network-compression-threshold"));
                if (fileThreshold != null) {
                    return fileThreshold;
                }
            } catch (IOException ignored) {
                return DEFAULT_COMPRESSION_THRESHOLD;
            }
        }
        return DEFAULT_COMPRESSION_THRESHOLD;
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int compressedLength(byte[] packetBytes) {
        Deflater deflater = DEFLATER.get();
        deflater.reset();
        deflater.setInput(packetBytes);
        deflater.finish();

        int totalBytes = 0;
        byte[] buffer = BUFFER.get();
        while (!deflater.finished()) {
            int writtenBytes = deflater.deflate(buffer);
            if (writtenBytes <= 0) {
                break;
            }
            totalBytes += writtenBytes;
        }
        deflater.reset();
        return Math.max(totalBytes, 0);
    }

    private static int varIntSize(int value) {
        int bytes = 1;
        while ((value & -128) != 0) {
            value >>>= 7;
            bytes++;
        }
        return bytes;
    }
}
