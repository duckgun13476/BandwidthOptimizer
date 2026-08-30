package com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd;

import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

final class ZstdRuntimeBridge {

    private static final String COMPRESS_CONTEXT_CLASS = "com.github.luben.zstd.ZstdCompressCtx";
    private static final String DECOMPRESS_CONTEXT_CLASS = "com.github.luben.zstd.ZstdDecompressCtx";
    private static final String END_DIRECTIVE_CLASS = "com.github.luben.zstd.EndDirective";
    private static final String NATIVE_CLASS = "com.github.luben.zstd.util.Native";
    private static final String EMBEDDED_ZSTD_RESOURCE = "META-INF/bandwidthoptimizer/libs/zstd-jni-1.5.7-7.jar";
    private static final String EMBEDDED_ZSTD_FILE_NAME = "zstd-jni-1.5.7-7.jar";
    private static final String WINDOWS_AMD64_NATIVE_RESOURCE = "win/amd64/libzstd-jni-1.5.7-7.dll";
    private static final String WINDOWS_AMD64_NATIVE_FILE_NAME = "libzstd-jni-1.5.7-7.dll";
    private static final String ZSTD_TEMP_FOLDER_PROPERTY = "ZstdTempFolder";
    private static final String DRIVER_BACKUP_DIRECTORY = "driver-backup";
    private static final String EMBEDDED_LIBS_DIRECTORY = "embedded-libs";
    private static final String WINDOWS_NATIVE_DIRECTORY = "native-libs";
    private static final int MAX_PRIMARY_NATIVE_DRIVER_FILES = 3;
    private static final int MAX_LEGACY_ROOT_NATIVE_DRIVER_FILES = 1;
    private static final DateTimeFormatter BACKUP_DIRECTORY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final Bindings BINDINGS = loadBindings();

    private ZstdRuntimeBridge() {
    }

    static Context createContext(int compressionLevel) {
        return BINDINGS.createContext(compressionLevel);
    }

    private static Bindings loadBindings() {
        Throwable firstFailure = null;
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            return bind(contextClassLoader);
        } catch (Throwable throwable) {
            firstFailure = throwable;
        }

        ClassLoader ownClassLoader = ZstdRuntimeBridge.class.getClassLoader();
        if (ownClassLoader != contextClassLoader) {
            try {
                return bind(ownClassLoader);
            } catch (Throwable throwable) {
                firstFailure.addSuppressed(throwable);
            }
        }

