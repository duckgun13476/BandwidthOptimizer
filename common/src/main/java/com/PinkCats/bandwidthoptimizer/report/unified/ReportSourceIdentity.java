package com.PinkCats.bandwidthoptimizer.report.unified;

import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HexFormat;

final class ReportSourceIdentity {
    private static final String FILE_NAME = ".source-fingerprint";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile String cached;

    private ReportSourceIdentity() {
    }

    static String loadOrCreate() throws IOException {
        String current = cached;
        if (current != null) {
            return current;
        }
        synchronized (ReportSourceIdentity.class) {
            current = cached;
            if (current != null) {
                return current;
            }
            Path directory = BandwidthOptimizerOutputPaths.resolveDirectory("reports");
            Files.createDirectories(directory);
            Path target = directory.resolve(FILE_NAME);
            current = readValid(target);
            if (current == null) {
                byte[] random = new byte[32];
                RANDOM.nextBytes(random);
                current = HexFormat.of().formatHex(random);
                Path temporary = target.resolveSibling(FILE_NAME + ".tmp");
                Files.writeString(temporary, current, StandardCharsets.US_ASCII);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            cached = current;
            return current;
        }
    }

    static String loadOrCreateUnchecked() {
        try {
            return loadOrCreate();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String readValid(Path target) throws IOException {
        if (!Files.isRegularFile(target)) {
            return null;
        }
        String value = Files.readString(target, StandardCharsets.US_ASCII).trim();
        return value.matches("[0-9a-f]{64}") ? value : null;
    }

    static void resetForTesting() {
        cached = null;
    }
}
