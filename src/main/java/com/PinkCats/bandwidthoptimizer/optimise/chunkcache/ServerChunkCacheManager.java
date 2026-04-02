package com.PinkCats.bandwidthoptimizer.optimise.chunkcache;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.network.ModNetwork;
import com.PinkCats.bandwidthoptimizer.network.message.ClientToServerChunkCacheMissPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheRefreshPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheUsePacket;
import com.PinkCats.bandwidthoptimizer.network.algorithm.play.PlayPacketReplaySupport;
import com.PinkCats.bandwidthoptimizer.network.algorithm.play.ServerOptimizationTelemetryManager;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class ServerChunkCacheManager {

    public static final long TTL_MILLIS = 600_000L;
    private static final double CACHE_RADIUS_MULTIPLIER = 3D;
    private static final double FORCE_REFRESH_MULTIPLIER = 0.6D;
    private static final Map<UUID, PlayerState> STATES = new ConcurrentHashMap<>();

    private ServerChunkCacheManager() {
    }

    public static void resetPlayer(ServerPlayer player) {
        if (player != null) {
            STATES.put(player.getUUID(), new PlayerState());
        }
    }

    public static void removePlayer(ServerPlayer player) {
        if (player != null) {
            STATES.remove(player.getUUID());
        }
    }

    public static boolean tryHandle(ServerPlayer player, ClientboundLevelChunkWithLightPacket packet) {
        if (player == null || packet == null) {
            return false;
        }
        PlayerState state = STATES.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        long now = System.currentTimeMillis();
        int viewDistance = Math.max(2, player.server.getPlayerList().getViewDistance());
        int cacheRadius = Math.max(viewDistance, (int) Math.ceil(viewDistance * CACHE_RADIUS_MULTIPLIER));
        int forceRefreshRadius = Math.max(1, (int) Math.floor(viewDistance * FORCE_REFRESH_MULTIPLIER));
        ChunkPos playerChunk = player.chunkPosition();
        ChunkPos targetChunk = new ChunkPos(packet.getX(), packet.getZ());
        int distanceSquared = distanceSquared(playerChunk, targetChunk);
        long key = targetChunk.toLong();
        synchronized (state) {
            prune(state, playerChunk, cacheRadius, now);
            ChunkEntry entry = state.entries.get(key);
            boolean forceRefresh = distanceSquared <= forceRefreshRadius * forceRefreshRadius;
            if (!forceRefresh && entry != null && now - entry.lastRefreshMillis <= TTL_MILLIS) {
                ClientboundChunkCacheUsePacket usePacket = new ClientboundChunkCacheUsePacket(state.sessionId, targetChunk.x, targetChunk.z);
                ModNetwork.sendChunkCacheUseToPlayer(player, usePacket);
                long rawBytes = entry.rawBytes;
                long sentBytes = usePacket.encodedSize();
                long savedBytes = Math.max(rawBytes - sentBytes, 0L);
                ChunkCacheStats.recordHit(rawBytes, sentBytes, savedBytes);
                ServerOptimizationTelemetryManager.recordChunkCache(player, savedBytes, true, false);
                return true;
            }

            byte[] encodedPacketBytes = PlayPacketReplaySupport.encodePacket(packet);
            ClientboundChunkCacheRefreshPacket refreshPacket = new ClientboundChunkCacheRefreshPacket(state.sessionId, targetChunk.x, targetChunk.z, encodedPacketBytes);
            ModNetwork.sendChunkCacheRefreshToPlayer(player, refreshPacket);
            state.entries.put(key, new ChunkEntry(now, encodedPacketBytes.length));
            ChunkCacheStats.recordRefresh(encodedPacketBytes.length, refreshPacket.encodedSize());
            ServerOptimizationTelemetryManager.recordChunkCache(player, 0L, false, true);
            return true;
        }
    }

    public static void handleCacheMiss(ServerPlayer player, ClientToServerChunkCacheMissPacket packet) {
        PlayerState state = STATES.get(player.getUUID());
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.sessionId != packet.sessionId()) {
                return;
            }
            long key = new ChunkPos(packet.chunkX(), packet.chunkZ()).toLong();
            ChunkEntry entry = state.entries.get(key);
            if (entry == null) {
                Bandwidthoptimizer.LOGGER.warn(
                        "[ChunkCache][Server][MissWithoutEntry] player={}, chunk=({}, {}), sessionId={}",
                        player.getGameProfile().getName(),
                        packet.chunkX(),
                        packet.chunkZ(),
                        packet.sessionId()
                );
                return;
            }
            ClientboundChunkCacheRefreshPacket refreshPacket = rebuildRefreshPacket(player, state.sessionId, packet.chunkX(), packet.chunkZ());
            if (refreshPacket == null) {
                state.entries.remove(key);
                Bandwidthoptimizer.LOGGER.warn(
                        "[ChunkCache][Server][MissRebuildFailed] player={}, chunk=({}, {}), sessionId={}",
                        player.getGameProfile().getName(),
                        packet.chunkX(),
                        packet.chunkZ(),
                        packet.sessionId()
                );
                return;
            }
            ModNetwork.sendChunkCacheRefreshToPlayer(player, refreshPacket);
            entry.lastRefreshMillis = System.currentTimeMillis();
            entry.rawBytes = refreshPacket.encodedPacketBytes().length;
            ChunkCacheStats.recordRefresh(entry.rawBytes, refreshPacket.encodedSize());
            ChunkCacheStats.recordMissRefresh(entry.rawBytes, refreshPacket.encodedSize());
        }
    }

    private static ClientboundChunkCacheRefreshPacket rebuildRefreshPacket(ServerPlayer player, long sessionId, int chunkX, int chunkZ) {
        ServerLevel level = player.serverLevel();
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return null;
        }
        ClientboundLevelChunkWithLightPacket rebuiltPacket = new ClientboundLevelChunkWithLightPacket(
                chunk,
                level.getChunkSource().getLightEngine(),
                null,
                null
        );
        byte[] encodedPacketBytes = PlayPacketReplaySupport.encodePacket(rebuiltPacket);
        return new ClientboundChunkCacheRefreshPacket(sessionId, chunkX, chunkZ, encodedPacketBytes);
    }

    private static void prune(PlayerState state, ChunkPos playerChunk, int cacheRadius, long now) {
        Iterator<Map.Entry<Long, ChunkEntry>> iterator = state.entries.entrySet().iterator();
        int radiusSquared = cacheRadius * cacheRadius;
        while (iterator.hasNext()) {
            Map.Entry<Long, ChunkEntry> entry = iterator.next();
            ChunkPos chunkPos = new ChunkPos(entry.getKey());
            if (distanceSquared(playerChunk, chunkPos) > radiusSquared || now - entry.getValue().lastRefreshMillis > TTL_MILLIS) {
                iterator.remove();
            }
        }
    }

    private static int distanceSquared(ChunkPos a, ChunkPos b) {
        int dx = a.x - b.x;
        int dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static final class PlayerState {
        private final long sessionId = ThreadLocalRandom.current().nextLong();
        private final Map<Long, ChunkEntry> entries = new ConcurrentHashMap<>();
    }

    private static final class ChunkEntry {
        private long lastRefreshMillis;
        private int rawBytes;

        private ChunkEntry(long lastRefreshMillis, int rawBytes) {
            this.lastRefreshMillis = lastRefreshMillis;
            this.rawBytes = rawBytes;
        }
    }
}