        try {
            return bindEmbedded(false);
        } catch (Throwable primaryEmbeddedFailure) {
            try {
                return bindEmbedded(true);
            } catch (Throwable backupEmbeddedFailure) {
                primaryEmbeddedFailure.addSuppressed(backupEmbeddedFailure);
                Throwable throwable = primaryEmbeddedFailure;
                IllegalStateException exception = new IllegalStateException("zstd-jni is unavailable", throwable);
                exception.addSuppressed(firstFailure);
                throw exception;
            }
        }
    }

    private static Bindings bind(ClassLoader classLoader) throws ReflectiveOperationException {
        Class<?> compressContextClass = Class.forName(COMPRESS_CONTEXT_CLASS, true, classLoader);
        Class<?> decompressContextClass = Class.forName(DECOMPRESS_CONTEXT_CLASS, true, classLoader);
        Class<?> endDirectiveClass = Class.forName(END_DIRECTIVE_CLASS, true, classLoader);
        Constructor<?> compressConstructor = compressContextClass.getConstructor();
        Constructor<?> decompressConstructor = decompressContextClass.getConstructor();
        Method setLevel = compressContextClass.getMethod("setLevel", int.class);
        Method compressStream = compressContextClass.getMethod(
                "compressDirectByteBufferStream",
                ByteBuffer.class,
                ByteBuffer.class,
                endDirectiveClass
        );
        Method decompressStream = decompressContextClass.getMethod(
                "decompressDirectByteBufferStream",
                ByteBuffer.class,
                ByteBuffer.class
        );
        Method compressReset = compressContextClass.getMethod("reset");
        Method decompressReset = decompressContextClass.getMethod("reset");
        Method compressClose = compressContextClass.getMethod("close");
        Method decompressClose = decompressContextClass.getMethod("close");
        Object continueDirective = endDirectiveClass.getField("CONTINUE").get(null);
        Object flushDirective = endDirectiveClass.getField("FLUSH").get(null);
        Object endDirective = endDirectiveClass.getField("END").get(null);
        return new Bindings(
                classLoader,
                compressConstructor,
                decompressConstructor,
                setLevel,
                compressStream,
                decompressStream,
                compressReset,
                decompressReset,
                compressClose,
                decompressClose,
                continueDirective,
                flushDirective,
                endDirective
        );
    }

    private static Bindings bindEmbedded(boolean backupDriver) throws IOException, ReflectiveOperationException {
        Path driverDirectory = backupDriver ? backupDriverDirectory() : BandwidthOptimizerOutputPaths.nativeDriveDirectory();
        String previousTempFolder = System.getProperty(ZSTD_TEMP_FOLDER_PROPERTY);
        System.setProperty(ZSTD_TEMP_FOLDER_PROPERTY, driverDirectory.toAbsolutePath().toString());
        try {
            ClassLoader embeddedClassLoader = createEmbeddedClassLoader(driverDirectory, backupDriver);
            if (isWindowsAmd64()) {
                loadEmbeddedWindowsNative(embeddedClassLoader, driverDirectory, backupDriver);
            }
            Bindings bindings = bind(embeddedClassLoader);
            if (backupDriver) {
                cleanupInactiveBackupDrivers(driverDirectory);
            } else {
                cleanupDriversAfterPrimarySuccess();
            }
            return bindings;
        } catch (Throwable failure) {
            if (backupDriver) {
                deleteDirectoryIfPossible(driverDirectory);
                cleanupInactiveBackupDrivers(null);
            }
            throw failure;
        } finally {
            restoreZstdTempFolder(previousTempFolder);
        }
    }

    private static boolean isWindowsAmd64() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")
                && "amd64".equalsIgnoreCase(System.getProperty("os.arch", ""));
    }

    private static void loadEmbeddedWindowsNative(
            ClassLoader embeddedClassLoader,
            Path driverDirectory,
            boolean forceUniqueFile
    ) throws IOException, ReflectiveOperationException {
        Path nativeDriver = writeEmbeddedWindowsNative(embeddedClassLoader, driverDirectory, forceUniqueFile);
        try {
            if (!Files.isRegularFile(nativeDriver) || Files.size(nativeDriver) <= 0L) {
                throw new IOException("Embedded zstd native driver disappeared before load: " + nativeDriver);
            }
            System.load(nativeDriver.toAbsolutePath().toString());
        } catch (UnsatisfiedLinkError error) {
            UnsatisfiedLinkError failure = new UnsatisfiedLinkError(
                    "Embedded zstd native load failed: path=" + nativeDriver
                            + ", exists=" + Files.isRegularFile(nativeDriver)
                            + ", bytes=" + safeFileSize(nativeDriver)
                            + ", reason=" + error.getMessage()
            );
            failure.initCause(error);
            throw failure;
        }

        Class<?> nativeClass = Class.forName(NATIVE_CLASS, true, embeddedClassLoader);
        nativeClass.getMethod("assumeLoaded").invoke(null);
    }

    private static Path writeEmbeddedWindowsNative(
            ClassLoader embeddedClassLoader,
            Path driverDirectory,
            boolean forceUniqueFile
    ) throws IOException {
        Path nativeDirectory = driverDirectory.resolve(WINDOWS_NATIVE_DIRECTORY);
        String suffix = forceUniqueFile ? "-" + Thread.currentThread().getId() : "";
        Path nativeDriver = nativeDirectory.resolve(WINDOWS_AMD64_NATIVE_FILE_NAME.replace(".dll", suffix + ".dll"));
        if (!forceUniqueFile && Files.isRegularFile(nativeDriver) && Files.size(nativeDriver) > 0L) {
            return nativeDriver;
        }

        Files.createDirectories(nativeDirectory);
        Path temporaryDriver = Files.createTempFile(nativeDirectory, "bo-zstd-", ".tmp");
        try (InputStream inputStream = embeddedClassLoader.getResourceAsStream(WINDOWS_AMD64_NATIVE_RESOURCE)) {
            if (inputStream == null) {
                throw new IOException("Missing embedded Windows zstd native resource: " + WINDOWS_AMD64_NATIVE_RESOURCE);
            }
            Files.copy(inputStream, temporaryDriver, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporaryDriver, nativeDriver, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryDriver, nativeDriver, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryDriver);
        }
        return nativeDriver;
    }

    private static long safeFileSize(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : -1L;
        } catch (IOException ignored) {
            return -1L;
        }
    }

    private static void restoreZstdTempFolder(String previousTempFolder) {
        if (previousTempFolder == null) {
            System.clearProperty(ZSTD_TEMP_FOLDER_PROPERTY);
            return;
        }
        System.setProperty(ZSTD_TEMP_FOLDER_PROPERTY, previousTempFolder);
    }

    private static ClassLoader createEmbeddedClassLoader(Path driverDirectory, boolean backupDriver) throws IOException {
        ClassLoader sourceClassLoader = ZstdRuntimeBridge.class.getClassLoader();
        Path embeddedJarPath = writeEmbeddedJar(sourceClassLoader, driverDirectory, backupDriver);
        URL embeddedJarUrl = embeddedJarPath.toUri().toURL();
        return new URLClassLoader(new URL[]{embeddedJarUrl}, ClassLoader.getPlatformClassLoader());
    }

    // 将嵌入的 zstd jar 写入驱动目录；主目录已有可读 jar 时直接复用，避免 Windows 文件占用导致覆盖失败。
    private static Path writeEmbeddedJar(ClassLoader sourceClassLoader, Path driverDirectory, boolean forceUniqueFile) throws IOException {
        Path embeddedJarPath = embeddedJarPath(driverDirectory, forceUniqueFile);
        if (!forceUniqueFile && Files.isRegularFile(embeddedJarPath) && Files.size(embeddedJarPath) > 0L) {
            return embeddedJarPath;
        }

        Files.createDirectories(embeddedJarPath.getParent());
        try (InputStream inputStream = sourceClassLoader.getResourceAsStream(EMBEDDED_ZSTD_RESOURCE)) {
            if (inputStream == null) {
                throw new IOException("Missing embedded zstd-jni resource: " + EMBEDDED_ZSTD_RESOURCE);
            }
            try {
                Files.copy(inputStream, embeddedJarPath, forceUniqueFile
                        ? new StandardCopyOption[0]
                        : new StandardCopyOption[]{StandardCopyOption.REPLACE_EXISTING});
            } catch (FileAlreadyExistsException exception) {
                if (Files.isRegularFile(embeddedJarPath) && Files.size(embeddedJarPath) > 0L) {
                    return embeddedJarPath;
                }
                throw exception;
            }
        }
        return embeddedJarPath;
    }

    private static Path embeddedJarPath(Path driverDirectory, boolean forceUniqueFile) {
        String fileName = forceUniqueFile
                ? "zstd-jni-1.5.7-7-" + Thread.currentThread().getId() + ".jar"
                : EMBEDDED_ZSTD_FILE_NAME;
        return driverDirectory.resolve(EMBEDDED_LIBS_DIRECTORY).resolve(fileName);
    }

    // 主驱动目录被旧进程占用时，退到独立 backup 目录，让 jar 与 native dll 都从新路径加载。
    private static Path backupDriverDirectory() throws IOException {
        Path backupRoot = BandwidthOptimizerOutputPaths.nativeDriveDirectory().resolve(DRIVER_BACKUP_DIRECTORY);
        Files.createDirectories(backupRoot);
        String directoryName = BACKUP_DIRECTORY_TIME_FORMAT.format(LocalDateTime.now())
                + "-" + Thread.currentThread().getId();
        Path backupDirectory = backupRoot.resolve(directoryName);
        Files.createDirectories(backupDirectory);
        return backupDirectory;
    }

    // Remove inactive fallback drivers; active Windows locks are left untouched.
    private static void cleanupDriversAfterPrimarySuccess() {
        cleanupPrimaryNativeDrivers();
        cleanupLegacyRootDrivers();
        cleanupInactiveBackupDrivers(null);
    }

    // 主驱动目录可用时，清理旧的 zstd native dll/so/dylib；当前被加载的文件删除失败会自动保留。
    private static void cleanupPrimaryNativeDrivers() {
        Path driverDirectory = BandwidthOptimizerOutputPaths.nativeDriveDirectory();
        if (!Files.isDirectory(driverDirectory)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.list(driverDirectory)) {
            java.util.List<Path> nativeFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(ZstdRuntimeBridge::isZstdNativeDriverFile)
                    .sorted((left, right) -> compareLastModifiedDescending(left, right))
                    .toList();
            for (int index = MAX_PRIMARY_NATIVE_DRIVER_FILES; index < nativeFiles.size(); index++) {
                try {
                    Files.deleteIfExists(nativeFiles.get(index));
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean isZstdNativeDriverFile(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String fileName = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return fileName.matches("libzstd-jni-1\\.5\\.7-\\d+\\.(dll|so|dylib)");
    }

    private static boolean isEmbeddedZstdJarFile(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String fileName = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return EMBEDDED_ZSTD_FILE_NAME.equals(fileName)
                || fileName.matches("zstd-jni-1\\.5\\.7-7-\\d+\\.jar");
    }

    // 新版 drive 可用后，保守清理旧版 native 根目录中遗留的 zstd 驱动缓存。
    private static void cleanupLegacyRootDrivers() {
        Path outputRoot = BandwidthOptimizerOutputPaths.outputRoot();
        Path primaryDrive = BandwidthOptimizerOutputPaths.nativeDriveDirectory();
        if (!Files.isDirectory(outputRoot) || outputRoot.normalize().equals(primaryDrive.normalize())) {
            return;
        }
        cleanupLegacyRootNativeFiles(outputRoot);
        cleanupLegacyRootEmbeddedLibs(outputRoot.resolve(EMBEDDED_LIBS_DIRECTORY));
    }

    // 根目录只保留极少数最新 native 文件，避免误删当前异常回退仍可能占用的旧文件。
    private static void cleanupLegacyRootNativeFiles(Path outputRoot) {
        try (java.util.stream.Stream<Path> paths = Files.list(outputRoot)) {
            java.util.List<Path> nativeFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(ZstdRuntimeBridge::isZstdNativeDriverFile)
                    .sorted((left, right) -> compareLastModifiedDescending(left, right))
                    .toList();
            for (int index = MAX_LEGACY_ROOT_NATIVE_DRIVER_FILES; index < nativeFiles.size(); index++) {
                try {
                    Files.deleteIfExists(nativeFiles.get(index));
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    // 旧根目录 embedded-libs 只删除确定属于 zstd-jni 的 jar；目录非空则保留。
    private static void cleanupLegacyRootEmbeddedLibs(Path embeddedLibsDirectory) {
        if (!Files.isDirectory(embeddedLibsDirectory)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.list(embeddedLibsDirectory)) {
            paths.filter(Files::isRegularFile)
                    .filter(ZstdRuntimeBridge::isEmbeddedZstdJarFile)
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
            return;
        }
        try (java.util.stream.Stream<Path> remaining = Files.list(embeddedLibsDirectory)) {
            if (remaining.findAny().isEmpty()) {
                Files.deleteIfExists(embeddedLibsDirectory);
            }
        } catch (IOException ignored) {
        }
    }

    private static int compareLastModifiedDescending(Path left, Path right) {
        try {
            return Files.getLastModifiedTime(right).compareTo(Files.getLastModifiedTime(left));
        } catch (IOException ignored) {
            return right.getFileName().toString().compareTo(left.getFileName().toString());
        }
    }

    private static void cleanupInactiveBackupDrivers(Path activeDriverDirectory) {
        Path backupRoot = BandwidthOptimizerOutputPaths.nativeDriveDirectory().resolve(DRIVER_BACKUP_DIRECTORY);
        if (!Files.isDirectory(backupRoot)) {
            return;
        }

        try (java.util.stream.Stream<Path> paths = Files.list(backupRoot)) {
            java.util.List<Path> backupDirectories = paths
                    .filter(Files::isDirectory)
                    .sorted(java.util.Comparator.reverseOrder())
                    .toList();
            for (Path backupDirectory : backupDirectories) {
                if (activeDriverDirectory != null && backupDirectory.equals(activeDriverDirectory)) {
                    continue;
                }
                deleteDirectoryIfPossible(backupDirectory);
            }
        } catch (IOException ignored) {
        }
    }

    // 尝试删除单个 backup 目录，遇到 Windows 文件占用时保持原样。
    private static void deleteDirectoryIfPossible(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            java.util.List<Path> entries = paths
                    .sorted(java.util.Comparator.reverseOrder())
                    .toList();
            for (Path entry : entries) {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException ignored) {
                    return;
                }
            }
        } catch (IOException ignored) {
        }
    }

    static final class Context {

        private final Bindings bindings;
        private final Object compressContext;
        private final Object decompressContext;
        private boolean closed;

        private Context(Bindings bindings, int compressionLevel) {
            this.bindings = bindings;
            this.compressContext = bindings.newCompressContext(compressionLevel);
            this.decompressContext = bindings.newDecompressContext();
        }

        boolean compressDirectByteBufferStream(
                ByteBuffer targetBuffer,
                ByteBuffer sourceBuffer,
                StreamDirective directive
        ) {
            ensureOpen();
            return (Boolean) this.bindings.invoke(
                    this.bindings.compressStream,
                    this.compressContext,
                    targetBuffer,
                    sourceBuffer,
                    this.bindings.directive(directive)
            );
        }

        boolean decompressDirectByteBufferStream(ByteBuffer targetBuffer, ByteBuffer sourceBuffer) {
            ensureOpen();
            return (Boolean) this.bindings.invoke(
                    this.bindings.decompressStream,
                    this.decompressContext,
                    targetBuffer,
                    sourceBuffer
            );
        }

        void reset(int compressionLevel) {
            ensureOpen();
            this.bindings.invoke(this.bindings.compressReset, this.compressContext);
            this.bindings.invoke(this.bindings.setLevel, this.compressContext, compressionLevel);
            this.bindings.invoke(this.bindings.decompressReset, this.decompressContext);
        }

        void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            RuntimeException failure = null;
            try {
                this.bindings.invoke(this.bindings.compressClose, this.compressContext);
            } catch (RuntimeException exception) {
                failure = exception;
            }
            try {
                this.bindings.invoke(this.bindings.decompressClose, this.decompressContext);
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private void ensureOpen() {
            if (this.closed) {
                throw new IllegalStateException("zstd runtime context is closed");
            }
        }
    }

    enum StreamDirective {
        CONTINUE,
        FLUSH,
        END
    }

    private static final class Bindings {

        private final ClassLoader classLoader;
        private final Constructor<?> compressConstructor;
        private final Constructor<?> decompressConstructor;
        private final Method setLevel;
        private final Method compressStream;
        private final Method decompressStream;
        private final Method compressReset;
        private final Method decompressReset;
        private final Method compressClose;
        private final Method decompressClose;
        private final Object continueDirective;
        private final Object flushDirective;
        private final Object endDirective;

        private Bindings(
                ClassLoader classLoader,
                Constructor<?> compressConstructor,
                Constructor<?> decompressConstructor,
                Method setLevel,
                Method compressStream,
                Method decompressStream,
                Method compressReset,
                Method decompressReset,
                Method compressClose,
                Method decompressClose,
                Object continueDirective,
                Object flushDirective,
                Object endDirective
        ) {
            this.classLoader = classLoader;
            this.compressConstructor = compressConstructor;
            this.decompressConstructor = decompressConstructor;
            this.setLevel = setLevel;
            this.compressStream = compressStream;
            this.decompressStream = decompressStream;
            this.compressReset = compressReset;
            this.decompressReset = decompressReset;
            this.compressClose = compressClose;
            this.decompressClose = decompressClose;
            this.continueDirective = continueDirective;
            this.flushDirective = flushDirective;
            this.endDirective = endDirective;
        }

        private Object directive(StreamDirective directive) {
            return switch (directive) {
                case CONTINUE -> this.continueDirective;
                case FLUSH -> this.flushDirective;
                case END -> this.endDirective;
            };
        }

        private Context createContext(int compressionLevel) {
            return new Context(this, compressionLevel);
        }

        private Object newCompressContext(int compressionLevel) {
            Object context = construct(this.compressConstructor);
            invoke(this.setLevel, context, compressionLevel);
            return context;
        }

        private Object newDecompressContext() {
            return construct(this.decompressConstructor);
        }

        private Object construct(Constructor<?> constructor) {
            try {
                return constructor.newInstance();
            } catch (InstantiationException | IllegalAccessException exception) {
                throw new IllegalStateException("Failed to create zstd runtime context", exception);
            } catch (InvocationTargetException exception) {
                throw unwrap("Failed to create zstd runtime context", exception);
            }
        }

        private Object invoke(Method method, Object target, Object... arguments) {
            try {
                return method.invoke(target, arguments);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Failed to invoke zstd runtime", exception);
            } catch (InvocationTargetException exception) {
                throw unwrap("Failed to invoke zstd runtime", exception);
            }
        }

        private RuntimeException unwrap(String message, InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                return runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            return new IllegalStateException(message, cause);
        }
    }
}
