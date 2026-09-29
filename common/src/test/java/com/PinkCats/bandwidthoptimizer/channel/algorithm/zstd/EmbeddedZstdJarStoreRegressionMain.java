package com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class EmbeddedZstdJarStoreRegressionMain {

    private static final String RESOURCE = "test/zstd.jar";

    private EmbeddedZstdJarStoreRegressionMain() {
    }

    public static void main(String[] args) throws IOException {
        Path directory = Files.createTempDirectory("bo-zstd-store-regression-");
        Path target = directory.resolve("zstd-jni.jar");
        byte[] complete = completeJar();
        byte[] truncated = Arrays.copyOf(complete, complete.length - 20);
        ClassLoader goodSource = source(complete);
        ClassLoader badSource = source(truncated);
        try {
            Files.write(target, truncated);
            require(!EmbeddedZstdJarStore.isUsable(target), "truncated cache passed validation");
            expectFailure(() -> EmbeddedZstdJarStore.install(badSource, RESOURCE, target, false));
            require(Arrays.equals(truncated, Files.readAllBytes(target)), "failed extraction changed cache");

            EmbeddedZstdJarStore.install(goodSource, RESOURCE, target, false);
            require(EmbeddedZstdJarStore.isUsable(target), "valid resource did not repair corrupt cache");
            require(Arrays.equals(complete, Files.readAllBytes(target)), "repair did not install complete resource");

            EmbeddedZstdJarStore.install(badSource, RESOURCE, target, false);
            require(Arrays.equals(complete, Files.readAllBytes(target)), "valid cache was overwritten");

            Path backup = directory.resolve("backup.jar");
            expectFailure(() -> EmbeddedZstdJarStore.install(badSource, RESOURCE, backup, true));
            require(!Files.exists(backup), "failed backup extraction left a partial jar");
            EmbeddedZstdJarStore.install(goodSource, RESOURCE, backup, true);
            require(EmbeddedZstdJarStore.isUsable(backup), "unique backup extraction failed");
            System.out.println("embedded-zstd-jar-store: passed");
        } finally {
            Files.deleteIfExists(backupPath(directory));
            Files.deleteIfExists(target);
            Files.deleteIfExists(directory);
        }
    }

    private static Path backupPath(Path directory) {
        return directory.resolve("backup.jar");
    }

    private static byte[] completeJar() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String name : new String[]{"ZstdCompressCtx", "ZstdDecompressCtx", "EndDirective"}) {
                zip.putNextEntry(new ZipEntry("com/github/luben/zstd/" + name + ".class"));
                zip.write(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static ClassLoader source(byte[] bytes) {
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return RESOURCE.equals(name) ? new ByteArrayInputStream(bytes) : null;
            }
        };
    }

    private static void expectFailure(CheckedOperation operation) throws IOException {
        try {
            operation.run();
            throw new AssertionError("corrupt embedded resource was accepted");
        } catch (IOException expected) {
            // The invalid source must fail before a jar becomes loadable.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface CheckedOperation {
        void run() throws IOException;
    }
}
