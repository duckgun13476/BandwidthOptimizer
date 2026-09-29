package com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
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

    static Path install(ClassLoader sourceClassLoader, String resource, Path target, boolean unique) throws IOException {
        if (!unique && isUsable(target)) {
            return target;
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
            if (!isUsable(temporary)) {
                throw new IOException("Embedded zstd-jni resource is incomplete or corrupt: " + resource);
            }
            try {
                if (unique) {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } else {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (FileAlreadyExistsException exception) {
                if (isUsable(target)) {
                    return target;
                }
                throw exception;
            }
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static boolean isUsable(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) == 0L || Files.size(path) > MAX_JAR_BYTES) {
                return false;
            }
            try (ZipFile zip = new ZipFile(path.toFile())) {
                if (zip.getEntry(COMPRESS_CLASS) == null || zip.getEntry(DECOMPRESS_CLASS) == null
                        || zip.getEntry(DIRECTIVE_CLASS) == null) {
                    return false;
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
                                return false;
                            }
                            crc.update(buffer, 0, read);
                        }
                    }
                    if (size != entry.getSize() || crc.getValue() != entry.getCrc()) {
                        return false;
                    }
                }
                return true;
            }
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }
}
