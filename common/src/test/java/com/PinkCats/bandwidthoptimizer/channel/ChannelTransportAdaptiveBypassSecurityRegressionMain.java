package com.PinkCats.bandwidthoptimizer.channel;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class ChannelTransportAdaptiveBypassSecurityRegressionMain {

    private static final int MAX_ENTRIES = 4096;

    private ChannelTransportAdaptiveBypassSecurityRegressionMain() {}

    public static void main(String[] args) throws Exception {
        ReflectionAccess access = new ReflectionAccess();
        verifyDeterministicBoundedEviction(access);
        verifyActiveEntriesRejectAdmission(access);
        verifyConcurrentAdmissionNeverExceedsLimit(access);
        access.reset();
        System.out.println("Adaptive bypass security regression passed.");
    }

    private static void verifyDeterministicBoundedEviction(ReflectionAccess access) throws Exception {
        access.reset();
        long now = TimeUnit.MINUTES.toNanos(10L);
        Object first = null;
        for (int index = 0; index < MAX_ENTRIES; index++) {
            Object key = access.key("test:inactive_" + index, index);
            if (index == 0) {
                first = key;
            }
            require(access.admit(key, now + index) != null, "entry was rejected below the hard limit");
        }
        Object extra = access.key("test:extra", MAX_ENTRIES);
        require(access.admit(extra, now + MAX_ENTRIES) != null, "inactive entry was not evicted at the limit");
        require(access.size() == MAX_ENTRIES, "entry table exceeded its hard limit");
        require(!access.contains(first), "oldest inactive entry was not evicted deterministically");
        require(access.contains(extra), "replacement entry was not admitted");
        System.out.println("adaptive-bypass-cap: oldest inactive entry evicted at 4096 entries");
    }

    private static void verifyActiveEntriesRejectAdmission(ReflectionAccess access) throws Exception {
        access.reset();
        long now = TimeUnit.MINUTES.toNanos(20L);
        for (int index = 0; index < MAX_ENTRIES; index++) {
            require(access.admit(access.key("test:active_" + index, index), now) != null,
                    "active fixture was rejected below the hard limit");
        }
        access.markAllActive(now + TimeUnit.MINUTES.toNanos(1L));
        Object refused = access.key("test:refused", MAX_ENTRIES + 1);
        require(access.admit(refused, now) == null, "active bypass entry was evicted for a new key");
        require(access.admit(access.key("test:cooldown", MAX_ENTRIES + 2), now + 1L) == null,
                "saturated admission cooldown did not refuse a repeated scan");
        require(access.size() == MAX_ENTRIES, "refused admission changed the hard-bounded size");
        System.out.println("adaptive-bypass-active: saturated active table preserved and new learning refused");
    }

    private static void verifyConcurrentAdmissionNeverExceedsLimit(ReflectionAccess access) throws Exception {
        access.reset();
        int workers = 8;
        int entriesPerWorker = 1024;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<?>> futures = new ArrayList<>(workers);
        try {
            for (int worker = 0; worker < workers; worker++) {
                int workerId = worker;
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                        long now = TimeUnit.MINUTES.toNanos(30L) + workerId;
                        for (int index = 0; index < entriesPerWorker; index++) {
                            access.admit(access.key("test:concurrent_" + workerId + '_' + index,
                                    workerId * entriesPerWorker + index), now);
                        }
                    } catch (ReflectiveOperationException | InterruptedException exception) {
                        throw new IllegalStateException(exception);
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30L, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
            require(executor.awaitTermination(30L, TimeUnit.SECONDS), "concurrent admission did not finish");
        }
        require(access.size() == MAX_ENTRIES, "concurrent admission escaped the hard limit");
        System.out.println("adaptive-bypass-concurrency: 8192 unique admissions retained exactly 4096 entries");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class ReflectionAccess {
        private final Constructor<?> keyConstructor;
        private final Method admitMethod;
        private final Map<Object, Object> entries;
        private final ArrayDeque<Object> admissionOrder;
        private final Object admissionLock;
        private final Field nextScanField;
        private final Field bypassUntilField;

        @SuppressWarnings("unchecked")
        private ReflectionAccess() throws Exception {
            Class<?> owner = ChannelTransportAdaptiveBypass.class;
            Class<?> keyClass = Class.forName(owner.getName() + "$Key");
            Class<?> entryClass = Class.forName(owner.getName() + "$Entry");
            this.keyConstructor = keyClass.getDeclaredConstructor(
                    String.class, String.class, String.class, String.class, int.class, int.class);
            this.keyConstructor.setAccessible(true);
            this.admitMethod = owner.getDeclaredMethod("getOrAdmitEntry", keyClass, long.class);
            this.admitMethod.setAccessible(true);
            Field entriesField = owner.getDeclaredField("ENTRIES");
            entriesField.setAccessible(true);
            this.entries = (Map<Object, Object>) entriesField.get(null);
            Field orderField = owner.getDeclaredField("ADMISSION_ORDER");
            orderField.setAccessible(true);
            this.admissionOrder = (ArrayDeque<Object>) orderField.get(null);
            Field lockField = owner.getDeclaredField("ADMISSION_LOCK");
            lockField.setAccessible(true);
            this.admissionLock = lockField.get(null);
            this.nextScanField = owner.getDeclaredField("nextSaturatedScanNanos");
            this.nextScanField.setAccessible(true);
            this.bypassUntilField = entryClass.getDeclaredField("bypassUntilNanos");
            this.bypassUntilField.setAccessible(true);
        }

        private Object key(String channel, int packetId) throws ReflectiveOperationException {
            return keyConstructor.newInstance("play", "CLIENTBOUND", "test.Packet", channel, packetId, 8);
        }

        private Object admit(Object key, long now) throws ReflectiveOperationException {
            return admitMethod.invoke(null, key, now);
        }

        private int size() {
            return entries.size();
        }

        private boolean contains(Object key) {
            return entries.containsKey(key);
        }

        private void markAllActive(long untilNanos) throws IllegalAccessException {
            for (Object entry : entries.values()) {
                bypassUntilField.setLong(entry, untilNanos);
            }
        }

        private void reset() throws IllegalAccessException {
            synchronized (admissionLock) {
                entries.clear();
                admissionOrder.clear();
                nextScanField.setLong(null, 0L);
            }
        }
    }
}
