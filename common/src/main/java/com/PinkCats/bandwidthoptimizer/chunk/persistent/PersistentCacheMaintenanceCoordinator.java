package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class PersistentCacheMaintenanceCoordinator {

    private final Consumer<Runnable> scheduler;
    private final BooleanSupplier maintenanceWindowOpen;
    private final Consumer<String> maintenanceAction;
    private final AtomicBoolean requested = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<String> latestReason = new AtomicReference<>("disconnect");

    PersistentCacheMaintenanceCoordinator(
            Consumer<Runnable> scheduler,
            BooleanSupplier maintenanceWindowOpen,
            Consumer<String> maintenanceAction
    ) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.maintenanceWindowOpen = Objects.requireNonNull(maintenanceWindowOpen);
        this.maintenanceAction = Objects.requireNonNull(maintenanceAction);
    }

    void request(String reason) {
        latestReason.set(reason == null || reason.isBlank() ? "disconnect" : reason);
        requested.set(true);
        scheduleDrain();
    }

    private void scheduleDrain() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.accept(this::drain);
        } catch (RuntimeException exception) {
            running.set(false);
            throw exception;
        }
    }

    private void drain() {
        try {
            while (requested.getAndSet(false)) {
                if (maintenanceWindowOpen.getAsBoolean()) {
                    maintenanceAction.accept(latestReason.get());
                }
            }
        } finally {
            running.set(false);
            if (requested.get()) {
                scheduleDrain();
            }
        }
    }
}
