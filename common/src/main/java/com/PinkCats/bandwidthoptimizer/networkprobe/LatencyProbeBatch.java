package com.PinkCats.bandwidthoptimizer.networkprobe;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.function.BiConsumer;

/** Main-thread queue; completion callbacks must return to the same thread. */
final class LatencyProbeBatch<T> {
    private final ArrayDeque<T> pending;
    private final int limit;
    private final BiConsumer<T, Runnable> launch;
    private final Runnable finished;
    private int active;
    private boolean pumping;
    private boolean complete;

    LatencyProbeBatch(Collection<T> targets, int limit, BiConsumer<T, Runnable> launch, Runnable finished) {
        if (limit < 1) throw new IllegalArgumentException("Invalid batch limit");
        this.pending = new ArrayDeque<>(targets);
        this.limit = limit;
        this.launch = launch;
        this.finished = finished;
    }

    void start() {
        pump();
    }

    private void pump() {
        if (pumping || complete) return;
        pumping = true;
        try {
            while (active < limit && !pending.isEmpty()) {
                launchNext();
            }
            if (active == 0 && pending.isEmpty()) {
                complete = true;
                finished.run();
            }
        } finally {
            pumping = false;
        }
    }

    private void launchNext() {
        T target = pending.removeFirst();
        active++;
        boolean[] done = {false};
        Runnable finishOne = () -> {
            if (!done[0]) {
                done[0] = true;
                active--;
                pump();
            }
        };
        try {
            launch.accept(target, finishOne);
        } catch (RuntimeException failure) {
            finishOne.run();
            throw failure;
        }
    }
}
