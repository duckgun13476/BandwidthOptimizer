package com.PinkCats.bandwidthoptimizer.server.stat;

import com.PinkCats.bandwidthoptimizer.compat.create.CreateBlockEntityUpdateGate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Local save
public final class ServerBandwidthPersistentStats extends SavedData {

    static final String DATA_NAME = "bandwidthoptimizer_server_bandwidth_stats";
    private static final int DATA_VERSION = 2;

    private final MutableCounters totals = new MutableCounters();
    private final Map<UUID, PlayerCounters> players = new LinkedHashMap<>();

    public static ServerBandwidthPersistentStats load(CompoundTag tag) {
        ServerBandwidthPersistentStats stats = new ServerBandwidthPersistentStats();
        if (tag == null) {
            return stats;
        }

        stats.totals.read(tag.getCompound("totals"));
        ListTag playerList = tag.getList("players", 10);
        for (int i = 0; i < playerList.size(); i++) {
            CompoundTag playerTag = playerList.getCompound(i);
            if (!playerTag.hasUUID("uuid")) {
                continue;
            }
            UUID playerId = playerTag.getUUID("uuid");
            PlayerCounters counters = new PlayerCounters(playerId);
            counters.playerName = playerTag.getString("name");
            counters.read(playerTag.getCompound("counters"));
            stats.players.put(playerId, counters);
        }
        return stats;
    }


    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putInt("version", DATA_VERSION);
        tag.putLong("updatedAtMillis", System.currentTimeMillis());
        tag.put("totals", this.totals.write());

