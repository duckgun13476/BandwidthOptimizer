package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerBoLogExportClientReceiver {

    private static final Map<String, PendingExport> PENDING = new ConcurrentHashMap<>();

    private ServerBoLogExportClientReceiver() {
    }

    public static void accept(
            String sessionId,
            String fileName,
            int chunkIndex,
            int totalChunks,
            int totalBytes,
            byte[] chunkBytes
    ) {
        if (sessionId == null || sessionId.isBlank() || totalChunks <= 0 || totalBytes < 0) {
            return;
        }
        PendingExport pending = PENDING.computeIfAbsent(
                sessionId,
                ignored -> new PendingExport(sanitizeFileName(fileName), totalChunks, totalBytes)
        );
        if (!pending.accept(chunkIndex, chunkBytes)) {
            return;
        }
        if (pending.isComplete()) {
            PENDING.remove(sessionId);
            write(pending);
        }
    }

    private static void write(PendingExport pending) {
        Path outputPath = BandwidthOptimizerOutputPaths.resolve(
                "diagnostics",
                "server-bo-logs",
                pending.fileName
        );
        try {
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(outputPath, pending.bytes);
            Bandwidthoptimizer.LOGGER.info("[BO:ServerLogExport] Saved server BO log export to {}", outputPath.toAbsolutePath());
        } catch (IOException exception) {
            Bandwidthoptimizer.LOGGER.warn("[BO:ServerLogExport] Failed to save server BO log export", exception);
        }
    }

    private static String sanitizeFileName(String fileName) {
        String safe = fileName == null || fileName.isBlank() ? "server-bo-log.log" : fileName;
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < safe.length(); index++) {
            char character = safe.charAt(index);
            if ((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '.'
                    || character == '-'
                    || character == '_') {
                builder.append(character);
            } else {
                builder.append('_');
            }
        }
        return builder.isEmpty() ? "server-bo-log.log" : builder.toString();
    }

    private static final class PendingExport {
        private final String fileName;
        private final byte[] bytes;
        private final byte[][] chunks;
        private final BitSet received;
        private int receivedCount;

        private PendingExport(String fileName, int totalChunks, int totalBytes) {
            this.fileName = fileName;
            this.bytes = new byte[totalBytes];
            this.chunks = new byte[totalChunks][];
            this.received = new BitSet(totalChunks);
        }

        private synchronized boolean accept(int chunkIndex, byte[] chunkBytes) {
            if (chunkIndex < 0 || chunkIndex >= chunks.length || chunkBytes == null) {
                return false;
            }
            if (received.get(chunkIndex)) {
                return false;
            }
            chunks[chunkIndex] = chunkBytes.clone();
            received.set(chunkIndex);
            receivedCount++;
            if (isComplete()) {
                int offset = 0;
                for (byte[] chunk : chunks) {
                    if (chunk == null || offset + chunk.length > bytes.length) {
                        return false;
                    }
                    System.arraycopy(chunk, 0, bytes, offset, chunk.length);
                    offset += chunk.length;
                }
                return offset == bytes.length;
            }
            return true;
        }

        private synchronized boolean isComplete() {
            return receivedCount == chunks.length;
        }
    }
}
