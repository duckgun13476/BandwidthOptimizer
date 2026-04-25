package com.PinkCats.bandwidthoptimizer.chunk.verify.stats;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
            Path temporaryOutputPath = resolveTemporaryOutputPath();
            Files.writeString(
                    temporaryOutputPath,
                    ChunkHotspotStats.snapshotReport().toPropertiesText(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            replaceOutputFile(temporaryOutputPath, REPORT_OUTPUT_PATH);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to write chunk hotspot verify report: " + REPORT_OUTPUT_PATH.toAbsolutePath(),
                    exception
            );
        }
    }

    private static Path resolveTemporaryOutputPath() {
        String fileName = REPORT_OUTPUT_PATH.getFileName() == null
                ? "chunk-hotspot-stats.properties"
                : REPORT_OUTPUT_PATH.getFileName().toString();
        return REPORT_OUTPUT_PATH.resolveSibling(fileName + ".tmp");
    }

    private static void replaceOutputFile(Path temporaryOutputPath, Path outputPath) throws IOException {
        try {
            Files.move(
                    temporaryOutputPath,
                    outputPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(
                    temporaryOutputPath,
                    outputPath,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }
}
