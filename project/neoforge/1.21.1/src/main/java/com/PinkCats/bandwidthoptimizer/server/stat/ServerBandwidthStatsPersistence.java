package com.PinkCats.bandwidthoptimizer.server.stat;

import com.PinkCats.bandwidthoptimizer.compat.create.CreateBlockEntityUpdateGate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerBandwidthStatsPersistence {

    private static final int FLUSH_INTERVAL_TICKS = 20 * 60 * 2;
    private static final ConcurrentHashMap<String, ChannelBandwidthStats.Snapshot> LAST_FLUSHED =
            new ConcurrentHashMap<>();
    private static volatile ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot lastFlushedCacheReuse =
            ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot.empty();
    private static volatile CreateBlockEntityUpdateGate.Snapshot lastFlushedCreateGate = emptyCreateGateSnapshot();
    private static volatile MinecraftServer lastServer;

    private ServerBandwidthStatsPersistence() {}


    public static void rememberServer(MinecraftServer server) {
        if (server != null)
            lastServer = server;
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null)
            return;
        rememberServer(server);
        if (server.getTickCount() % FLUSH_INTERVAL_TICKS == 0)
            flushAll(server);
    }


    public static void flushBeforeBind(ServerPlayer player, ChannelBandwidthStats stats) {
        if (player == null || stats == null)
            return;
        rememberServer(player.server);
        flushSnapshot(player.server, stats.snapshot());
    }


    public static void flushBeforeUnbind(ServerPlayer player, ChannelBandwidthStats stats) {
        flushBeforeBind(player, stats);
    }

    public static void flushOnChannelClose(ChannelBandwidthStats.Snapshot snapshot) {
        if (snapshot == null)
            return;
        MinecraftServer server = lastServer;
        if (server == null)
            return;
        server.execute(() -> flushSnapshot(server, snapshot));
    }


    public static void flushOnServerStopping(MinecraftServer server) {
        if (server == null)
            return;
        rememberServer(server);
        flushAll(server);
    }


    public static void resetAll(MinecraftServer server) {
        if (server == null) {
            LAST_FLUSHED.clear();
            lastFlushedCacheReuse = ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot.empty();
            lastFlushedCreateGate = emptyCreateGateSnapshot();
            return;
        }
        rememberServer(server);
        get(server).resetAll();
        LAST_FLUSHED.clear();
        lastFlushedCacheReuse = ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot.empty();
        lastFlushedCreateGate = emptyCreateGateSnapshot();
    }


    public static ServerBandwidthStatsRegistry.TotalsSnapshot snapshotTotals(MinecraftServer server) {
        if (server == null)
            return ServerBandwidthStatsRegistry.snapshotSessionTotals();
        rememberServer(server);
        return get(server).snapshotTotals(
                ServerBandwidthStatsRegistry.activeChannelCount(),
                ServerBandwidthStatsRegistry.boundPlayerCount(),
                pendingDeltas(),
                pendingCacheReuseDelta(),
                pendingCreateGateDelta()
        );
    }


    public static List<ChannelBandwidthStats.Snapshot> snapshotPlayers(MinecraftServer server, int limit) {
        if (server == null)
            return ServerBandwidthStatsRegistry.snapshotChannels();
        rememberServer(server);
        flushAll(server);
        return get(server).snapshotPlayers(limit);
    }

    private static ServerBandwidthPersistentStats get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                        ServerBandwidthPersistentStats::new,
                        (tag, provider) -> ServerBandwidthPersistentStats.load(tag)
                ),
                ServerBandwidthPersistentStats.DATA_NAME
        );
    }

    private static void flushAll(MinecraftServer server) {
        ServerBandwidthPersistentStats persistentStats = get(server);
        for (ChannelBandwidthStats.Snapshot snapshot : ServerBandwidthStatsRegistry.snapshotChannels()) {
            ChannelBandwidthStats.Snapshot delta = deltaSinceLastFlush(snapshot);
            if (delta != null)
                persistentStats.addDelta(delta);
        }
        persistentStats.addServerCacheReuseDelta(cacheReuseDeltaSinceLastFlush());
        persistentStats.addCreateGateDelta(createGateDeltaSinceLastFlush());
    }

    private static void flushSnapshot(MinecraftServer server, ChannelBandwidthStats.Snapshot snapshot) {
        if (server == null || snapshot == null)
            return;
        ChannelBandwidthStats.Snapshot delta = deltaSinceLastFlush(snapshot);
        if (delta != null)
            get(server).addDelta(delta);
        get(server).addServerCacheReuseDelta(cacheReuseDeltaSinceLastFlush());
        get(server).addCreateGateDelta(createGateDeltaSinceLastFlush());
    }

    private static List<ChannelBandwidthStats.Snapshot> pendingDeltas() {
        List<ChannelBandwidthStats.Snapshot> deltas = new ArrayList<>();
        for (ChannelBandwidthStats.Snapshot snapshot : ServerBandwidthStatsRegistry.snapshotChannels()) {
            ChannelBandwidthStats.Snapshot delta = deltaWithoutRecording(snapshot);
            if (delta != null)
                deltas.add(delta);
        }
        return deltas;
    }

    private static ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot cacheReuseDeltaSinceLastFlush() {
        ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot current =
                ServerBandwidthStatsRegistry.snapshotServerCacheReuse();
        ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot delta = cacheReuseDelta(current, lastFlushedCacheReuse);
        lastFlushedCacheReuse = current == null
                ? ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot.empty()
                : current;
        return hasCacheReuse(delta) ? delta : null;
    }

    private static ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot pendingCacheReuseDelta() {
        ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot delta =
                cacheReuseDelta(ServerBandwidthStatsRegistry.snapshotServerCacheReuse(), lastFlushedCacheReuse);
        return hasCacheReuse(delta) ? delta : null;
    }

    private static CreateBlockEntityUpdateGate.Snapshot createGateDeltaSinceLastFlush() {
        CreateBlockEntityUpdateGate.Snapshot current = CreateBlockEntityUpdateGate.snapshotStats();
        CreateBlockEntityUpdateGate.Snapshot delta = createGateDelta(current, lastFlushedCreateGate);
        lastFlushedCreateGate = current == null ? emptyCreateGateSnapshot() : current;
        return hasCreateGate(delta) ? delta : null;
    }

    private static CreateBlockEntityUpdateGate.Snapshot pendingCreateGateDelta() {
        CreateBlockEntityUpdateGate.Snapshot delta =
                createGateDelta(CreateBlockEntityUpdateGate.snapshotStats(), lastFlushedCreateGate);
        return hasCreateGate(delta) ? delta : null;
    }

    private static ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot cacheReuseDelta(
            ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot current,
            ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot previous
    ) {
        ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot safeCurrent =
                current == null ? ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot.empty() : current;
        ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot safePrevious =
                previous == null ? ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot.empty() : previous;
        return new ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot(
                positiveDelta(safeCurrent.offlineReuseConfirmedFrames(), safePrevious.offlineReuseConfirmedFrames()),
                positiveDelta(safeCurrent.offlineReuseConfirmedSavedBytes(), safePrevious.offlineReuseConfirmedSavedBytes()),
                positiveDelta(safeCurrent.offlineReuseConfirmedWireBytes(), safePrevious.offlineReuseConfirmedWireBytes()),
                positiveDelta(safeCurrent.temporaryReuseSavedBytes(), safePrevious.temporaryReuseSavedBytes())
        );
    }

    private static CreateBlockEntityUpdateGate.Snapshot createGateDelta(
            CreateBlockEntityUpdateGate.Snapshot current,
            CreateBlockEntityUpdateGate.Snapshot previous
    ) {
        CreateBlockEntityUpdateGate.Snapshot safeCurrent = current == null ? emptyCreateGateSnapshot() : current;
        CreateBlockEntityUpdateGate.Snapshot safePrevious = previous == null ? emptyCreateGateSnapshot() : previous;
        return new CreateBlockEntityUpdateGate.Snapshot(
                positiveDelta(safeCurrent.delayedPackets(), safePrevious.delayedPackets()),
                positiveDelta(safeCurrent.supersededPackets(), safePrevious.supersededPackets()),
                positiveDelta(safeCurrent.releasedPackets(), safePrevious.releasedPackets()),
                positiveDelta(safeCurrent.droppedPackets(), safePrevious.droppedPackets()),
                positiveDelta(safeCurrent.delayedBytes(), safePrevious.delayedBytes()),
                positiveDelta(safeCurrent.supersededSavedBytes(), safePrevious.supersededSavedBytes()),
                positiveDelta(safeCurrent.releasedBytes(), safePrevious.releasedBytes()),
                positiveDelta(safeCurrent.droppedSavedBytes(), safePrevious.droppedSavedBytes())
        );
    }

    private static ChannelBandwidthStats.Snapshot deltaSinceLastFlush(ChannelBandwidthStats.Snapshot snapshot) {
        if (snapshot == null || snapshot.channelId() == null)
            return null;
        ChannelBandwidthStats.Snapshot previous = LAST_FLUSHED.get(snapshot.channelId());
        ChannelBandwidthStats.Snapshot delta = delta(snapshot, previous);
        LAST_FLUSHED.put(snapshot.channelId(), snapshot);
        return hasTraffic(delta) ? delta : null;
    }

    private static ChannelBandwidthStats.Snapshot deltaWithoutRecording(ChannelBandwidthStats.Snapshot snapshot) {
        if (snapshot == null || snapshot.channelId() == null) {
            return null;
        }
        ChannelBandwidthStats.Snapshot delta = delta(snapshot, LAST_FLUSHED.get(snapshot.channelId()));
        return hasTraffic(delta) ? delta : null;
    }

    private static ChannelBandwidthStats.Snapshot delta(
            ChannelBandwidthStats.Snapshot current,
            ChannelBandwidthStats.Snapshot previous
    ) {
        if (current == null) {
            return null;
        }
        if (previous == null) {
            return current;
        }
        return new ChannelBandwidthStats.Snapshot(
                current.channelId(),
                current.playerId(),
                current.playerName(),
                current.createdAtMillis(),
                current.boundAtMillis(),
                positiveDelta(current.outboundRawEncodedPackets(), previous.outboundRawEncodedPackets()),
                positiveDelta(current.outboundRawEncodedBytes(), previous.outboundRawEncodedBytes()),
                positiveDelta(current.outboundVanillaCompressedEstimateBytes(), previous.outboundVanillaCompressedEstimateBytes()),
                positiveDelta(current.outboundVanillaEstimateWireBytes(), previous.outboundVanillaEstimateWireBytes()),
                positiveDelta(current.inboundRawEncodedPackets(), previous.inboundRawEncodedPackets()),
                positiveDelta(current.inboundRawEncodedBytes(), previous.inboundRawEncodedBytes()),
                positiveDelta(current.outboundTransportFrames(), previous.outboundTransportFrames()),
                positiveDelta(current.outboundTransportFrameBytes(), previous.outboundTransportFrameBytes()),
                positiveDelta(current.inboundTransportFrames(), previous.inboundTransportFrames()),
                positiveDelta(current.inboundTransportFrameBytes(), previous.inboundTransportFrameBytes()),
                positiveDelta(current.outboundBypassPackets(), previous.outboundBypassPackets()),
                positiveDelta(current.outboundBypassBytes(), previous.outboundBypassBytes()),
                positiveDelta(current.inboundBypassPackets(), previous.inboundBypassPackets()),
                positiveDelta(current.inboundBypassBytes(), previous.inboundBypassBytes()),
                positiveDelta(current.outboundWireBytes(), previous.outboundWireBytes()),
                positiveDelta(current.inboundWireBytes(), previous.inboundWireBytes())
        );
    }

    private static long positiveDelta(long current, long previous) {
        return current >= previous ? current - previous : 0L;
    }

    private static boolean hasTraffic(ChannelBandwidthStats.Snapshot snapshot) {
        return snapshot != null
                && (snapshot.outboundRawEncodedPackets() != 0L
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
                || snapshot.inboundWireBytes() != 0L);
    }

    private static boolean hasCacheReuse(ServerBandwidthStatsRegistry.ServerCacheReuseSnapshot snapshot) {
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

    private static CreateBlockEntityUpdateGate.Snapshot emptyCreateGateSnapshot() {
        return new CreateBlockEntityUpdateGate.Snapshot(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }
}
