package com.PinkCats.bandwidthoptimizer.connection;

import io.netty.channel.embedded.EmbeddedChannel;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class ConnectionPayloadTaskGuardRegressionMain {

    private ConnectionPayloadTaskGuardRegressionMain() {}

    public static void main(String[] args) {
        EmbeddedChannel channel = new EmbeddedChannel();
        AtomicInteger executions = new AtomicInteger();

        Runnable current = ConnectionPayloadTaskGuard.guard(channel, "test:current", (Runnable) executions::incrementAndGet);
        current.run();
        assertEquals(1, executions.get(), "current task must execute");

        Runnable stale = ConnectionPayloadTaskGuard.guard(channel, "test:stale", (Runnable) executions::incrementAndGet);
        long initialGeneration = ConnectionPayloadTaskGuard.generation(channel);
        ConnectionPayloadTaskGuard.advanceProtocolGeneration(channel);
        stale.run();
        assertEquals(1, executions.get(), "task from an older protocol generation must be dropped");
        assertEquals(initialGeneration + 1L, ConnectionPayloadTaskGuard.generation(channel), "generation must advance");

        Supplier<String> currentSupplier = ConnectionPayloadTaskGuard.guard(channel, "test:supplier", () -> "ok");
        if (!"ok".equals(currentSupplier.get())) {
            throw new AssertionError("current supplier must execute");
        }

        Supplier<String> staleSupplier = ConnectionPayloadTaskGuard.guard(channel, "test:stale_supplier", () -> "bad");
        ConnectionPayloadTaskGuard.advanceProtocolGeneration(channel);
        if (staleSupplier.get() != null) {
            throw new AssertionError("stale supplier must complete without running business code");
        }

        Runnable afterClose = ConnectionPayloadTaskGuard.guard(channel, "test:closed", (Runnable) executions::incrementAndGet);
        ConnectionPayloadTaskGuard.close(channel);
        afterClose.run();
        ConnectionPayloadTaskGuard.guard(
                channel,
                "test:captured_after_close",
                (Runnable) executions::incrementAndGet
        ).run();
        assertEquals(1, executions.get(), "closed connections must reject old and newly captured tasks");
        assertEquals(4L, ConnectionPayloadTaskGuard.droppedTasks(channel), "all stale tasks must be counted");

        channel.finishAndReleaseAll();
        System.out.println("Connection payload task guard regression passed");
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
