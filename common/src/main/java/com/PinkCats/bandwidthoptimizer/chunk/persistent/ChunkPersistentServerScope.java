package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;

public final class ChunkPersistentServerScope {

    private static final String SCOPE_FILE_NAME = "persistent-server-cache-scope.properties";
    private static final String SCOPE_ID_KEY = "scopeId";
    private static volatile String cachedScopeHash;

    private ChunkPersistentServerScope() {}

    public static String currentScopeHash() {
        String current = cachedScopeHash;
        if (isSafeScopeHash(current)) {
            return current;
        }

        synchronized (ChunkPersistentServerScope.class) {
            if (isSafeScopeHash(cachedScopeHash)) {
                return cachedScopeHash;
            }
            cachedScopeHash = loadOrCreateScopeHash();
            return cachedScopeHash;
        }
    }

    public static boolean isSafeScopeHash(String scopeHash) {
        return scopeHash != null && scopeHash.matches("[0-9a-fA-F]{64}");
    }

    private static String loadOrCreateScopeHash() {
        Path scopeFile = scopeFile();
        Properties properties = new Properties();
        if (Files.isRegularFile(scopeFile)) {
            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Files.readAllBytes(scopeFile))) {
                properties.load(inputStream);
                String loadedScopeId = properties.getProperty(SCOPE_ID_KEY, "");
                if (!loadedScopeId.isBlank()) {
                    return hashScopeId(loadedScopeId);
                }
            } catch (IOException exception) {
                Bandwidthoptimizer.LOGGER.warn(
                        "[ChunkPersistentScope][Load][Fail] scopeFile={}, reason={}",
                        scopeFile,
                        exception.toString()
                );
            }
        }

        String newScopeId = UUID.randomUUID().toString();
        properties.setProperty(SCOPE_ID_KEY, newScopeId);
        try {
            Files.createDirectories(scopeFile.getParent());
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                properties.store(outputStream, "BandwidthOptimizer persistent server cache scope");
                Files.write(scopeFile, outputStream.toByteArray());
            }
        } catch (IOException exception) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[ChunkPersistentScope][Create][Fail] scopeFile={}, reason={}",
                    scopeFile,
                    exception.toString()
            );
        }
        return hashScopeId(newScopeId);
    }

    private static String hashScopeId(String scopeId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(scopeId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    private static Path scopeFile() {
        return BandwidthOptimizerOutputPaths.outputRoot().resolve(SCOPE_FILE_NAME);
    }
}
