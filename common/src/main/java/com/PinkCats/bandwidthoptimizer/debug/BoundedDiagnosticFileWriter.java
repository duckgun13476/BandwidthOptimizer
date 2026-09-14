package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Keeps diagnostic disk I/O outside latency-sensitive network threads. */
public final class BoundedDiagnosticFileWriter implements AutoCloseable {

    static final int DEFAULT_MAX_QUEUED_LINES = 256;
    static final long DEFAULT_MAX_QUEUED_BYTES = 16L * 1024L * 1024L;
    private static final int FLUSH_LINE_INTERVAL = 64;
    private static final long FLUSH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1L);

    private final Path outputPath;
    private final String outputName;
    private final ArrayBlockingQueue<QueuedLine> queue;
    private final long maxQueuedBytes;
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicLong droppedLines = new AtomicLong();
    private final AtomicBoolean workerStarted = new AtomicBoolean();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean failed = new AtomicBoolean();
    private final AtomicBoolean overflowWarned = new AtomicBoolean();
    private final AtomicBoolean failureWarned = new AtomicBoolean();
    private volatile Thread worker;

    public BoundedDiagnosticFileWriter(Path outputPath, String outputName) {
        this(outputPath, outputName, DEFAULT_MAX_QUEUED_LINES, DEFAULT_MAX_QUEUED_BYTES);
    }

    BoundedDiagnosticFileWriter(Path outputPath, String outputName, int maxQueuedLines, long maxQueuedBytes) {
        if (outputPath == null || maxQueuedLines <= 0 || maxQueuedBytes <= 0L) {
            throw new IllegalArgumentException("Invalid bounded diagnostic writer configuration");
        }
        this.outputPath = outputPath;
        this.outputName = outputName == null || outputName.isBlank() ? outputPath.getFileName().toString() : outputName;
        this.queue = new ArrayBlockingQueue<>(maxQueuedLines);
        this.maxQueuedBytes = maxQueuedBytes;
    }

    public boolean offer(String line) {
        if (line == null || !this.accepting.get() || this.failed.get()) {
            return false;
        }

        long retainedBytes = retainedBytes(line);
        if (!reserveBytes(retainedBytes)) {
            recordOverflow();
            return false;
        }

        QueuedLine queuedLine = new QueuedLine(line, retainedBytes);
        if (!this.queue.offer(queuedLine)) {
            this.queuedBytes.addAndGet(-retainedBytes);
            recordOverflow();
            return false;
        }
        startWorkerIfNeeded();
        if ((!this.accepting.get() || this.failed.get()) && this.queue.remove(queuedLine)) {
            this.queuedBytes.addAndGet(-retainedBytes);
            return false;
        }
        return true;
    }

    public long droppedLines() {
        return this.droppedLines.get();
    }

    public boolean failed() {
        return this.failed.get();
    }

    @Override
    public void close() {
        this.accepting.set(false);
        Thread activeWorker = this.worker;
        if (activeWorker == null || activeWorker == Thread.currentThread()) {
            return;
        }
        try {
            activeWorker.join(2_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean reserveBytes(long retainedBytes) {
        if (retainedBytes > this.maxQueuedBytes) {
            return false;
        }
        while (true) {
            long current = this.queuedBytes.get();
            if (current > this.maxQueuedBytes - retainedBytes) {
                return false;
            }
            if (this.queuedBytes.compareAndSet(current, current + retainedBytes)) {
                return true;
            }
        }
    }

    private void startWorkerIfNeeded() {
        if (!this.workerStarted.compareAndSet(false, true)) {
            return;
        }
        Thread newWorker = new Thread(this::writeLoop, "bo-diagnostic-file-" + sanitizeThreadName(this.outputName));
        newWorker.setDaemon(true);
        newWorker.setContextClassLoader(BoundedDiagnosticFileWriter.class.getClassLoader());
        this.worker = newWorker;
        newWorker.start();
    }

    private void writeLoop() {
        try {
            Path parent = this.outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(
                    this.outputPath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            )) {
                drainTo(writer);
            }
        } catch (IOException | RuntimeException exception) {
            fail(exception);
        } finally {
            discardQueuedLines();
        }
    }

    private void drainTo(BufferedWriter writer) throws IOException {
        int linesSinceFlush = 0;
        long lastFlushNanos = System.nanoTime();
        while (this.accepting.get() || !this.queue.isEmpty()) {
            QueuedLine queuedLine;
            try {
                queuedLine = this.queue.poll(250L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
            if (queuedLine == null) {
                if (linesSinceFlush > 0) {
                    writer.flush();
                    linesSinceFlush = 0;
                    lastFlushNanos = System.nanoTime();
                }
                continue;
            }

            try {
                writer.write(queuedLine.line());
                writer.newLine();
            } finally {
                this.queuedBytes.addAndGet(-queuedLine.retainedBytes());
            }
            linesSinceFlush++;
            long now = System.nanoTime();
            if (linesSinceFlush >= FLUSH_LINE_INTERVAL || now - lastFlushNanos >= FLUSH_INTERVAL_NANOS) {
                writer.flush();
                linesSinceFlush = 0;
                lastFlushNanos = now;
            }
        }
        writer.flush();
    }

    private void recordOverflow() {
        this.droppedLines.incrementAndGet();
        if (this.overflowWarned.compareAndSet(false, true)) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[BO] Diagnostic output queue is full; dropping samples for {}",
                    this.outputName
            );
        }
    }

    private void fail(Exception exception) {
        this.failed.set(true);
        this.accepting.set(false);
        if (this.failureWarned.compareAndSet(false, true)) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[BO] Disabled diagnostic output {} after a disk write failure at {}",
                    this.outputName,
                    this.outputPath.toAbsolutePath(),
                    exception
            );
        }
    }

    private void discardQueuedLines() {
        QueuedLine queuedLine;
        while ((queuedLine = this.queue.poll()) != null) {
            this.queuedBytes.addAndGet(-queuedLine.retainedBytes());
            this.droppedLines.incrementAndGet();
        }
    }

    private static long retainedBytes(String line) {
        return Math.min(Long.MAX_VALUE, 64L + (long) line.length() * Character.BYTES);
    }

    private static String sanitizeThreadName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private record QueuedLine(String line, long retainedBytes) {
    }
}
