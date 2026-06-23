package com.PinkCats.bandwidthoptimizer.chunk.persistent;

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
import com.PinkCats.bandwidthoptimizer.debug.DebugRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.util.BandwidthOptimizerOutputPaths;
import io.netty.channel.Channel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
    private static final String CHECKPOINT_DIRTY_BLOBS_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheCheckpointDirtyBlobs";
    private static final String CHECKPOINT_DIRTY_BYTES_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheCheckpointDirtyBytes";
    private static final String BACKUP_ENABLED_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheBackupEnabled";
    private static final String BACKUP_INTERVAL_MILLIS_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheBackupMillis";
    private static final String MAX_PENDING_STORE_TASKS_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheMaxPendingStores";
    private static final boolean DEFAULT_ENABLED = true;
    private static final int DEFAULT_MANIFEST_LIMIT = 512;
    private static final int DEFAULT_MANIFEST_BATCH_ENTRIES = 512;
    private static final boolean DEFAULT_MANIFEST_REFRESH_ENABLED = true;
    private static final long DEFAULT_MANIFEST_REFRESH_INTERVAL_MILLIS = 5_000L;
    private static final long DEFAULT_MANIFEST_REFRESH_QUIET_MILLIS = 8_000L;
    private static final int DEFAULT_ZIP_LEVEL = 2;
    private static final long DEFAULT_CHECKPOINT_INTERVAL_MILLIS = 5_000L;
    private static final int DEFAULT_CHECKPOINT_DIRTY_BLOBS = 16;
    private static final long DEFAULT_CHECKPOINT_DIRTY_BYTES = 512L * 1024L;
    private static final boolean DEFAULT_BACKUP_ENABLED = true;
    private static final long DEFAULT_BACKUP_INTERVAL_MILLIS = 600_000L;
    private static final int DEFAULT_MAX_PENDING_STORE_TASKS = 2048;
    private static final Object LOCK = new Object();
    private static final Object FLUSH_LOCK = new Object();
    private static final Set<String> MANIFEST_SENT_CHANNELS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, Long> LAST_USED_UPDATES = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger PENDING_STORE_TASKS = new java.util.concurrent.atomic.AtomicInteger();
    private static final String ZIP_FILE_NAME = "client-persistent-chunk-cache.zip";
    private static final String INDEX_ENTRY_NAME = "index.properties";
    private static final String BLOBS_ENTRY_DIRECTORY = "blobs/";
    private static final String LEGACY_DIRECTORY_NAME = "client-persistent-chunk-cache";
    private static final ScheduledExecutorService IO_EXECUTOR = Executors.newSingleThreadScheduledExecutor(new CacheThreadFactory());
    private static ZipCacheSnapshot cachedSnapshot;
    private static volatile ZipCacheSnapshot loadedHotPathSnapshot;
    private static volatile String loadedHotPathServerScopeHash = "";
    private static String activeServerScopeHash = "";
    private static boolean preloadStarted;
    private static boolean checkpointLoopStarted;
    private static boolean backupLoopStarted;
    private static boolean manifestRefreshLoopStarted;
    private static boolean shutdownHookInstalled;
    private static boolean flushQueued;
    private static boolean dirty;
    private static int dirtyBlobWrites;
    private static long dirtyBytes;
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
            startBackupLoopIfNeeded();
            if (preloadStarted) {
                return;
            }
            preloadStarted = true;
        }

        IO_EXECUTOR.execute(() -> {
            long startedAtMillis = System.currentTimeMillis();
            synchronized (LOCK) {
                try {
                    cachedZipCacheSnapshot();
                    publishLoadedHotPathSnapshotLocked();
                    if (DebugRuntimeConfig.isDiagnoseEnabled()) {
                        Bandwidthoptimizer.LOGGER.info(
                                "[ChunkPersistentCache][Preload] cacheFile={}, blobs={}, millis={}, reason={}",
                                cacheFile(),
                                cachedSnapshot.blobs().size(),
                                System.currentTimeMillis() - startedAtMillis,
                                safeText(reason, "client_startup")
                        );
                    }
                } catch (IOException exception) {
                    cachedSnapshot = ZipCacheSnapshot.empty();
                    publishLoadedHotPathSnapshotLocked();
                    if (DebugRuntimeConfig.isDiagnoseEnabled()) {
                        Bandwidthoptimizer.LOGGER.warn(
                                "[ChunkPersistentCache][Preload][Fail] cacheFile={}, reason={}",
                                cacheFile(),
                                exception.toString()
                        );
                    }
                }
            }
        });
    }

    public static void flushNow(String reason) {
        ZipCacheSnapshot snapshotToWrite;
        synchronized (LOCK) {
            if (cachedSnapshot == null || (!dirty && LAST_USED_UPDATES.isEmpty())) {
                return;
            }
            mergePendingLastUsedUpdatesLocked(cachedSnapshot);
            snapshotToWrite = cachedSnapshot.copy();
            dirty = false;
            dirtyBlobWrites = 0;
            dirtyBytes = 0L;
        }

        synchronized (FLUSH_LOCK) {
            try {
                long startedAtMillis = System.currentTimeMillis();
                writeZipCacheSnapshot(snapshotToWrite);
                if (DebugRuntimeConfig.isDiagnoseEnabled()) {
                    Bandwidthoptimizer.LOGGER.info(
                            "[ChunkPersistentCache][Flush] cacheFile={}, blobs={}, millis={}, reason={}",
                            cacheFile(),
                            snapshotToWrite.blobs().size(),
                            System.currentTimeMillis() - startedAtMillis,
                            safeText(reason, "client_shutdown")
                    );
                }
            } catch (IOException exception) {
                synchronized (LOCK) {
                    dirty = true;
                }
                if (DebugRuntimeConfig.isDiagnoseEnabled()) {
                    Bandwidthoptimizer.LOGGER.warn(
                            "[ChunkPersistentCache][Flush][Fail] cacheFile={}, reason={}",
                            cacheFile(),
                            exception.toString()
                    );
                }
            }
        }
    }


    public static void storeFullSnapshot(ChunkHotspotFrame frame, byte[] restoredPacketBytes) {
        String serverScopeHash = currentServerScopeHash();
        storeFullSnapshot(frame, restoredPacketBytes, serverScopeHash, null);
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
        String serverScopeHash = loadedHotPathServerScopeHash;
        if (!isEnabled()
                || !isSafeScopeHash(serverScopeHash)
                || hotspotKind != ChunkHotspotKind.FULL_CHUNK
                || encodedPacketBytes == null
                || encodedPacketBytes.length == 0) {
            return;
        }

        int pendingStores = PENDING_STORE_TASKS.incrementAndGet();
        if (pendingStores > maxPendingStoreTasks()) {
            PENDING_STORE_TASKS.decrementAndGet();
            if (DebugRuntimeConfig.isDiagnoseEnabled()) {
                Bandwidthoptimizer.LOGGER.warn(
                        "[ChunkPersistentCache][Store][Drop] chunk={}, bytes={}, pending={}, reason={}",
                        coordinate == null ? "<none>" : coordinate.logText(),
                        encodedPacketBytes.length,
                        pendingStores,
                        safeText(reason, "persistent_cache_store_queue_full")
                );
            }
            return;
        }

        byte[] snapshotBytes = encodedPacketBytes.clone();
        try {
            IO_EXECUTOR.execute(() -> {
                try {
                    ChunkSnapshotFingerprint fingerprint =
                            ChunkSnapshotFingerprintService.fingerprintOutboundPacket(snapshotBytes);
                    if (fingerprint == null || fingerprint.hashHex() == null || fingerprint.hashHex().isBlank()) {
                        return;
                    }
                    ChunkHotspotFrame frame = new ChunkHotspotFrame(
                            ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                            ChunkHotspotFrameOp.PUBLISH_FULL,
                            Math.max(epoch, 0L),
                            0L,
                            protocolName == null ? "PLAY" : protocolName,
                            safeText(packetClassName, ""),
                            hotspotKind,
                            laneKind,
                            coordinate,
                            snapshotBytes.length,
                            1L,
                            0L,
                            fingerprint.hashHex(),
                            fingerprint.hashHex(),
                            0L,
                            safeText(reason, "persistent_cache_from_decoded_inbound_full")
                    );
                    storeFullSnapshot(frame, snapshotBytes, serverScopeHash, fingerprint);
                } finally {
                    PENDING_STORE_TASKS.decrementAndGet();
                }
            });
        } catch (RuntimeException exception) {
            PENDING_STORE_TASKS.decrementAndGet();
            if (DebugRuntimeConfig.isDiagnoseEnabled()) {
                Bandwidthoptimizer.LOGGER.warn(
                        "[ChunkPersistentCache][Store][QueueFail] chunk={}, bytes={}, reason={}",
                        coordinate == null ? "<none>" : coordinate.logText(),
                        encodedPacketBytes.length,
                        exception.toString()
                );
            }
        }
    }

    private static void storeFullSnapshot(
            ChunkHotspotFrame frame,
            byte[] restoredPacketBytes,
            String serverScopeHash,
            ChunkSnapshotFingerprint knownFingerprint
    ) {
        if (!isEnabled()
                || !isSafeScopeHash(serverScopeHash)
                || !isStorableFullSnapshot(frame)
                || restoredPacketBytes == null
                || restoredPacketBytes.length == 0) {
            return;
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
            return;
        }

        long storeStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        synchronized (LOCK) {
            try {
                ZipCacheSnapshot cacheSnapshot = cachedZipCacheSnapshot();
                String keyPrefix = entryPrefix(serverScopeHash, frame.coordinate());
                String blobEntryName = blobEntryName(fingerprint.hashHex());
                if (fingerprint.hashHex().equals(cacheSnapshot.index().getProperty(keyPrefix + "hash", ""))
                        && cacheSnapshot.blobs().containsKey(blobEntryName)) {
                    cacheSnapshot.verifiedBlobEntries().add(blobEntryName);
                    cacheSnapshot.index().setProperty(keyPrefix + "lastUsedAtMillis", Long.toString(System.currentTimeMillis()));
                    markDirty(0);
                    logStore(frame, restoredPacketBytes.length, cacheFile());
                    return;
                }
                cacheSnapshot.index().setProperty(keyPrefix + "hash", fingerprint.hashHex());
                cacheSnapshot.index().setProperty(keyPrefix + "protocolName", safeText(frame.protocolName(), "PLAY"));
                cacheSnapshot.index().setProperty(keyPrefix + "packetClassName", safeText(frame.packetClassName(), ""));
                cacheSnapshot.index().setProperty(keyPrefix + "fullSnapshotVersion", Long.toString(Math.max(frame.fullSnapshotVersion(), 1L)));
                cacheSnapshot.index().setProperty(keyPrefix + "encodedBytes", Integer.toString(restoredPacketBytes.length));
                cacheSnapshot.index().setProperty(keyPrefix + "lastUsedAtMillis", Long.toString(System.currentTimeMillis()));
                cacheSnapshot.index().setProperty(keyPrefix + "serverScopeHash", serverScopeHash);
                cacheSnapshot.blobs().putIfAbsent(blobEntryName, restoredPacketBytes.clone());
                cacheSnapshot.verifiedBlobEntries().add(blobEntryName);
                pruneUnreferencedBlobs(cacheSnapshot);
                markDirty(restoredPacketBytes.length);
                markManifestRefreshDirty();
                publishLoadedHotPathSnapshotLocked();
                logStore(frame, restoredPacketBytes.length, cacheFile());
            } catch (IOException exception) {
                if (DebugRuntimeConfig.isDiagnoseEnabled()) {
                    Bandwidthoptimizer.LOGGER.warn(
                            "[ChunkPersistentCache][Store][Fail] chunk={}, hash={}, reason={}",
                            frame.coordinate().logText(),
                            shortenHash(frame.payloadHash()),
                            exception.toString()
                    );
                }
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
    }

    public static byte[] findLoadedPacketBytes(ChunkHotspotFrame frame) {
        String serverScopeHash = loadedHotPathServerScopeHash;
        ZipCacheSnapshot cacheSnapshot = loadedHotPathSnapshot;
        if (!isEnabled()
                || !isSafeScopeHash(serverScopeHash)
                || cacheSnapshot == null
                || !isStorableFullSnapshot(frame)) {
            return null;
        }

        long loadStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        String keyPrefix = entryPrefix(serverScopeHash, frame.coordinate());
        String cachedHash = cacheSnapshot.index().getProperty(keyPrefix + "hash", "");
        if (!frame.payloadHash().equals(cachedHash)) {
            return null;
        }

        byte[] packetBytes = cacheSnapshot.blobs().get(blobEntryName(cachedHash));
        boolean matchedHash = packetBytes != null
                && packetBytes.length > 0
                && cacheSnapshot.verifiedBlobEntries().contains(blobEntryName(cachedHash));
        ChunkLoadDelayProbe.logStage(
                (Channel) null,
                frame,
                "client",
                "persistent_loaded_find_verified_check",
                0L,
                packetBytes == null ? 0 : packetBytes.length,
                "matched=" + matchedHash
        );
        if (!matchedHash) {
            return null;
        }

        recordLastUsed(serverScopeHash, frame.coordinate());
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

    public static byte[] findLoadedBasePacketBytes(ChunkHotspotFrame frame) {
        String serverScopeHash = loadedHotPathServerScopeHash;
        ZipCacheSnapshot cacheSnapshot = loadedHotPathSnapshot;
        if (!isEnabled()
                || !isSafeScopeHash(serverScopeHash)
                || cacheSnapshot == null
                || !isStorableFullSnapshot(frame)
                || frame.baseSnapshotHash() == null
                || !isSafeHash(frame.baseSnapshotHash())) {
            return null;
        }

        long loadStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        String keyPrefix = entryPrefix(serverScopeHash, frame.coordinate());
        String cachedHash = cacheSnapshot.index().getProperty(keyPrefix + "hash", "");
        if (!frame.baseSnapshotHash().equals(cachedHash)) {
            return null;
        }

        byte[] packetBytes = cacheSnapshot.blobs().get(blobEntryName(cachedHash));
        boolean matchedHash = packetBytes != null
                && packetBytes.length > 0
                && cacheSnapshot.verifiedBlobEntries().contains(blobEntryName(cachedHash));
        ChunkLoadDelayProbe.logStage(
                (Channel) null,
                frame,
                "client",
                "persistent_loaded_find_base_verified_check",
                0L,
                packetBytes == null ? 0 : packetBytes.length,
                "matched=" + matchedHash
        );
        if (!matchedHash) {
            return null;
        }

        recordLastUsed(serverScopeHash, frame.coordinate());
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
        String serverScopeHash = currentServerScopeHash();
        if (!isEnabled() || !isSafeScopeHash(serverScopeHash) || !isStorableFullSnapshot(frame)) {
            return null;
        }

        long loadStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        synchronized (LOCK) {
            try {
                ZipCacheSnapshot cacheSnapshot = cachedZipCacheSnapshot();
                String keyPrefix = entryPrefix(serverScopeHash, frame.coordinate());
                String cachedHash = cacheSnapshot.index().getProperty(keyPrefix + "hash", "");
                if (!frame.payloadHash().equals(cachedHash)) {
                    return null;
                }

                byte[] packetBytes = cacheSnapshot.blobs().get(blobEntryName(cachedHash));
                boolean matchedHash = packetBytes != null
                        && packetBytes.length > 0
                        && cacheSnapshot.verifiedBlobEntries().contains(blobEntryName(cachedHash));
                ChunkLoadDelayProbe.logStage(
                        (Channel) null,
                        frame,
                        "client",
                        "persistent_find_verified_check",
                        0L,
                        packetBytes == null ? 0 : packetBytes.length,
                        "matched=" + matchedHash
                );
                if (!matchedHash) {
                    return null;
                }

                recordLastUsed(serverScopeHash, frame.coordinate());
                logLoad(frame, packetBytes.length);
                ChunkLoadDelayProbe.logStage(
                        (Channel) null,
                        frame,
                        "client",
                        "persistent_find_total",
                        ChunkLoadDelayProbe.elapsedMillisSince(loadStartNanos),
                        packetBytes.length,
                        "hit=true"
                );
                return packetBytes.clone();
            } catch (IOException exception) {
                if (DebugRuntimeConfig.isDiagnoseEnabled()) {
                    Bandwidthoptimizer.LOGGER.warn(
                            "[ChunkPersistentCache][Load][Fail] chunk={}, hash={}, reason={}",
                            frame.coordinate().logText(),
                            shortenHash(frame.payloadHash()),
                            exception.toString()
                    );
                }
                return null;
            }
        }
    }

    public static byte[] findBasePacketBytes(ChunkHotspotFrame frame) {
        String serverScopeHash = currentServerScopeHash();
        if (!isEnabled()
                || !isSafeScopeHash(serverScopeHash)
                || !isStorableFullSnapshot(frame)
                || frame.baseSnapshotHash() == null
                || !isSafeHash(frame.baseSnapshotHash())) {
            return null;
        }

        long loadStartNanos = ChunkLoadDelayProbe.isEnabled() ? System.nanoTime() : 0L;
        synchronized (LOCK) {
            try {
                ZipCacheSnapshot cacheSnapshot = cachedZipCacheSnapshot();
                String keyPrefix = entryPrefix(serverScopeHash, frame.coordinate());
                String cachedHash = cacheSnapshot.index().getProperty(keyPrefix + "hash", "");
                if (!frame.baseSnapshotHash().equals(cachedHash)) {
                    return null;
                }

                byte[] packetBytes = cacheSnapshot.blobs().get(blobEntryName(cachedHash));
                boolean matchedHash = packetBytes != null
                        && packetBytes.length > 0
                        && cacheSnapshot.verifiedBlobEntries().contains(blobEntryName(cachedHash));
                ChunkLoadDelayProbe.logStage(
                        (Channel) null,
                        frame,
                        "client",
                        "persistent_find_base_verified_check",
                        0L,
                        packetBytes == null ? 0 : packetBytes.length,
                        "matched=" + matchedHash
                );
                if (!matchedHash) {
                    return null;
                }

                recordLastUsed(serverScopeHash, frame.coordinate());
                logLoad(frame, packetBytes.length);
                ChunkLoadDelayProbe.logStage(
                        (Channel) null,
                        frame,
                        "client",
                        "persistent_find_base_total",
                        ChunkLoadDelayProbe.elapsedMillisSince(loadStartNanos),
                        packetBytes.length,
                        "hit=true"
                );
                return packetBytes.clone();
            } catch (IOException exception) {
                if (DebugRuntimeConfig.isDiagnoseEnabled()) {
                    Bandwidthoptimizer.LOGGER.warn(
                            "[ChunkPersistentCache][LoadBase][Fail] chunk={}, hash={}, reason={}",
                            frame.coordinate().logText(),
                            shortenHash(frame.baseSnapshotHash()),
                            exception.toString()
                    );
                }
                return null;
            }
        }
    }

    public static void applyServerCacheScope(Channel channel, ChunkHotspotFrame frame) {
        if (frame == null
                || frame.operation() != com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp.SERVER_CACHE_SCOPE
                || !isSafeScopeHash(frame.payloadHash())) {
            return;
        }
        setActiveServerScopeHash(channel, frame.payloadHash().toLowerCase(Locale.ROOT), frame.reason());
    }

    public static void prepareForServerSwitch(Channel channel, String reason) {
        if (channel == null) {
            return;
        }
        String previousScopeHash = currentServerScopeHash();
        boolean hadManifestForChannel = hasManifestForChannel(channel);
        clearActiveServerScope(channel);
        boolean cleared = (previousScopeHash != null && !previousScopeHash.isBlank()) || hadManifestForChannel;
        if (DebugRuntimeConfig.isDiagnoseEnabled()) {
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
        if (DebugRuntimeConfig.isDiagnoseEnabled()) {
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

        IO_EXECUTOR.execute(() -> sendManifestOnceFromWorker(channel, reason, manifestKey, serverScopeHash));
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
                if (DebugRuntimeConfig.isDiagnoseEnabled()) {
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
            if (DebugRuntimeConfig.isDiagnoseEnabled()) {
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
                if (DebugRuntimeConfig.isDiagnoseEnabled()) {
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
            if (DebugRuntimeConfig.isDiagnoseEnabled()) {
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
        synchronized (LOCK) {
            ZipCacheSnapshot cacheSnapshot;
            try {
                cacheSnapshot = cachedZipCacheSnapshot();
            } catch (IOException ignored) {
                return List.of();
            }

            ArrayList<ManifestEntry> entries = new ArrayList<>();
            Properties index = cacheSnapshot.index();
            for (String key : index.stringPropertyNames()) {
                if (!key.endsWith(".hash")) {
                    continue;
                }

                String coordinateKey = key.substring(0, key.length() - ".hash".length());
                ScopedCoordinateKey scopedCoordinateKey = parseScopedCoordinateKey(coordinateKey);
                String hash = index.getProperty(key, "");
                if (scopedCoordinateKey == null
                        || !serverScopeHash.equals(scopedCoordinateKey.serverScopeHash())
                        || scopedCoordinateKey.coordinate() == null
                        || !scopedCoordinateKey.coordinate().present()
                        || !isSafeHash(hash)
                        || !cacheSnapshot.verifiedBlobEntries().contains(blobEntryName(hash))) {
                    continue;
                }

                entries.add(new ManifestEntry(
                        scopedCoordinateKey.coordinate(),
                        hash,
                        index.getProperty(coordinateKey + ".protocolName", "PLAY"),
                        index.getProperty(coordinateKey + ".packetClassName", ""),
                        readLong(index, coordinateKey + ".fullSnapshotVersion", 1L),
                        readInt(index, coordinateKey + ".encodedBytes", 0),
                        readLong(index, coordinateKey + ".lastUsedAtMillis", 0L)
                ));
            }

            entries.sort(Comparator.comparingLong(ManifestEntry::lastUsedAtMillis).reversed());
            int limit = Math.max(readIntProperty(MANIFEST_LIMIT_PROPERTY, DEFAULT_MANIFEST_LIMIT), 0);
            if (entries.size() > limit) {
                return List.copyOf(entries.subList(0, limit));
            }
            return List.copyOf(entries);
        }
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
        writeZipCacheSnapshot(migratedSnapshot);
        cachedSnapshot = migratedSnapshot;
        deleteDirectoryTree(legacyRoot);
        if (DebugRuntimeConfig.isDiagnoseEnabled()) {
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
                () -> flushNow("jvm_shutdown"),
                "BandwidthOptimizer-ChunkPersistentCache-Shutdown"
        ));
    }

    private static void startCheckpointLoopIfNeeded() {
        if (checkpointLoopStarted) {
            return;
        }
        checkpointLoopStarted = true;
        long intervalMillis = checkpointIntervalMillis();
        IO_EXECUTOR.scheduleWithFixedDelay(
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
        IO_EXECUTOR.schedule(
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
        IO_EXECUTOR.schedule(
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
            if (DebugRuntimeConfig.isDiagnoseEnabled()) {
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
            if (DebugRuntimeConfig.isDiagnoseEnabled()) {
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
        startBackupLoopIfNeeded();
        migrateLegacyDirectoryIfNeeded();
        if (cachedSnapshot == null) {
            cachedSnapshot = readZipCacheSnapshot();
        }
        publishLoadedHotPathSnapshotLocked();
        ChunkLoadDelayProbe.logStage(
                (Channel) null,
                null,
                "client",
                "persistent_cached_snapshot",
                ChunkLoadDelayProbe.elapsedMillisSince(startNanos),
                cachedSnapshot == null ? 0 : cachedSnapshot.blobs().size(),
                "loaded=" + (cachedSnapshot != null)
        );
        return cachedSnapshot;
    }

    private static void markDirty(int encodedBytes) {
        dirty = true;
        dirtyBlobWrites++;
        dirtyBytes += Math.max(encodedBytes, 0);
        if (dirtyBlobWrites >= checkpointDirtyBlobs() || dirtyBytes >= checkpointDirtyBytes()) {
            queueAsyncFlush("dirty_threshold_checkpoint");
        }
    }

    private static void markManifestRefreshDirty() {
        manifestRefreshDirtyGeneration++;
        manifestRefreshLastDirtyMillis = System.currentTimeMillis();
    }

    private static void queueAsyncFlush(String reason) {
        if (flushQueued) {
            return;
        }
        flushQueued = true;
        IO_EXECUTOR.execute(() -> {
            try {
                flushNow(reason);
            } finally {
                boolean shouldQueueAgain;
                synchronized (LOCK) {
                    flushQueued = false;
                    shouldQueueAgain = dirty
                            && (dirtyBlobWrites >= checkpointDirtyBlobs() || dirtyBytes >= checkpointDirtyBytes());
                }
                if (shouldQueueAgain) {
                    synchronized (LOCK) {
                        queueAsyncFlush("dirty_followup_checkpoint");
                    }
                }
            }
        });
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
        try (ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(sourcePath))) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                if (zipEntry.isDirectory()) {
                    continue;
                }

                byte[] entryBytes = readAllBytes(zipInputStream);
                String entryName = zipEntry.getName();
                if (INDEX_ENTRY_NAME.equals(entryName)) {
                    try (ByteArrayInputStream inputStream = new ByteArrayInputStream(entryBytes)) {
                        index.load(inputStream);
                    }
                } else if (entryName.startsWith(BLOBS_ENTRY_DIRECTORY) && entryName.endsWith(".bin")) {
                    blobs.put(entryName, entryBytes);
                }
            }
        }
        return verifyLoadedZipCacheSnapshot(new ZipCacheSnapshot(index, blobs));
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

    private static void writeZipCacheSnapshot(ZipCacheSnapshot cacheSnapshot) throws IOException {
        pruneUnreferencedBlobs(cacheSnapshot);
        Files.createDirectories(cacheFile().getParent());
        Path tempPath = cacheFile().resolveSibling(ZIP_FILE_NAME + ".tmp");
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(tempPath))) {
            zipOutputStream.setLevel(zipCompressionLevel());
            writeZipEntry(zipOutputStream, INDEX_ENTRY_NAME, encodeIndex(cacheSnapshot.index()));
            ArrayList<String> blobEntryNames = new ArrayList<>(cacheSnapshot.blobs().keySet());
            blobEntryNames.sort(String::compareTo);
            for (String blobEntryName : blobEntryNames) {
                byte[] blobBytes = cacheSnapshot.blobs().get(blobEntryName);
                if (blobBytes != null && blobBytes.length > 0) {
                    writeZipEntry(zipOutputStream, blobEntryName, blobBytes);
                }
            }
        }
        moveReplacing(tempPath, cacheFile());
        cachedSnapshot = cacheSnapshot;
        publishLoadedHotPathSnapshotLocked();
    }

    private static void publishLoadedHotPathSnapshotLocked() {
        loadedHotPathServerScopeHash = activeServerScopeHash == null ? "" : activeServerScopeHash;
        loadedHotPathSnapshot = cachedSnapshot == null ? null : cachedSnapshot.copy();
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

    private static int checkpointDirtyBlobs() {
        return Math.max(readIntProperty(CHECKPOINT_DIRTY_BLOBS_PROPERTY, DEFAULT_CHECKPOINT_DIRTY_BLOBS), 1);
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

    private static long checkpointDirtyBytes() {
        return Math.max(readLongProperty(CHECKPOINT_DIRTY_BYTES_PROPERTY, DEFAULT_CHECKPOINT_DIRTY_BYTES), 64L * 1024L);
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

    private static void recordLastUsed(String serverScopeHash, ChunkPacketCoordinate coordinate) {
        if (!isSafeScopeHash(serverScopeHash) || coordinate == null || !coordinate.present()) {
            return;
        }
        LAST_USED_UPDATES.put(entryPrefix(serverScopeHash, coordinate), System.currentTimeMillis());
    }

    private static void mergePendingLastUsedUpdatesLocked(ZipCacheSnapshot cacheSnapshot) {
        if (cacheSnapshot == null || cacheSnapshot.index() == null || LAST_USED_UPDATES.isEmpty()) {
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
    }

    private static String currentServerScopeHash() {
        synchronized (LOCK) {
            return activeServerScopeHash == null ? "" : activeServerScopeHash;
        }
    }

    private static void clearActiveServerScope(Channel channel) {
        synchronized (LOCK) {
            activeServerScopeHash = "";
            activeManifestRefreshChannel = null;
            activeManifestRefreshChannelId = "";
            manifestRefreshDirtyGeneration = 0L;
            manifestRefreshInFlightGeneration = 0L;
            manifestRefreshSentGeneration = 0L;
            manifestRefreshLastDirtyMillis = 0L;
            publishLoadedHotPathSnapshotLocked();
        }
        if (channel != null) {
            MANIFEST_SENT_CHANNELS.removeIf(key -> key.startsWith(com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel) + "|"));
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
            activeServerScopeHash = serverScopeHash.toLowerCase(Locale.ROOT);
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
        if (DebugRuntimeConfig.isDiagnoseEnabled()) {
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
        cacheSnapshot.verifiedBlobEntries().removeIf(blobEntryName -> !cacheSnapshot.blobs().containsKey(blobEntryName));
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
            String hash = index.getProperty(key, "");
            if (isSafeHash(hash)) {
                referencedBlobEntries.add(blobEntryName(hash));
            }
        }
        return referencedBlobEntries;
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

    private static void logStore(ChunkHotspotFrame frame, int encodedBytes, Path cachePath) {
        if (!DebugRuntimeConfig.isDiagnoseEnabled()) {
            return;
        }
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkPersistentCache][Store] chunk={}, hash={}, bytes={}, cacheFile={}",
                frame.coordinate().logText(),
                shortenHash(frame.payloadHash()),
                encodedBytes,
                cachePath
        );
    }

    private static void logLoad(ChunkHotspotFrame frame, int encodedBytes) {
        if (!DebugRuntimeConfig.isDiagnoseEnabled()) {
            return;
        }
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkPersistentCache][Load] chunk={}, hash={}, bytes={}",
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

    private static final class CacheThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "BandwidthOptimizer-ChunkPersistentCache-IO");
            thread.setDaemon(true);
            return thread;
        }
    }

    private record ScopedCoordinateKey(
            String serverScopeHash,
            ChunkPacketCoordinate coordinate
    ) {
    }

    private record ZipCacheSnapshot(
            Properties index,
            Map<String, byte[]> blobs,
            Set<String> verifiedBlobEntries
    ) {
        private ZipCacheSnapshot(Properties index, Map<String, byte[]> blobs) {
            this(index, blobs, Set.of());
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
            return new ZipCacheSnapshot(index, blobs, verifiedBlobEntries);
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
