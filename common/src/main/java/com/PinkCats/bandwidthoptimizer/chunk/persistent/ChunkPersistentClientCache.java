package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.debug.ChunkLoadDelayProbe;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportControlFrameSender;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameCodec;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprintService;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticRuntimeSwitch;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import com.PinkCats.bandwidthoptimizer.integration.voxy.VoxyChunkBoundCompat;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ChunkPersistentClientCache {

    private static final String ENABLED_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCache";
    private static final String MANIFEST_LIMIT_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheManifestLimit";
    private static final String MANIFEST_BATCH_ENTRIES_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheManifestBatchEntries";
    private static final String MANIFEST_REFRESH_ENABLED_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheManifestRefreshEnabled";
    private static final String MANIFEST_REFRESH_INTERVAL_MILLIS_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheManifestRefreshMillis";
    private static final String MANIFEST_REFRESH_QUIET_MILLIS_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheManifestRefreshQuietMillis";
    private static final String ZIP_LEVEL_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheZipLevel";
    private static final String CHECKPOINT_INTERVAL_MILLIS_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheCheckpointMillis";
    private static final String BACKUP_ENABLED_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheBackupEnabled";
    private static final String BACKUP_INTERVAL_MILLIS_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheBackupMillis";
    private static final String MAX_PENDING_STORE_TASKS_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheMaxPendingStores";
    private static final String MAX_PENDING_STORE_BYTES_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheMaxPendingStoreBytes";
    private static final String READY_CACHE_BYTES_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheReadyBytes";
    private static final String MAX_DISK_BYTES_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheMaxDiskBytes";
    private static final String MAX_DISK_ENTRIES_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheMaxEntries";
    private static final String MIN_SCOPE_ENTRIES_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheMinScopeEntries";
    private static final String COMPACTION_BYTES_PER_SECOND_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheCompactionBytesPerSecond";
    private static final boolean DEFAULT_ENABLED = true;
    private static final int DEFAULT_MANIFEST_LIMIT = 512;
    private static final int DEFAULT_MANIFEST_BATCH_ENTRIES = 512;
    private static final boolean DEFAULT_MANIFEST_REFRESH_ENABLED = true;
    private static final long DEFAULT_MANIFEST_REFRESH_INTERVAL_MILLIS = 5_000L;
    private static final long DEFAULT_MANIFEST_REFRESH_QUIET_MILLIS = 8_000L;
    private static final int DEFAULT_ZIP_LEVEL = 2;
    private static final long DEFAULT_CHECKPOINT_INTERVAL_MILLIS = 900_000L;
    private static final boolean DEFAULT_BACKUP_ENABLED = true;
    private static final long DEFAULT_BACKUP_INTERVAL_MILLIS = 600_000L;
    private static final int DEFAULT_MAX_PENDING_STORE_TASKS = 2048;
    private static final long DEFAULT_MAX_PENDING_STORE_BYTES = 64L * 1024L * 1024L;
    private static final long DEFAULT_READY_CACHE_BYTES = 96L * 1024L * 1024L;
    private static final long DEFAULT_MAX_DISK_BYTES = 512L * 1024L * 1024L;
    private static final int DEFAULT_MAX_DISK_ENTRIES = 100_000;
    private static final int DEFAULT_MIN_SCOPE_ENTRIES = 1_024;
    private static final long DEFAULT_COMPACTION_BYTES_PER_SECOND = 8L * 1024L * 1024L;
    private static final long MAINTENANCE_IDLE_DELAY_MILLIS = 2_000L;
    private static final double COMPACTION_MINIMUM_INVALID_RATIO = 0.60D;
    private static final double DELTA_MAXIMUM_FULL_COST_RATIO = 0.40D;
    private static final String STORAGE_KIND_FULL = "FULL";
    private static final String STORAGE_KIND_DELTA = "DELTA";
    private static final int STORE_DRAIN_BATCH_LIMIT = 256;
    private static final long HOT_PATH_PUBLISH_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(50L);
    private static final long SLOW_IO_WARNING_MILLIS = 1_500L;
    private static final long SLOW_IO_WARNING_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(5L);
    private static final Object LOCK = new Object();
    private static final Object FLUSH_LOCK = new Object();
    private static final Object DISK_INIT_LOCK = new Object();
    private static final Set<String> MANIFEST_SENT_CHANNELS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, Long> LAST_USED_UPDATES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> HIT_COUNT_UPDATES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> SAVED_BYTES_UPDATES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, PendingStoreRequest> PENDING_STORE_REQUESTS = new ConcurrentHashMap<>();
    private static final AtomicInteger PENDING_STORE_REQUEST_COUNT = new AtomicInteger();
    private static final AtomicLong PENDING_STORE_REQUEST_BYTES = new AtomicLong();
    private static final AtomicBoolean STORE_DRAIN_QUEUED = new AtomicBoolean();
    private static final AttributeKey<PreparedReadyState> PREPARED_READY_STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:persistent_prepared_ready");
    private static final int MAX_PREPARED_READY_ENTRIES = 576;
    private static final long MAX_PREPARED_READY_EXTRA_BYTES = 32L * 1024L * 1024L;
    private static final long PREPARED_READY_TTL_NANOS = TimeUnit.SECONDS.toNanos(5L);
    private static final long ADVERTISED_READY_TTL_NANOS = TimeUnit.SECONDS.toNanos(30L);
    private static final String ZIP_FILE_NAME = "client-persistent-chunk-cache.zip";
    private static final String INDEX_ENTRY_NAME = "index.properties";
    private static final String BLOBS_ENTRY_DIRECTORY = "blobs/";
    private static final String LEGACY_DIRECTORY_NAME = "client-persistent-chunk-cache";
    private static final String V2_DIRECTORY_NAME = "client-persistent-chunk-cache-v2";
    private static final String MIGRATION_PENDING_FILE_NAME = "legacy-zip-migration.pending";
    private static final ScheduledExecutorService IO_EXECUTOR = Executors.newSingleThreadScheduledExecutor(new CacheThreadFactory());
    private static final PersistentCacheMaintenanceCoordinator MAINTENANCE_COORDINATOR =
            new PersistentCacheMaintenanceCoordinator(
                    task -> IO_EXECUTOR.schedule(task, MAINTENANCE_IDLE_DELAY_MILLIS, TimeUnit.MILLISECONDS),
                    ChunkPersistentClientCache::isMaintenanceWindowOpen,
                    ChunkPersistentClientCache::runPersistentMaintenance
            );
    private static final ScheduledThreadPoolExecutor IO_WATCHDOG_EXECUTOR = createIoWatchdogExecutor();
    private static long lastSlowIoWarningNanos;
    private static long suppressedSlowIoWarnings;
    private static ZipCacheSnapshot cachedSnapshot;
    private static volatile PersistentChunkCacheDiskStore diskStore;
    private static final LinkedHashMap<String, byte[]> READY_BLOBS = new LinkedHashMap<>(256, 0.75f, true);
    private static long readyBlobBytes;
    private static volatile LoadedHotPathSnapshot loadedHotPathSnapshot = LoadedHotPathSnapshot.empty("");
    private static final HashMap<String, String> HOT_PATH_HASH_BY_KEY = new HashMap<>();
    private static final HashMap<String, HashSet<String>> HOT_PATH_KEYS_BY_HASH = new HashMap<>();
    private static String activeServerScopeHash = "";
    private static String activeConnectionChannelId = "";
    private static long activeScopeGeneration;
    private static boolean preloadStarted;
    private static boolean checkpointLoopStarted;
    private static boolean backupLoopStarted;
    private static boolean manifestRefreshLoopStarted;
    private static boolean shutdownHookInstalled;
    private static boolean flushQueued;
    private static boolean dirty;
    private static int dirtyBlobWrites;
    private static long dirtyBytes;
    private static final AtomicLong LAST_SUCCESSFUL_FLUSH_MILLIS = new AtomicLong(System.currentTimeMillis());
    private static Channel activeManifestRefreshChannel;
    private static String activeManifestRefreshChannelId = "";
    private static long manifestRefreshDirtyGeneration;
    private static long manifestRefreshInFlightGeneration;
    private static long manifestRefreshSentGeneration;
    private static long manifestRefreshLastDirtyMillis;


    // Chunk local client side
    private ChunkPersistentClientCache() {}

    public static void startAsyncPreload(String reason) {
        if (!isEnabled()) {
            return;
        }

        synchronized (LOCK) {
            installShutdownHookIfNeeded();
            startCheckpointLoopIfNeeded();
            if (preloadStarted) {
                return;
            }
            preloadStarted = true;
        }

        executeIo("preload", () -> {
            long startedAtMillis = System.currentTimeMillis();
            try {
                CachePreloadResult preload = loadV2CacheFromDisk();
                synchronized (LOCK) {
                    cachedSnapshot = preload.snapshot();
                    replaceReadyBlobsLocked(preload.readyBlobs());
                    publishLoadedHotPathSnapshotLocked();
                }
                if (shouldLogCacheDiagnose()) {
                    Bandwidthoptimizer.LOGGER.info(
                            "[ChunkPersistentCache][Preload] cacheRoot={}, ready={}, readyBytes={}, indexed={}, millis={}, reason={}",
                            cacheRoot(),
                            preload.readyBlobs().size(),
                            preload.readyBytes(),
                            preload.snapshot().verifiedBlobEntries().size(),
                            System.currentTimeMillis() - startedAtMillis,
                            safeText(reason, "client_startup")
                    );
                }
            } catch (IOException exception) {
                synchronized (LOCK) {
                    cachedSnapshot = ZipCacheSnapshot.empty();
                    replaceReadyBlobsLocked(Map.of());
                    publishLoadedHotPathSnapshotLocked();
                }
                if (shouldLogCacheDiagnose()) {
                    Bandwidthoptimizer.LOGGER.warn(
                            "[ChunkPersistentCache][Preload][Fail] cacheRoot={}, reason={}",
                            cacheRoot(),
                            exception.toString()
                    );
                }
            }
        });
    }

    public static void flushNow(String reason) {
        Properties indexToWrite;
        String safeReason = safeText(reason, "client_cache_flush");
        int pendingStores = PENDING_STORE_REQUEST_COUNT.get();
        synchronized (LOCK) {
            if (shouldSkipBlockingFlush(safeReason)) {
                return;
            }
            if (shouldDeferFlushForPendingStores(safeReason, pendingStores)) {
                schedulePendingStoreDrain();
                return;
            }
            if (cachedSnapshot == null || (!dirty && !hasPendingActivityUpdates())) {
                return;
            }
            mergePendingActivityUpdatesLocked(cachedSnapshot);
            indexToWrite = copyProperties(cachedSnapshot.index());
            dirty = false;
            dirtyBlobWrites = 0;
            dirtyBytes = 0L;
        }

        synchronized (FLUSH_LOCK) {
            try {
                long startedAtMillis = System.currentTimeMillis();
                persistentDiskStore().checkpoint(indexToWrite);
                LAST_SUCCESSFUL_FLUSH_MILLIS.set(System.currentTimeMillis());
                if (shouldLogCacheDiagnose()) {
                    Bandwidthoptimizer.LOGGER.info(
                            "[ChunkPersistentCache][Flush] cacheFile={}, blobs={}, millis={}, reason={}",
                            cacheRoot(),
                            indexToWrite.size(),
                            System.currentTimeMillis() - startedAtMillis,
                            safeReason
                    );
                }
            } catch (IOException exception) {
                synchronized (LOCK) {
                    dirty = true;
                }
                if (shouldLogCacheDiagnose()) {
                    Bandwidthoptimizer.LOGGER.warn(
                            "[ChunkPersistentCache][Flush][Fail] cacheFile={}, reason={}",
                            cacheRoot(),
                            exception.toString()
                    );
                }
            }
        }
    }

    public static void flushAsync(String reason) {
        if (!isEnabled()) {
            return;
        }
        queueAsyncFlush(reason);
    }


    public static void storeFullSnapshot(ChunkHotspotFrame frame, byte[] restoredPacketBytes) {
        LoadedHotPathSnapshot snapshot = loadedHotPathSnapshot;
        publishHotPathMutations(storeFullSnapshot(
                frame,
                restoredPacketBytes,
                snapshot.serverScopeHash(),
                snapshot.scopeGeneration(),
                null
        ));
    }

    public static void storeInboundFullChunkAsync(
            String protocolName,
            long epoch,
            String packetClassName,
            ChunkHotspotKind hotspotKind,
            ChunkLaneKind laneKind,
            ChunkPacketCoordinate coordinate,
            byte[] encodedPacketBytes,
            String reason
    ) {
        LoadedHotPathSnapshot hotPathSnapshot = loadedHotPathSnapshot;
        String serverScopeHash = hotPathSnapshot.serverScopeHash();
        long scopeGeneration = hotPathSnapshot.scopeGeneration();
        if (!isEnabled()
                || !isSafeScopeHash(serverScopeHash)
                || hotspotKind != ChunkHotspotKind.FULL_CHUNK
                || coordinate == null
                || !coordinate.present()
                || encodedPacketBytes == null
                || encodedPacketBytes.length == 0) {
            return;
        }

        byte[] snapshotBytes = encodedPacketBytes;
        String storeKey = pendingStoreKey(serverScopeHash, coordinate);
        PendingStoreRequest request = new PendingStoreRequest(
                serverScopeHash,
                protocolName,
                epoch,
                packetClassName,
                hotspotKind,
                laneKind,
                coordinate,
                snapshotBytes,
                scopeGeneration,
                reason
        );
        PendingStoreRequest previousRequest = PENDING_STORE_REQUESTS.put(storeKey, request);
        long pendingBytes = PENDING_STORE_REQUEST_BYTES.addAndGet(request.snapshotBytes().length
                - (previousRequest == null ? 0L : previousRequest.snapshotBytes().length));
        if (previousRequest == null) {
            int pendingStores = PENDING_STORE_REQUEST_COUNT.incrementAndGet();
            if (pendingStores > maxPendingStoreTasks() || pendingBytes > maxPendingStoreBytes()) {
                if (PENDING_STORE_REQUESTS.remove(storeKey, request)) {
                    pendingStores = PENDING_STORE_REQUEST_COUNT.decrementAndGet();
                    pendingBytes = PENDING_STORE_REQUEST_BYTES.addAndGet(-request.snapshotBytes().length);
                } else {
                    pendingStores = PENDING_STORE_REQUEST_COUNT.get();
                    pendingBytes = PENDING_STORE_REQUEST_BYTES.get();
                }
                if (shouldLogCacheDiagnose()) {
                    Bandwidthoptimizer.LOGGER.warn(
                            "[ChunkPersistentCache][Store][Drop] chunk={}, bytes={}, pending={}, pendingBytes={}, taskLimit={}, byteLimit={}, reason={}",
                            coordinate.logText(),
                            snapshotBytes.length,
                            pendingStores,
                            pendingBytes,
                            maxPendingStoreTasks(),
                            maxPendingStoreBytes(),
                            safeText(reason, "persistent_cache_store_queue_full")
                    );
                }
                return;
            }
        } else if (pendingBytes > maxPendingStoreBytes()) {
            if (PENDING_STORE_REQUESTS.remove(storeKey, request)) {
                PENDING_STORE_REQUEST_COUNT.decrementAndGet();
                PENDING_STORE_REQUEST_BYTES.addAndGet(-request.snapshotBytes().length);
            }
            if (shouldLogCacheDiagnose()) {
                Bandwidthoptimizer.LOGGER.warn(
                        "[ChunkPersistentCache][Store][DropReplace] chunk={}, bytes={}, pending={}, pendingBytes={}, byteLimit={}, reason={}",
                        coordinate.logText(),
                        snapshotBytes.length,
                        PENDING_STORE_REQUEST_COUNT.get(),
                        PENDING_STORE_REQUEST_BYTES.get(),
                        maxPendingStoreBytes(),
                        safeText(reason, "persistent_cache_store_queue_full")
                );
            }
            return;
        }

        schedulePendingStoreDrain();
    }

    private static void schedulePendingStoreDrain() {
        if (!STORE_DRAIN_QUEUED.compareAndSet(false, true)) {
            return;
        }
        try {
            executeIo("store-drain", ChunkPersistentClientCache::drainPendingStoreRequests);
        } catch (RuntimeException exception) {
            STORE_DRAIN_QUEUED.set(false);
            if (shouldLogCacheDiagnose()) {
                Bandwidthoptimizer.LOGGER.warn(
                        "[ChunkPersistentCache][Store][QueueFail] pending={}, reason={}",
                        PENDING_STORE_REQUEST_COUNT.get(),
                        exception.toString()
                );
            }
        }
    }

    private static void drainPendingStoreRequests() {
        int drained = 0;
        HotPathMutationBatch hotPathBatch = new HotPathMutationBatch();
        long lastPublishedNanos = System.nanoTime();
        try {
            while (drained < STORE_DRAIN_BATCH_LIMIT) {
                PendingStoreRequest request = pollPendingStoreRequest();
                if (request == null) {
                    break;
                }
                HotPathMutation mutation = processPendingStoreRequest(request);
                if (mutation != null) {
                    if (!hotPathBatch.accepts(mutation)) {
                        publishHotPathMutations(hotPathBatch.drain());
                    }
                    hotPathBatch.add(mutation);
                }
                drained++;
                long nowNanos = System.nanoTime();
                if (!hotPathBatch.isEmpty()
                        && nowNanos - lastPublishedNanos >= HOT_PATH_PUBLISH_INTERVAL_NANOS) {
                    publishHotPathMutations(hotPathBatch.drain());
                    lastPublishedNanos = nowNanos;
                }
            }
        } finally {
            publishHotPathMutations(hotPathBatch.drain());
            STORE_DRAIN_QUEUED.set(false);
            if (!PENDING_STORE_REQUESTS.isEmpty()) {
                schedulePendingStoreDrain();
            } else {
                queueIdleCheckpointAfterStoreDrain();
            }
        }
    }

    private static PendingStoreRequest pollPendingStoreRequest() {
        for (Map.Entry<String, PendingStoreRequest> entry : PENDING_STORE_REQUESTS.entrySet()) {
            if (PENDING_STORE_REQUESTS.remove(entry.getKey(), entry.getValue())) {
                PENDING_STORE_REQUEST_COUNT.decrementAndGet();
                PENDING_STORE_REQUEST_BYTES.addAndGet(-entry.getValue().snapshotBytes().length);
                return entry.getValue();
            }
        }
        return null;
    }

    private static HotPathMutation processPendingStoreRequest(PendingStoreRequest request) {
        try {
            ChunkSnapshotFingerprint fingerprint =
                    ChunkSnapshotFingerprintService.fingerprintOutboundPacket(request.snapshotBytes());
            if (fingerprint == null || fingerprint.hashHex() == null || fingerprint.hashHex().isBlank()) {
                return null;
            }
            ChunkHotspotFrame frame = new ChunkHotspotFrame(
                    ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                    ChunkHotspotFrameOp.PUBLISH_FULL,
                    Math.max(request.epoch(), 0L),
                    0L,
                    request.protocolName() == null ? "PLAY" : request.protocolName(),
                    safeText(request.packetClassName(), ""),
                    request.hotspotKind(),
                    request.laneKind(),
                    request.coordinate(),
                    request.snapshotBytes().length,
                    1L,
                    0L,
                    fingerprint.hashHex(),
                    fingerprint.hashHex(),
                    0L,
                    safeText(request.reason(), "persistent_cache_from_decoded_inbound_full")
            );
            return storeFullSnapshot(
                    frame,
                    request.snapshotBytes(),
                    request.serverScopeHash(),
                    request.scopeGeneration(),
                    fingerprint
            );
        } catch (RuntimeException exception) {
            if (shouldLogCacheDiagnose()) {
                Bandwidthoptimizer.LOGGER.warn(
                        "[ChunkPersistentCache][Store][WorkerFail] chunk={}, bytes={}, pending={}, reason={}",
                        request.coordinate().logText(),
                        request.snapshotBytes().length,
                        PENDING_STORE_REQUEST_COUNT.get(),
                        exception.toString()
                );
            }
        }
        return null;
    }

    private static HotPathMutation storeFullSnapshot(
            ChunkHotspotFrame frame,
            byte[] restoredPacketBytes,
            String serverScopeHash,
            long scopeGeneration,
            ChunkSnapshotFingerprint knownFingerprint
    ) {
        if (!isEnabled()
                || !isSafeScopeHash(serverScopeHash)
                || !isStorableFullSnapshot(frame)
                || restoredPacketBytes == null
                || restoredPacketBytes.length == 0) {
            return null;
        }

        long fingerprintStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        ChunkSnapshotFingerprint fingerprint = knownFingerprint == null
                ? ChunkSnapshotFingerprintService.fingerprintOutboundPacket(restoredPacketBytes)
                : knownFingerprint;
        ChunkLoadDelayProbe.logStage(
                (Channel) null,
                frame,
                "client",
                "persistent_store_fingerprint",
                ChunkLoadDelayProbe.elapsedMillisSince(fingerprintStartNanos),
                restoredPacketBytes.length,
                ""
        );
        if (fingerprint == null
                || fingerprint.hashHex() == null
                || !fingerprint.hashHex().equals(frame.payloadHash())) {
            return null;
        }

        long storeStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        HotPathMutation hotPathMutation = null;
        try {
            String keyPrefix = entryPrefix(serverScopeHash, frame.coordinate());
            PersistentChunkCacheDiskStore store = persistentDiskStore();

            long previousHitCount = 0L;
            long previousSavedBytes = 0L;
            int previousTemperature = 9;
            String previousHash = "";
            String previousStorageKind = STORAGE_KIND_FULL;
            String previousBaseHash = "";
            synchronized (LOCK) {
                if (cachedSnapshot != null) {
                    previousHitCount = readLong(cachedSnapshot.index(), keyPrefix + "hitCount", 0L);
                    previousSavedBytes = readLong(cachedSnapshot.index(), keyPrefix + "savedBytes", 0L);
                    previousTemperature = readInt(cachedSnapshot.index(), keyPrefix + "temperature", 9);
                    previousHash = cachedSnapshot.index().getProperty(keyPrefix + "hash", "");
                    previousStorageKind = cachedSnapshot.index().getProperty(
                            keyPrefix + "storageKind",
                            STORAGE_KIND_FULL
                    );
                    previousBaseHash = cachedSnapshot.index().getProperty(keyPrefix + "baseHash", "");
                }
            }

            String storageKind = STORAGE_KIND_FULL;
            String baseHash = "";
            String deltaHash = "";
            if (!store.containsBlob(fingerprint.hashHex())) {
                String candidateBaseHash = STORAGE_KIND_DELTA.equals(previousStorageKind)
                        ? previousBaseHash
                        : previousHash;
                byte[] candidateBase = null;
                if (isSafeHash(candidateBaseHash)) {
                    try {
                        candidateBase = store.readBlob(candidateBaseHash);
                    } catch (IOException ignored) {
                        // A damaged baseline is replaced by a new FULL snapshot.
                    }
                }
                PersistentChunkCacheDiskStore.DeltaEvaluation deltaEvaluation = null;
                if (candidateBase != null) {
                    try {
                        deltaEvaluation = store.evaluateDelta(
                                candidateBase,
                                restoredPacketBytes,
                                DELTA_MAXIMUM_FULL_COST_RATIO
                        );
                    } catch (IOException ignored) {
                        // Cost selection failure falls back to a self-contained FULL snapshot.
                    }
                }
                if (deltaEvaluation != null && deltaEvaluation.useDelta()) {
                    storageKind = STORAGE_KIND_DELTA;
                    baseHash = candidateBaseHash;
                    deltaHash = store.writeDeltaBlob(deltaEvaluation);
                } else {
                    store.writeEvaluatedFullBlob(fingerprint.hashHex(), restoredPacketBytes, deltaEvaluation);
                }
            }

            Properties mutation = new Properties();
            mutation.setProperty(keyPrefix + "hash", fingerprint.hashHex());
            mutation.setProperty(keyPrefix + "protocolName", safeText(frame.protocolName(), "PLAY"));
            mutation.setProperty(keyPrefix + "packetClassName", safeText(frame.packetClassName(), ""));
            mutation.setProperty(keyPrefix + "fullSnapshotVersion", Long.toString(Math.max(frame.fullSnapshotVersion(), 1L)));
            mutation.setProperty(keyPrefix + "encodedBytes", Integer.toString(restoredPacketBytes.length));
            mutation.setProperty(keyPrefix + "lastUsedAtMillis", Long.toString(System.currentTimeMillis()));
            mutation.setProperty(keyPrefix + "serverScopeHash", serverScopeHash);
            mutation.setProperty(keyPrefix + "hitCount", Long.toString(previousHitCount));
            mutation.setProperty(keyPrefix + "savedBytes", Long.toString(previousSavedBytes));
            mutation.setProperty(keyPrefix + "temperature", Integer.toString(previousTemperature));
            mutation.setProperty(keyPrefix + "storageKind", storageKind);
            mutation.setProperty(keyPrefix + "baseHash", baseHash);
            mutation.setProperty(keyPrefix + "deltaHash", deltaHash);
            store.appendIndexEntry(keyPrefix, mutation);

            synchronized (LOCK) {
                ZipCacheSnapshot cacheSnapshot = cachedZipCacheSnapshot();
                applyIndexEntry(cacheSnapshot.index(), keyPrefix, mutation);
                for (String storageHash : storageHashes(mutation, keyPrefix)) {
                    cacheSnapshot.verifiedBlobEntries().add(blobEntryName(storageHash));
                }
                Set<String> evictedHashes = putReadyBlobLocked(fingerprint.hashHex(), restoredPacketBytes);
                markDirty(restoredPacketBytes.length);
                markManifestRefreshDirty();
                hotPathMutation = updateHotPathTrackingLocked(
                        serverScopeHash,
                        scopeGeneration,
                        keyPrefix,
                        frame.coordinate(),
                        fingerprint.hashHex(),
                        mutation,
                        evictedHashes
                );
            }
            logStore(frame, restoredPacketBytes.length, cacheRoot());
        } catch (IOException exception) {
            if (shouldLogCacheDiagnose()) {
                Bandwidthoptimizer.LOGGER.warn(
                        "[ChunkPersistentCache][Store][Fail] chunk={}, hash={}, reason={}",
                        frame.coordinate().logText(),
                        shortenHash(frame.payloadHash()),
                        exception.toString()
                );
            }
        }
        ChunkLoadDelayProbe.logStage(
                (Channel) null,
                frame,
                "client",
                "persistent_store_total",
                ChunkLoadDelayProbe.elapsedMillisSince(storeStartNanos),
                restoredPacketBytes.length,
                ""
        );
        return hotPathMutation;
    }

    public static byte[] findLoadedPacketBytes(ChunkHotspotFrame frame) {
        LoadedHotPathSnapshot hotPathSnapshot = loadedHotPathSnapshot;
        String serverScopeHash = hotPathSnapshot.serverScopeHash();
        if (!isEnabled()
                || !isSafeScopeHash(serverScopeHash)
                || !isStorableFullSnapshot(frame)) {
            return null;
        }

        long loadStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        String keyPrefix = entryPrefix(serverScopeHash, frame.coordinate());
        LoadedCacheEntry cacheEntry = hotPathSnapshot.entriesByKeyPrefix().get(keyPrefix);
        if (cacheEntry == null || !frame.payloadHash().equals(cacheEntry.payloadHash())) {
            return null;
        }

        byte[] packetBytes = cacheEntry.packetBytes();
        boolean matchedHash = packetBytes.length > 0;
        ChunkLoadDelayProbe.logStage(
                (Channel) null,
                frame,
                "client",
                "persistent_loaded_find_verified_check",
                0L,
                matchedHash ? packetBytes.length : cacheEntry.encodedBytes(),
                "matched=" + matchedHash
        );
        if (!matchedHash) {
            return null;
        }

        recordCacheUse(serverScopeHash, frame.coordinate(), packetBytes.length);
        logLoad(frame, packetBytes.length);
        ChunkLoadDelayProbe.logStage(
                (Channel) null,
                frame,
                "client",
                "persistent_loaded_find_total",
                ChunkLoadDelayProbe.elapsedMillisSince(loadStartNanos),
                packetBytes.length,
                "hit=true"
        );
        return packetBytes.clone();
    }

    public static byte[] takePreparedPacketBytes(Channel channel, ChunkHotspotFrame frame) {
        return takePreparedPacketBytes(channel, frame, frame == null ? "" : frame.payloadHash());
    }

    public static byte[] takePreparedBasePacketBytes(Channel channel, ChunkHotspotFrame frame) {
        return takePreparedPacketBytes(channel, frame, frame == null ? "" : frame.baseSnapshotHash());
    }

    private static byte[] takePreparedPacketBytes(Channel channel, ChunkHotspotFrame frame, String expectedHash) {
        if (channel == null
                || frame == null
                || frame.coordinate() == null
                || !frame.coordinate().present()
                || !isSafeHash(expectedHash)) {
            return null;
        }
        PreparedReadyState state = channel.attr(PREPARED_READY_STATE_KEY).get();
        byte[] packetBytes = state == null
                ? null
                : state.take(currentServerScopeHash(), frame.coordinate(), expectedHash);
        if (packetBytes != null) {
            recordCacheUse(currentServerScopeHash(), frame.coordinate(), packetBytes.length);
        }
        return packetBytes;
    }

    public static byte[] findLoadedBasePacketBytes(ChunkHotspotFrame frame) {
        LoadedHotPathSnapshot hotPathSnapshot = loadedHotPathSnapshot;
        String serverScopeHash = hotPathSnapshot.serverScopeHash();
        if (!isEnabled()
                || !isSafeScopeHash(serverScopeHash)
                || !isStorableFullSnapshot(frame)
                || frame.baseSnapshotHash() == null
                || !isSafeHash(frame.baseSnapshotHash())) {
            return null;
        }

        long loadStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        String keyPrefix = entryPrefix(serverScopeHash, frame.coordinate());
        LoadedCacheEntry cacheEntry = hotPathSnapshot.entriesByKeyPrefix().get(keyPrefix);
        if (cacheEntry == null || !frame.baseSnapshotHash().equals(cacheEntry.payloadHash())) {
            return null;
        }

        byte[] packetBytes = cacheEntry.packetBytes();
        boolean matchedHash = packetBytes.length > 0;
        ChunkLoadDelayProbe.logStage(
                (Channel) null,
                frame,
                "client",
                "persistent_loaded_find_base_verified_check",
                0L,
                matchedHash ? packetBytes.length : cacheEntry.encodedBytes(),
                "matched=" + matchedHash
        );
        if (!matchedHash) {
            return null;
        }

        recordCacheUse(serverScopeHash, frame.coordinate(), packetBytes.length);
        logLoad(frame, packetBytes.length);
        ChunkLoadDelayProbe.logStage(
                (Channel) null,
                frame,
                "client",
                "persistent_loaded_find_base_total",
                ChunkLoadDelayProbe.elapsedMillisSince(loadStartNanos),
                packetBytes.length,
                "hit=true"
        );
        return packetBytes.clone();
    }


    public static byte[] findPacketBytes(ChunkHotspotFrame frame) {
        return findLoadedPacketBytes(frame);
    }

    public static byte[] findBasePacketBytes(ChunkHotspotFrame frame) {
        return findLoadedBasePacketBytes(frame);
    }

    public static void applyServerCacheScope(Channel channel, ChunkHotspotFrame frame) {
        if (frame == null
                || frame.operation() != com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp.SERVER_CACHE_SCOPE
                || !isSafeScopeHash(frame.payloadHash())) {
            return;
        }
        setActiveServerScopeHash(channel, frame.payloadHash().toLowerCase(Locale.ROOT), frame.reason());
    }

    public static void prepareColdEntryAsync(Channel channel, ChunkHotspotFrame prepareFrame) {
        if (channel == null
                || prepareFrame == null
                || prepareFrame.operation() != ChunkHotspotFrameOp.CACHE_PREPARE
                || prepareFrame.coordinate() == null
                || !prepareFrame.coordinate().present()
                || !isSafeScopeHash(prepareFrame.baseSnapshotHash())) {
            return;
        }

        String requestedScope = prepareFrame.baseSnapshotHash().toLowerCase(Locale.ROOT);
        long requestedGeneration;
        synchronized (LOCK) {
            if (!requestedScope.equals(activeServerScopeHash)) {
                sendColdPrepareMiss(channel, prepareFrame, "persistent_cache_prepare_stale_scope");
                return;
            }
            requestedGeneration = activeScopeGeneration;
        }

        VoxyChunkBoundCompat.beginVisualHandoff(
                prepareFrame.coordinate().chunkX(),
                prepareFrame.coordinate().chunkZ(),
                prepareFrame.observedPacketCount()
        );

        executeIo("cold-prepare-read", () -> {
            ColdCacheEntry coldEntry = findColdCacheEntry(requestedScope, prepareFrame.coordinate());
            if (coldEntry == null || coldEntry.encodedBytes() > readyCacheByteBudget()) {
                sendColdPrepareMiss(channel, prepareFrame, "persistent_cache_prepare_index_miss");
                return;
            }

            byte[] packetBytes;
            try {
                packetBytes = readStoredPacketBytes(persistentDiskStore(), coldEntry);
            } catch (IOException exception) {
                sendColdPrepareMiss(channel, prepareFrame, "persistent_cache_prepare_read_miss");
                return;
            }
            if (packetBytes == null
                    || packetBytes.length == 0
                    || packetBytes.length > readyCacheByteBudget()
                    || !matchesHash(packetBytes, coldEntry.payloadHash())) {
                sendColdPrepareMiss(channel, prepareFrame, "persistent_cache_prepare_hash_miss");
                return;
            }

            synchronized (LOCK) {
                if (requestedGeneration != activeScopeGeneration
                        || !requestedScope.equals(activeServerScopeHash)) {
                    return;
                }
            }
            channel.eventLoop().execute(() -> {
                if (!channel.isOpen() || !requestedScope.equals(currentServerScopeHash())) {
                    return;
                }
                if (!getOrCreatePreparedReadyState(channel).put(
                        requestedScope,
                        prepareFrame.coordinate(),
                        coldEntry.payloadHash(),
                        packetBytes,
                        PREPARED_READY_TTL_NANOS
                )) {
                    ChunkTransportControlFrameSender.sendPersistentCacheMiss(
                            channel,
                            prepareFrame,
                            "persistent_cache_prepare_ready_budget"
                    );
                    return;
                }
                ChunkTransportControlFrameSender.sendPersistentCacheReady(
                        channel,
                        prepareFrame,
                        coldEntry.payloadHash(),
                        coldEntry.protocolName(),
                        coldEntry.packetClassName(),
                        coldEntry.fullSnapshotVersion(),
                        packetBytes.length
                );
            });
        });
    }

    public static void prepareForServerSwitch(Channel channel, String reason) {
        if (channel == null) {
            return;
        }
        String previousScopeHash = currentServerScopeHash();
        boolean hadManifestForChannel = hasManifestForChannel(channel);
        channel.attr(PREPARED_READY_STATE_KEY).set(null);
        VoxyChunkBoundCompat.clearVisualHandoffs();
        clearActiveServerScope(channel, true);
        boolean cleared = (previousScopeHash != null && !previousScopeHash.isBlank()) || hadManifestForChannel;
        if (shouldLogCacheDiagnose()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkPersistentCache][Scope][Reset] channel={}, previousScope={}, hadManifest={}, reason={}, cleared={}",
                    com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel),
                    shortenHash(previousScopeHash),
                    hadManifestForChannel,
                    safeText(reason, "server_switch"),
                    cleared
            );
        }
    }

    public static int sendManifestOnce(Channel channel, String reason) {
        if (channel == null) {
            return 0;
        }

        String channelId = com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel);
        String serverScopeHash = currentServerScopeHash();
        if (!isSafeScopeHash(serverScopeHash)) {
            return 0;
        }

        if (!isEnabled()) {
            ChunkTransportControlFrameSender.sendPersistentClientCacheManifestComplete(
                    channel,
                    serverScopeHash,
                    safeText(reason, "persistent_client_cache_manifest") + "_disabled_complete"
            );
            return 0;
        }

        String manifestKey = channelId + "|" + serverScopeHash;
        if (!MANIFEST_SENT_CHANNELS.add(manifestKey)) {
            return 0;
        }

        long manifestLoadStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        List<ManifestEntry> entries = loadManifestEntries(serverScopeHash);
        List<PreparedReadySeed> advertisedEntries = capturePreparedReadySeeds(serverScopeHash, entries);
        ChunkLoadDelayProbe.logStage(
                channel,
                null,
                "client",
                "persistent_manifest_load_entries",
                ChunkLoadDelayProbe.elapsedMillisSince(manifestLoadStartNanos),
                entries.size(),
                "reason=" + safeText(reason, "persistent_client_cache_manifest")
        );
        int sentCount = 0;
        retainAdvertisedEntries(channel, serverScopeHash, advertisedEntries);
        long manifestSendStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        int batchEntries = manifestBatchEntries();
        for (int startIndex = 0; startIndex < entries.size(); startIndex += batchEntries) {
            int endIndex = Math.min(startIndex + batchEntries, entries.size());
            List<ManifestEntry> batch = entries.subList(startIndex, endIndex);
            byte[] batchPayloadBytes = ChunkPersistentClientCacheManifestBatchCodec.encode(batch, safeText(reason, "persistent_client_cache_manifest"));
            if (ChunkTransportControlFrameSender.sendPersistentClientCacheManifestBatch(
                    channel,
                    batchPayloadBytes,
                    batch.size(),
                    serverScopeHash,
                    safeText(reason, "persistent_client_cache_manifest") + "_batch"
            )) {
                sentCount += batch.size();
            }
        }
        ChunkTransportControlFrameSender.sendPersistentClientCacheManifestComplete(
                channel,
                serverScopeHash,
                safeText(reason, "persistent_client_cache_manifest") + "_complete"
        );
        if (shouldLogCacheDiagnose()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkPersistentCache][Manifest] channel={}, entries={}, sent={}, cacheFile={}, reason={}",
                    manifestKey,
                    entries.size(),
                    sentCount,
                    cacheFile(),
                    reason == null ? "" : reason
            );
        }
        ChunkLoadDelayProbe.logStage(
                channel,
                null,
                "client",
                "persistent_manifest_send_total",
                ChunkLoadDelayProbe.elapsedMillisSince(manifestSendStartNanos),
                sentCount,
                "entries=" + entries.size() + ", reason=" + safeText(reason, "persistent_client_cache_manifest")
        );
        return sentCount;
    }

    // Prepares manifest batches outside the Netty inbound callback.
    public static void sendManifestOnceAsync(Channel channel, String reason) {
        if (channel == null) {
            return;
        }

        String channelId = com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel);
        String serverScopeHash = currentServerScopeHash();
        if (!isSafeScopeHash(serverScopeHash)) {
            return;
        }

        if (!isEnabled()) {
            channel.eventLoop().execute(() -> ChunkTransportControlFrameSender.sendPersistentClientCacheManifestComplete(
                    channel,
                    serverScopeHash,
                    safeText(reason, "persistent_client_cache_manifest") + "_disabled_complete"
            ));
            return;
        }

        String manifestKey = channelId + "|" + serverScopeHash;
        if (!MANIFEST_SENT_CHANNELS.add(manifestKey)) {
            return;
        }

        executeIo("initial-manifest", () -> sendManifestOnceFromWorker(channel, reason, manifestKey, serverScopeHash));
    }

    // Sends cache changes after the first chunk wave has already continued.
    private static void sendManifestRefreshFromWorker(
            Channel channel,
            String reason,
            String manifestKey,
            String serverScopeHash,
            long generation
    ) {
        if (channel == null || !channel.isOpen()) {
            clearManifestRefreshInFlight(generation);
            return;
        }

        List<ManifestEntry> entries = loadManifestEntries(serverScopeHash);
        List<PreparedReadySeed> advertisedEntries = capturePreparedReadySeeds(serverScopeHash, entries);
        ArrayList<ManifestBatchPayload> batchPayloads = new ArrayList<>();
        int batchEntries = manifestBatchEntries();
        for (int startIndex = 0; startIndex < entries.size(); startIndex += batchEntries) {
            int endIndex = Math.min(startIndex + batchEntries, entries.size());
            List<ManifestEntry> batch = entries.subList(startIndex, endIndex);
            batchPayloads.add(new ManifestBatchPayload(
                    ChunkPersistentClientCacheManifestBatchCodec.encode(batch, safeText(reason, "persistent_client_cache_manifest_refresh")),
                    batch.size()
            ));
        }

        channel.eventLoop().execute(() -> {
            if (!serverScopeHash.equals(currentServerScopeHash()) || !channel.isOpen()) {
                if (shouldLogCacheDiagnose()) {
                    Bandwidthoptimizer.LOGGER.info(
                            "[ChunkPersistentCache][Manifest][RefreshSkipStale] channel={}, scope={}, currentScope={}, reason={}",
                            manifestKey,
                            shortenHash(serverScopeHash),
                            shortenHash(currentServerScopeHash()),
                            safeText(reason, "persistent_client_cache_manifest_refresh")
                    );
                }
                clearManifestRefreshInFlight(generation);
                return;
            }
            int sentCount = 0;
            retainAdvertisedEntries(channel, serverScopeHash, advertisedEntries);
            for (ManifestBatchPayload batchPayload : batchPayloads) {
                if (ChunkTransportControlFrameSender.sendPersistentClientCacheManifestBatch(
                        channel,
                        batchPayload.payloadBytes,
                        batchPayload.entryCount,
                        serverScopeHash,
                        safeText(reason, "persistent_client_cache_manifest_refresh") + "_batch"
                )) {
                    sentCount += batchPayload.entryCount;
                }
            }
            ChunkTransportControlFrameSender.sendPersistentClientCacheManifestComplete(
                    channel,
                    serverScopeHash,
                    safeText(reason, "persistent_client_cache_manifest_refresh") + "_complete"
            );
            synchronized (LOCK) {
                manifestRefreshSentGeneration = Math.max(manifestRefreshSentGeneration, generation);
                if (manifestRefreshInFlightGeneration <= generation) {
                    manifestRefreshInFlightGeneration = 0L;
                }
            }
            if (shouldLogCacheDiagnose()) {
                Bandwidthoptimizer.LOGGER.info(
                        "[ChunkPersistentCache][Manifest][Refresh] channel={}, entries={}, sent={}, batches={}, generation={}, reason={}",
                        manifestKey,
                        entries.size(),
                        sentCount,
                        batchPayloads.size(),
                        generation,
                        safeText(reason, "persistent_client_cache_manifest_refresh")
                );
            }
        });
    }

    private static void clearManifestRefreshInFlight(long generation) {
        synchronized (LOCK) {
            if (manifestRefreshInFlightGeneration <= generation) {
                manifestRefreshInFlightGeneration = 0L;
            }
        }
    }

    // Builds payloads on the cache worker and writes them back on the event loop.
    private static void sendManifestOnceFromWorker(
            Channel channel,
            String reason,
            String manifestKey,
            String serverScopeHash
    ) {
        if (channel == null || !channel.isOpen()) {
            return;
        }

        long manifestLoadStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        List<ManifestEntry> entries = loadManifestEntries(serverScopeHash);
        List<PreparedReadySeed> advertisedEntries = capturePreparedReadySeeds(serverScopeHash, entries);
        BloomCatalogPayload bloomCatalogPayload = buildBloomCatalogPayload(serverScopeHash);
        ChunkLoadDelayProbe.logStage(
                channel,
                null,
                "client",
                "persistent_manifest_load_entries_async",
                ChunkLoadDelayProbe.elapsedMillisSince(manifestLoadStartNanos),
                entries.size(),
                "reason=" + safeText(reason, "persistent_client_cache_manifest")
        );

        long encodeStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        ArrayList<ManifestBatchPayload> batchPayloads = new ArrayList<>();
        int batchEntries = manifestBatchEntries();
        for (int startIndex = 0; startIndex < entries.size(); startIndex += batchEntries) {
            int endIndex = Math.min(startIndex + batchEntries, entries.size());
            List<ManifestEntry> batch = entries.subList(startIndex, endIndex);
            batchPayloads.add(new ManifestBatchPayload(
                    ChunkPersistentClientCacheManifestBatchCodec.encode(batch, safeText(reason, "persistent_client_cache_manifest")),
                    batch.size()
            ));
        }
        ChunkLoadDelayProbe.logStage(
                channel,
                null,
                "client",
                "persistent_manifest_encode_batches_async",
                ChunkLoadDelayProbe.elapsedMillisSince(encodeStartNanos),
                batchPayloads.size(),
                "entries=" + entries.size() + ", reason=" + safeText(reason, "persistent_client_cache_manifest")
        );

        long sendStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        channel.eventLoop().execute(() -> {
            if (!serverScopeHash.equals(currentServerScopeHash())) {
                if (shouldLogCacheDiagnose()) {
                    Bandwidthoptimizer.LOGGER.info(
                            "[ChunkPersistentCache][Manifest][SkipStale] channel={}, scope={}, currentScope={}, reason={}",
                            manifestKey,
                            shortenHash(serverScopeHash),
                            shortenHash(currentServerScopeHash()),
                            safeText(reason, "persistent_client_cache_manifest")
                    );
                }
                return;
            }
            int sentCount = 0;
            retainAdvertisedEntries(channel, serverScopeHash, advertisedEntries);
            if (bloomCatalogPayload != null) {
                ChunkTransportControlFrameSender.sendPersistentClientCacheBloom(
                        channel,
                        bloomCatalogPayload.payloadBytes(),
                        bloomCatalogPayload.entryCount(),
                        serverScopeHash
                );
            }
            for (ManifestBatchPayload batchPayload : batchPayloads) {
                if (ChunkTransportControlFrameSender.sendPersistentClientCacheManifestBatch(
                        channel,
                        batchPayload.payloadBytes,
                        batchPayload.entryCount,
                        serverScopeHash,
                        safeText(reason, "persistent_client_cache_manifest") + "_batch"
                )) {
                    sentCount += batchPayload.entryCount;
                }
            }
            ChunkTransportControlFrameSender.sendPersistentClientCacheManifestComplete(
                    channel,
                    serverScopeHash,
                    safeText(reason, "persistent_client_cache_manifest") + "_complete"
            );
            if (shouldLogCacheDiagnose()) {
                Bandwidthoptimizer.LOGGER.info(
                        "[ChunkPersistentCache][Manifest][Async] channel={}, entries={}, sent={}, batches={}, cacheFile={}, reason={}",
                        manifestKey,
                        entries.size(),
                        sentCount,
                        batchPayloads.size(),
                        cacheFile(),
                        reason == null ? "" : reason
                );
            }
            ChunkLoadDelayProbe.logStage(
                    channel,
                    null,
                    "client",
                    "persistent_manifest_send_total_async",
                    ChunkLoadDelayProbe.elapsedMillisSince(sendStartNanos),
                    sentCount,
                    "entries=" + entries.size() + ", batches=" + batchPayloads.size() + ", reason=" + safeText(reason, "persistent_client_cache_manifest")
            );
        });
    }
    private static List<ManifestEntry> loadManifestEntries(String serverScopeHash) {
        if (!isSafeScopeHash(serverScopeHash)) {
            return List.of();
        }
        LoadedHotPathSnapshot snapshot = loadedHotPathSnapshot;
        if (!serverScopeHash.equals(snapshot.serverScopeHash())) {
            return List.of();
        }
        ArrayList<ManifestEntry> entries = new ArrayList<>();
        snapshot.entriesByKeyPrefix().forEachValue(entry -> entries.add(entry.manifestEntry()));
        entries.sort(Comparator.comparingLong(ManifestEntry::lastUsedAtMillis).reversed());
        int limit = Math.max(readIntProperty(MANIFEST_LIMIT_PROPERTY, DEFAULT_MANIFEST_LIMIT), 0);
        return entries.size() > limit ? List.copyOf(entries.subList(0, limit)) : List.copyOf(entries);
    }

    private static List<PreparedReadySeed> capturePreparedReadySeeds(
            String serverScopeHash,
            List<ManifestEntry> entries
    ) {
        LoadedHotPathSnapshot snapshot = loadedHotPathSnapshot;
        if (!serverScopeHash.equals(snapshot.serverScopeHash()) || entries == null || entries.isEmpty()) {
            return List.of();
        }
        ArrayList<PreparedReadySeed> seeds = new ArrayList<>(entries.size());
        for (ManifestEntry entry : entries) {
            LoadedCacheEntry loaded = snapshot.entriesByKeyPrefix().get(entryPrefix(serverScopeHash, entry.coordinate()));
            if (loaded != null && entry.payloadHash().equals(loaded.payloadHash())) {
                seeds.add(new PreparedReadySeed(entry.coordinate(), entry.payloadHash(), loaded.packetBytes().clone()));
            }
        }
        return List.copyOf(seeds);
    }

    private static void retainAdvertisedEntries(
            Channel channel,
            String serverScopeHash,
            List<PreparedReadySeed> entries
    ) {
        if (channel == null || entries == null || entries.isEmpty()) {
            return;
        }
        PreparedReadyState state = getOrCreatePreparedReadyState(channel);
        for (PreparedReadySeed entry : entries) {
            state.put(
                    serverScopeHash,
                    entry.coordinate(),
                    entry.payloadHash(),
                    entry.packetBytes(),
                    ADVERTISED_READY_TTL_NANOS
            );
        }
    }

    private static BloomCatalogPayload buildBloomCatalogPayload(String serverScopeHash) {
        if (!isSafeScopeHash(serverScopeHash)) {
            return null;
        }
        ArrayList<ChunkPacketCoordinate> coordinates = new ArrayList<>();
        synchronized (LOCK) {
            if (cachedSnapshot == null) {
                return null;
            }
            Properties index = cachedSnapshot.index();
            for (String key : index.stringPropertyNames()) {
                if (!key.endsWith(".hash")) {
                    continue;
                }
                String coordinateKey = key.substring(0, key.length() - ".hash".length());
                ScopedCoordinateKey scoped = parseScopedCoordinateKey(coordinateKey);
                String hash = index.getProperty(key, "");
                if (scoped != null
                        && serverScopeHash.equals(scoped.serverScopeHash())
                        && scoped.coordinate() != null
                        && scoped.coordinate().present()
                        && isSafeHash(hash)
                        && storageAvailable(cachedSnapshot.index(), coordinateKey + ".", cachedSnapshot.verifiedBlobEntries())) {
                    coordinates.add(scoped.coordinate());
                }
            }
        }
        ChunkPersistentBloomCatalog catalog = ChunkPersistentBloomCatalog.build(coordinates);
        return new BloomCatalogPayload(catalog.encode(), catalog.entryCount());
    }

    private static ColdCacheEntry findColdCacheEntry(
            String serverScopeHash,
            ChunkPacketCoordinate coordinate
    ) {
        synchronized (LOCK) {
            if (cachedSnapshot == null || !serverScopeHash.equals(activeServerScopeHash)) {
                return null;
            }
            String keyPrefix = entryPrefix(serverScopeHash, coordinate);
            String hash = cachedSnapshot.index().getProperty(keyPrefix + "hash", "");
            if (!isSafeHash(hash)
                    || !storageAvailable(cachedSnapshot.index(), keyPrefix, cachedSnapshot.verifiedBlobEntries())) {
                return null;
            }
            return new ColdCacheEntry(
                    hash,
                    cachedSnapshot.index().getProperty(keyPrefix + "protocolName", "PLAY"),
                    cachedSnapshot.index().getProperty(keyPrefix + "packetClassName", ""),
                    readLong(cachedSnapshot.index(), keyPrefix + "fullSnapshotVersion", 1L),
                    readInt(cachedSnapshot.index(), keyPrefix + "encodedBytes", 0),
                    cachedSnapshot.index().getProperty(keyPrefix + "storageKind", STORAGE_KIND_FULL),
                    cachedSnapshot.index().getProperty(keyPrefix + "baseHash", ""),
                    cachedSnapshot.index().getProperty(keyPrefix + "deltaHash", "")
            );
        }
    }

    private static void sendColdPrepareMiss(Channel channel, ChunkHotspotFrame prepareFrame, String reason) {
        if (channel == null) {
            return;
        }
        channel.eventLoop().execute(() -> {
            if (channel.isOpen()) {
                ChunkTransportControlFrameSender.sendPersistentCacheMiss(channel, prepareFrame, reason);
            }
        });
    }


    private static boolean isStorableFullSnapshot(ChunkHotspotFrame frame) {
        return frame != null
                && frame.hotspotKind() == ChunkHotspotKind.FULL_CHUNK
                && frame.laneKind() == ChunkLaneKind.FULL
                && frame.coordinate() != null
                && frame.coordinate().present()
                && frame.payloadHash() != null
                && isSafeHash(frame.payloadHash());
    }

    private static boolean matchesHash(byte[] packetBytes, String expectedHash) {
        ChunkSnapshotFingerprint fingerprint = ChunkSnapshotFingerprintService.fingerprintOutboundPacket(packetBytes);
        return fingerprint != null
                && fingerprint.hashHex() != null
                && fingerprint.hashHex().equals(expectedHash);
    }

    private static void migrateLegacyDirectoryIfNeeded() throws IOException {
        Path legacyRoot = legacyRootDirectory();
        Path legacyIndexPath = legacyRoot.resolve(INDEX_ENTRY_NAME);
        if (Files.isRegularFile(cacheFile()) || !Files.isRegularFile(legacyIndexPath)) {
            return;
        }

        Properties legacyIndex = new Properties();
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Files.readAllBytes(legacyIndexPath))) {
            legacyIndex.load(inputStream);
        }

        Map<String, byte[]> legacyBlobs = new HashMap<>();
        Path legacyBlobDirectory = legacyRoot.resolve("blobs");
        if (Files.isDirectory(legacyBlobDirectory)) {
            try (Stream<Path> paths = Files.list(legacyBlobDirectory)) {
                for (Path blobPath : paths.filter(Files::isRegularFile).toList()) {
                    String fileName = fileName(blobPath);
                    if (!fileName.endsWith(".bin")) {
                        continue;
                    }
                    String hash = fileName.substring(0, fileName.length() - ".bin".length());
                    if (isSafeHash(hash)) {
                        legacyBlobs.put(blobEntryName(hash), Files.readAllBytes(blobPath));
                    }
                }
            }
        }

        ZipCacheSnapshot migratedSnapshot = new ZipCacheSnapshot(legacyIndex, legacyBlobs);
        cachedSnapshot = writeZipCacheSnapshot(migratedSnapshot);
        deleteDirectoryTree(legacyRoot);
        if (shouldLogCacheDiagnose()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkPersistentCache][Migrate] legacy={}, zip={}, blobs={}",
                    legacyRoot,
                    cacheFile(),
                    legacyBlobs.size()
            );
        }
    }

    private static void installShutdownHookIfNeeded() {
        if (shutdownHookInstalled) {
            return;
        }
        shutdownHookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> runMonitoredIoOperation("shutdown-flush", () -> flushNow("jvm_shutdown")),
                "BandwidthOptimizer-ChunkPersistentCache-Shutdown"
        ));
    }

    private static void startCheckpointLoopIfNeeded() {
        if (checkpointLoopStarted) {
            return;
        }
        checkpointLoopStarted = true;
        long intervalMillis = checkpointIntervalMillis();
        scheduleIoWithFixedDelay(
                "periodic-checkpoint",
                () -> flushNow("periodic_checkpoint"),
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
    }

    private static void startBackupLoopIfNeeded() {
        if (backupLoopStarted) {
            return;
        }
        backupLoopStarted = true;
        scheduleNextBackup(backupIntervalMillis());
    }

    private static void startManifestRefreshLoopIfNeeded() {
        if (manifestRefreshLoopStarted) {
            return;
        }
        manifestRefreshLoopStarted = true;
        scheduleNextManifestRefresh(manifestRefreshIntervalMillis());
    }

    private static void scheduleNextManifestRefresh(long delayMillis) {
        scheduleIo(
                "manifest-refresh",
                () -> {
                    try {
                        runManifestRefreshTick();
                    } finally {
                        scheduleNextManifestRefresh(manifestRefreshIntervalMillis());
                    }
                },
                Math.max(delayMillis, 1_000L),
                TimeUnit.MILLISECONDS
        );
    }

    private static void runManifestRefreshTick() {
        if (!isEnabled() || !isManifestRefreshEnabled()) {
            return;
        }

        Channel channel;
        String channelId;
        String serverScopeHash;
        long generation;
        synchronized (LOCK) {
            if (manifestRefreshDirtyGeneration <= manifestRefreshSentGeneration
                    || manifestRefreshInFlightGeneration > manifestRefreshSentGeneration
                    || activeManifestRefreshChannel == null
                    || !activeManifestRefreshChannel.isOpen()
                    || !isSafeScopeHash(activeServerScopeHash)) {
                return;
            }
            // Keep background manifest refresh out of active chunk bursts.
            long nowMillis = System.currentTimeMillis();
            long quietMillis = manifestRefreshQuietMillis();
            long millisSinceDirty = manifestRefreshLastDirtyMillis <= 0L
                    ? quietMillis
                    : nowMillis - manifestRefreshLastDirtyMillis;
            if (millisSinceDirty < quietMillis) {
                ChunkLoadDelayProbe.logStage(
                        activeManifestRefreshChannel,
                        null,
                        "client",
                        "persistent_manifest_refresh_defer_active_chunk_wave",
                        0,
                        0,
                        "generation=" + manifestRefreshDirtyGeneration
                                + ", millisSinceDirty=" + millisSinceDirty
                                + ", quietMillis=" + quietMillis
                );
                return;
            }
            channel = activeManifestRefreshChannel;
            channelId = activeManifestRefreshChannelId;
            serverScopeHash = activeServerScopeHash;
            generation = manifestRefreshDirtyGeneration;
            manifestRefreshInFlightGeneration = generation;
        }

        sendManifestRefreshFromWorker(
                channel,
                "persistent_client_cache_dirty_refresh",
                channelId + "|" + serverScopeHash,
                serverScopeHash,
                generation
        );
    }

    private static void scheduleNextBackup(long delayMillis) {
        scheduleIo(
                "periodic-backup",
                () -> {
                    try {
                        backupNow("periodic_backup");
                    } finally {
                        scheduleNextBackup(backupIntervalMillis());
                    }
                },
                Math.max(delayMillis, 1_000L),
                TimeUnit.MILLISECONDS
        );
    }

    private static void backupNow(String reason) {
        if (!isBackupEnabled()) {
            return;
        }
        flushNow("backup_before_copy");
        Path sourcePath = cacheFile();
        if (!Files.isRegularFile(sourcePath)) {
            return;
        }

        Path targetPath = backupFile();
        Path tempPath = targetPath.resolveSibling(targetPath.getFileName() + ".tmp");
        try {
            long startedAtMillis = System.currentTimeMillis();
            Files.createDirectories(targetPath.getParent());
            Files.copy(sourcePath, tempPath, StandardCopyOption.REPLACE_EXISTING);
            moveReplacing(tempPath, targetPath);
            if (shouldLogCacheDiagnose()) {
                Bandwidthoptimizer.LOGGER.info(
                        "[ChunkPersistentCache][Backup] cacheFile={}, backupFile={}, bytes={}, millis={}, reason={}",
                        sourcePath,
                        targetPath,
                        Files.size(targetPath),
                        System.currentTimeMillis() - startedAtMillis,
                        safeText(reason, "periodic_backup")
                );
            }
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException ignored) {
            }
            if (shouldLogCacheDiagnose()) {
                Bandwidthoptimizer.LOGGER.warn(
                        "[ChunkPersistentCache][Backup][Fail] cacheFile={}, backupFile={}, reason={}",
                        sourcePath,
                        targetPath,
                        exception.toString()
                );
            }
        }
    }

    private static ZipCacheSnapshot cachedZipCacheSnapshot() throws IOException {
        long startNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        installShutdownHookIfNeeded();
        startCheckpointLoopIfNeeded();
        if (cachedSnapshot == null) {
            CachePreloadResult preload = loadV2CacheFromDisk();
            cachedSnapshot = preload.snapshot();
            replaceReadyBlobsLocked(preload.readyBlobs());
            publishLoadedHotPathSnapshotLocked();
        }
        ChunkLoadDelayProbe.logStage(
                (Channel) null,
                null,
                "client",
                "persistent_cached_snapshot",
                ChunkLoadDelayProbe.elapsedMillisSince(startNanos),
                cachedSnapshot == null ? 0 : cachedSnapshot.verifiedBlobEntries().size(),
                "loaded=" + (cachedSnapshot != null)
        );
        return cachedSnapshot;
    }

    private static PersistentChunkCacheDiskStore persistentDiskStore() throws IOException {
        PersistentChunkCacheDiskStore current = diskStore;
        if (current != null) {
            return current;
        }
        synchronized (DISK_INIT_LOCK) {
            if (diskStore == null) {
                diskStore = PersistentChunkCacheDiskStore.open(cacheRoot(), zipCompressionLevel());
            }
            return diskStore;
        }
    }

    private static CachePreloadResult loadV2CacheFromDisk() throws IOException {
        PersistentChunkCacheDiskStore store = persistentDiskStore();
        Properties index = store.loadIndex();
        boolean migrationPendingAtStartup = Files.isRegularFile(cacheRoot().resolve(MIGRATION_PENDING_FILE_NAME));
        if (index.isEmpty() || (!migrationPendingAtStartup && hasLegacyCacheSource())) {
            migrateLegacyCaches(store, index);
            index = store.loadIndex();
        }
        if (migrationPendingAtStartup && verifyOrRepairLegacyMigration(store, index)) {
            cleanupLegacyCachesAfterVerifiedRestart();
        }

        Set<String> availableHashes = store.availableHashes();
        removeUnavailableIndexEntries(index, availableHashes);
        LinkedHashMap<String, byte[]> ready = preloadReadyBlobs(store, index);
        Set<String> verifiedEntries = new HashSet<>();
        for (String hash : availableHashes) {
            verifiedEntries.add(blobEntryName(hash));
        }
        return new CachePreloadResult(
                new ZipCacheSnapshot(index, new HashMap<>(), verifiedEntries, cacheRoot()),
                ready,
                ready.values().stream().mapToLong(bytes -> bytes.length).sum()
        );
    }

    private static LinkedHashMap<String, byte[]> preloadReadyBlobs(
            PersistentChunkCacheDiskStore store,
            Properties index
    ) {
        return preloadReadyBlobs(store, index, "");
    }

    private static LinkedHashMap<String, byte[]> preloadReadyBlobs(
            PersistentChunkCacheDiskStore store,
            Properties index,
            String requiredScopeHash
    ) {
        ArrayList<String> coordinateKeys = new ArrayList<>();
        for (String key : index.stringPropertyNames()) {
            if (key.endsWith(".hash")) {
                String coordinateKey = key.substring(0, key.length() - ".hash".length());
                ScopedCoordinateKey parsed = parseScopedCoordinateKey(coordinateKey);
                if (requiredScopeHash == null
                        || requiredScopeHash.isBlank()
                        || (parsed != null && requiredScopeHash.equals(parsed.serverScopeHash()))) {
                    coordinateKeys.add(coordinateKey);
                }
            }
        }
        coordinateKeys.sort(Comparator.comparingLong(
                coordinateKey -> -readLong(index, coordinateKey + ".lastUsedAtMillis", 0L)
        ));
        LinkedHashMap<String, byte[]> ready = new LinkedHashMap<>();
        long budget = readyCacheByteBudget();
        long loadedBytes = 0L;
        Set<String> attemptedHashes = new HashSet<>();
        for (String coordinateKey : coordinateKeys) {
            String hash = index.getProperty(coordinateKey + ".hash", "");
            if (!isSafeHash(hash) || !attemptedHashes.add(hash)) {
                continue;
            }
            int encodedBytes = readInt(index, coordinateKey + ".encodedBytes", 0);
            if (encodedBytes <= 0 || encodedBytes > budget - loadedBytes) {
                continue;
            }
            try {
                byte[] packetBytes = readStoredPacketBytes(store, index, coordinateKey + ".");
                if (packetBytes != null && packetBytes.length > 0 && matchesHash(packetBytes, hash)) {
                    ready.put(hash, packetBytes);
                    loadedBytes += packetBytes.length;
                }
            } catch (IOException ignored) {
                // A damaged cache entry is a normal miss.
            }
        }
        return ready;
    }

    private static void prepareReadyScopeAsync(Channel channel, String serverScopeHash) {
        executeIo("scope-preload", () -> {
            try {
                Properties index;
                synchronized (LOCK) {
                    if (cachedSnapshot == null || !serverScopeHash.equals(activeServerScopeHash)) {
                        return;
                    }
                    index = copyProperties(cachedSnapshot.index());
                }
                LinkedHashMap<String, byte[]> ready = preloadReadyBlobs(persistentDiskStore(), index, serverScopeHash);
                synchronized (LOCK) {
                    if (!serverScopeHash.equals(activeServerScopeHash)) {
                        return;
                    }
                    replaceReadyBlobsLocked(ready);
                    publishLoadedHotPathSnapshotLocked();
                    markManifestRefreshDirty();
                }
            } catch (IOException ignored) {
                // The initial manifest remains empty and the server falls back to FULL.
            }
        });
    }

    private static void migrateLegacyCaches(PersistentChunkCacheDiskStore store, Properties targetIndex) throws IOException {
        migrateLegacyDirectoryIfNeeded();
        ZipCacheSnapshot legacy = readZipCacheSnapshot();
        if (legacy.index().isEmpty()) {
            return;
        }
        ArrayList<String> staleCoordinateKeys = new ArrayList<>();
        for (String key : legacy.index().stringPropertyNames()) {
            if (!key.endsWith(".hash")) {
                continue;
            }
            String coordinateKey = key.substring(0, key.length() - ".hash".length());
            String hash = legacy.index().getProperty(key, "");
            try {
                byte[] packetBytes = readZipBlobEntryBytes(legacy.sourcePath(), blobEntryName(hash));
                if (packetBytes == null || !matchesHash(packetBytes, hash)) {
                    staleCoordinateKeys.add(coordinateKey);
                    continue;
                }
                store.writeBlob(hash, packetBytes);
                copyIndexEntry(legacy.index(), targetIndex, coordinateKey);
                store.appendIndexEntry(coordinateKey + ".", targetIndex);
            } catch (IOException exception) {
                staleCoordinateKeys.add(coordinateKey);
            }
        }
        for (String coordinateKey : staleCoordinateKeys) {
            removeSnapshotIndexEntry(targetIndex, coordinateKey);
        }
        store.checkpoint(targetIndex);
        Files.createDirectories(cacheRoot());
        Files.writeString(cacheRoot().resolve(MIGRATION_PENDING_FILE_NAME), Long.toString(System.currentTimeMillis()));
    }

    private static void cleanupLegacyCachesAfterVerifiedRestart() {
        Path marker = cacheRoot().resolve(MIGRATION_PENDING_FILE_NAME);
        if (!Files.isRegularFile(marker)) {
            return;
        }
        try {
            Files.deleteIfExists(cacheFile());
            Files.deleteIfExists(backupFile());
            Files.deleteIfExists(cacheFile().resolveSibling(ZIP_FILE_NAME + ".tmp"));
            deleteDirectoryTree(legacyRootDirectory());
            Files.deleteIfExists(marker);
        } catch (IOException ignored) {
        }
    }

    private static void removeUnavailableIndexEntries(Properties index, Set<String> availableHashes) {
        ArrayList<String> stale = new ArrayList<>();
        for (String key : index.stringPropertyNames()) {
            if (key.endsWith(".hash")) {
                String coordinateKey = key.substring(0, key.length() - ".hash".length());
                Set<String> entryStorageHashes = storageHashes(index, coordinateKey + ".");
                if (entryStorageHashes.isEmpty() || !entryStorageHashes.stream().allMatch(availableHashes::contains)) {
                    stale.add(coordinateKey);
                }
            }
        }
        stale.forEach(coordinateKey -> removeSnapshotIndexEntry(index, coordinateKey));
    }

    private static byte[] readStoredPacketBytes(
            PersistentChunkCacheDiskStore store,
            Properties index,
            String keyPrefix
    ) throws IOException {
        String payloadHash = index.getProperty(keyPrefix + "hash", "");
        String storageKind = index.getProperty(keyPrefix + "storageKind", STORAGE_KIND_FULL);
        byte[] packetBytes;
        if (STORAGE_KIND_DELTA.equals(storageKind)) {
            String baseHash = index.getProperty(keyPrefix + "baseHash", "");
            String deltaHash = index.getProperty(keyPrefix + "deltaHash", "");
            if (!isSafeHash(baseHash) || !isSafeHash(deltaHash)) {
                return null;
            }
            byte[] baseBytes = store.readBlob(baseHash);
            byte[] deltaBytes = store.readBlob(deltaHash);
            if (baseBytes == null || deltaBytes == null) {
                return null;
            }
            packetBytes = PersistentChunkCacheDeltaCodec.decode(baseBytes, deltaBytes, 64 * 1024 * 1024);
        } else {
            packetBytes = store.readBlob(payloadHash);
        }
        int expectedBytes = readInt(index, keyPrefix + "encodedBytes", 0);
        return packetBytes != null
                && (expectedBytes <= 0 || packetBytes.length == expectedBytes)
                && matchesHash(packetBytes, payloadHash)
                ? packetBytes
                : null;
    }

    private static byte[] readStoredPacketBytes(
            PersistentChunkCacheDiskStore store,
            ColdCacheEntry entry
    ) throws IOException {
        Properties index = new Properties();
        String prefix = "entry.";
        index.setProperty(prefix + "hash", entry.payloadHash());
        index.setProperty(prefix + "encodedBytes", Integer.toString(entry.encodedBytes()));
        index.setProperty(prefix + "storageKind", entry.storageKind());
        index.setProperty(prefix + "baseHash", entry.baseHash());
        index.setProperty(prefix + "deltaHash", entry.deltaHash());
        return readStoredPacketBytes(store, index, prefix);
    }

    private static Set<String> storageHashes(Properties index, String keyPrefix) {
        if (index == null || keyPrefix == null) {
            return Set.of();
        }
        String storageKind = index.getProperty(keyPrefix + "storageKind", STORAGE_KIND_FULL);
        if (STORAGE_KIND_DELTA.equals(storageKind)) {
            String baseHash = index.getProperty(keyPrefix + "baseHash", "");
            String deltaHash = index.getProperty(keyPrefix + "deltaHash", "");
            return isSafeHash(baseHash) && isSafeHash(deltaHash) ? Set.of(baseHash, deltaHash) : Set.of();
        }
        String hash = index.getProperty(keyPrefix + "hash", "");
        return isSafeHash(hash) ? Set.of(hash) : Set.of();
    }

    private static boolean storageAvailable(Properties index, String keyPrefix, Set<String> blobEntries) {
        Set<String> storageHashes = storageHashes(index, keyPrefix);
        return !storageHashes.isEmpty()
                && storageHashes.stream().allMatch(hash -> blobEntries.contains(blobEntryName(hash)));
    }

    private static void markDirty(int encodedBytes) {
        dirty = true;
        int safeEncodedBytes = Math.max(encodedBytes, 0);
        if (safeEncodedBytes > 0) {
            dirtyBlobWrites++;
            dirtyBytes += safeEncodedBytes;
        }
    }

    private static void markManifestRefreshDirty() {
        manifestRefreshDirtyGeneration++;
        manifestRefreshLastDirtyMillis = System.currentTimeMillis();
    }

    private static void queueAsyncFlush(String reason) {
        synchronized (LOCK) {
            queueAsyncFlushLocked(reason);
        }
    }

    private static void queueAsyncFlushLocked(String reason) {
        if (flushQueued) {
            return;
        }
        flushQueued = true;
        try {
            executeIo("async-flush", () -> {
                try {
                    flushNow(reason);
                } finally {
                    synchronized (LOCK) {
                        flushQueued = false;
                    }
                }
            });
        } catch (RuntimeException exception) {
            flushQueued = false;
            if (shouldLogCacheDiagnose()) {
                Bandwidthoptimizer.LOGGER.warn(
                        "[ChunkPersistentCache][Flush][QueueFail] reason={}, pending={}, error={}",
                        safeText(reason, "async_flush"),
                        PENDING_STORE_REQUEST_COUNT.get(),
                        exception.toString()
                );
            }
        }
    }

    private static void queueIdleCheckpointAfterStoreDrain() {
        synchronized (LOCK) {
            if (PENDING_STORE_REQUEST_COUNT.get() != 0 || !dirty) {
                return;
            }
            long elapsedMillis = System.currentTimeMillis() - LAST_SUCCESSFUL_FLUSH_MILLIS.get();
            if (elapsedMillis < checkpointIntervalMillis()) {
                return;
            }
            queueAsyncFlushLocked("store_drain_idle_checkpoint");
        }
    }

    public static void onChannelClosed(Channel channel, String reason) {
        if (channel == null) {
            return;
        }
        channel.attr(PREPARED_READY_STATE_KEY).set(null);
        if (clearActiveServerScope(channel, false)) {
            MAINTENANCE_COORDINATOR.request(safeText(reason, "channel_close"));
        }
    }

    private static void runPersistentMaintenance(String reason) {
        if (!isMaintenanceWindowOpen()) {
            return;
        }
        try {
            PersistentChunkCacheDiskStore store = persistentDiskStore();
            Properties workingIndex;
            synchronized (LOCK) {
                if (cachedSnapshot == null || !isMaintenanceWindowOpen()) {
                    return;
                }
                mergePendingActivityUpdatesLocked(cachedSnapshot);
                workingIndex = copyProperties(cachedSnapshot.index());
            }

            PersistentChunkCacheRetentionPolicy.RetentionPlan retention =
                    PersistentChunkCacheRetentionPolicy.plan(
                            workingIndex,
                            store::storedBytes,
                            new PersistentChunkCacheRetentionPolicy.RetentionLimits(
                                    persistentCacheMaxDiskBytes(),
                                    persistentCacheMaxEntries(),
                                    persistentCacheMinimumScopeEntries()
                            )
                    );
            for (Map.Entry<String, Integer> entry : retention.temperatures().entrySet()) {
                if (workingIndex.containsKey(entry.getKey() + "hash")) {
                    workingIndex.setProperty(entry.getKey() + "temperature", Integer.toString(entry.getValue()));
                }
            }
            synchronized (LOCK) {
                if (cachedSnapshot == null || !isMaintenanceWindowOpen()) {
                    return;
                }
                for (String keyPrefix : retention.evictedKeyPrefixes()) {
                    removeSnapshotIndexEntry(workingIndex, removeTrailingDot(keyPrefix));
                }
                cachedSnapshot.index().clear();
                cachedSnapshot.index().putAll(workingIndex);
                cachedSnapshot.verifiedBlobEntries().removeIf(
                        blobEntry -> !retention.referencedHashes().contains(hashFromBlobEntry(blobEntry))
                );
                Set<String> retainedPayloadHashes = referencedPayloadHashes(workingIndex);
                READY_BLOBS.keySet().removeIf(hash -> !retainedPayloadHashes.contains(hash));
                readyBlobBytes = READY_BLOBS.values().stream().mapToLong(bytes -> bytes.length).sum();
                dirty = false;
                dirtyBlobWrites = 0;
                dirtyBytes = 0L;
                LAST_SUCCESSFUL_FLUSH_MILLIS.set(System.currentTimeMillis());
                publishLoadedHotPathSnapshotLocked();
            }

            if (!isMaintenanceWindowOpen()) {
                synchronized (LOCK) {
                    dirty = true;
                }
                return;
            }

            if (!retention.evictedKeyPrefixes().isEmpty()) {
                store.appendIndexRemovals(retention.evictedKeyPrefixes());
            }
            store.checkpoint(workingIndex);

            store.pruneUnreferencedBlobs(retention.referencedHashes(), 0);
            long segmentBytesBeforeCompaction = store.totalSegmentBytes();
            double minimumInvalidRatio = segmentBytesBeforeCompaction > persistentCacheMaxDiskBytes()
                    ? 0.0D
                    : COMPACTION_MINIMUM_INVALID_RATIO;
            PersistentChunkCacheDiskStore.CompactionResult compaction = store.compactOneSealedSegment(
                    retention.referencedHashes(),
                    minimumInvalidRatio,
                    persistentCacheCompactionBytesPerSecond(),
                    ChunkPersistentClientCache::isMaintenanceWindowOpen
            );
            if (shouldLogCacheDiagnose()) {
                DiagnosticLog.info(
                        DiagnosticToolRegistry.Tool.CACHE_PERSISTENT_IO,
                        "action=maintenance, reason={}, evictedEntries={}, retainedHashes={}, retainedBytes={}, compacted={}, cancelled={}, segment={}, segmentBeforeBytes={}, segmentAfterBytes={}, totalSegmentBytes={}",
                        safeText(reason, "persistent_cache_maintenance"),
                        retention.evictedKeyPrefixes().size(),
                        retention.referencedHashes().size(),
                        retention.retainedBytes(),
                        compaction.compacted(),
                        compaction.cancelled(),
                        fileName(compaction.segment()),
                        compaction.beforeBytes(),
                        compaction.afterBytes(),
                        compaction.totalSegmentBytes()
                );
            }
        } catch (IOException | RuntimeException exception) {
            synchronized (LOCK) {
                dirty = true;
            }
            Bandwidthoptimizer.LOGGER.warn(
                    "[ChunkPersistentCache][Maintenance][Fail] reason={}, error={}",
                    safeText(reason, "persistent_cache_maintenance"),
                    exception.toString()
            );
        }
    }

    private static boolean isMaintenanceWindowOpen() {
        synchronized (LOCK) {
            return isEnabled()
                    && (activeConnectionChannelId == null || activeConnectionChannelId.isBlank())
                    && (activeServerScopeHash == null || activeServerScopeHash.isBlank())
                    && PENDING_STORE_REQUEST_COUNT.get() == 0;
        }
    }

    private static String removeTrailingDot(String keyPrefix) {
        return keyPrefix != null && keyPrefix.endsWith(".")
                ? keyPrefix.substring(0, keyPrefix.length() - 1)
                : keyPrefix;
    }

    private static String hashFromBlobEntry(String blobEntry) {
        if (blobEntry == null
                || !blobEntry.startsWith(BLOBS_ENTRY_DIRECTORY)
                || !blobEntry.endsWith(".bin")) {
            return "";
        }
        return blobEntry.substring(BLOBS_ENTRY_DIRECTORY.length(), blobEntry.length() - ".bin".length());
    }

    private static boolean shouldSkipBlockingFlush(String reason) {
        String safeReason = safeText(reason, "");
        return "jvm_shutdown".equals(safeReason)
                || safeReason.contains("logging_out")
                || safeReason.contains("disconnect")
                || safeReason.contains("stopping");
    }

    private static boolean shouldDeferFlushForPendingStores(String reason, int pendingStores) {
        if (pendingStores <= 0) {
            return false;
        }
        String safeReason = safeText(reason, "");
        return safeReason.contains("checkpoint") || safeReason.contains("backup");
    }

    private static ZipCacheSnapshot readZipCacheSnapshot() throws IOException {
        if (!Files.isRegularFile(cacheFile())) {
            if (Files.isRegularFile(backupFile())) {
                return readZipCacheSnapshot(backupFile());
            }
            return ZipCacheSnapshot.empty();
        }

        try {
            return readZipCacheSnapshot(cacheFile());
        } catch (IOException exception) {
            if (Files.isRegularFile(backupFile())) {
                return readZipCacheSnapshot(backupFile());
            }
            throw exception;
        }
    }

    private static ZipCacheSnapshot readZipCacheSnapshot(Path sourcePath) throws IOException {
        Properties index = new Properties();
        Map<String, byte[]> blobs = new HashMap<>();
        Set<String> blobEntries = new HashSet<>();
        try (ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(sourcePath))) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                if (zipEntry.isDirectory()) {
                    continue;
                }

                String entryName = zipEntry.getName();
                if (INDEX_ENTRY_NAME.equals(entryName)) {
                    byte[] entryBytes = readAllBytes(zipInputStream);
                    try (ByteArrayInputStream inputStream = new ByteArrayInputStream(entryBytes)) {
                        index.load(inputStream);
                    }
                } else if (entryName.startsWith(BLOBS_ENTRY_DIRECTORY) && entryName.endsWith(".bin")) {
                    blobEntries.add(entryName);
                }
            }
        }
        return verifyLoadedZipCacheSnapshot(new ZipCacheSnapshot(index, blobs, blobEntries, sourcePath));
    }

    private static ZipCacheSnapshot verifyLoadedZipCacheSnapshot(ZipCacheSnapshot cacheSnapshot) {
        if (cacheSnapshot == null || cacheSnapshot.index() == null || cacheSnapshot.blobs() == null) {
            return ZipCacheSnapshot.empty();
        }

        ArrayList<String> staleCoordinateKeys = new ArrayList<>();
        for (String key : cacheSnapshot.index().stringPropertyNames()) {
            if (!key.endsWith(".hash")) {
                continue;
            }

            String coordinateKey = key.substring(0, key.length() - ".hash".length());
            String hash = cacheSnapshot.index().getProperty(key, "");
            String blobEntryName = isSafeHash(hash) ? blobEntryName(hash) : "";
            byte[] packetBytes = blobEntryName.isBlank() ? null : cacheSnapshot.blobs().get(blobEntryName);
            if (packetBytes != null && packetBytes.length > 0 && matchesHash(packetBytes, hash)) {
                cacheSnapshot.verifiedBlobEntries().add(blobEntryName);
            } else if (!blobEntryName.isBlank() && cacheSnapshot.verifiedBlobEntries().contains(blobEntryName)) {
                continue;
            } else {
                staleCoordinateKeys.add(coordinateKey);
            }
        }

        for (String coordinateKey : staleCoordinateKeys) {
            removeSnapshotIndexEntry(cacheSnapshot.index(), coordinateKey);
        }
        pruneUnreferencedBlobs(cacheSnapshot);
        return cacheSnapshot;
    }

    private static ZipCacheSnapshot writeZipCacheSnapshot(ZipCacheSnapshot cacheSnapshot) throws IOException {
        pruneUnreferencedBlobs(cacheSnapshot);
        Path sourcePath = cacheSnapshot.sourcePath() != null && Files.isRegularFile(cacheSnapshot.sourcePath())
                ? cacheSnapshot.sourcePath()
                : null;
        Set<String> sourceBlobEntries = sourcePath == null ? Set.of() : readZipBlobEntryNames(sourcePath);
        Set<String> referencedBlobEntries = referencedBlobEntries(cacheSnapshot.index());
        ArrayList<String> staleCoordinateKeys = new ArrayList<>();
        for (String key : cacheSnapshot.index().stringPropertyNames()) {
            if (!key.endsWith(".hash")) {
                continue;
            }
            String coordinateKey = key.substring(0, key.length() - ".hash".length());
            String hash = cacheSnapshot.index().getProperty(key, "");
            String blobEntryName = isSafeHash(hash) ? blobEntryName(hash) : "";
            if (blobEntryName.isBlank()
                    || (!cacheSnapshot.blobs().containsKey(blobEntryName) && !sourceBlobEntries.contains(blobEntryName))) {
                staleCoordinateKeys.add(coordinateKey);
            }
        }
        for (String coordinateKey : staleCoordinateKeys) {
            removeSnapshotIndexEntry(cacheSnapshot.index(), coordinateKey);
        }
        referencedBlobEntries = referencedBlobEntries(cacheSnapshot.index());
        Files.createDirectories(cacheFile().getParent());
        Path tempPath = cacheFile().resolveSibling(ZIP_FILE_NAME + ".tmp");
        Set<String> writtenBlobEntries = new HashSet<>();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(tempPath))) {
            zipOutputStream.setLevel(zipCompressionLevel());
            writeZipEntry(zipOutputStream, INDEX_ENTRY_NAME, encodeIndex(cacheSnapshot.index()));
            ArrayList<String> blobEntryNames = new ArrayList<>(referencedBlobEntries);
            blobEntryNames.sort(String::compareTo);
            for (String blobEntryName : blobEntryNames) {
                byte[] blobBytes = cacheSnapshot.blobs().get(blobEntryName);
                if ((blobBytes == null || blobBytes.length == 0) && sourcePath != null) {
                    blobBytes = readZipBlobEntryBytes(sourcePath, blobEntryName);
                }
                if (blobBytes != null && blobBytes.length > 0) {
                    writeZipEntry(zipOutputStream, blobEntryName, blobBytes);
                    writtenBlobEntries.add(blobEntryName);
                }
            }
        }
        moveReplacing(tempPath, cacheFile());
        return new ZipCacheSnapshot(cacheSnapshot.index(), new HashMap<>(), writtenBlobEntries, cacheFile());
    }

    private static void publishHotPathMutations(HotPathMutation mutation) {
        if (mutation == null) {
            return;
        }
        publishHotPathMutations(new HotPathMutationSet(
                mutation.scopeGeneration(),
                mutation.upsert() == null ? Map.of() : Map.of(mutation.keyPrefix(), mutation.upsert()),
                mutation.removals()
        ));
    }

    private static void publishHotPathMutations(HotPathMutationSet mutations) {
        if (mutations == null || mutations.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            if (mutations.scopeGeneration() != activeScopeGeneration) {
                return;
            }
            LoadedHotPathSnapshot current = loadedHotPathSnapshot;
            if (current.scopeGeneration() != activeScopeGeneration
                    || !current.serverScopeHash().equals(activeServerScopeHash)) {
                publishLoadedHotPathSnapshotLocked();
                current = loadedHotPathSnapshot;
            }
            loadedHotPathSnapshot = current.withChanges(mutations.upserts(), mutations.removals());
        }
    }

    private static void publishLoadedHotPathSnapshotLocked() {
        String serverScopeHash = activeServerScopeHash == null ? "" : activeServerScopeHash;
        HOT_PATH_HASH_BY_KEY.clear();
        HOT_PATH_KEYS_BY_HASH.clear();
        if (!isSafeScopeHash(serverScopeHash) || cachedSnapshot == null) {
            loadedHotPathSnapshot = LoadedHotPathSnapshot.empty(serverScopeHash, activeScopeGeneration);
            return;
        }

        HashMap<String, LoadedCacheEntry> entriesByKeyPrefix = new HashMap<>();
        Properties index = cachedSnapshot.index();
        for (String key : index.stringPropertyNames()) {
            if (!key.endsWith(".hash")) {
                continue;
            }

            String coordinateKey = key.substring(0, key.length() - ".hash".length());
            ScopedCoordinateKey scopedCoordinateKey = parseScopedCoordinateKey(coordinateKey);
            String hash = index.getProperty(key, "");
            byte[] packetBytes = READY_BLOBS.get(hash);
            if (scopedCoordinateKey == null
                    || !serverScopeHash.equals(scopedCoordinateKey.serverScopeHash())
                    || scopedCoordinateKey.coordinate() == null
                    || !scopedCoordinateKey.coordinate().present()
                    || packetBytes == null
                    || packetBytes.length == 0) {
                continue;
            }
            ManifestEntry manifestEntry = new ManifestEntry(
                    scopedCoordinateKey.coordinate(),
                    hash,
                    index.getProperty(coordinateKey + ".protocolName", "PLAY"),
                    index.getProperty(coordinateKey + ".packetClassName", ""),
                    readLong(index, coordinateKey + ".fullSnapshotVersion", 1L),
                    readInt(index, coordinateKey + ".encodedBytes", packetBytes.length),
                    readLong(index, coordinateKey + ".lastUsedAtMillis", 0L)
            );
            String keyPrefix = entryPrefix(serverScopeHash, scopedCoordinateKey.coordinate());
            entriesByKeyPrefix.put(
                    keyPrefix,
                    new LoadedCacheEntry(hash, packetBytes, manifestEntry)
            );
            trackHotPathKeyLocked(keyPrefix, hash);
        }
        loadedHotPathSnapshot = new LoadedHotPathSnapshot(
                serverScopeHash,
                activeScopeGeneration,
                PersistentHotPathIndex.from(entriesByKeyPrefix)
        );
    }

    private static Set<String> putReadyBlobLocked(String hash, byte[] packetBytes) {
        if (!isSafeHash(hash) || packetBytes == null || packetBytes.length == 0) {
            return Set.of();
        }
        HashSet<String> evictedHashes = new HashSet<>();
        byte[] previous = READY_BLOBS.put(hash, packetBytes.clone());
        readyBlobBytes += packetBytes.length - (previous == null ? 0L : previous.length);
        long budget = readyCacheByteBudget();
        var iterator = READY_BLOBS.entrySet().iterator();
        while (readyBlobBytes > budget && READY_BLOBS.size() > 1 && iterator.hasNext()) {
            Map.Entry<String, byte[]> eldest = iterator.next();
            readyBlobBytes -= eldest.getValue().length;
            evictedHashes.add(eldest.getKey());
            iterator.remove();
        }
        return evictedHashes.isEmpty() ? Set.of() : Set.copyOf(evictedHashes);
    }

    private static HotPathMutation updateHotPathTrackingLocked(
            String serverScopeHash,
            long scopeGeneration,
            String keyPrefix,
            ChunkPacketCoordinate coordinate,
            String payloadHash,
            Properties mutation,
            Set<String> evictedHashes
    ) {
        HashSet<String> removals = new HashSet<>();
        if (evictedHashes != null) {
            for (String evictedHash : evictedHashes) {
                HashSet<String> keys = HOT_PATH_KEYS_BY_HASH.remove(evictedHash);
                if (keys == null) {
                    continue;
                }
                for (String key : keys) {
                    HOT_PATH_HASH_BY_KEY.remove(key);
                    removals.add(key);
                }
            }
        }

        LoadedCacheEntry upsert = null;
        long publicationGeneration = activeScopeGeneration;
        if (scopeGeneration == publicationGeneration
                && serverScopeHash.equals(activeServerScopeHash)
                && coordinate != null
                && coordinate.present()) {
            byte[] packetBytes = READY_BLOBS.get(payloadHash);
            if (packetBytes != null && packetBytes.length > 0) {
                removeTrackedHotPathKeyLocked(keyPrefix);
                trackHotPathKeyLocked(keyPrefix, payloadHash);
                removals.remove(keyPrefix);
                ManifestEntry manifestEntry = new ManifestEntry(
                        coordinate,
                        payloadHash,
                        mutation.getProperty(keyPrefix + "protocolName", "PLAY"),
                        mutation.getProperty(keyPrefix + "packetClassName", ""),
                        readLong(mutation, keyPrefix + "fullSnapshotVersion", 1L),
                        readInt(mutation, keyPrefix + "encodedBytes", packetBytes.length),
                        readLong(mutation, keyPrefix + "lastUsedAtMillis", 0L)
                );
                upsert = new LoadedCacheEntry(payloadHash, packetBytes, manifestEntry);
            }
        }
        return upsert == null && removals.isEmpty()
                ? null
                : new HotPathMutation(publicationGeneration, keyPrefix, upsert, Set.copyOf(removals));
    }

    private static void trackHotPathKeyLocked(String keyPrefix, String hash) {
        if (keyPrefix == null || keyPrefix.isBlank() || !isSafeHash(hash)) {
            return;
        }
        HOT_PATH_HASH_BY_KEY.put(keyPrefix, hash);
        HOT_PATH_KEYS_BY_HASH.computeIfAbsent(hash, ignored -> new HashSet<>()).add(keyPrefix);
    }

    private static void removeTrackedHotPathKeyLocked(String keyPrefix) {
        String previousHash = HOT_PATH_HASH_BY_KEY.remove(keyPrefix);
        if (previousHash == null) {
            return;
        }
        HashSet<String> keys = HOT_PATH_KEYS_BY_HASH.get(previousHash);
        if (keys != null) {
            keys.remove(keyPrefix);
            if (keys.isEmpty()) {
                HOT_PATH_KEYS_BY_HASH.remove(previousHash);
            }
        }
    }

    private static PreparedReadyState getOrCreatePreparedReadyState(Channel channel) {
        PreparedReadyState existing = channel.attr(PREPARED_READY_STATE_KEY).get();
        if (existing != null) {
            return existing;
        }
        PreparedReadyState created = new PreparedReadyState(channel);
        PreparedReadyState raced = channel.attr(PREPARED_READY_STATE_KEY).setIfAbsent(created);
        return raced == null ? created : raced;
    }

    private static void replaceReadyBlobsLocked(Map<String, byte[]> readyBlobs) {
        READY_BLOBS.clear();
        readyBlobBytes = 0L;
        if (readyBlobs != null) {
            for (Map.Entry<String, byte[]> entry : readyBlobs.entrySet()) {
                putReadyBlobLocked(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void applyIndexEntry(Properties target, String keyPrefix, Properties source) {
        for (String suffix : List.of(
                "hash", "protocolName", "packetClassName", "fullSnapshotVersion",
                "encodedBytes", "lastUsedAtMillis", "serverScopeHash",
                "hitCount", "savedBytes", "temperature", "storageKind", "baseHash", "deltaHash"
        )) {
            target.setProperty(keyPrefix + suffix, source.getProperty(keyPrefix + suffix, ""));
        }
    }

    private static void copyIndexEntry(Properties source, Properties target, String coordinateKey) {
        applyIndexEntry(target, coordinateKey + ".", source);
    }

    private static Properties copyProperties(Properties source) {
        Properties copy = new Properties();
        if (source != null) {
            copy.putAll(source);
        }
        return copy;
    }

    private static int zipCompressionLevel() {
        int configuredLevel = readIntProperty(ZIP_LEVEL_PROPERTY, DEFAULT_ZIP_LEVEL);
        if (configuredLevel < Deflater.NO_COMPRESSION || configuredLevel > Deflater.BEST_COMPRESSION) {
            return DEFAULT_ZIP_LEVEL;
        }
        return configuredLevel;
    }

    private static long checkpointIntervalMillis() {
        return Math.max(readLongProperty(CHECKPOINT_INTERVAL_MILLIS_PROPERTY, DEFAULT_CHECKPOINT_INTERVAL_MILLIS), 1_000L);
    }

    private static int manifestBatchEntries() {
        return Math.max(readIntProperty(MANIFEST_BATCH_ENTRIES_PROPERTY, DEFAULT_MANIFEST_BATCH_ENTRIES), 1);
    }

    private static boolean isManifestRefreshEnabled() {
        return Boolean.parseBoolean(System.getProperty(
                MANIFEST_REFRESH_ENABLED_PROPERTY,
                Boolean.toString(DEFAULT_MANIFEST_REFRESH_ENABLED)
        ));
    }

    private static long manifestRefreshIntervalMillis() {
        return Math.max(
                readLongProperty(MANIFEST_REFRESH_INTERVAL_MILLIS_PROPERTY, DEFAULT_MANIFEST_REFRESH_INTERVAL_MILLIS),
                1_000L
        );
    }

    private static long manifestRefreshQuietMillis() {
        return Math.max(
                readLongProperty(MANIFEST_REFRESH_QUIET_MILLIS_PROPERTY, DEFAULT_MANIFEST_REFRESH_QUIET_MILLIS),
                0L
        );
    }

    private static boolean isBackupEnabled() {
        return Boolean.parseBoolean(System.getProperty(BACKUP_ENABLED_PROPERTY, Boolean.toString(DEFAULT_BACKUP_ENABLED)));
    }

    private static long backupIntervalMillis() {
        return Math.max(readLongProperty(BACKUP_INTERVAL_MILLIS_PROPERTY, DEFAULT_BACKUP_INTERVAL_MILLIS), 1_000L);
    }

    private static int maxPendingStoreTasks() {
        return Math.max(readIntProperty(MAX_PENDING_STORE_TASKS_PROPERTY, DEFAULT_MAX_PENDING_STORE_TASKS), 1);
    }

    private static long maxPendingStoreBytes() {
        return Math.max(readLongProperty(MAX_PENDING_STORE_BYTES_PROPERTY, DEFAULT_MAX_PENDING_STORE_BYTES), 1024L * 1024L);
    }

    private static long readyCacheByteBudget() {
        return Math.max(readLongProperty(READY_CACHE_BYTES_PROPERTY, DEFAULT_READY_CACHE_BYTES), 8L * 1024L * 1024L);
    }

    private static long persistentCacheMaxDiskBytes() {
        return Math.max(readLongProperty(MAX_DISK_BYTES_PROPERTY, DEFAULT_MAX_DISK_BYTES), 128L * 1024L * 1024L);
    }

    private static int persistentCacheMaxEntries() {
        return Math.max(readIntProperty(MAX_DISK_ENTRIES_PROPERTY, DEFAULT_MAX_DISK_ENTRIES), 4_096);
    }

    private static int persistentCacheMinimumScopeEntries() {
        return Math.max(
                Math.min(readIntProperty(MIN_SCOPE_ENTRIES_PROPERTY, DEFAULT_MIN_SCOPE_ENTRIES), persistentCacheMaxEntries()),
                0
        );
    }

    private static boolean hasLegacyCacheSource() {
        return Files.isRegularFile(cacheFile())
                || Files.isRegularFile(backupFile())
                || Files.isRegularFile(legacyRootDirectory().resolve(INDEX_ENTRY_NAME));
    }

    private static boolean verifyOrRepairLegacyMigration(
            PersistentChunkCacheDiskStore store,
            Properties targetIndex
    ) {
        if (!hasLegacyCacheSource()) {
            return true;
        }
        try {
            ZipCacheSnapshot legacy = readZipCacheSnapshot();
            for (String key : targetIndex.stringPropertyNames()) {
                if (!key.endsWith(".hash")) {
                    continue;
                }
                String coordinateKey = key.substring(0, key.length() - ".hash".length());
                String targetHash = targetIndex.getProperty(key, "");
                if (!isSafeHash(targetHash)) {
                    continue;
                }
                try {
                    byte[] targetBytes = readStoredPacketBytes(store, targetIndex, coordinateKey + ".");
                    if (targetBytes != null && matchesHash(targetBytes, targetHash)) {
                        continue;
                    }
                } catch (IOException ignored) {
                    // Repair the migrated copy from the retained legacy source below.
                }
                String legacyHash = legacy.index().getProperty(key, "");
                if (!targetHash.equals(legacyHash)) {
                    continue;
                }
                byte[] legacyBytes = readZipBlobEntryBytes(legacy.sourcePath(), blobEntryName(legacyHash));
                if (legacyBytes == null || !matchesHash(legacyBytes, legacyHash)) {
                    continue;
                }
                store.rewriteBlob(legacyHash, legacyBytes);
                byte[] repairedBytes = store.readBlob(legacyHash);
                if (repairedBytes == null || !matchesHash(repairedBytes, legacyHash)) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static long persistentCacheCompactionBytesPerSecond() {
        return Math.max(
                readLongProperty(COMPACTION_BYTES_PER_SECOND_PROPERTY, DEFAULT_COMPACTION_BYTES_PER_SECOND),
                1024L * 1024L
        );
    }

    private static void recordCacheUse(
            String serverScopeHash,
            ChunkPacketCoordinate coordinate,
            int savedBytes
    ) {
        if (!isSafeScopeHash(serverScopeHash) || coordinate == null || !coordinate.present()) {
            return;
        }
        String keyPrefix = entryPrefix(serverScopeHash, coordinate);
        LAST_USED_UPDATES.put(keyPrefix, System.currentTimeMillis());
        HIT_COUNT_UPDATES.merge(keyPrefix, 1L, ChunkPersistentClientCache::saturatingAdd);
        if (savedBytes > 0) {
            SAVED_BYTES_UPDATES.merge(keyPrefix, (long) savedBytes, ChunkPersistentClientCache::saturatingAdd);
        }
    }

    private static boolean hasPendingActivityUpdates() {
        return !LAST_USED_UPDATES.isEmpty() || !HIT_COUNT_UPDATES.isEmpty() || !SAVED_BYTES_UPDATES.isEmpty();
    }

    private static void mergePendingActivityUpdatesLocked(ZipCacheSnapshot cacheSnapshot) {
        if (cacheSnapshot == null || cacheSnapshot.index() == null || !hasPendingActivityUpdates()) {
            return;
        }
        for (Map.Entry<String, Long> entry : LAST_USED_UPDATES.entrySet()) {
            String keyPrefix = entry.getKey();
            Long lastUsedAtMillis = entry.getValue();
            if (keyPrefix == null || lastUsedAtMillis == null) {
                continue;
            }
            if (LAST_USED_UPDATES.remove(keyPrefix, lastUsedAtMillis)
                    && cacheSnapshot.index().containsKey(keyPrefix + "hash")) {
                cacheSnapshot.index().setProperty(keyPrefix + "lastUsedAtMillis", Long.toString(lastUsedAtMillis));
            }
        }
        mergeCounterUpdates(cacheSnapshot.index(), HIT_COUNT_UPDATES, "hitCount");
        mergeCounterUpdates(cacheSnapshot.index(), SAVED_BYTES_UPDATES, "savedBytes");
    }

    private static void mergeCounterUpdates(
            Properties index,
            ConcurrentHashMap<String, Long> updates,
            String suffix
    ) {
        for (Map.Entry<String, Long> entry : updates.entrySet()) {
            String keyPrefix = entry.getKey();
            Long delta = entry.getValue();
            if (keyPrefix == null || delta == null) {
                continue;
            }
            if (updates.remove(keyPrefix, delta) && index.containsKey(keyPrefix + "hash")) {
                long previous = readLong(index, keyPrefix + suffix, 0L);
                index.setProperty(keyPrefix + suffix, Long.toString(saturatingAdd(previous, delta)));
            }
        }
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        if (right < 0L && left < Long.MIN_VALUE - right) {
            return Long.MIN_VALUE;
        }
        return left + right;
    }

    private static String currentServerScopeHash() {
        synchronized (LOCK) {
            return activeServerScopeHash == null ? "" : activeServerScopeHash;
        }
    }

    private static boolean clearActiveServerScope(Channel channel, boolean claimConnection) {
        String channelId = channel == null
                ? ""
                : com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel);
        boolean owned;
        synchronized (LOCK) {
            owned = claimConnection || channelId.equals(activeConnectionChannelId);
            if (owned) {
                activeScopeGeneration++;
                activeServerScopeHash = "";
                activeConnectionChannelId = claimConnection ? channelId : "";
                activeManifestRefreshChannel = null;
                activeManifestRefreshChannelId = "";
                manifestRefreshDirtyGeneration = 0L;
                manifestRefreshInFlightGeneration = 0L;
                manifestRefreshSentGeneration = 0L;
                manifestRefreshLastDirtyMillis = 0L;
                publishLoadedHotPathSnapshotLocked();
            }
        }
        removeManifestState(channel, channelId);
        return owned;
    }

    private static void removeManifestState(Channel channel, String channelId) {
        if (channel != null && channelId != null && !channelId.isBlank()) {
            MANIFEST_SENT_CHANNELS.removeIf(key -> key.startsWith(channelId + "|"));
        }
    }

    private static boolean hasManifestForChannel(Channel channel) {
        if (channel == null) {
            return false;
        }
        String channelPrefix = com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel) + "|";
        for (String manifestKey : MANIFEST_SENT_CHANNELS) {
            if (manifestKey != null && manifestKey.startsWith(channelPrefix)) {
                return true;
            }
        }
        return false;
    }

    private static void setActiveServerScopeHash(Channel channel, String serverScopeHash, String reason) {
        if (!isSafeScopeHash(serverScopeHash)) {
            return;
        }
        synchronized (LOCK) {
            activeScopeGeneration++;
            activeServerScopeHash = serverScopeHash.toLowerCase(Locale.ROOT);
            activeConnectionChannelId = channel == null
                    ? ""
                    : com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel);
            activeManifestRefreshChannel = channel;
            activeManifestRefreshChannelId = channel == null
                    ? ""
                    : com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel);
            startManifestRefreshLoopIfNeeded();
            publishLoadedHotPathSnapshotLocked();
        }
        if (channel != null) {
            MANIFEST_SENT_CHANNELS.removeIf(key -> key.startsWith(com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel) + "|"));
        }
        prepareReadyScopeAsync(channel, serverScopeHash.toLowerCase(Locale.ROOT));
        if (shouldLogCacheDiagnose()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkPersistentCache][Scope] channel={}, scope={}, reason={}",
                    channel == null ? "<none>" : com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel),
                    shortenHash(serverScopeHash),
                    safeText(reason, "server_cache_scope")
            );
        }
    }

    private static void writeZipEntry(ZipOutputStream zipOutputStream, String entryName, byte[] entryBytes) throws IOException {
        ZipEntry zipEntry = new ZipEntry(entryName);
        zipOutputStream.putNextEntry(zipEntry);
        zipOutputStream.write(entryBytes);
        zipOutputStream.closeEntry();
    }

    private static byte[] encodeIndex(Properties properties) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            properties.store(outputStream, "BandwidthOptimizer persistent client chunk cache");
            return outputStream.toByteArray();
        }
    }

    private static Set<String> readZipBlobEntryNames(Path sourcePath) throws IOException {
        if (sourcePath == null || !Files.isRegularFile(sourcePath)) {
            return Set.of();
        }
        Set<String> blobEntries = new HashSet<>();
        try (ZipFile zipFile = new ZipFile(sourcePath.toFile())) {
            Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
            while (zipEntries.hasMoreElements()) {
                ZipEntry zipEntry = zipEntries.nextElement();
                String entryName = zipEntry.getName();
                if (!zipEntry.isDirectory()
                        && entryName.startsWith(BLOBS_ENTRY_DIRECTORY)
                        && entryName.endsWith(".bin")) {
                    blobEntries.add(entryName);
                }
            }
        }
        return blobEntries;
    }

    private static byte[] readZipBlobEntryBytes(Path sourcePath, String blobEntryName) throws IOException {
        if (sourcePath == null
                || blobEntryName == null
                || blobEntryName.isBlank()
                || !Files.isRegularFile(sourcePath)) {
            return null;
        }
        try (ZipFile zipFile = new ZipFile(sourcePath.toFile())) {
            ZipEntry zipEntry = zipFile.getEntry(blobEntryName);
            if (zipEntry == null || zipEntry.isDirectory()) {
                return null;
            }
            try (InputStream inputStream = zipFile.getInputStream(zipEntry)) {
                return inputStream.readAllBytes();
            }
        }
    }

    private static byte[] readVerifiedBlobBytesLocked(ZipCacheSnapshot cacheSnapshot, String hash) throws IOException {
        if (cacheSnapshot == null || !isSafeHash(hash)) {
            return null;
        }
        String blobEntryName = blobEntryName(hash);
        byte[] packetBytes = cacheSnapshot.blobs().get(blobEntryName);
        if ((packetBytes == null || packetBytes.length == 0) && cacheSnapshot.verifiedBlobEntries().contains(blobEntryName)) {
            packetBytes = readZipBlobEntryBytes(cacheSnapshot.sourcePath(), blobEntryName);
        }
        if (packetBytes == null || packetBytes.length == 0) {
            return null;
        }
        if (!matchesHash(packetBytes, hash)) {
            cacheSnapshot.verifiedBlobEntries().remove(blobEntryName);
            cacheSnapshot.blobs().remove(blobEntryName);
            return null;
        }
        cacheSnapshot.verifiedBlobEntries().add(blobEntryName);
        return packetBytes;
    }

    private static byte[] readAllBytes(ZipInputStream zipInputStream) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int readBytes;
            while ((readBytes = zipInputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, readBytes);
            }
            return outputStream.toByteArray();
        }
    }

    private static void moveReplacing(Path tempPath, Path targetPath) throws IOException {
        try {
            Files.move(tempPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException firstFailure) {
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteDirectoryTree(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Path cacheFile() {
        return BandwidthOptimizerOutputPaths.outputRoot().resolve(ZIP_FILE_NAME);
    }

    private static Path cacheRoot() {
        return BandwidthOptimizerOutputPaths.outputRoot().resolve(V2_DIRECTORY_NAME);
    }

    private static Path backupFile() {
        return BandwidthOptimizerOutputPaths.outputRoot().resolve("client-persistent-chunk-cache.backup.zip");
    }

    private static Path legacyRootDirectory() {
        return BandwidthOptimizerOutputPaths.outputRoot().resolve(LEGACY_DIRECTORY_NAME);
    }

    private static String blobEntryName(String hash) {
        return BLOBS_ENTRY_DIRECTORY + hash.toLowerCase(Locale.ROOT) + ".bin";
    }

    private static int pruneUnreferencedBlobs(ZipCacheSnapshot cacheSnapshot) {
        if (cacheSnapshot == null || cacheSnapshot.index() == null || cacheSnapshot.blobs() == null) {
            return 0;
        }
        pruneLegacyUnscopedIndexEntries(cacheSnapshot.index());
        Set<String> referencedBlobEntries = referencedBlobEntries(cacheSnapshot.index());
        int before = cacheSnapshot.blobs().size();
        cacheSnapshot.blobs().keySet().removeIf(blobEntryName -> !referencedBlobEntries.contains(blobEntryName));
        cacheSnapshot.verifiedBlobEntries().removeIf(blobEntryName -> !referencedBlobEntries.contains(blobEntryName));
        return Math.max(before - cacheSnapshot.blobs().size(), 0);
    }

    private static void removeSnapshotIndexEntry(Properties index, String coordinateKey) {
        if (index == null || coordinateKey == null || coordinateKey.isBlank()) {
            return;
        }
        index.remove(coordinateKey + ".hash");
        index.remove(coordinateKey + ".protocolName");
        index.remove(coordinateKey + ".packetClassName");
        index.remove(coordinateKey + ".fullSnapshotVersion");
        index.remove(coordinateKey + ".encodedBytes");
        index.remove(coordinateKey + ".lastUsedAtMillis");
        index.remove(coordinateKey + ".serverScopeHash");
        index.remove(coordinateKey + ".hitCount");
        index.remove(coordinateKey + ".savedBytes");
        index.remove(coordinateKey + ".temperature");
        index.remove(coordinateKey + ".storageKind");
        index.remove(coordinateKey + ".baseHash");
        index.remove(coordinateKey + ".deltaHash");
    }

    private static Set<String> referencedBlobEntries(Properties index) {
        Set<String> referencedBlobEntries = new HashSet<>();
        if (index == null) {
            return referencedBlobEntries;
        }
        for (String key : index.stringPropertyNames()) {
            if (!key.endsWith(".hash")) {
                continue;
            }
            String coordinateKey = key.substring(0, key.length() - ".hash".length());
            ScopedCoordinateKey scopedCoordinateKey = parseScopedCoordinateKey(coordinateKey);
            if (scopedCoordinateKey == null
                    || scopedCoordinateKey.coordinate() == null
                    || !scopedCoordinateKey.coordinate().present()) {
                continue;
            }
            for (String storageHash : storageHashes(index, coordinateKey + ".")) {
                referencedBlobEntries.add(blobEntryName(storageHash));
            }
        }
        return referencedBlobEntries;
    }

    private static Set<String> referencedPayloadHashes(Properties index) {
        HashSet<String> hashes = new HashSet<>();
        if (index == null) {
            return hashes;
        }
        for (String key : index.stringPropertyNames()) {
            if (key.endsWith(".hash")) {
                String hash = index.getProperty(key, "");
                if (isSafeHash(hash)) {
                    hashes.add(hash);
                }
            }
        }
        return hashes;
    }

    private static int pruneLegacyUnscopedIndexEntries(Properties index) {
        if (index == null) {
            return 0;
        }
        ArrayList<String> staleKeys = new ArrayList<>();
        for (String key : index.stringPropertyNames()) {
            if (key != null && key.startsWith("chunk.")) {
                staleKeys.add(key);
            }
        }
        for (String staleKey : staleKeys) {
            index.remove(staleKey);
        }
        return staleKeys.size();
    }

    private static String entryPrefix(String serverScopeHash, ChunkPacketCoordinate coordinate) {
        return "scope." + serverScopeHash.toLowerCase(Locale.ROOT)
                + ".chunk." + coordinate.chunkX() + "." + coordinate.chunkZ() + ".";
    }

    private static String pendingStoreKey(String serverScopeHash, ChunkPacketCoordinate coordinate) {
        return serverScopeHash.toLowerCase(Locale.ROOT)
                + ":" + coordinate.chunkX()
                + ":" + coordinate.chunkZ();
    }

    private static ScopedCoordinateKey parseScopedCoordinateKey(String coordinateKey) {
        if (coordinateKey == null || !coordinateKey.startsWith("scope.")) {
            return null;
        }

        String[] parts = coordinateKey.split("\\.");
        if (parts.length != 5 || !"chunk".equals(parts[2]) || !isSafeScopeHash(parts[1])) {
            return null;
        }

        try {
            return new ScopedCoordinateKey(
                    parts[1].toLowerCase(Locale.ROOT),
                    ChunkPacketCoordinate.ofChunk(Integer.parseInt(parts[3]), Integer.parseInt(parts[4]))
            );
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isSafeScopeHash(String scopeHash) {
        return ChunkPersistentServerScope.isSafeScopeHash(scopeHash);
    }

    private static boolean isSafeHash(String hash) {
        return hash != null && hash.matches("[0-9a-fA-F]{64}");
    }

    private static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, Boolean.toString(DEFAULT_ENABLED)));
    }

    private static int readIntProperty(String propertyName, int fallbackValue) {
        String rawValue = System.getProperty(propertyName);
        if (rawValue == null || rawValue.isBlank()) {
            return fallbackValue;
        }
        try {
            return Integer.parseInt(rawValue.trim());
        } catch (NumberFormatException ignored) {
            return fallbackValue;
        }
    }

    private static long readLongProperty(String propertyName, long fallbackValue) {
        String rawValue = System.getProperty(propertyName);
        if (rawValue == null || rawValue.isBlank()) {
            return fallbackValue;
        }
        try {
            return Long.parseLong(rawValue.trim());
        } catch (NumberFormatException ignored) {
            return fallbackValue;
        }
    }

    private static int readInt(Properties properties, String key, int fallbackValue) {
        try {
            return Integer.parseInt(properties.getProperty(key, Integer.toString(fallbackValue)).trim());
        } catch (NumberFormatException ignored) {
            return fallbackValue;
        }
    }

    private static long readLong(Properties properties, String key, long fallbackValue) {
        try {
            return Long.parseLong(properties.getProperty(key, Long.toString(fallbackValue)).trim());
        } catch (NumberFormatException ignored) {
            return fallbackValue;
        }
    }

    private static String safeText(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text;
    }

    private static boolean shouldLogCacheDiagnose() {
        return DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.CACHE_PERSISTENT_IO)
                || DiagnosticRuntimeSwitch.isEnabled(DiagnosticRuntimeSwitch.Topic.CACHE);
    }

    private static void logStore(ChunkHotspotFrame frame, int encodedBytes, Path cachePath) {
        if (!shouldLogCacheDiagnose()) {
            return;
        }
        DiagnosticLog.info(DiagnosticToolRegistry.Tool.CACHE_PERSISTENT_IO, "action=store, chunk={}, hash={}, bytes={}, cacheFile={}",
                frame.coordinate().logText(),
                shortenHash(frame.payloadHash()),
                encodedBytes,
                cachePath
        );
    }

    private static void logLoad(ChunkHotspotFrame frame, int encodedBytes) {
        if (!shouldLogCacheDiagnose()) {
            return;
        }
        DiagnosticLog.info(DiagnosticToolRegistry.Tool.CACHE_PERSISTENT_IO, "action=load, chunk={}, hash={}, bytes={}",
                frame.coordinate().logText(),
                shortenHash(frame.payloadHash()),
                encodedBytes
        );
    }

    private static String shortenHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return "<none>";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    private static String fileName(Path path) {
        Path fileName = path == null ? null : path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    private static ScheduledThreadPoolExecutor createIoWatchdogExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, new CacheWatchdogThreadFactory());
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static void executeIo(String operation, Runnable action) {
        IO_EXECUTOR.execute(() -> runMonitoredIoOperation(operation, action));
    }

    private static void scheduleIo(String operation, Runnable action, long delay, TimeUnit unit) {
        IO_EXECUTOR.schedule(() -> runMonitoredIoOperation(operation, action), delay, unit);
    }

    private static void scheduleIoWithFixedDelay(
            String operation,
            Runnable action,
            long initialDelay,
            long delay,
            TimeUnit unit
    ) {
        IO_EXECUTOR.scheduleWithFixedDelay(
                () -> runMonitoredIoOperation(operation, action),
                initialDelay,
                delay,
                unit
        );
    }

    private static void runMonitoredIoOperation(String operation, Runnable action) {
        SlowIoOperation state = new SlowIoOperation(
                safeText(operation, "persistent-cache-io"),
                System.nanoTime(),
                new AtomicBoolean()
        );
        ScheduledFuture<?> warningFuture = null;
        try {
            warningFuture = IO_WATCHDOG_EXECUTOR.schedule(
                    () -> warnIfIoStillRunning(state),
                    SLOW_IO_WARNING_MILLIS,
                    TimeUnit.MILLISECONDS
            );
        } catch (RuntimeException ignored) {
            // Diagnostics must never affect cache IO.
        }
        try {
            action.run();
        } finally {
            state.completed().set(true);
            if (warningFuture != null) {
                warningFuture.cancel(false);
            }
        }
    }

    private static void warnIfIoStillRunning(SlowIoOperation state) {
        if (state.completed().get()) {
            return;
        }
        long nowNanos = System.nanoTime();
        if (lastSlowIoWarningNanos != 0L
                && nowNanos - lastSlowIoWarningNanos < SLOW_IO_WARNING_INTERVAL_NANOS) {
            suppressedSlowIoWarnings++;
            return;
        }
        long suppressed = suppressedSlowIoWarnings;
        suppressedSlowIoWarnings = 0L;
        lastSlowIoWarningNanos = nowNanos;
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(nowNanos - state.startedAtNanos());
        Bandwidthoptimizer.LOGGER.warn(
                "[ChunkPersistentCache][SlowIO] operation={}, elapsedMillis={}, pendingStores={}, pendingBytes={}, suppressed={}; operation is still running",
                state.operation(),
                elapsedMillis,
                PENDING_STORE_REQUEST_COUNT.get(),
                PENDING_STORE_REQUEST_BYTES.get(),
                suppressed
        );
    }

    private static final class CacheThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "BandwidthOptimizer-ChunkPersistentCache-IO");
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class CacheWatchdogThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "BandwidthOptimizer-ChunkPersistentCache-Watchdog");
            thread.setDaemon(true);
            return thread;
        }
    }

    private record SlowIoOperation(String operation, long startedAtNanos, AtomicBoolean completed) {}

    private static final class PreparedReadyState {

        private final Channel channel;
        private final LinkedHashMap<PreparedReadyKey, PreparedReadyEntry> entries = new LinkedHashMap<>();
        private long bytes;
        private long cleanupGeneration;
        private long cleanupDeadlineNanos = Long.MAX_VALUE;

        private PreparedReadyState(Channel channel) {
            this.channel = channel;
        }

        private synchronized boolean put(
                String serverScopeHash,
                ChunkPacketCoordinate coordinate,
                String payloadHash,
                byte[] packetBytes,
                long ttlNanos
        ) {
            long nowNanos = System.nanoTime();
            removeExpired(nowNanos);
            if (!isSafeScopeHash(serverScopeHash)
                    || coordinate == null
                    || !coordinate.present()
                    || !isSafeHash(payloadHash)
                    || packetBytes == null
                    || packetBytes.length == 0
                    || packetBytes.length > readyCacheByteBudget() + MAX_PREPARED_READY_EXTRA_BYTES) {
                return false;
            }

            PreparedReadyKey key = new PreparedReadyKey(serverScopeHash, coordinate, payloadHash);
            PreparedReadyEntry previous = entries.remove(key);
            if (previous != null) {
                bytes -= previous.packetBytes().length;
            }
            if (entries.size() >= MAX_PREPARED_READY_ENTRIES
                    || bytes + packetBytes.length > readyCacheByteBudget() + MAX_PREPARED_READY_EXTRA_BYTES) {
                if (previous != null) {
                    entries.put(key, previous);
                    bytes += previous.packetBytes().length;
                }
                return false;
            }
            byte[] retainedBytes = packetBytes.clone();
            entries.put(key, new PreparedReadyEntry(retainedBytes, nowNanos + Math.max(ttlNanos, 1L)));
            bytes += retainedBytes.length;
            scheduleCleanup(nowNanos);
            return true;
        }

        private synchronized byte[] take(
                String serverScopeHash,
                ChunkPacketCoordinate coordinate,
                String payloadHash
        ) {
            long nowNanos = System.nanoTime();
            removeExpired(nowNanos);
            PreparedReadyEntry entry = entries.remove(new PreparedReadyKey(serverScopeHash, coordinate, payloadHash));
            if (entry == null) {
                return null;
            }
            bytes -= entry.packetBytes().length;
            return entry.packetBytes().clone();
        }

        private void removeExpired(long nowNanos) {
            var iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                PreparedReadyEntry entry = iterator.next().getValue();
                if (nowNanos >= entry.expiresAtNanos()) {
                    bytes -= entry.packetBytes().length;
                    iterator.remove();
                }
            }
        }

        private void scheduleCleanup(long nowNanos) {
            long earliestDeadlineNanos = Long.MAX_VALUE;
            for (PreparedReadyEntry entry : entries.values()) {
                earliestDeadlineNanos = Math.min(earliestDeadlineNanos, entry.expiresAtNanos());
            }
            if (earliestDeadlineNanos == Long.MAX_VALUE || earliestDeadlineNanos >= cleanupDeadlineNanos) {
                return;
            }
            long generation = ++cleanupGeneration;
            cleanupDeadlineNanos = earliestDeadlineNanos;
            long delayNanos = Math.max(earliestDeadlineNanos - nowNanos, 1L);
            channel.eventLoop().schedule(() -> cleanupExpired(generation), delayNanos, TimeUnit.NANOSECONDS);
        }

        private synchronized void cleanupExpired(long generation) {
            if (generation != cleanupGeneration) {
                return;
            }
            long nowNanos = System.nanoTime();
            removeExpired(nowNanos);
            cleanupDeadlineNanos = Long.MAX_VALUE;
            scheduleCleanup(nowNanos);
        }
    }

    private record PreparedReadyKey(
            String serverScopeHash,
            ChunkPacketCoordinate coordinate,
            String payloadHash
    ) {
    }

    private record PreparedReadyEntry(byte[] packetBytes, long expiresAtNanos) {
    }

    private record ScopedCoordinateKey(
            String serverScopeHash,
            ChunkPacketCoordinate coordinate
    ) {
    }

    private record LoadedHotPathSnapshot(
            String serverScopeHash,
            long scopeGeneration,
            PersistentHotPathIndex<LoadedCacheEntry> entriesByKeyPrefix
    ) {
        private LoadedHotPathSnapshot {
            serverScopeHash = serverScopeHash == null ? "" : serverScopeHash;
            entriesByKeyPrefix = entriesByKeyPrefix == null ? PersistentHotPathIndex.empty() : entriesByKeyPrefix;
        }

        private static LoadedHotPathSnapshot empty(String serverScopeHash) {
            return empty(serverScopeHash, 0L);
        }

        private static LoadedHotPathSnapshot empty(String serverScopeHash, long scopeGeneration) {
            return new LoadedHotPathSnapshot(serverScopeHash, scopeGeneration, PersistentHotPathIndex.empty());
        }

        private LoadedHotPathSnapshot withChanges(
                Map<String, LoadedCacheEntry> upserts,
                Set<String> removals
        ) {
            return new LoadedHotPathSnapshot(
                    serverScopeHash,
                    scopeGeneration,
                    entriesByKeyPrefix.withChanges(upserts, removals)
            );
        }
    }

    private record HotPathMutation(
            long scopeGeneration,
            String keyPrefix,
            LoadedCacheEntry upsert,
            Set<String> removals
    ) {
        private HotPathMutation {
            keyPrefix = keyPrefix == null ? "" : keyPrefix;
            removals = removals == null ? Set.of() : Set.copyOf(removals);
        }
    }

    private record HotPathMutationSet(
            long scopeGeneration,
            Map<String, LoadedCacheEntry> upserts,
            Set<String> removals
    ) {
        private HotPathMutationSet {
            upserts = upserts == null ? Map.of() : Map.copyOf(upserts);
            removals = removals == null ? Set.of() : Set.copyOf(removals);
        }

        private boolean isEmpty() {
            return upserts.isEmpty() && removals.isEmpty();
        }
    }

    private static final class HotPathMutationBatch {
        private long scopeGeneration = Long.MIN_VALUE;
        private final HashMap<String, LoadedCacheEntry> upserts = new HashMap<>();
        private final HashSet<String> removals = new HashSet<>();

        private boolean accepts(HotPathMutation mutation) {
            return isEmpty() || mutation.scopeGeneration() == scopeGeneration;
        }

        private void add(HotPathMutation mutation) {
            if (mutation == null) {
                return;
            }
            if (isEmpty()) {
                scopeGeneration = mutation.scopeGeneration();
            }
            for (String removal : mutation.removals()) {
                upserts.remove(removal);
                removals.add(removal);
            }
            if (mutation.upsert() != null && !mutation.keyPrefix().isBlank()) {
                removals.remove(mutation.keyPrefix());
                upserts.put(mutation.keyPrefix(), mutation.upsert());
            }
        }

        private boolean isEmpty() {
            return upserts.isEmpty() && removals.isEmpty();
        }

        private HotPathMutationSet drain() {
            if (isEmpty()) {
                return null;
            }
            HotPathMutationSet drained = new HotPathMutationSet(
                    scopeGeneration,
                    Map.copyOf(upserts),
                    Set.copyOf(removals)
            );
            scopeGeneration = Long.MIN_VALUE;
            upserts.clear();
            removals.clear();
            return drained;
        }
    }

    private record LoadedCacheEntry(
            String payloadHash,
            byte[] packetBytes,
            ManifestEntry manifestEntry
    ) {
        private LoadedCacheEntry {
            payloadHash = payloadHash == null ? "" : payloadHash;
            packetBytes = packetBytes == null ? new byte[0] : packetBytes;
            manifestEntry = manifestEntry == null
                    ? new ManifestEntry(ChunkPacketCoordinate.unknown(), "", "PLAY", "", 1L, 0, 0L)
                    : manifestEntry;
        }

        private int encodedBytes() {
            return packetBytes.length;
        }
    }

    private record CachePreloadResult(
            ZipCacheSnapshot snapshot,
            Map<String, byte[]> readyBlobs,
            long readyBytes
    ) {
    }

    private record BloomCatalogPayload(byte[] payloadBytes, int entryCount) {
        private BloomCatalogPayload {
            payloadBytes = payloadBytes == null ? new byte[0] : payloadBytes;
            entryCount = Math.max(entryCount, 0);
        }
    }

    private record ColdCacheEntry(
            String payloadHash,
            String protocolName,
            String packetClassName,
            long fullSnapshotVersion,
            int encodedBytes,
            String storageKind,
            String baseHash,
            String deltaHash
    ) {
    }

    private record PreparedReadySeed(
            ChunkPacketCoordinate coordinate,
            String payloadHash,
            byte[] packetBytes
    ) {
    }

    private record PendingStoreRequest(
            String serverScopeHash,
            String protocolName,
            long epoch,
            String packetClassName,
            ChunkHotspotKind hotspotKind,
            ChunkLaneKind laneKind,
            ChunkPacketCoordinate coordinate,
            byte[] snapshotBytes,
            long scopeGeneration,
            String reason
    ) {
        private PendingStoreRequest {
            serverScopeHash = serverScopeHash == null ? "" : serverScopeHash;
            protocolName = protocolName == null ? "PLAY" : protocolName;
            packetClassName = packetClassName == null ? "" : packetClassName;
            snapshotBytes = snapshotBytes == null ? new byte[0] : snapshotBytes.clone();
            reason = reason == null ? "" : reason;
        }
    }

    private record ZipCacheSnapshot(
            Properties index,
            Map<String, byte[]> blobs,
            Set<String> verifiedBlobEntries,
            Path sourcePath
    ) {
        private ZipCacheSnapshot(Properties index, Map<String, byte[]> blobs) {
            this(index, blobs, Set.of(), null);
        }

        private ZipCacheSnapshot(Properties index, Map<String, byte[]> blobs, Set<String> verifiedBlobEntries) {
            this(index, blobs, verifiedBlobEntries, null);
        }

        private ZipCacheSnapshot {
            Properties safeIndex = new Properties();
            if (index != null) {
                safeIndex.putAll(index);
            }
            index = safeIndex;
            blobs = blobs == null ? new HashMap<>() : new HashMap<>(blobs);
            verifiedBlobEntries = verifiedBlobEntries == null ? new HashSet<>() : new HashSet<>(verifiedBlobEntries);
        }

        private static ZipCacheSnapshot empty() {
            return new ZipCacheSnapshot(new Properties(), new HashMap<>());
        }

        private ZipCacheSnapshot copy() {
            return new ZipCacheSnapshot(index, blobs, verifiedBlobEntries, sourcePath);
        }
    }

    // Holds worker-built payloads until the event loop writes them.
    private record ManifestBatchPayload(
            byte[] payloadBytes,
            int entryCount
    ) {
    }

    public record ManifestEntry(
            ChunkPacketCoordinate coordinate,
            String payloadHash,
            String protocolName,
            String packetClassName,
            long fullSnapshotVersion,
            int encodedBytes,
            long lastUsedAtMillis
    ) {

        private ChunkHotspotFrame toFrame(String reason) {
            return new ChunkHotspotFrame(
                    com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                    com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp.CLIENT_CACHE_MANIFEST,
                    0L,
                    0L,
                    safeText(this.protocolName, "PLAY"),
                    safeText(this.packetClassName, ""),
                    ChunkHotspotKind.FULL_CHUNK,
                    ChunkLaneKind.FULL,
                    this.coordinate,
                    Math.max(this.encodedBytes, 0),
                    Math.max(this.fullSnapshotVersion, 1L),
                    0L,
                    this.payloadHash == null ? "" : this.payloadHash,
                    this.payloadHash == null ? "" : this.payloadHash,
                    0L,
                    reason == null || reason.isBlank() ? "persistent_client_cache_manifest" : reason
            );
        }
    }
}
