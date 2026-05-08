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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class ZstdRuntimeBridge {

    private static final String COMPRESS_CONTEXT_CLASS = "com.github.luben.zstd.ZstdCompressCtx";
    private static final String DECOMPRESS_CONTEXT_CLASS = "com.github.luben.zstd.ZstdDecompressCtx";
    private static final String END_DIRECTIVE_CLASS = "com.github.luben.zstd.EndDirective";
    private static final String EMBEDDED_ZSTD_RESOURCE = "META-INF/bandwidthoptimizer/libs/zstd-jni-1.5.7-7.jar";
    private static final String ZSTD_TEMP_FOLDER_PROPERTY = "ZstdTempFolder";
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
            return bind(createEmbeddedClassLoader());
        } catch (Throwable throwable) {
            IllegalStateException exception = new IllegalStateException("zstd-jni is unavailable", throwable);
            exception.addSuppressed(firstFailure);
            throw exception;
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
        Object continueDirective = endDirectiveClass.getField("CONTINUE").get(null);
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
                continueDirective,
                endDirective
        );
    }

    private static ClassLoader createEmbeddedClassLoader() throws IOException {
        ClassLoader sourceClassLoader = ZstdRuntimeBridge.class.getClassLoader();
        Path embeddedJarPath = embeddedJarPath();
        try (InputStream inputStream = sourceClassLoader.getResourceAsStream(EMBEDDED_ZSTD_RESOURCE)) {
            if (inputStream == null) {
                throw new IOException("Missing embedded zstd-jni resource: " + EMBEDDED_ZSTD_RESOURCE);
            }
            Files.createDirectories(embeddedJarPath.getParent());
            Files.copy(inputStream, embeddedJarPath, StandardCopyOption.REPLACE_EXISTING);
        }
        URL embeddedJarUrl = embeddedJarPath.toUri().toURL();
        return new URLClassLoader(new URL[]{embeddedJarUrl}, ClassLoader.getPlatformClassLoader());
    }

    private static Path embeddedJarPath() {
        String configuredTempFolder = System.getProperty(ZSTD_TEMP_FOLDER_PROPERTY);
        Path tempFolder = configuredTempFolder == null || configuredTempFolder.isBlank()
                ? BandwidthOptimizerOutputPaths.nativeDriveDirectory()
                : Path.of(configuredTempFolder);
        return tempFolder.resolve("embedded-libs").resolve("zstd-jni-1.5.7-7.jar");
    }

    static final class Context {

        private final Bindings bindings;
        private final Object compressContext;
        private final Object decompressContext;

        private Context(Bindings bindings, int compressionLevel) {
            this.bindings = bindings;
            this.compressContext = bindings.newCompressContext(compressionLevel);
            this.decompressContext = bindings.newDecompressContext();
        }

        boolean compressDirectByteBufferStream(ByteBuffer targetBuffer, ByteBuffer sourceBuffer, boolean end) {
            return (Boolean) this.bindings.invoke(
                    this.bindings.compressStream,
                    this.compressContext,
                    targetBuffer,
                    sourceBuffer,
                    end ? this.bindings.endDirective : this.bindings.continueDirective
            );
        }

        boolean decompressDirectByteBufferStream(ByteBuffer targetBuffer, ByteBuffer sourceBuffer) {
            return (Boolean) this.bindings.invoke(
                    this.bindings.decompressStream,
                    this.decompressContext,
                    targetBuffer,
                    sourceBuffer
            );
        }

        void reset(int compressionLevel) {
            this.bindings.invoke(this.bindings.compressReset, this.compressContext);
            this.bindings.invoke(this.bindings.setLevel, this.compressContext, compressionLevel);
            this.bindings.invoke(this.bindings.decompressReset, this.decompressContext);
        }
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
        private final Object continueDirective;
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
                Object continueDirective,
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
            this.continueDirective = continueDirective;
            this.endDirective = endDirective;
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
