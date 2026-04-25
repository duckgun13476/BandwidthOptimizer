package com.PinkCats.bandwidthoptimizer.chunk.snapshot;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.chunk.store.global.ChunkGlobalSnapshotStore;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkShadowSnapshotManager {

    private static final ConcurrentHashMap<String, ChannelShadowState> CHANNEL_STATES = new ConcurrentHashMap<>();

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
                observePacket(context.channel().id().asLongText(), epoch, descriptor, packet, encodedPacketBytes);
        ChunkGlobalSnapshotStore.observeMaterializedSnapshot(context.channel().id().asLongText(), snapshot);
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

    public static ChunkShadowSnapshot snapshotChunk(String channelId, ChunkPacketCoordinate coordinate) {
        if (channelId == null || channelId.isBlank() || coordinate == null || !coordinate.present())
            return null;

        ChannelShadowState state = CHANNEL_STATES.get(channelId);
        return state == null ? null : state.snapshotChunk(coordinate);
    }

    public static byte[] materializeFullChunkPacket(
            String channelId,
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
                : state.materializeFullChunkPacket(coordinate, expectedFullSnapshotVersion, expectedPayloadHash);
        if (materializedPacketBytes != null) {
            return materializedPacketBytes;
        }
        return ChunkGlobalSnapshotStore.findMaterializedFullChunkPacket(
                channelId,
                coordinate,
                expectedFullSnapshotVersion,
                expectedPayloadHash
        );
    }

    public static void invalidateChunk(String channelId, ChunkPacketCoordinate coordinate) {
        if (channelId == null || channelId.isBlank() || coordinate == null || !coordinate.present()) {
            return;
        }

        ChannelShadowState state = CHANNEL_STATES.get(channelId);
        if (state != null)
            state.invalidateChunk(coordinate);
        ChunkGlobalSnapshotStore.invalidateChunk(channelId, coordinate);
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
        return state.observePacket(epoch, descriptor, packet, encodedPacketBytes);
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
                    chunkKeyText(descriptor.coordinate()),
                    ignored -> new MutableChunkShadowSnapshot(descriptor.coordinate())
            );
            return chunkSnapshot.applyObservation(epoch, descriptor, packet, encodedPacketBytes);
        }

        synchronized ChunkShadowSnapshot snapshotChunk(ChunkPacketCoordinate coordinate) {
            MutableChunkShadowSnapshot snapshot = this.chunkSnapshots.get(chunkKeyText(coordinate));
            return snapshot == null ? null : snapshot.toImmutable();
        }

        synchronized byte[] materializeFullChunkPacket(
                ChunkPacketCoordinate coordinate,
                long expectedFullSnapshotVersion,
                String expectedPayloadHash
        ) {
            MutableChunkShadowSnapshot snapshot = this.chunkSnapshots.get(chunkKeyText(coordinate));
            return snapshot == null
                    ? null
                    : ChunkSnapshotMaterializer.materializeFullChunkPacket(
                            snapshot.toImmutable(),
                            expectedFullSnapshotVersion,
                            expectedPayloadHash
                    );
        }

        synchronized void invalidateChunk(ChunkPacketCoordinate coordinate) {
            this.chunkSnapshots.remove(chunkKeyText(coordinate));
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
                    )
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
            this.packetSnapshots.remove(safeSemanticKey);
            this.packetSnapshots.put(safeSemanticKey, packetSnapshot);
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
}
