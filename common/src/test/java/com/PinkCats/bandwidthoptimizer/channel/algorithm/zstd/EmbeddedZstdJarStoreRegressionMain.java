package com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
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
        String expectedHash = sha256(complete);
        List<String> warnings = new ArrayList<>();
        ClassLoader goodSource = source(complete);
        ClassLoader badSource = source(truncated);
        try {
            Files.write(target, truncated);
            require(!EmbeddedZstdJarStore.isUsable(target), "truncated cache passed validation");
            IOException sourceFailure = expectFailure(() -> install(badSource, target, false, complete, expectedHash, warnings));
            require(sourceFailure.getMessage().contains("source=test-outer.jar!/test/zstd.jar"),
                    "failure omitted embedded source location");
            require(sourceFailure.getMessage().contains("actualBytes=" + truncated.length),
                    "failure omitted embedded resource's actual length");
            require(sourceFailure.getMessage().contains("cache=" + target.toAbsolutePath()),
                    "failure omitted cache location");
            require(Arrays.equals(truncated, Files.readAllBytes(target)), "failed extraction changed cache");

            install(goodSource, target, false, complete, expectedHash, warnings);
            require(EmbeddedZstdJarStore.isUsable(target), "valid resource did not repair corrupt cache");
            require(Arrays.equals(complete, Files.readAllBytes(target)), "repair did not install complete resource");
            require(warnings.stream().anyMatch(warning -> warning.contains("Repaired BO private zstd-jni disk cache")),
                    "cache repair did not report its location");

            warnings.clear();
            install(badSource, target, false, complete, expectedHash, warnings);
            require(Arrays.equals(complete, Files.readAllBytes(target)), "valid cache was overwritten");
            require(warnings.size() == 1 && warnings.get(0).contains("BO jar embedded zstd-jni is damaged"),
                    "corrupt BO jar was not reported when a valid cache masked the failure");

            Path backup = directory.resolve("backup.jar");
            expectFailure(() -> install(badSource, backup, true, complete, expectedHash, warnings));
            require(!Files.exists(backup), "failed backup extraction left a partial jar");
            install(goodSource, backup, true, complete, expectedHash, warnings);
            require(EmbeddedZstdJarStore.isUsable(backup), "unique backup extraction failed");
            if (args.length > 0) {
                verifyReportedArchive(Path.of(args[0]), directory);
            }
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

    private static void verifyReportedArchive(Path archive, Path directory) throws IOException {
        String embeddedResource = "META-INF/bandwidthoptimizer/libs/zstd-jni-1.5.7-7.jar";
        String expectedHash = "D4725BCE2C3E4F3DF54CBEA8BF5966E240872BB7BA7658572EDEDAEF28A01D83";
        Path outerJar = directory.resolve("reported-bo.jar");
        try {
            String outerName;
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                ZipEntry entry = zip.stream().filter(candidate -> candidate.getName().startsWith("BandwidthOptimizer-")
                        && candidate.getName().endsWith("-forge-1.20.1.jar")).findFirst()
                        .orElseThrow(() -> new AssertionError("reported BO jar missing from mods.zip"));
                outerName = entry.getName();
                try (InputStream input = zip.getInputStream(entry)) {
                    Files.copy(input, outerJar);
                }
            }
            URL url = outerJar.toUri().toURL();
            try (URLClassLoader source = new URLClassLoader(new URL[]{url}, null)) {
                IOException failure = expectFailure(() -> EmbeddedZstdJarStore.install(source, embeddedResource,
                        archive + "!/" + outerName + "!/" + embeddedResource,
                        directory.resolve("reported-cache.jar"), false, 7_400_645L, expectedHash, warning -> {}));
                require(failure.getMessage().contains("BO jar embedded zstd-jni is damaged"),
                        "reported jar did not identify corrupt embedded dependency");
                require(failure.getMessage().contains("actualBytes=1609853"),
                        "reported jar error omitted actual embedded resource length: " + failure.getMessage());
                require(failure.getMessage().contains("actualSha256=8EBC4F57BB38F0FA32029E1C07835420B72E0B2A73DB59B4313253E285B26BEE"),
                        "reported jar error omitted actual embedded resource hash: " + failure.getMessage());
                System.out.println("reported-mods-zip: " + failure.getMessage());
            }
        } finally {
            Files.deleteIfExists(outerJar);
        }
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

    private static Path install(ClassLoader source, Path target, boolean unique, byte[] expected,
                                String expectedHash, List<String> warnings) throws IOException {
        return EmbeddedZstdJarStore.install(source, RESOURCE, "test-outer.jar!/" + RESOURCE,
                target, unique, expected.length, expectedHash, warnings::add);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static IOException expectFailure(CheckedOperation operation) throws IOException {
        try {
            operation.run();
            throw new AssertionError("corrupt embedded resource was accepted");
        } catch (IOException expected) {
            return expected;
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