        ListTag playerList = new ListTag();
        for (PlayerCounters counters : this.players.values()) {
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("uuid", counters.playerId);
            playerTag.putString("name", counters.playerName == null ? "" : counters.playerName);
            playerTag.put("counters", counters.write());
            playerList.add(playerTag);
        }
        tag.put("players", playerList);
        return tag;
    }

    public void addDelta(ChannelBandwidthStats.Snapshot delta) {
        if (delta == null || !hasTraffic(delta)) {
            return;
        }

        this.totals.add(delta);
        if (delta.playerId() != null) {
            PlayerCounters playerCounters = this.players.computeIfAbsent(delta.playerId(), PlayerCounters::new);
            if (delta.playerName() != null && !delta.playerName().isBlank()) {
                playerCounters.playerName = delta.playerName();
            }
            playerCounters.add(delta);
        }
        setDirty();
    }

    public void addServerCacheReuseDelta(ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot delta) {
        if (delta == null || !hasServerCacheReuse(delta)) {
            return;
        }

        this.totals.add(delta);
        setDirty();
    }

    public void addCreateGateDelta(CreateBlockEntityUpdateGate.Snapshot delta) {
        if (delta == null || !hasCreateGate(delta)) {
            return;
        }

        this.totals.add(delta);
        setDirty();
    }


    public void resetAll() {
        this.totals.reset();
        this.players.clear();
        setDirty();
    }


    public ServerBandwidthStatsRegistry.TotalsSnapshot snapshotTotals(
            int activeChannels,
            int boundPlayers,
            List<ChannelBandwidthStats.Snapshot> pendingDeltas,
            ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot pendingCacheReuseDelta,
            CreateBlockEntityUpdateGate.Snapshot pendingCreateGateDelta
    ) {
        MutableCounters snapshot = this.totals.copy();
        if (pendingDeltas != null) {
            for (ChannelBandwidthStats.Snapshot delta : pendingDeltas) {
                snapshot.add(delta);
            }
        }
        snapshot.add(pendingCacheReuseDelta);
        snapshot.add(pendingCreateGateDelta);
        return snapshot.toTotalsSnapshot(activeChannels, boundPlayers);
    }

    public List<ChannelBandwidthStats.Snapshot> snapshotPlayers(int limit) {
        int safeLimit = Math.max(limit, 1);
        List<ChannelBandwidthStats.Snapshot> snapshots = new ArrayList<>();
        for (PlayerCounters counters : this.players.values()) {
            snapshots.add(counters.toSnapshot());
        }
        snapshots.sort(Comparator
                .comparingLong(ChannelBandwidthStats.Snapshot::outboundWireBytes)
                .reversed()
                .thenComparing(snapshot -> snapshot.playerName() == null ? "" : snapshot.playerName()));
        if (snapshots.size() <= safeLimit) {
            return List.copyOf(snapshots);
        }
        return List.copyOf(snapshots.subList(0, safeLimit));
    }

    private static boolean hasTraffic(ChannelBandwidthStats.Snapshot snapshot) {
        return snapshot.outboundRawEncodedPackets() != 0L
                || snapshot.outboundRawEncodedBytes() != 0L
                || snapshot.outboundVanillaCompressedEstimateBytes() != 0L
                || snapshot.outboundVanillaEstimateWireBytes() != 0L
                || snapshot.inboundRawEncodedPackets() != 0L
                || snapshot.inboundRawEncodedBytes() != 0L
                || snapshot.outboundTransportFrames() != 0L
                || snapshot.outboundTransportFrameBytes() != 0L
                || snapshot.inboundTransportFrames() != 0L
                || snapshot.inboundTransportFrameBytes() != 0L
                || snapshot.outboundBypassPackets() != 0L
                || snapshot.outboundBypassBytes() != 0L
                || snapshot.inboundBypassPackets() != 0L
                || snapshot.inboundBypassBytes() != 0L
                || snapshot.outboundWireBytes() != 0L
                || snapshot.inboundWireBytes() != 0L;
    }

    private static boolean hasServerCacheReuse(ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot snapshot) {
        return snapshot != null
                && (snapshot.offlineReuseConfirmedFrames() != 0L
                || snapshot.offlineReuseConfirmedSavedBytes() != 0L
                || snapshot.offlineReuseConfirmedWireBytes() != 0L
                || snapshot.temporaryReuseSavedBytes() != 0L);
    }

    private static boolean hasCreateGate(CreateBlockEntityUpdateGate.Snapshot snapshot) {
        return snapshot != null
                && (snapshot.observedBytes() != 0L
                || snapshot.savedBytes() != 0L
                || snapshot.savedPackets() != 0L
                || snapshot.releasedPackets() != 0L);
    }

    private static class PlayerCounters extends MutableCounters {
        private final UUID playerId;
        private String playerName;

        private PlayerCounters(UUID playerId) {
            this.playerId = playerId;
        }

        private ChannelBandwidthStats.Snapshot toSnapshot() {
            String name = this.playerName == null || this.playerName.isBlank() ? "<unknown-player>" : this.playerName;
            return new ChannelBandwidthStats.Snapshot(
                    "player:" + this.playerId,
                    this.playerId,
                    name,
                    0L,
                    0L,
                    this.outboundRawEncodedPackets,
                    this.outboundRawEncodedBytes,
                    this.outboundVanillaCompressedEstimateBytes,
                    this.outboundVanillaEstimateWireBytes,
                    this.inboundRawEncodedPackets,
                    this.inboundRawEncodedBytes,
                    this.outboundTransportFrames,
                    this.outboundTransportFrameBytes,
                    this.inboundTransportFrames,
                    this.inboundTransportFrameBytes,
                    this.outboundBypassPackets,
                    this.outboundBypassBytes,
                    this.inboundBypassPackets,
                    this.inboundBypassBytes,
                    this.outboundWireBytes,
                    this.inboundWireBytes
            );
        }
    }

    static class MutableCounters {
        protected long outboundRawEncodedPackets;
        protected long outboundRawEncodedBytes;
        protected long outboundVanillaCompressedEstimateBytes;
        protected long outboundVanillaEstimateWireBytes;
        protected long inboundRawEncodedPackets;
        protected long inboundRawEncodedBytes;
        protected long outboundTransportFrames;
        protected long outboundTransportFrameBytes;
        protected long inboundTransportFrames;
        protected long inboundTransportFrameBytes;
        protected long outboundBypassPackets;
        protected long outboundBypassBytes;
        protected long inboundBypassPackets;
        protected long inboundBypassBytes;
        protected long outboundWireBytes;
        protected long inboundWireBytes;
        protected long serverOfflineReuseConfirmedFrames;
        protected long serverOfflineReuseConfirmedSavedBytes;
        protected long serverOfflineReuseConfirmedWireBytes;
        protected long serverTemporaryReuseSavedBytes;
        protected long serverCreateGateObservedBytes;
        protected long serverCreateGateSavedBytes;
        protected long serverCreateGateSavedPackets;
        protected long serverCreateGateReleasedPackets;

        protected void add(ChannelBandwidthStats.Snapshot delta) {
            if (delta == null) {
                return;
            }
            this.outboundRawEncodedPackets += delta.outboundRawEncodedPackets();
            this.outboundRawEncodedBytes += delta.outboundRawEncodedBytes();
            this.outboundVanillaCompressedEstimateBytes += delta.outboundVanillaCompressedEstimateBytes();
            this.outboundVanillaEstimateWireBytes += delta.outboundVanillaEstimateWireBytes();
            this.inboundRawEncodedPackets += delta.inboundRawEncodedPackets();
            this.inboundRawEncodedBytes += delta.inboundRawEncodedBytes();
            this.outboundTransportFrames += delta.outboundTransportFrames();
            this.outboundTransportFrameBytes += delta.outboundTransportFrameBytes();
            this.inboundTransportFrames += delta.inboundTransportFrames();
            this.inboundTransportFrameBytes += delta.inboundTransportFrameBytes();
            this.outboundBypassPackets += delta.outboundBypassPackets();
            this.outboundBypassBytes += delta.outboundBypassBytes();
            this.inboundBypassPackets += delta.inboundBypassPackets();
            this.inboundBypassBytes += delta.inboundBypassBytes();
            this.outboundWireBytes += delta.outboundWireBytes();
            this.inboundWireBytes += delta.inboundWireBytes();
        }

        protected void add(ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot delta) {
            if (delta == null) {
                return;
            }
            this.serverOfflineReuseConfirmedFrames += delta.offlineReuseConfirmedFrames();
            this.serverOfflineReuseConfirmedSavedBytes += delta.offlineReuseConfirmedSavedBytes();
            this.serverOfflineReuseConfirmedWireBytes += delta.offlineReuseConfirmedWireBytes();
            this.serverTemporaryReuseSavedBytes += delta.temporaryReuseSavedBytes();
        }

        protected void add(CreateBlockEntityUpdateGate.Snapshot delta) {
            if (delta == null) {
                return;
            }
            this.serverCreateGateObservedBytes += delta.observedBytes();
            this.serverCreateGateSavedBytes += delta.savedBytes();
            this.serverCreateGateSavedPackets += delta.savedPackets();
            this.serverCreateGateReleasedPackets += delta.releasedPackets();
        }

        protected void read(CompoundTag tag) {
            if (tag == null) {
                return;
            }
            this.outboundRawEncodedPackets = tag.getLong("outboundRawEncodedPackets");
            this.outboundRawEncodedBytes = tag.getLong("outboundRawEncodedBytes");
            this.outboundVanillaCompressedEstimateBytes = tag.getLong("outboundVanillaCompressedEstimateBytes");
            this.outboundVanillaEstimateWireBytes = tag.getLong("outboundVanillaEstimateWireBytes");
            this.inboundRawEncodedPackets = tag.getLong("inboundRawEncodedPackets");
            this.inboundRawEncodedBytes = tag.getLong("inboundRawEncodedBytes");
            this.outboundTransportFrames = tag.getLong("outboundTransportFrames");
            this.outboundTransportFrameBytes = tag.getLong("outboundTransportFrameBytes");
            this.inboundTransportFrames = tag.getLong("inboundTransportFrames");
            this.inboundTransportFrameBytes = tag.getLong("inboundTransportFrameBytes");
            this.outboundBypassPackets = tag.getLong("outboundBypassPackets");
            this.outboundBypassBytes = tag.getLong("outboundBypassBytes");
            this.inboundBypassPackets = tag.getLong("inboundBypassPackets");
            this.inboundBypassBytes = tag.getLong("inboundBypassBytes");
            this.outboundWireBytes = tag.getLong("outboundWireBytes");
            this.inboundWireBytes = tag.getLong("inboundWireBytes");
            this.serverOfflineReuseConfirmedFrames = tag.getLong("serverOfflineReuseConfirmedFrames");
            this.serverOfflineReuseConfirmedSavedBytes = tag.getLong("serverOfflineReuseConfirmedSavedBytes");
            this.serverOfflineReuseConfirmedWireBytes = tag.getLong("serverOfflineReuseConfirmedWireBytes");
            this.serverTemporaryReuseSavedBytes = tag.getLong("serverTemporaryReuseSavedBytes");
            this.serverCreateGateObservedBytes = tag.getLong("serverCreateGateObservedBytes");
            this.serverCreateGateSavedBytes = tag.getLong("serverCreateGateSavedBytes");
            this.serverCreateGateSavedPackets = tag.getLong("serverCreateGateSavedPackets");
            this.serverCreateGateReleasedPackets = tag.getLong("serverCreateGateReleasedPackets");
        }


        protected CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("outboundRawEncodedPackets", this.outboundRawEncodedPackets);
            tag.putLong("outboundRawEncodedBytes", this.outboundRawEncodedBytes);
            tag.putLong("outboundVanillaCompressedEstimateBytes", this.outboundVanillaCompressedEstimateBytes);
            tag.putLong("outboundVanillaEstimateWireBytes", this.outboundVanillaEstimateWireBytes);
            tag.putLong("inboundRawEncodedPackets", this.inboundRawEncodedPackets);
            tag.putLong("inboundRawEncodedBytes", this.inboundRawEncodedBytes);
            tag.putLong("outboundTransportFrames", this.outboundTransportFrames);
            tag.putLong("outboundTransportFrameBytes", this.outboundTransportFrameBytes);
            tag.putLong("inboundTransportFrames", this.inboundTransportFrames);
            tag.putLong("inboundTransportFrameBytes", this.inboundTransportFrameBytes);
            tag.putLong("outboundBypassPackets", this.outboundBypassPackets);
            tag.putLong("outboundBypassBytes", this.outboundBypassBytes);
            tag.putLong("inboundBypassPackets", this.inboundBypassPackets);
            tag.putLong("inboundBypassBytes", this.inboundBypassBytes);
            tag.putLong("outboundWireBytes", this.outboundWireBytes);
            tag.putLong("inboundWireBytes", this.inboundWireBytes);
            tag.putLong("serverOfflineReuseConfirmedFrames", this.serverOfflineReuseConfirmedFrames);
            tag.putLong("serverOfflineReuseConfirmedSavedBytes", this.serverOfflineReuseConfirmedSavedBytes);
            tag.putLong("serverOfflineReuseConfirmedWireBytes", this.serverOfflineReuseConfirmedWireBytes);
            tag.putLong("serverTemporaryReuseSavedBytes", this.serverTemporaryReuseSavedBytes);
            tag.putLong("serverCreateGateObservedBytes", this.serverCreateGateObservedBytes);
            tag.putLong("serverCreateGateSavedBytes", this.serverCreateGateSavedBytes);
            tag.putLong("serverCreateGateSavedPackets", this.serverCreateGateSavedPackets);
            tag.putLong("serverCreateGateReleasedPackets", this.serverCreateGateReleasedPackets);
            return tag;
        }

        protected MutableCounters copy() {
            MutableCounters copy = new MutableCounters();
            copy.outboundRawEncodedPackets = this.outboundRawEncodedPackets;
            copy.outboundRawEncodedBytes = this.outboundRawEncodedBytes;
            copy.outboundVanillaCompressedEstimateBytes = this.outboundVanillaCompressedEstimateBytes;
            copy.outboundVanillaEstimateWireBytes = this.outboundVanillaEstimateWireBytes;
            copy.inboundRawEncodedPackets = this.inboundRawEncodedPackets;
            copy.inboundRawEncodedBytes = this.inboundRawEncodedBytes;
            copy.outboundTransportFrames = this.outboundTransportFrames;
            copy.outboundTransportFrameBytes = this.outboundTransportFrameBytes;
            copy.inboundTransportFrames = this.inboundTransportFrames;
            copy.inboundTransportFrameBytes = this.inboundTransportFrameBytes;
            copy.outboundBypassPackets = this.outboundBypassPackets;
            copy.outboundBypassBytes = this.outboundBypassBytes;
            copy.inboundBypassPackets = this.inboundBypassPackets;
            copy.inboundBypassBytes = this.inboundBypassBytes;
            copy.outboundWireBytes = this.outboundWireBytes;
            copy.inboundWireBytes = this.inboundWireBytes;
            copy.serverOfflineReuseConfirmedFrames = this.serverOfflineReuseConfirmedFrames;
            copy.serverOfflineReuseConfirmedSavedBytes = this.serverOfflineReuseConfirmedSavedBytes;
            copy.serverOfflineReuseConfirmedWireBytes = this.serverOfflineReuseConfirmedWireBytes;
            copy.serverTemporaryReuseSavedBytes = this.serverTemporaryReuseSavedBytes;
            copy.serverCreateGateObservedBytes = this.serverCreateGateObservedBytes;
            copy.serverCreateGateSavedBytes = this.serverCreateGateSavedBytes;
            copy.serverCreateGateSavedPackets = this.serverCreateGateSavedPackets;
            copy.serverCreateGateReleasedPackets = this.serverCreateGateReleasedPackets;
            return copy;
        }

        protected void reset() {
            this.outboundRawEncodedPackets = 0L;
            this.outboundRawEncodedBytes = 0L;
            this.outboundVanillaCompressedEstimateBytes = 0L;
            this.outboundVanillaEstimateWireBytes = 0L;
            this.inboundRawEncodedPackets = 0L;
            this.inboundRawEncodedBytes = 0L;
            this.outboundTransportFrames = 0L;
            this.outboundTransportFrameBytes = 0L;
            this.inboundTransportFrames = 0L;
            this.inboundTransportFrameBytes = 0L;
            this.outboundBypassPackets = 0L;
            this.outboundBypassBytes = 0L;
            this.inboundBypassPackets = 0L;
            this.inboundBypassBytes = 0L;
            this.outboundWireBytes = 0L;
            this.inboundWireBytes = 0L;
            this.serverOfflineReuseConfirmedFrames = 0L;
            this.serverOfflineReuseConfirmedSavedBytes = 0L;
            this.serverOfflineReuseConfirmedWireBytes = 0L;
            this.serverTemporaryReuseSavedBytes = 0L;
            this.serverCreateGateObservedBytes = 0L;
            this.serverCreateGateSavedBytes = 0L;
            this.serverCreateGateSavedPackets = 0L;
            this.serverCreateGateReleasedPackets = 0L;
        }

        private ServerBandwidthStatsRegistry.TotalsSnapshot toTotalsSnapshot(int activeChannels, int boundPlayers) {
            return new ServerBandwidthStatsRegistry.TotalsSnapshot(
                    activeChannels,
                    boundPlayers,
                    this.outboundRawEncodedPackets,
                    this.outboundRawEncodedBytes,
                    this.outboundVanillaCompressedEstimateBytes,
                    this.outboundVanillaEstimateWireBytes,
                    this.inboundRawEncodedPackets,
                    this.inboundRawEncodedBytes,
                    this.outboundTransportFrames,
                    this.outboundTransportFrameBytes,
                    this.inboundTransportFrames,
                    this.inboundTransportFrameBytes,
                    this.outboundBypassPackets,
                    this.outboundBypassBytes,
                    this.inboundBypassPackets,
                    this.inboundBypassBytes,
                    this.outboundWireBytes,
                    this.inboundWireBytes,
                    this.serverOfflineReuseConfirmedFrames,
                    this.serverOfflineReuseConfirmedSavedBytes,
                    this.serverOfflineReuseConfirmedWireBytes,
                    this.serverTemporaryReuseSavedBytes,
                    this.serverCreateGateObservedBytes,
                    this.serverCreateGateSavedBytes,
                    this.serverCreateGateSavedPackets,
                    this.serverCreateGateReleasedPackets
            );
        }
    }
}
