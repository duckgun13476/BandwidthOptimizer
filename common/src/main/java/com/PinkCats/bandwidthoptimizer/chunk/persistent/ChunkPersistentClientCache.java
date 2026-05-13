package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportControlFrameSender;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
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
    private static final String ZIP_LEVEL_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheZipLevel";
    private static final String CHECKPOINT_INTERVAL_MILLIS_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheCheckpointMillis";
    private static final String CHECKPOINT_DIRTY_BLOBS_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheCheckpointDirtyBlobs";
    private static final String CHECKPOINT_DIRTY_BYTES_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheCheckpointDirtyBytes";
    private static final String BACKUP_ENABLED_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheBackupEnabled";
    private static final String BACKUP_INTERVAL_MILLIS_PROPERTY = "bandwidthoptimizer.clientPersistentChunkCacheBackupMillis";
    private static final boolean DEFAULT_ENABLED = true;
    private static final int DEFAULT_MANIFEST_LIMIT = 512;
    private static final int DEFAULT_ZIP_LEVEL = 2;
    private static final long DEFAULT_CHECKPOINT_INTERVAL_MILLIS = 5_000L;
    private static final int DEFAULT_CHECKPOINT_DIRTY_BLOBS = 16;
    private static final long DEFAULT_CHECKPOINT_DIRTY_BYTES = 512L * 1024L;
    private static final boolean DEFAULT_BACKUP_ENABLED = true;
    private static final long DEFAULT_BACKUP_INTERVAL_MILLIS = 600_000L;
    private static final Object LOCK = new Object();
    private static final Object FLUSH_LOCK = new Object();
    private static final Set<String> MANIFEST_SENT_CHANNELS = ConcurrentHashMap.newKeySet();
    private static final String ZIP_FILE_NAME = "client-persistent-chunk-cache.zip";
    private static final String INDEX_ENTRY_NAME = "index.properties";
    private static final String BLOBS_ENTRY_DIRECTORY = "blobs/";
    private static final String LEGACY_DIRECTORY_NAME = "client-persistent-chunk-cache";
    private static final ScheduledExecutorService IO_EXECUTOR = Executors.newSingleThreadScheduledExecutor(new CacheThreadFactory());
    private static ZipCacheSnapshot cachedSnapshot;
    private static String activeServerScopeHash = "";
    private static boolean preloadStarted;
    private static boolean checkpointLoopStarted;
    private static boolean backupLoopStarted;
    private static boolean shutdownHookInstalled;
    private static boolean flushQueued;
    private static boolean dirty;
    private static int dirtyBlobWrites;
    private static long dirtyBytes;


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
            if (!dirty || cachedSnapshot == null) {
                return;
            }
            snapshotToWrite = new ZipCacheSnapshot(cachedSnapshot.index(), cachedSnapshot.blobs());
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
        if (!isEnabled()
                || !isSafeScopeHash(serverScopeHash)
                || !isStorableFullSnapshot(frame)
                || restoredPacketBytes == null
                || restoredPacketBytes.length == 0) {
            return;
        }

        ChunkSnapshotFingerprint fingerprint = ChunkSnapshotFingerprintService.fingerprintOutboundPacket(restoredPacketBytes);
        if (fingerprint == null
                || fingerprint.hashHex() == null
                || !fingerprint.hashHex().equals(frame.payloadHash())) {
            return;
        }

        synchronized (LOCK) {
            try {
                ZipCacheSnapshot cacheSnapshot = cachedZipCacheSnapshot();
                String keyPrefix = entryPrefix(serverScopeHash, frame.coordinate());
                String blobEntryName = blobEntryName(fingerprint.hashHex());
                if (fingerprint.hashHex().equals(cacheSnapshot.index().getProperty(keyPrefix + "hash", ""))
                        && cacheSnapshot.blobs().containsKey(blobEntryName)) {
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
                pruneUnreferencedBlobs(cacheSnapshot);
                markDirty(restoredPacketBytes.length);
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
    }


    public static byte[] findPacketBytes(ChunkHotspotFrame frame) {
        String serverScopeHash = currentServerScopeHash();
        if (!isEnabled() || !isSafeScopeHash(serverScopeHash) || !isStorableFullSnapshot(frame)) {
            return null;
        }

        synchronized (LOCK) {
            try {
                ZipCacheSnapshot cacheSnapshot = cachedZipCacheSnapshot();
                String keyPrefix = entryPrefix(serverScopeHash, frame.coordinate());
                String cachedHash = cacheSnapshot.index().getProperty(keyPrefix + "hash", "");
                if (!frame.payloadHash().equals(cachedHash)) {
                    return null;
                }

                byte[] packetBytes = cacheSnapshot.blobs().get(blobEntryName(cachedHash));
                if (packetBytes == null || packetBytes.length == 0 || !matchesHash(packetBytes, cachedHash)) {
                    return null;
                }

                cacheSnapshot.index().setProperty(keyPrefix + "lastUsedAtMillis", Long.toString(System.currentTimeMillis()));
                markDirty(0);
                logLoad(frame, packetBytes.length);
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

        synchronized (LOCK) {
            try {
                ZipCacheSnapshot cacheSnapshot = cachedZipCacheSnapshot();
                String keyPrefix = entryPrefix(serverScopeHash, frame.coordinate());
                String cachedHash = cacheSnapshot.index().getProperty(keyPrefix + "hash", "");
                if (!frame.baseSnapshotHash().equals(cachedHash)) {
                    return null;
                }

                byte[] packetBytes = cacheSnapshot.blobs().get(blobEntryName(cachedHash));
                if (packetBytes == null || packetBytes.length == 0 || !matchesHash(packetBytes, cachedHash)) {
                    return null;
                }

                cacheSnapshot.index().setProperty(keyPrefix + "lastUsedAtMillis", Long.toString(System.currentTimeMillis()));
                markDirty(0);
                logLoad(frame, packetBytes.length);
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
                    channel.id().asLongText(),
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

        String channelId = channel.id().asLongText();
        String serverScopeHash = currentServerScopeHash();
        if (!isSafeScopeHash(serverScopeHash)) {
            return 0;
        }

        if (!isEnabled()) {
            ChunkTransportControlFrameSender.sendPersistentClientCacheManifestComplete(
                    channel,
                    safeText(reason, "persistent_client_cache_manifest") + "_disabled_complete"
            );
            return 0;
        }

        String manifestKey = channelId + "|" + serverScopeHash;
        if (!MANIFEST_SENT_CHANNELS.add(manifestKey)) {
            return 0;
        }

        List<ManifestEntry> entries = loadManifestEntries(serverScopeHash);
        int sentCount = 0;
        for (ManifestEntry entry : entries) {
            if (ChunkTransportControlFrameSender.sendPersistentClientCacheManifest(channel, entry.toFrame(reason))) {
                sentCount++;
            }
        }
        ChunkTransportControlFrameSender.sendPersistentClientCacheManifestComplete(
                channel,
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
        return sentCount;
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
                        || !isSafeHash(hash)) {
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
        installShutdownHookIfNeeded();
        startCheckpointLoopIfNeeded();
        startBackupLoopIfNeeded();
        migrateLegacyDirectoryIfNeeded();
        if (cachedSnapshot == null) {
            cachedSnapshot = readZipCacheSnapshot();
        }
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
        return new ZipCacheSnapshot(index, blobs);
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

    private static long checkpointDirtyBytes() {
        return Math.max(readLongProperty(CHECKPOINT_DIRTY_BYTES_PROPERTY, DEFAULT_CHECKPOINT_DIRTY_BYTES), 64L * 1024L);
    }

    private static boolean isBackupEnabled() {
        return Boolean.parseBoolean(System.getProperty(BACKUP_ENABLED_PROPERTY, Boolean.toString(DEFAULT_BACKUP_ENABLED)));
    }

    private static long backupIntervalMillis() {
        return Math.max(readLongProperty(BACKUP_INTERVAL_MILLIS_PROPERTY, DEFAULT_BACKUP_INTERVAL_MILLIS), 1_000L);
    }

    private static String currentServerScopeHash() {
        synchronized (LOCK) {
            return activeServerScopeHash == null ? "" : activeServerScopeHash;
        }
    }

    private static void clearActiveServerScope(Channel channel) {
        synchronized (LOCK) {
            activeServerScopeHash = "";
        }
        if (channel != null) {
            MANIFEST_SENT_CHANNELS.removeIf(key -> key.startsWith(channel.id().asLongText() + "|"));
        }
    }

    private static boolean hasManifestForChannel(Channel channel) {
        if (channel == null) {
            return false;
        }
        String channelPrefix = channel.id().asLongText() + "|";
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
        }
        if (channel != null) {
            MANIFEST_SENT_CHANNELS.removeIf(key -> key.startsWith(channel.id().asLongText() + "|"));
        }
        if (DebugRuntimeConfig.isDiagnoseEnabled()) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkPersistentCache][Scope] channel={}, scope={}, reason={}",
                    channel == null ? "<none>" : channel.id().asLongText(),
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
        return Math.max(before - cacheSnapshot.blobs().size(), 0);
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
            Map<String, byte[]> blobs
    ) {
        private ZipCacheSnapshot {
            Properties safeIndex = new Properties();
            if (index != null) {
                safeIndex.putAll(index);
            }
            index = safeIndex;
            blobs = blobs == null ? new HashMap<>() : new HashMap<>(blobs);
        }

        private static ZipCacheSnapshot empty() {
            return new ZipCacheSnapshot(new Properties(), new HashMap<>());
        }
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
