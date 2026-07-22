package com.PinkCats.bandwidthoptimizer.chunk.snapshot.shadow;

import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprintService;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotMaterializer;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotSemanticKeyResolver;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.lane.ChunkLanePacketSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.lane.ChunkLaneSnapshot;
import com.PinkCats.bandwidthoptimizer.chunk.store.global.ChunkGlobalSnapshotStore;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.LoaderEnvironmentCompat;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkShadowSnapshotManager {

    private static final ConcurrentHashMap<String, ChannelShadowState> CHANNEL_STATES = new ConcurrentHashMap<>();
    private static final long DEFAULT_SERVER_ORIGINAL_BYTES_BUDGET =
            Config.RuntimeProperty.Chunk.DEFAULT_SERVER_SHADOW_ORIGINAL_BYTES_BUDGET_BYTES;
    private static final long DEFAULT_SERVER_METADATA_ENTRY_LIMIT =
            Config.RuntimeProperty.Chunk.DEFAULT_SERVER_SHADOW_METADATA_ENTRY_LIMIT;
    private static final long SERVER_BUDGET_CHECK_INTERVAL_MILLIS = 1_000L;
    private static final long SERVER_ORIGINAL_BYTES_BUDGET = readLongProperty(
            Config.RuntimeProperty.Chunk.SERVER_SHADOW_ORIGINAL_BYTES_BUDGET_BYTES,
            DEFAULT_SERVER_ORIGINAL_BYTES_BUDGET
    );
    private static final long SERVER_METADATA_ENTRY_LIMIT = readLongProperty(
            Config.RuntimeProperty.Chunk.SERVER_SHADOW_METADATA_ENTRY_LIMIT,
            DEFAULT_SERVER_METADATA_ENTRY_LIMIT
    );
    private static volatile long nextServerBudgetCheckAtMillis;
    private static volatile long serverEvictedOriginalBytes;
    private static volatile long serverEvictedOriginalPackets;
    private static volatile long serverEvictedMetadataPackets;

    private ChunkShadowSnapshotManager() {}

    public static ChunkShadowSnapshot observeOutboundPacket(
            ChannelHandlerContext context,
            long epoch,
            ChunkPacketDescriptor descriptor,
            Packet<?> packet,
            byte[] encodedPacketBytes
    ) {
        if (context == null)
            return null;
        ChunkShadowSnapshot snapshot =
                observePacket(com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(context.channel()), epoch, descriptor, packet, encodedPacketBytes);
        ChunkGlobalSnapshotStore.observeMaterializedSnapshot(com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(context.channel()), snapshot);
        return snapshot;
    }


    public static ChunkShadowSnapshot observeInboundPacket(
            String channelId,
            long epoch,
            ChunkPacketDescriptor descriptor,
            Packet<?> packet,
            byte[] encodedPacketBytes
    ) {
        return observePacket(channelId, epoch, descriptor, packet, encodedPacketBytes);
    }

    public static ChunkShadowSnapshot snapshotChunk(String channelId, long scopeId, ChunkPacketCoordinate coordinate) {
        if (channelId == null || channelId.isBlank() || coordinate == null || !coordinate.present())
            return null;

        ChannelShadowState state = CHANNEL_STATES.get(channelId);
        return state == null ? null : state.snapshotChunk(scopeId, coordinate);
    }

    public static ChunkShadowSnapshot snapshotChunk(String channelId, ChunkPacketCoordinate coordinate) {
        if (channelId == null || channelId.isBlank() || coordinate == null || !coordinate.present())
            return null;

        ChannelShadowState state = CHANNEL_STATES.get(channelId);
        return state == null ? null : state.findLatestChunkAcrossScopes(coordinate);
    }

    public static byte[] materializeFullChunkPacket(
            String channelId,
            long scopeId,
            ChunkPacketCoordinate coordinate,
            long expectedFullSnapshotVersion,
            String expectedPayloadHash
    ) {
        if (channelId == null || channelId.isBlank() || coordinate == null || !coordinate.present()) {
            return null;
        }

        ChannelShadowState state = CHANNEL_STATES.get(channelId);
        byte[] materializedPacketBytes = state == null
                ? null
                : state.materializeFullChunkPacket(scopeId, coordinate, expectedFullSnapshotVersion, expectedPayloadHash);
        if (materializedPacketBytes != null) {
            return materializedPacketBytes;
        }
        return ChunkGlobalSnapshotStore.findMaterializedFullChunkPacket(
                channelId,
                scopeId,
                coordinate,
                expectedFullSnapshotVersion,
                expectedPayloadHash
        );
    }

    public static byte[] materializeFullChunkPacket(
            String channelId,
            ChunkPacketCoordinate coordinate,
            long expectedFullSnapshotVersion,
            String expectedPayloadHash
    ) {
        return materializeFullChunkPacket(channelId, 0L, coordinate, expectedFullSnapshotVersion, expectedPayloadHash);
    }

    public static void invalidateChunk(String channelId, long scopeId, ChunkPacketCoordinate coordinate) {
        if (channelId == null || channelId.isBlank() || coordinate == null || !coordinate.present()) {
            return;
        }

        ChannelShadowState state = CHANNEL_STATES.get(channelId);
        if (state != null)
            state.invalidateChunk(scopeId, coordinate);
        ChunkGlobalSnapshotStore.invalidateChunk(channelId, scopeId, coordinate);
    }

    public static void invalidateChunk(String channelId, ChunkPacketCoordinate coordinate) {
        if (channelId == null || channelId.isBlank() || coordinate == null || !coordinate.present()) {
            return;
        }

        ChannelShadowState state = CHANNEL_STATES.get(channelId);
        if (state != null)
            state.invalidateChunkAcrossScopes(coordinate);
        ChunkGlobalSnapshotStore.invalidateChunkAcrossScopes(channelId, coordinate);
    }


    public static void clearChannel(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return;
        }
        CHANNEL_STATES.remove(channelId);
        ChunkGlobalSnapshotStore.clearChannel(channelId);
    }

    public static void clearAll() {
        CHANNEL_STATES.clear();
    }

    public static Snapshot snapshot() {
        long channelCount = 0L;
        long chunkCount = 0L;
        long fullSnapshotChunkCount = 0L;
        long packetCount = 0L;
        long totalEncodedBytes = 0L;
        long retainedOriginalBytes = 0L;
        long retainedOriginalPacketCount = 0L;

        for (ChannelShadowState channelState : CHANNEL_STATES.values()) {
            if (channelState == null) {
                continue;
            }

            ChannelSnapshotTotals channelTotals = channelState.snapshotTotals();
            if (channelTotals.chunkCount() <= 0L && channelTotals.packetCount() <= 0L) {
                continue;
            }

            channelCount++;
            chunkCount += channelTotals.chunkCount();
            fullSnapshotChunkCount += channelTotals.fullSnapshotChunkCount();
            packetCount += channelTotals.packetCount();
            totalEncodedBytes += channelTotals.totalEncodedBytes();
            retainedOriginalBytes += channelTotals.retainedOriginalBytes();
            retainedOriginalPacketCount += channelTotals.retainedOriginalPacketCount();
        }

        return new Snapshot(
                channelCount,
                chunkCount,
                fullSnapshotChunkCount,
                packetCount,
                totalEncodedBytes,
                retainedOriginalBytes,
                retainedOriginalPacketCount,
                SERVER_ORIGINAL_BYTES_BUDGET,
                SERVER_METADATA_ENTRY_LIMIT,
                serverEvictedOriginalBytes,
                serverEvictedOriginalPackets,
                serverEvictedMetadataPackets
        );
    }

    public static TrimResult trimToTotalBytes(long targetBytes) {
        long safeTargetBytes = Math.max(targetBytes, 0L);
        long currentTotalBytes = snapshot().totalEncodedBytes();
        if (currentTotalBytes <= safeTargetBytes) {
            return new TrimResult(0L, List.of());
        }

        long releasedBytes = 0L;
        ArrayList<EvictedChunkSnapshot> evictedChunks = new ArrayList<>();
        while (currentTotalBytes > safeTargetBytes) {
            EvictionCandidate evictionCandidate = findOldestChunkCandidate();
            if (evictionCandidate == null) {
                break;
            }

            ChannelShadowState channelState = CHANNEL_STATES.get(evictionCandidate.channelId());
            if (channelState == null) {
                continue;
            }

            EvictedChunkSnapshot evictedChunk = channelState.evictChunk(
                    evictionCandidate.channelId(),
                    evictionCandidate.scopedChunkKey()
            );
            if (evictedChunk == null) {
                continue;
            }

            ChunkGlobalSnapshotStore.invalidateChunk(
                    evictedChunk.channelId(),
                    evictedChunk.scopeId(),
                    evictedChunk.coordinate()
            );
            if (channelState.isEmpty()) {
                CHANNEL_STATES.remove(evictionCandidate.channelId(), channelState);
            }

            evictedChunks.add(evictedChunk);
            releasedBytes += evictedChunk.releasedBytes();
            currentTotalBytes = Math.max(currentTotalBytes - evictedChunk.releasedBytes(), 0L);
        }
        return new TrimResult(releasedBytes, List.copyOf(evictedChunks));
    }

    private static ChunkShadowSnapshot observePacket(
            String channelId,
            long epoch,
            ChunkPacketDescriptor descriptor,
            Packet<?> packet,
            byte[] encodedPacketBytes
    ) {
        if (channelId == null
                || channelId.isBlank()
                || descriptor == null
                || packet == null
                || encodedPacketBytes == null
                || !descriptor.hasChunkCoordinate()) {
            return null;
        }

        ChannelShadowState state = CHANNEL_STATES.computeIfAbsent(channelId, ignored -> new ChannelShadowState());
        ChunkShadowSnapshot snapshot = state.observePacket(epoch, descriptor, packet, encodedPacketBytes);
        enforceServerBudgetsIfNeeded();
        return snapshot;
    }


    private static EvictionCandidate findOldestChunkCandidate() {
        EvictionCandidate oldestCandidate = null;
        for (Map.Entry<String, ChannelShadowState> channelEntry : CHANNEL_STATES.entrySet()) {
            if (channelEntry.getValue() == null) {
                continue;
            }

            EvictionCandidate channelCandidate = channelEntry.getValue().findOldestChunkCandidate(channelEntry.getKey());
            if (channelCandidate == null) {
                continue;
            }
            if (oldestCandidate == null || channelCandidate.lastUpdatedAtMillis() < oldestCandidate.lastUpdatedAtMillis()) {
                oldestCandidate = channelCandidate;
            }
        }
        return oldestCandidate;
    }

    // Trim server-side payload bytes while preserving hash metadata for offline ref reuse.
    private static void enforceServerBudgetsIfNeeded() {
        if (LoaderEnvironmentCompat.isClientSide()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextServerBudgetCheckAtMillis) {
            return;
        }
        nextServerBudgetCheckAtMillis = now + SERVER_BUDGET_CHECK_INTERVAL_MILLIS;

        ServerOriginalBytesTrimResult byteTrimResult = trimServerOriginalBytesToTotal(SERVER_ORIGINAL_BYTES_BUDGET);
        if (byteTrimResult.releasedBytes() > 0L || byteTrimResult.evictedPackets() > 0L) {
            serverEvictedOriginalBytes += byteTrimResult.releasedBytes();
            serverEvictedOriginalPackets += byteTrimResult.evictedPackets();
        }

        long metadataEvictions = trimServerMetadataToEntryLimit(SERVER_METADATA_ENTRY_LIMIT);
        if (metadataEvictions > 0L) {
            serverEvictedMetadataPackets += metadataEvictions;
        }
    }

    // Prefer evicting cold full payload bytes before dropping any chunk metadata.
    private static ServerOriginalBytesTrimResult trimServerOriginalBytesToTotal(long targetBytes) {
        long safeTargetBytes = Math.max(targetBytes, 0L);
        Snapshot beforeSnapshot = snapshot();
        if (beforeSnapshot.retainedOriginalBytes() <= safeTargetBytes) {
            return new ServerOriginalBytesTrimResult(0L, 0L);
        }

        ArrayList<PacketEvictionCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, ChannelShadowState> channelEntry : CHANNEL_STATES.entrySet()) {
            if (channelEntry.getValue() == null) {
                continue;
            }
            channelEntry.getValue().appendRetainedPacketCandidates(channelEntry.getKey(), candidates);
        }
        candidates.sort(Comparator
                .comparingLong(PacketEvictionCandidate::accessCount)
                .thenComparingLong(PacketEvictionCandidate::lastAccessAtMillis)
                .thenComparing(Comparator.comparingLong(PacketEvictionCandidate::retainedBytes).reversed()));

        long currentBytes = beforeSnapshot.retainedOriginalBytes();
        long releasedBytes = 0L;
        long evictedPackets = 0L;
        for (PacketEvictionCandidate candidate : candidates) {
            if (currentBytes <= safeTargetBytes) {
                break;
            }
            ChannelShadowState channelState = CHANNEL_STATES.get(candidate.channelId());
            if (channelState == null) {
                continue;
            }
            long released = channelState.dropOriginalPacketBytes(candidate);
            if (released <= 0L) {
                continue;
            }
            currentBytes = Math.max(currentBytes - released, 0L);
            releasedBytes += released;
            evictedPackets++;
        }
        return new ServerOriginalBytesTrimResult(releasedBytes, evictedPackets);
    }

    // Metadata is only trimmed by count as a last resort after payload trimming.
    private static long trimServerMetadataToEntryLimit(long entryLimit) {
        long safeEntryLimit = Math.max(entryLimit, 1L);
        Snapshot currentSnapshot = snapshot();
        if (currentSnapshot.packetCount() <= safeEntryLimit) {
            return 0L;
        }

        ArrayList<MetadataEvictionCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, ChannelShadowState> channelEntry : CHANNEL_STATES.entrySet()) {
            if (channelEntry.getValue() == null) {
                continue;
            }
            channelEntry.getValue().appendColdMetadataCandidates(channelEntry.getKey(), candidates);
        }
        candidates.sort(Comparator
                .comparingLong(MetadataEvictionCandidate::retainedOriginalBytes)
                .thenComparingLong(MetadataEvictionCandidate::accessCount)
                .thenComparingLong(MetadataEvictionCandidate::lastAccessAtMillis));

        long packetCount = currentSnapshot.packetCount();
        long evictedPackets = 0L;
        for (MetadataEvictionCandidate candidate : candidates) {
            if (packetCount <= safeEntryLimit) {
                break;
            }
            ChannelShadowState channelState = CHANNEL_STATES.get(candidate.channelId());
            if (channelState == null) {
                continue;
            }
            long removedPackets = channelState.evictChunkMetadata(candidate.scopedChunkKey());
            if (removedPackets <= 0L) {
                continue;
            }
            if (channelState.isEmpty()) {
                CHANNEL_STATES.remove(candidate.channelId(), channelState);
            }
            packetCount = Math.max(packetCount - removedPackets, 0L);
            evictedPackets += removedPackets;
        }
        return evictedPackets;
    }

    private static long readLongProperty(String propertyName, long defaultValue) {
        String rawValue = System.getProperty(propertyName, Long.toString(defaultValue));
        try {
            return Math.max(Long.parseLong(rawValue.trim()), 0L);
        } catch (RuntimeException ignored) {
            return Math.max(defaultValue, 0L);
        }
    }

    private static final class ChannelShadowState {

        private final Map<String, MutableChunkShadowSnapshot> chunkSnapshots = new HashMap<>();

        synchronized ChunkShadowSnapshot observePacket(
                long epoch,
                ChunkPacketDescriptor descriptor,
                Packet<?> packet,
                byte[] encodedPacketBytes
        ) {
            MutableChunkShadowSnapshot chunkSnapshot = this.chunkSnapshots.computeIfAbsent(
                    scopedChunkKeyText(epoch, descriptor.coordinate()),
                    ignored -> new MutableChunkShadowSnapshot(descriptor.coordinate())
            );
            return chunkSnapshot.applyObservation(epoch, descriptor, packet, encodedPacketBytes);
        }

        synchronized ChunkShadowSnapshot snapshotChunk(long scopeId, ChunkPacketCoordinate coordinate) {
            MutableChunkShadowSnapshot snapshot = this.chunkSnapshots.get(scopedChunkKeyText(scopeId, coordinate));
            if (snapshot == null) {
                return null;
            }
            snapshot.touchAllPackets(System.currentTimeMillis());
            return snapshot.toImmutable();
        }

        synchronized ChunkShadowSnapshot findLatestChunkAcrossScopes(ChunkPacketCoordinate coordinate) {
            MutableChunkShadowSnapshot latestSnapshot = null;
            for (MutableChunkShadowSnapshot snapshot : this.chunkSnapshots.values()) {
                if (!matchesCoordinate(snapshot, coordinate)) {
                    continue;
                }
                if (latestSnapshot == null || snapshot.lastUpdatedAtMillis > latestSnapshot.lastUpdatedAtMillis) {
                    latestSnapshot = snapshot;
                }
            }
            if (latestSnapshot == null) {
                return null;
            }
            latestSnapshot.touchAllPackets(System.currentTimeMillis());
            return latestSnapshot.toImmutable();
        }

        synchronized byte[] materializeFullChunkPacket(
                long scopeId,
                ChunkPacketCoordinate coordinate,
                long expectedFullSnapshotVersion,
                String expectedPayloadHash
        ) {
            MutableChunkShadowSnapshot scopedSnapshot = this.chunkSnapshots.get(scopedChunkKeyText(scopeId, coordinate));
            return materializeMatchingSnapshot(
                    scopedSnapshot,
                    expectedFullSnapshotVersion,
                    expectedPayloadHash
            );
        }

        synchronized void invalidateChunk(long scopeId, ChunkPacketCoordinate coordinate) {
            this.chunkSnapshots.remove(scopedChunkKeyText(scopeId, coordinate));
        }

        synchronized void invalidateChunkAcrossScopes(ChunkPacketCoordinate coordinate) {
            this.chunkSnapshots.entrySet().removeIf(entry -> matchesCoordinate(entry.getValue(), coordinate));
        }


        synchronized EvictionCandidate findOldestChunkCandidate(String channelId) {
            String oldestScopedChunkKey = null;
            MutableChunkShadowSnapshot oldestSnapshot = null;
            for (Map.Entry<String, MutableChunkShadowSnapshot> entry : this.chunkSnapshots.entrySet()) {
                MutableChunkShadowSnapshot snapshot = entry.getValue();
                if (snapshot == null) {
                    continue;
                }
                if (oldestSnapshot == null || snapshot.lastUpdatedAtMillis < oldestSnapshot.lastUpdatedAtMillis) {
                    oldestSnapshot = snapshot;
                    oldestScopedChunkKey = entry.getKey();
                }
            }
            if (oldestSnapshot == null || oldestScopedChunkKey == null) {
                return null;
            }
            return new EvictionCandidate(channelId, oldestScopedChunkKey, oldestSnapshot.lastUpdatedAtMillis);
        }


        synchronized EvictedChunkSnapshot evictChunk(String channelId, String scopedChunkKey) {
            MutableChunkShadowSnapshot removedSnapshot = this.chunkSnapshots.remove(scopedChunkKey);
            return removedSnapshot == null ? null : removedSnapshot.toEvictedSnapshot(channelId, scopedChunkKey);
        }

        synchronized long evictChunkMetadata(String scopedChunkKey) {
            MutableChunkShadowSnapshot removedSnapshot = this.chunkSnapshots.remove(scopedChunkKey);
            return removedSnapshot == null ? 0L : removedSnapshot.packetCount();
        }

        synchronized void appendRetainedPacketCandidates(
                String channelId,
                List<PacketEvictionCandidate> candidates
        ) {
            if (candidates == null) {
                return;
            }
            for (Map.Entry<String, MutableChunkShadowSnapshot> entry : this.chunkSnapshots.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                entry.getValue().appendRetainedPacketCandidates(channelId, entry.getKey(), candidates);
            }
        }

        synchronized long dropOriginalPacketBytes(PacketEvictionCandidate candidate) {
            if (candidate == null) {
                return 0L;
            }
            MutableChunkShadowSnapshot chunkSnapshot = this.chunkSnapshots.get(candidate.scopedChunkKey());
            return chunkSnapshot == null ? 0L : chunkSnapshot.dropOriginalPacketBytes(candidate);
        }

        synchronized void appendColdMetadataCandidates(
                String channelId,
                List<MetadataEvictionCandidate> candidates
        ) {
            if (candidates == null) {
                return;
            }
            for (Map.Entry<String, MutableChunkShadowSnapshot> entry : this.chunkSnapshots.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                candidates.add(entry.getValue().metadataEvictionCandidate(channelId, entry.getKey()));
            }
        }

        synchronized boolean isEmpty() {
            return this.chunkSnapshots.isEmpty();
        }

        synchronized ChannelSnapshotTotals snapshotTotals() {
            long chunkCount = this.chunkSnapshots.size();
            long fullSnapshotChunkCount = 0L;
            long packetCount = 0L;
            long totalEncodedBytes = 0L;
            long retainedOriginalBytes = 0L;
            long retainedOriginalPacketCount = 0L;

            for (MutableChunkShadowSnapshot chunkSnapshot : this.chunkSnapshots.values()) {
                if (chunkSnapshot == null) {
                    continue;
                }

                if (chunkSnapshot.hasFullSnapshot()) {
                    fullSnapshotChunkCount++;
                }

                packetCount += chunkSnapshot.packetCount();
                totalEncodedBytes += chunkSnapshot.totalEncodedBytes();
                retainedOriginalBytes += chunkSnapshot.retainedOriginalBytes();
                retainedOriginalPacketCount += chunkSnapshot.retainedOriginalPacketCount();
            }

            return new ChannelSnapshotTotals(
                    chunkCount,
                    fullSnapshotChunkCount,
                    packetCount,
                    totalEncodedBytes,
                    retainedOriginalBytes,
                    retainedOriginalPacketCount
            );
        }

        private static boolean matchesCoordinate(MutableChunkShadowSnapshot snapshot, ChunkPacketCoordinate coordinate) {
            return snapshot != null
                    && coordinate != null
                    && coordinate.present()
                    && snapshot.coordinate != null
                    && snapshot.coordinate.present()
                    && snapshot.coordinate.chunkX() == coordinate.chunkX()
                    && snapshot.coordinate.chunkZ() == coordinate.chunkZ();
        }

        private static byte[] materializeMatchingSnapshot(
                MutableChunkShadowSnapshot snapshot,
                long expectedFullSnapshotVersion,
                String expectedPayloadHash
        ) {
            if (snapshot == null) {
                return null;
            }
            snapshot.touchFullPacket(System.currentTimeMillis());
            return ChunkSnapshotMaterializer.materializeFullChunkPacket(
                    snapshot.toImmutable(),
                    expectedFullSnapshotVersion,
                    expectedPayloadHash
            );
        }
    }

    private static final class MutableChunkShadowSnapshot {

        private final ChunkPacketCoordinate coordinate;
        private final EnumMap<ChunkLaneKind, MutableLaneSnapshot> laneSnapshots = new EnumMap<>(ChunkLaneKind.class);
        private long epoch;
        private long mutationVersion;
        private long fullSnapshotVersion;
        private String fullSnapshotHash = "";
        private String fullSnapshotShortHash = "";
        private long lastUpdatedAtMillis;

        private MutableChunkShadowSnapshot(ChunkPacketCoordinate coordinate) {
            this.coordinate = coordinate == null ? ChunkPacketCoordinate.unknown() : coordinate;
        }

        private ChunkShadowSnapshot applyObservation(
                long epoch,
                ChunkPacketDescriptor descriptor,
                Packet<?> packet,
                byte[] encodedPacketBytes
        ) {
            ChunkSnapshotFingerprint fingerprint =
                    ChunkSnapshotFingerprintService.fingerprintOutboundPacket(encodedPacketBytes);
            long now = System.currentTimeMillis();
            this.epoch = Math.max(epoch, 0L);
            this.mutationVersion++;
            this.lastUpdatedAtMillis = now;

            if (descriptor.hotspotKind() == ChunkHotspotKind.FULL_CHUNK) {
                applyFullSnapshotObservation(descriptor, fingerprint, encodedPacketBytes, now);
            } else {
                applyDeltaObservation(descriptor, packet, fingerprint, encodedPacketBytes, now);
            }
            return toImmutable();
        }

        private void applyFullSnapshotObservation(
                ChunkPacketDescriptor descriptor,
                ChunkSnapshotFingerprint fingerprint,
                byte[] encodedPacketBytes,
                long observedAtMillis
        ) {
            boolean snapshotChanged = this.fullSnapshotVersion <= 0L
                    || !fingerprint.hashHex().equals(this.fullSnapshotHash);
            if (snapshotChanged) {
                this.fullSnapshotVersion = Math.max(this.fullSnapshotVersion, 0L) + 1L;
            }

            this.fullSnapshotHash = fingerprint.hashHex();
            this.fullSnapshotShortHash = fingerprint.shortHash();
            long previousFullPacketAccessCount = fullPacketAccessCount();
            this.laneSnapshots.clear();

            MutableLaneSnapshot fullLaneSnapshot = new MutableLaneSnapshot(ChunkLaneKind.FULL);
            fullLaneSnapshot.forceLaneVersion(Math.max(this.fullSnapshotVersion, 1L));
            fullLaneSnapshot.replacePacket(
                    "full",
                    buildPacketSnapshot(
                            descriptor,
                            "full",
                            fullLaneSnapshot.laneVersion(),
                            fingerprint,
                            encodedPacketBytes,
                            observedAtMillis
                    ).withAccessStats(previousFullPacketAccessCount + 1L, observedAtMillis)
            );
            this.laneSnapshots.put(ChunkLaneKind.FULL, fullLaneSnapshot);
        }

        // apply light/section/block/block entity to lane。
        private void applyDeltaObservation(
                ChunkPacketDescriptor descriptor,
                Packet<?> packet,
                ChunkSnapshotFingerprint fingerprint,
                byte[] encodedPacketBytes,
                long observedAtMillis
        ) {
            MutableLaneSnapshot laneSnapshot = this.laneSnapshots.computeIfAbsent(
                    descriptor.laneKind(),
                    MutableLaneSnapshot::new
            );
            String semanticKey = ChunkSnapshotSemanticKeyResolver.resolveSemanticKey(descriptor, packet);
            long laneVersion = laneSnapshot.nextLaneVersion();
            laneSnapshot.replacePacket(
                    semanticKey,
                    buildPacketSnapshot(
                            descriptor,
                            semanticKey,
                            laneVersion,
                            fingerprint,
                            encodedPacketBytes,
                            observedAtMillis
                    )
            );
        }

        private ChunkLanePacketSnapshot buildPacketSnapshot(
                ChunkPacketDescriptor descriptor,
                String semanticKey,
                long laneVersion,
                ChunkSnapshotFingerprint fingerprint,
                byte[] encodedPacketBytes,
                long observedAtMillis
        ) {
            return new ChunkLanePacketSnapshot(
                    descriptor.protocolName(),
                    descriptor.packetClassName(),
                    descriptor.hotspotKind(),
                    descriptor.laneKind(),
                    semanticKey,
                    laneVersion,
                    this.fullSnapshotVersion,
                    fingerprint.hashHex(),
                    fingerprint.shortHash(),
                    fingerprint.encodedBytes(),
                    observedAtMillis,
                    1L,
                    observedAtMillis,
                    encodedPacketBytes
            );
        }

        private ChunkShadowSnapshot toImmutable() {
            EnumMap<ChunkLaneKind, ChunkLaneSnapshot> immutableLaneSnapshots = new EnumMap<>(ChunkLaneKind.class);
            for (Map.Entry<ChunkLaneKind, MutableLaneSnapshot> entry : this.laneSnapshots.entrySet()) {
                immutableLaneSnapshots.put(entry.getKey(), entry.getValue().toImmutable());
            }
            return new ChunkShadowSnapshot(
                    this.coordinate,
                    this.epoch,
                    this.mutationVersion,
                    this.fullSnapshotVersion,
                    this.fullSnapshotHash,
                    this.fullSnapshotShortHash,
                    this.lastUpdatedAtMillis,
                    Collections.unmodifiableMap(immutableLaneSnapshots)
            );
        }

        private boolean hasFullSnapshot() {
            return this.fullSnapshotVersion > 0L
                    && this.fullSnapshotHash != null
                    && !this.fullSnapshotHash.isBlank()
                    && this.laneSnapshots.containsKey(ChunkLaneKind.FULL);
        }

        private long packetCount() {
            long totalPacketCount = 0L;
            for (MutableLaneSnapshot laneSnapshot : this.laneSnapshots.values()) {
                if (laneSnapshot == null) {
                    continue;
                }
                totalPacketCount += laneSnapshot.packetCount();
            }
            return totalPacketCount;
        }

        private long totalEncodedBytes() {
            long totalBytes = 0L;
            for (MutableLaneSnapshot laneSnapshot : this.laneSnapshots.values()) {
                if (laneSnapshot == null) {
                    continue;
                }
                totalBytes += laneSnapshot.totalEncodedBytes();
            }
            return totalBytes;
        }

        private long retainedOriginalBytes() {
            long totalBytes = 0L;
            for (MutableLaneSnapshot laneSnapshot : this.laneSnapshots.values()) {
                if (laneSnapshot == null) {
                    continue;
                }
                totalBytes += laneSnapshot.retainedOriginalBytes();
            }
            return totalBytes;
        }

        private long retainedOriginalPacketCount() {
            long totalPacketCount = 0L;
            for (MutableLaneSnapshot laneSnapshot : this.laneSnapshots.values()) {
                if (laneSnapshot == null) {
                    continue;
                }
                totalPacketCount += laneSnapshot.retainedOriginalPacketCount();
            }
            return totalPacketCount;
        }

        private long fullPacketAccessCount() {
            MutableLaneSnapshot laneSnapshot = this.laneSnapshots.get(ChunkLaneKind.FULL);
            return laneSnapshot == null ? 0L : laneSnapshot.packetAccessCount("full");
        }

        private void touchAllPackets(long nowMillis) {
            for (MutableLaneSnapshot laneSnapshot : this.laneSnapshots.values()) {
                if (laneSnapshot != null) {
                    laneSnapshot.touchAllPackets(nowMillis);
                }
            }
        }

        private void touchFullPacket(long nowMillis) {
            MutableLaneSnapshot laneSnapshot = this.laneSnapshots.get(ChunkLaneKind.FULL);
            if (laneSnapshot != null) {
                laneSnapshot.touchPacket("full", nowMillis);
            }
        }

        private void appendRetainedPacketCandidates(
                String channelId,
                String scopedChunkKey,
                List<PacketEvictionCandidate> candidates
        ) {
            for (MutableLaneSnapshot laneSnapshot : this.laneSnapshots.values()) {
                if (laneSnapshot != null) {
                    laneSnapshot.appendRetainedPacketCandidates(channelId, scopedChunkKey, candidates);
                }
            }
        }

        private long dropOriginalPacketBytes(PacketEvictionCandidate candidate) {
            if (candidate == null) {
                return 0L;
            }
            MutableLaneSnapshot laneSnapshot = this.laneSnapshots.get(candidate.laneKind());
            return laneSnapshot == null ? 0L : laneSnapshot.dropOriginalPacketBytes(candidate);
        }

        private MetadataEvictionCandidate metadataEvictionCandidate(String channelId, String scopedChunkKey) {
            long accessCount = 0L;
            long lastAccessAtMillis = 0L;
            for (MutableLaneSnapshot laneSnapshot : this.laneSnapshots.values()) {
                if (laneSnapshot == null) {
                    continue;
                }
                accessCount += laneSnapshot.totalAccessCount();
                lastAccessAtMillis = Math.max(lastAccessAtMillis, laneSnapshot.latestAccessAtMillis());
            }
            return new MetadataEvictionCandidate(
                    channelId,
                    scopedChunkKey,
                    this.retainedOriginalBytes(),
                    accessCount,
                    lastAccessAtMillis
            );
        }

        private EvictedChunkSnapshot toEvictedSnapshot(String channelId, String scopedChunkKey) {
            return new EvictedChunkSnapshot(
                    channelId == null ? "" : channelId,
                    scopedChunkKey == null ? "" : scopedChunkKey,
                    Math.max(this.epoch, 0L),
                    this.coordinate,
                    this.hasFullSnapshot(),
                    Math.max(this.fullSnapshotVersion, 0L),
                    this.fullSnapshotHash == null ? "" : this.fullSnapshotHash,
                    this.fullSnapshotShortHash == null ? "" : this.fullSnapshotShortHash,
                    this.totalEncodedBytes()
            );
        }
    }

    private static final class MutableLaneSnapshot {

        private final ChunkLaneKind laneKind;
        private final LinkedHashMap<String, ChunkLanePacketSnapshot> packetSnapshots = new LinkedHashMap<>();
        private long laneVersion;
        private String latestSemanticKey = "";

        private MutableLaneSnapshot(ChunkLaneKind laneKind) {
            this.laneKind = laneKind;
        }

        private long laneVersion() {
            return this.laneVersion;
        }

        private long nextLaneVersion() {
            this.laneVersion++;
            return this.laneVersion;
        }

        private void forceLaneVersion(long laneVersion) {
            this.laneVersion = Math.max(laneVersion, 0L);
        }

        private void replacePacket(String semanticKey, ChunkLanePacketSnapshot packetSnapshot) {
            String safeSemanticKey = semanticKey == null || semanticKey.isBlank() ? "default" : semanticKey;
            this.latestSemanticKey = safeSemanticKey;
            ChunkLanePacketSnapshot previousSnapshot = this.packetSnapshots.get(safeSemanticKey);
            ChunkLanePacketSnapshot safePacketSnapshot = packetSnapshot;
            if (safePacketSnapshot != null && previousSnapshot != null) {
                safePacketSnapshot = safePacketSnapshot.withAccessStats(
                        previousSnapshot.accessCount() + 1L,
                        safePacketSnapshot.observedAtMillis()
                );
            }
            this.packetSnapshots.remove(safeSemanticKey);
            this.packetSnapshots.put(safeSemanticKey, safePacketSnapshot);
        }


        private long packetCount() {
            return this.packetSnapshots.size();
        }

        private long totalEncodedBytes() {
            long totalBytes = 0L;
            for (ChunkLanePacketSnapshot packetSnapshot : this.packetSnapshots.values()) {
                totalBytes += packetSnapshot == null ? 0L : Math.max(packetSnapshot.encodedBytes(), 0);
            }
            return totalBytes;
        }

        private long retainedOriginalBytes() {
            long totalBytes = 0L;
            for (ChunkLanePacketSnapshot packetSnapshot : this.packetSnapshots.values()) {
                totalBytes += packetSnapshot == null ? 0L : packetSnapshot.retainedOriginalBytes();
            }
            return totalBytes;
        }

        private long retainedOriginalPacketCount() {
            long totalPacketCount = 0L;
            for (ChunkLanePacketSnapshot packetSnapshot : this.packetSnapshots.values()) {
                if (packetSnapshot != null && packetSnapshot.hasOriginalPacketBytes()) {
                    totalPacketCount++;
                }
            }
            return totalPacketCount;
        }

        private long packetAccessCount(String semanticKey) {
            ChunkLanePacketSnapshot packetSnapshot = this.packetSnapshots.get(
                    semanticKey == null || semanticKey.isBlank() ? "default" : semanticKey
            );
            return packetSnapshot == null ? 0L : packetSnapshot.accessCount();
        }

        private void touchAllPackets(long nowMillis) {
            ArrayList<String> keys = new ArrayList<>(this.packetSnapshots.keySet());
            for (String key : keys) {
                touchPacket(key, nowMillis);
            }
        }

        private void touchPacket(String semanticKey, long nowMillis) {
            String safeSemanticKey = semanticKey == null || semanticKey.isBlank() ? "default" : semanticKey;
            ChunkLanePacketSnapshot packetSnapshot = this.packetSnapshots.get(safeSemanticKey);
            if (packetSnapshot == null) {
                return;
            }
            this.packetSnapshots.put(safeSemanticKey, packetSnapshot.markAccess(nowMillis));
        }

        private void appendRetainedPacketCandidates(
                String channelId,
                String scopedChunkKey,
                List<PacketEvictionCandidate> candidates
        ) {
            if (candidates == null) {
                return;
            }
            for (Map.Entry<String, ChunkLanePacketSnapshot> entry : this.packetSnapshots.entrySet()) {
                ChunkLanePacketSnapshot packetSnapshot = entry.getValue();
                if (packetSnapshot == null || !packetSnapshot.hasOriginalPacketBytes()) {
                    continue;
                }
                candidates.add(new PacketEvictionCandidate(
                        channelId,
                        scopedChunkKey,
                        this.laneKind,
                        entry.getKey(),
                        packetSnapshot.payloadHash(),
                        packetSnapshot.retainedOriginalBytes(),
                        packetSnapshot.accessCount(),
                        packetSnapshot.lastAccessAtMillis()
                ));
            }
        }

        private long dropOriginalPacketBytes(PacketEvictionCandidate candidate) {
            if (candidate == null) {
                return 0L;
            }
            ChunkLanePacketSnapshot packetSnapshot = this.packetSnapshots.get(candidate.semanticKey());
            if (packetSnapshot == null
                    || !packetSnapshot.hasOriginalPacketBytes()
                    || !packetSnapshot.payloadHash().equals(candidate.payloadHash())) {
                return 0L;
            }
            long releasedBytes = packetSnapshot.retainedOriginalBytes();
            this.packetSnapshots.put(candidate.semanticKey(), packetSnapshot.withoutOriginalPacketBytes());
            return releasedBytes;
        }

        private long totalAccessCount() {
            long totalAccessCount = 0L;
            for (ChunkLanePacketSnapshot packetSnapshot : this.packetSnapshots.values()) {
                totalAccessCount += packetSnapshot == null ? 0L : packetSnapshot.accessCount();
            }
            return totalAccessCount;
        }

        private long latestAccessAtMillis() {
            long latestAccessAtMillis = 0L;
            for (ChunkLanePacketSnapshot packetSnapshot : this.packetSnapshots.values()) {
                latestAccessAtMillis = Math.max(
                        latestAccessAtMillis,
                        packetSnapshot == null ? 0L : packetSnapshot.lastAccessAtMillis()
                );
            }
            return latestAccessAtMillis;
        }

        private ChunkLaneSnapshot toImmutable() {
            return new ChunkLaneSnapshot(
                    this.laneKind,
                    this.laneVersion,
                    this.latestSemanticKey,
                    new LinkedHashMap<>(this.packetSnapshots)
            );
        }
    }

    private static String chunkKeyText(ChunkPacketCoordinate coordinate) {
        return coordinate == null || !coordinate.present()
                ? "<unknown>"
                : coordinate.chunkX() + "," + coordinate.chunkZ();
    }

    private static String scopedChunkKeyText(long scopeId, ChunkPacketCoordinate coordinate) {
        return Math.max(scopeId, 0L) + ":" + chunkKeyText(coordinate);
    }

    public record TrimResult(
            long releasedBytes,
            List<EvictedChunkSnapshot> evictedChunks
    ) {
        public TrimResult {
            releasedBytes = Math.max(releasedBytes, 0L);
            evictedChunks = evictedChunks == null ? List.of() : List.copyOf(evictedChunks);
        }
    }

    public record EvictedChunkSnapshot(
            String channelId,
            String scopedChunkKey,
            long scopeId,
            ChunkPacketCoordinate coordinate,
            boolean hadFullSnapshot,
            long fullSnapshotVersion,
            String fullSnapshotHash,
            String fullSnapshotShortHash,
            long releasedBytes
    ) {
        public EvictedChunkSnapshot {
            channelId = channelId == null ? "" : channelId;
            scopedChunkKey = scopedChunkKey == null ? "" : scopedChunkKey;
            coordinate = coordinate == null ? ChunkPacketCoordinate.unknown() : coordinate;
            fullSnapshotHash = fullSnapshotHash == null ? "" : fullSnapshotHash;
            fullSnapshotShortHash = fullSnapshotShortHash == null ? "" : fullSnapshotShortHash;
            releasedBytes = Math.max(releasedBytes, 0L);
        }
    }

    private record EvictionCandidate(
            String channelId,
            String scopedChunkKey,
            long lastUpdatedAtMillis
    ) {
    }

    private record ChannelSnapshotTotals(
            long chunkCount,
            long fullSnapshotChunkCount,
            long packetCount,
            long totalEncodedBytes,
            long retainedOriginalBytes,
            long retainedOriginalPacketCount
    ) {
    }

    private record PacketEvictionCandidate(
            String channelId,
            String scopedChunkKey,
            ChunkLaneKind laneKind,
            String semanticKey,
            String payloadHash,
            long retainedBytes,
            long accessCount,
            long lastAccessAtMillis
    ) {
        private PacketEvictionCandidate {
            channelId = channelId == null ? "" : channelId;
            scopedChunkKey = scopedChunkKey == null ? "" : scopedChunkKey;
            semanticKey = semanticKey == null || semanticKey.isBlank() ? "default" : semanticKey;
            payloadHash = payloadHash == null ? "" : payloadHash;
            retainedBytes = Math.max(retainedBytes, 0L);
            accessCount = Math.max(accessCount, 0L);
            lastAccessAtMillis = Math.max(lastAccessAtMillis, 0L);
        }
    }

    private record MetadataEvictionCandidate(
            String channelId,
            String scopedChunkKey,
            long retainedOriginalBytes,
            long accessCount,
            long lastAccessAtMillis
    ) {
        private MetadataEvictionCandidate {
            channelId = channelId == null ? "" : channelId;
            scopedChunkKey = scopedChunkKey == null ? "" : scopedChunkKey;
            retainedOriginalBytes = Math.max(retainedOriginalBytes, 0L);
            accessCount = Math.max(accessCount, 0L);
            lastAccessAtMillis = Math.max(lastAccessAtMillis, 0L);
        }
    }

    private record ServerOriginalBytesTrimResult(
            long releasedBytes,
            long evictedPackets
    ) {
        private ServerOriginalBytesTrimResult {
            releasedBytes = Math.max(releasedBytes, 0L);
            evictedPackets = Math.max(evictedPackets, 0L);
        }
    }

    public record Snapshot(
            long channelCount,
            long chunkCount,
            long fullSnapshotChunkCount,
            long packetCount,
            long totalEncodedBytes,
            long retainedOriginalBytes,
            long retainedOriginalPacketCount,
            long serverOriginalBytesBudget,
            long serverMetadataEntryLimit,
            long serverEvictedOriginalBytes,
            long serverEvictedOriginalPackets,
            long serverEvictedMetadataPackets
    ) {
    }
}
