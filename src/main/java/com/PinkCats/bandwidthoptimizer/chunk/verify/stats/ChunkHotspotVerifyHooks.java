package com.PinkCats.bandwidthoptimizer.chunk.verify.stats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class ChunkHotspotVerifyHooks {

    public static final Path REPORT_OUTPUT_PATH = Path.of("chunk-hotspot-stats.properties");

    private static final Object LOCK = new Object();

    private ChunkHotspotVerifyHooks() {
    }

    public static void initializeOutputFiles() {
        synchronized (LOCK) {
            ChunkHotspotStats.reset();
            writeCurrentReportUnsafe();
        }
    }

    public static void resetOutputFiles() {
        synchronized (LOCK) {
            ChunkHotspotStats.reset();
            writeCurrentReportUnsafe();
        }
    }

    public static void flushCurrentReport() {
        synchronized (LOCK) {
            writeCurrentReportUnsafe();
        }
    }

    private static void writeCurrentReportUnsafe() {
        try {
            Path parent = REPORT_OUTPUT_PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    REPORT_OUTPUT_PATH,
                    ChunkHotspotStats.snapshotReport().toPropertiesText(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to write chunk hotspot verify report: " + REPORT_OUTPUT_PATH.toAbsolutePath(),
                    exception
            );
        }
    }
}
