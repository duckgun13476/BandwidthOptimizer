package com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class EmbeddedZstdJarStore {

    private static final long MAX_JAR_BYTES = 64L * 1024L * 1024L;
    private static final String COMPRESS_CLASS = "com/github/luben/zstd/ZstdCompressCtx.class";
    private static final String DECOMPRESS_CLASS = "com/github/luben/zstd/ZstdDecompressCtx.class";
    private static final String DIRECTIVE_CLASS = "com/github/luben/zstd/EndDirective.class";

    private EmbeddedZstdJarStore() {
    }

    static Path install(
            ClassLoader sourceClassLoader,
            String resource,
            String sourceLocation,
            Path target,
            boolean unique,
            long expectedBytes,
            String expectedSha256,
            Consumer<String> warning
    ) throws IOException {
        String cacheProblem = unique ? "new backup location" : inspectCache(target, expectedBytes, expectedSha256);
        String sourceProblem = inspectSource(sourceClassLoader, resource, expectedBytes, expectedSha256);
        if (cacheProblem == null) {
            if (sourceProblem != null) {
                warning.accept("BO jar embedded zstd-jni is damaged; using verified disk cache. source="
                        + sourceLocation + ", sourceIssue=" + sourceProblem + ", cache=" + target.toAbsolutePath());
            }
            return target;
        }
        if (sourceProblem != null) {
            throw new IOException("BO jar embedded zstd-jni is damaged and cannot repair cache: source="
                    + sourceLocation + ", sourceIssue=" + sourceProblem + ", cache=" + target.toAbsolutePath()
                    + ", cacheIssue=" + cacheProblem);
        }

        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "zstd-jni-", ".tmp");
        try {
            try (InputStream input = sourceClassLoader.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IOException("Missing embedded zstd-jni resource: " + resource);
                }
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            String extractedProblem = inspectCache(temporary, expectedBytes, expectedSha256);
            if (extractedProblem != null) {
                throw new IOException("BO jar embedded zstd-jni changed during extraction: source="
                        + sourceLocation + ", extractedIssue=" + extractedProblem + ", cache=" + target.toAbsolutePath());
            }
            try {
                if (unique) {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } else {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (FileAlreadyExistsException exception) {
                if (inspectCache(target, expectedBytes, expectedSha256) == null) {
                    return target;
                }
                throw exception;
            }
            if (!unique && Files.isRegularFile(target) && !"missing".equals(cacheProblem)) {
                warning.accept("Repaired BO private zstd-jni disk cache: cache=" + target.toAbsolutePath()
                        + ", oldIssue=" + cacheProblem + ", source=" + sourceLocation);
            }
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String inspectSource(ClassLoader loader, String resource, long expectedBytes, String expectedSha256) {
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                return "missing resource " + resource;
            }
            DigestResult result = digest(input, expectedBytes);
            return fingerprintProblem(result, expectedBytes, expectedSha256);
        } catch (IOException exception) {
            return "unreadable resource: " + exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
    }

    private static String inspectCache(Path path, long expectedBytes, String expectedSha256) {
        if (!Files.isRegularFile(path)) {
            return "missing";
        }
        try (InputStream input = Files.newInputStream(path)) {
            return fingerprintProblem(digest(input, expectedBytes), expectedBytes, expectedSha256);
        } catch (IOException exception) {
            return "unreadable cache: " + exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
    }

    private static String fingerprintProblem(DigestResult actual, long expectedBytes, String expectedSha256) {
        if (actual.bytes() != expectedBytes || !actual.sha256().equalsIgnoreCase(expectedSha256)) {
            return "expectedBytes=" + expectedBytes + ", actualBytes=" + actual.bytes()
                    + ", expectedSha256=" + expectedSha256 + ", actualSha256=" + actual.sha256();
        }
        return null;
    }

    private static DigestResult digest(InputStream input, long expectedBytes) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM SHA-256 unavailable", exception);
        }
        long bytes = 0L;
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            bytes += read;
            if (bytes > Math.max(MAX_JAR_BYTES, expectedBytes)) {
                throw new IOException("embedded resource exceeds maximum expected size");
            }
            digest.update(buffer, 0, read);
        }
        return new DigestResult(bytes, HexFormat.of().withUpperCase().formatHex(digest.digest()));
    }

    private record DigestResult(long bytes, String sha256) {
    }

    static boolean isUsable(Path path) {
        return archiveProblem(path) == null;
    }

    private static String archiveProblem(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) == 0L || Files.size(path) > MAX_JAR_BYTES) {
                return "missing, empty, or oversized archive";
            }
            try (ZipFile zip = new ZipFile(path.toFile())) {
                if (zip.getEntry(COMPRESS_CLASS) == null || zip.getEntry(DECOMPRESS_CLASS) == null
                        || zip.getEntry(DIRECTIVE_CLASS) == null) {
                    return "required zstd binding classes are missing";
                }
                byte[] buffer = new byte[8192];
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    CRC32 crc = new CRC32();
                    long size = 0L;
                    try (InputStream input = zip.getInputStream(entry)) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            size += read;
                            if (size > MAX_JAR_BYTES) {
                                return "oversized archive entry: " + entry.getName();
                            }
                            crc.update(buffer, 0, read);
                        }
                    }
                    if (size != entry.getSize() || crc.getValue() != entry.getCrc()) {
                        return "archive entry size or CRC mismatch: " + entry.getName();
                    }
                }
                return null;
            }
        } catch (IOException | RuntimeException exception) {
            return "invalid ZIP: " + exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
    }
}
