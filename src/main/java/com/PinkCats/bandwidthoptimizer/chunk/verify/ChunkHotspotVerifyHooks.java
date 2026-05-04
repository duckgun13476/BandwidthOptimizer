package com.PinkCats.bandwidthoptimizer.chunk.verify;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class ChunkHotspotVerifyHooks {

    public static final Path REPORT_OUTPUT_PATH = Path.of("chunk-hotspot-stats.properties");

    private static final Object LOCK = new Object();
    private static long lastWriteFailureLogAtMillis;

    private ChunkHotspotVerifyHooks() {
    }

    public static void initializeOutputFiles() {
        synchronized (LOCK) {
            ChunkHotspotStats.reset();
            if (ChunkDiagnosticRuntimeConfig.isEnabled()) {
                writeCurrentReportUnsafe();
            }
        }
    }

    public static void resetOutputFiles() {
        synchronized (LOCK) {
            ChunkHotspotStats.reset();
            if (ChunkDiagnosticRuntimeConfig.isEnabled()) {
                writeCurrentReportUnsafe();
            }
        }
    }

    public static void flushCurrentReport() {
        if (!ChunkDiagnosticRuntimeConfig.isEnabled()) {
            return;
        }
        synchronized (LOCK) {
            try {
                writeCurrentReportUnsafe();
            } catch (IllegalStateException exception) {
                logWriteFailure(exception);
            }
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
        IOException lastException = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                moveOutputFile(temporaryOutputPath, outputPath);
                return;
            } catch (AccessDeniedException exception) {
                lastException = exception;
                sleepBeforeRetry(attempt);
            } catch (IOException exception) {
                lastException = exception;
                break;
            }
        }

        try {
            Files.copy(
                    temporaryOutputPath,
                    outputPath,
                    StandardCopyOption.REPLACE_EXISTING
            );
            Files.deleteIfExists(temporaryOutputPath);
        } catch (IOException exception) {
            if (lastException != null) {
                exception.addSuppressed(lastException);
            }
            throw exception;
        }
    }

    private static void moveOutputFile(Path temporaryOutputPath, Path outputPath) throws IOException {
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

    private static void sleepBeforeRetry(int attempt) {
        long delayMillis = 10L * (attempt + 1L);
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private static void logWriteFailure(IllegalStateException exception) {
        long now = System.currentTimeMillis();
        if (now - lastWriteFailureLogAtMillis < 3000L) {
            return;
        }
        lastWriteFailureLogAtMillis = now;
        Bandwidthoptimizer.LOGGER.warn(
                "[ChunkHotspotVerify] Failed to flush report, keep transport running. path={}, reason={}",
                REPORT_OUTPUT_PATH.toAbsolutePath(),
                exception.getMessage()
        );
    }
}
