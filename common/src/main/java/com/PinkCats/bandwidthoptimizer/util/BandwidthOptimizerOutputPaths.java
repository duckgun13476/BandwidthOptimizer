package com.PinkCats.bandwidthoptimizer.util;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class BandwidthOptimizerOutputPaths {

    public static final String OUTPUT_DIRECTORY_PROPERTY = "bandwidthoptimizer.outputDirectory";
    public static final String SHARED_OUTPUT_DIRECTORY_PROPERTY = "bandwidthoptimizer.sharedOutputDirectory";
    public static final String NATIVE_DRIVE_DIRECTORY_PROPERTY = "bandwidthoptimizer.nativeDriveDirectory";
    private static final String DEFAULT_OUTPUT_DIRECTORY = "bandwidthoptimizer-native";
    private static final String DEFAULT_NATIVE_DRIVE_DIRECTORY = "drive";
    private static final String[] LEGACY_OUTPUT_DIRECTORIES = {
            "transport-bypass-report",
            "transport-report",
            "transport-packet-rank",
            "chunk-boundary-bandwidth"
    };
    private static final String[] LEGACY_OUTPUT_FILES = {
            "chunk-hotspot-stats.properties",
            "chunk-hotspot-stats.properties.tmp",
            "transport-telemetry-dump.properties",
            "bo-runall-player-logged-in.marker",
            "bo-runall-chunk-hotspot-path-completed.marker",
            "bo-watch-boundary-refresh-patch.marker",
            "bo-runall-capture-reset.request"
    };
    private static final String[] LEGACY_JSONL_FILES = {
            "send.jsonl",
            "receive.jsonl"
    };
    private static final String LEGACY_BYPASS_REPORT_DIRECTORY = "transport-bypass-report";
    private static final String LEGACY_BYPASS_REPORT_FILE = "latest-bypass-report.txt";

    private BandwidthOptimizerOutputPaths() {}

    public static Path outputRoot() {
        return readPathProperty(OUTPUT_DIRECTORY_PROPERTY, DEFAULT_OUTPUT_DIRECTORY);
    }

    public static Path sharedOutputRoot() {
        return readPathProperty(SHARED_OUTPUT_DIRECTORY_PROPERTY, outputRoot().toString());
    }

    public static Path nativeDriveDirectory() {
        String configured = System.getProperty(NATIVE_DRIVE_DIRECTORY_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return outputRoot().resolve(DEFAULT_NATIVE_DRIVE_DIRECTORY);
    }


    public static LegacyMigrationResult migrateLegacyDefaultLayout() {
        Path outputRoot = outputRoot().toAbsolutePath().normalize();
        if (!DEFAULT_OUTPUT_DIRECTORY.equals(fileName(outputRoot))) {
            return LegacyMigrationResult.empty();
        }
        Path legacyRoot = outputRoot.getParent();
        if (legacyRoot == null || !Files.isDirectory(legacyRoot)) {
            return LegacyMigrationResult.empty();
        }
        LegacyMigrationCounter counter = new LegacyMigrationCounter();
        migrateLegacyOutputDirectories(legacyRoot, outputRoot, counter);
        migrateLegacyOutputFiles(legacyRoot, outputRoot, counter);
        deleteLegacyJsonlFiles(legacyRoot, counter);
        cleanupLegacyNativeDrive(legacyRoot, outputRoot, counter);
        cleanupLegacyOutputRootArtifacts(outputRoot, counter);
        return counter.toResult();
    }

    public static Path resolve(String first, String... more) {
        Path path = Path.of(first, more);
        return path.isAbsolute() ? path : outputRoot().resolve(path);
    }

    public static Path resolveDirectory(String directory) {
        if (directory == null || directory.isBlank()) {
            return outputRoot();
        }
        Path path = Path.of(directory);
        return path.isAbsolute() ? path : outputRoot().resolve(path);
    }

    public static Path resolveShared(String first, String... more) {
        Path path = Path.of(first, more);
        return path.isAbsolute() ? path : sharedOutputRoot().resolve(path);
    }

    private static Path readPathProperty(String propertyName, String fallback) {
        String configured = System.getProperty(propertyName);
        if (configured == null || configured.isBlank()) {
            return Path.of(fallback);
        }
        return Path.of(configured);
    }

    private static void migrateLegacyOutputDirectories(Path legacyRoot, Path outputRoot, LegacyMigrationCounter counter) {
        for (String directoryName : LEGACY_OUTPUT_DIRECTORIES) {
            Path legacyDirectory = legacyRoot.resolve(directoryName);
            Path targetDirectory = outputRoot.resolve(directoryName);
            migrateDirectoryContents(legacyDirectory, targetDirectory, counter);
        }
    }

    private static void migrateLegacyOutputFiles(Path legacyRoot, Path outputRoot, LegacyMigrationCounter counter) {
        for (String fileName : LEGACY_OUTPUT_FILES) {
            moveLegacyFile(legacyRoot.resolve(fileName), outputRoot.resolve(fileName), counter);
        }
    }

    private static void deleteLegacyJsonlFiles(Path legacyRoot, LegacyMigrationCounter counter) {
        for (String fileName : LEGACY_JSONL_FILES) {
            deleteIfRegularFile(legacyRoot.resolve(fileName), counter);
        }
    }


    private static void cleanupLegacyNativeDrive(Path legacyRoot, Path outputRoot, LegacyMigrationCounter counter) {
        Path legacyDrive = legacyRoot.resolve(DEFAULT_NATIVE_DRIVE_DIRECTORY);
        Path targetDrive = outputRoot.resolve(DEFAULT_NATIVE_DRIVE_DIRECTORY);
        if (!Files.isDirectory(legacyDrive) || !hasZstdDriverFile(targetDrive)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(legacyDrive)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> isZstdDriverFileName(fileName(path)))
                    .forEach(path -> deleteIfRegularFile(path, counter));
        } catch (IOException ignored) {
            counter.failedOperations++;
        }
        deleteEmptyDirectories(legacyDrive, counter);
    }

    private static void cleanupLegacyOutputRootArtifacts(Path outputRoot, LegacyMigrationCounter counter) {
        deleteLegacyBypassTxtReports(outputRoot, counter);
        deleteLegacyRootZstdDrivers(outputRoot, counter);
    }

    private static void deleteLegacyBypassTxtReports(Path outputRoot, LegacyMigrationCounter counter) {
        Path reportDirectory = outputRoot.resolve(LEGACY_BYPASS_REPORT_DIRECTORY);
        deleteIfRegularFile(reportDirectory.resolve(LEGACY_BYPASS_REPORT_FILE), counter);
        if (!Files.isDirectory(reportDirectory)) {
            return;
        }
        try (Stream<Path> paths = Files.list(reportDirectory)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> isLegacyBypassReportFileName(fileName(path)))
                    .forEach(path -> deleteIfRegularFile(path, counter));
        } catch (IOException ignored) {
            counter.failedOperations++;
        }
    }

    private static void deleteLegacyRootZstdDrivers(Path outputRoot, LegacyMigrationCounter counter) {
        if (!Files.isDirectory(outputRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.list(outputRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> isZstdDriverFileName(fileName(path)))
                    .forEach(path -> deleteIfRegularFile(path, counter));
        } catch (IOException ignored) {
            counter.failedOperations++;
        }
    }

    private static void migrateDirectoryContents(Path legacyDirectory, Path targetDirectory, LegacyMigrationCounter counter) {
        if (!Files.isDirectory(legacyDirectory)) {
            return;
        }
        ArrayList<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(legacyDirectory)) {
            paths.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException ignored) {
            counter.failedOperations++;
            return;
        }
        for (Path legacyFile : files) {
            Path relativePath = legacyDirectory.relativize(legacyFile);
            moveLegacyFile(legacyFile, targetDirectory.resolve(relativePath), counter);
        }
        deleteEmptyDirectories(legacyDirectory, counter);
    }

    private static void moveLegacyFile(Path legacyFile, Path targetFile, LegacyMigrationCounter counter) {
        if (!Files.isRegularFile(legacyFile)) {
            return;
        }
        try {
            Path parent = targetFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path finalTarget = Files.exists(targetFile) ? conflictTarget(targetFile) : targetFile;
            Files.move(legacyFile, finalTarget, StandardCopyOption.ATOMIC_MOVE);
            counter.movedOutputFiles++;
        } catch (IOException firstFailure) {
            try {
                Path parent = targetFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path finalTarget = Files.exists(targetFile) ? conflictTarget(targetFile) : targetFile;
                Files.move(legacyFile, finalTarget, StandardCopyOption.REPLACE_EXISTING);
                counter.movedOutputFiles++;
            } catch (IOException ignored) {
                counter.failedOperations++;
            }
        }
    }

    private static Path conflictTarget(Path targetFile) {
        String name = fileName(targetFile);
        int extensionIndex = name.lastIndexOf('.');
        String baseName = extensionIndex <= 0 ? name : name.substring(0, extensionIndex);
        String extension = extensionIndex <= 0 ? "" : name.substring(extensionIndex);
        Path parent = targetFile.getParent();
        String suffix = ".legacy-" + System.currentTimeMillis();
        Path candidate = (parent == null ? Path.of(baseName + suffix + extension) : parent.resolve(baseName + suffix + extension));
        int attempt = 1;
        while (Files.exists(candidate)) {
            String numberedSuffix = suffix + "-" + attempt;
            candidate = parent == null
                    ? Path.of(baseName + numberedSuffix + extension)
                    : parent.resolve(baseName + numberedSuffix + extension);
            attempt++;
        }
        return candidate;
    }

    private static void deleteIfRegularFile(Path path, LegacyMigrationCounter counter) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            Files.deleteIfExists(path);
            counter.deletedLegacyFiles++;
        } catch (IOException ignored) {
            counter.failedOperations++;
        }
    }

    private static boolean hasZstdDriverFile(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .map(BandwidthOptimizerOutputPaths::fileName)
                    .anyMatch(BandwidthOptimizerOutputPaths::isZstdDriverFileName);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean isZstdDriverFileName(String fileName) {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if ("zstd-jni-1.5.7-7.jar".equals(lowerName)) {
            return true;
        }
        return lowerName.matches("libzstd-jni-1\\.5\\.7-\\d+\\.(dll|so|dylib)");
    }

    private static boolean isLegacyBypassReportFileName(String fileName) {
        if (LEGACY_BYPASS_REPORT_FILE.equals(fileName)) {
            return true;
        }
        return fileName.matches("latest-bypass-report\\.legacy-\\d+(-\\d+)?\\.txt");
    }

    private static void deleteEmptyDirectories(Path rootDirectory, LegacyMigrationCounter counter) {
        if (!Files.isDirectory(rootDirectory)) {
            return;
        }
        List<Path> directories = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(rootDirectory)) {
            paths.filter(Files::isDirectory).forEach(directories::add);
        } catch (IOException ignored) {
            counter.failedOperations++;
            return;
        }
        directories.sort(Comparator.reverseOrder());
        for (Path directory : directories) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                if (!entries.iterator().hasNext()) {
                    Files.deleteIfExists(directory);
                }
            } catch (IOException ignored) {
                counter.failedOperations++;
            }
        }
    }


    private static String fileName(Path path) {
        Path fileName = path == null ? null : path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }


    public record LegacyMigrationResult(int movedOutputFiles, int deletedLegacyFiles, int failedOperations) {

        public static LegacyMigrationResult empty() {
            return new LegacyMigrationResult(0, 0, 0);
        }

        public boolean changed() {
            return this.movedOutputFiles > 0 || this.deletedLegacyFiles > 0 || this.failedOperations > 0;
        }
    }

    private static final class LegacyMigrationCounter {

        private int movedOutputFiles;
        private int deletedLegacyFiles;
        private int failedOperations;

        private LegacyMigrationResult toResult() {
            return new LegacyMigrationResult(this.movedOutputFiles, this.deletedLegacyFiles, this.failedOperations);
        }
    }
}
