package com.PinkCats.bandwidthoptimizer.optimise.chunkcache;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.network.ModNetwork;
import com.PinkCats.bandwidthoptimizer.network.message.ClientToServerChunkCacheMissPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheDeltaPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheRefreshPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheUsePacket;
import com.PinkCats.bandwidthoptimizer.network.algorithm.play.PlayPacketReplaySupport;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class ServerChunkCacheManager {

    public static final long TTL_MILLIS = 600_000L;
    private static final int MAX_ENTRIES_TOTAL = 8_192;
    private static final int MAX_ENTRIES_PER_DIMENSION = 2_048;
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
        ChunkPos targetChunk = new ChunkPos(packet.getX(), packet.getZ());
        ResourceLocation dimensionId = player.serverLevel().dimension().location();
        CacheKey key = new CacheKey(dimensionId, targetChunk.toLong());
        synchronized (state) {
            PruneResult pruneResult = prune(state, now, key);
            ChunkEntry entry = state.entries.get(key);
            if (entry != null) {
                entry.lastAccessMillis = now;
                ClientboundChunkCacheUsePacket usePacket = new ClientboundChunkCacheUsePacket(state.sessionId, dimensionId, targetChunk.x, targetChunk.z);
                ModNetwork.sendChunkCacheUseToPlayer(player, usePacket, entry.rawBytes);
                long rawBytes = entry.rawBytes;
                long sentBytes = usePacket.encodedSize();
                long savedBytes = Math.max(rawBytes - sentBytes, 0L);
                ChunkCacheStats.recordHit(rawBytes, sentBytes, savedBytes, entry.deltaPacketsSinceRefresh, entry.deltaBytesSinceRefresh);
                enforceLimits(state, player);
                return true;
            }

            byte[] encodedPacketBytes = PlayPacketReplaySupport.encodePacket(packet);
            ClientboundChunkCacheRefreshPacket refreshPacket = new ClientboundChunkCacheRefreshPacket(state.sessionId, dimensionId, targetChunk.x, targetChunk.z, encodedPacketBytes);
            ModNetwork.sendChunkCacheRefreshToPlayer(player, refreshPacket);
            state.entries.put(key, new ChunkEntry(now, now, encodedPacketBytes.length));
            ChunkCacheStats.recordRefresh(
                    encodedPacketBytes.length,
                    refreshPacket.encodedSize(),
                    determineRefreshReason(entry, pruneResult)
            );
            enforceLimits(state, player);
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
            ResourceLocation currentDimensionId = player.serverLevel().dimension().location();
            if (!currentDimensionId.equals(packet.dimensionId())) {
                return;
            }
            CacheKey key = new CacheKey(packet.dimensionId(), new ChunkPos(packet.chunkX(), packet.chunkZ()).toLong());
            ChunkEntry entry = state.entries.get(key);
            if (entry == null) {
                Bandwidthoptimizer.LOGGER.warn(
                        "[ChunkCache][Server][MissWithoutEntry] player={}, dimension={}, chunk=({}, {}), sessionId={}",
                        player.getGameProfile().getName(),
                        packet.dimensionId(),
                        packet.chunkX(),
                        packet.chunkZ(),
                        packet.sessionId()
                );
                return;
            }
            ClientboundChunkCacheRefreshPacket refreshPacket = rebuildRefreshPacket(player, state.sessionId, packet.dimensionId(), packet.chunkX(), packet.chunkZ());
            if (refreshPacket == null) {
                state.entries.remove(key);
                Bandwidthoptimizer.LOGGER.warn(
                        "[ChunkCache][Server][MissRebuildFailed] player={}, dimension={}, chunk=({}, {}), sessionId={}",
                        player.getGameProfile().getName(),
                        packet.dimensionId(),
                        packet.chunkX(),
                        packet.chunkZ(),
                        packet.sessionId()
                );
                return;
            }
            ModNetwork.sendChunkCacheRefreshToPlayer(player, refreshPacket);
            entry.lastRefreshMillis = System.currentTimeMillis();
            entry.lastAccessMillis = entry.lastRefreshMillis;
            entry.rawBytes = refreshPacket.encodedPacketBytes().length;
            entry.deltaPacketsSinceRefresh = 0L;
            entry.deltaBytesSinceRefresh = 0L;
            ChunkCacheStats.recordRefresh(entry.rawBytes, refreshPacket.encodedSize(), ChunkCacheStats.RefreshReason.CLIENT_MISS);
            ChunkCacheStats.recordMissRefresh(entry.rawBytes, refreshPacket.encodedSize());
        }
    }

    public static void routeDeltaToCachedPlayers(ServerLevel level, ChunkPos chunkPos, Packet<?> packet) {
        if (level == null || chunkPos == null || packet == null) {
            return;
        }
        if (!supportsDeltaReplay(packet)) {
            return;
        }

        long now = System.currentTimeMillis();
        ResourceLocation dimensionId = level.dimension().location();
        CacheKey key = new CacheKey(dimensionId, chunkPos.toLong());
        byte[] encodedPacketBytes = PlayPacketReplaySupport.encodePacket(packet);
        List<ServerPlayer> trackedPlayers = ((ServerChunkCache) level.getChunkSource()).chunkMap.getPlayers(chunkPos, false);

        for (ServerPlayer player : trackedPlayers) {
            PlayerState state = STATES.get(player.getUUID());
            if (state == null) {
                continue;
            }

            long sessionId;
            synchronized (state) {
                ChunkEntry entry = state.entries.get(key);
                if (entry == null) {
                    continue;
                }
                long idleMillis = now - entry.lastAccessMillis;
                if (idleMillis > TTL_MILLIS) {
                    continue;
                }
                entry.lastAccessMillis = now;
                entry.deltaPacketsSinceRefresh++;
                entry.deltaBytesSinceRefresh += encodedPacketBytes.length;
                sessionId = state.sessionId;
            }

            ClientboundChunkCacheDeltaPacket deltaPacket =
                    new ClientboundChunkCacheDeltaPacket(sessionId, dimensionId, chunkPos.x, chunkPos.z, encodedPacketBytes);
            ModNetwork.sendChunkCacheDeltaToPlayer(player, deltaPacket);
            ChunkCacheStats.recordDelta(encodedPacketBytes.length);
        }
    }

    private static ClientboundChunkCacheRefreshPacket rebuildRefreshPacket(ServerPlayer player, long sessionId, ResourceLocation dimensionId, int chunkX, int chunkZ) {
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
        return new ClientboundChunkCacheRefreshPacket(sessionId, dimensionId, chunkX, chunkZ, encodedPacketBytes);
    }

    private static ChunkCacheStats.RefreshReason determineRefreshReason(
            ChunkEntry entry,
            PruneResult pruneResult
    ) {
        if (pruneResult.targetRemovedByIdle()) {
            return ChunkCacheStats.RefreshReason.PRUNED_IDLE;
        }
        if (entry != null) {
            return ChunkCacheStats.RefreshReason.TTL_SINCE_REFRESH;
        }
        return ChunkCacheStats.RefreshReason.NO_ENTRY;
    }

    private static PruneResult prune(
            PlayerState state,
            long now,
            CacheKey targetKey
    ) {
        Iterator<Map.Entry<CacheKey, ChunkEntry>> typedIterator = state.entries.entrySet().iterator();
        boolean targetRemovedByIdle = false;
        while (typedIterator.hasNext()) {
            Map.Entry<CacheKey, ChunkEntry> entry = typedIterator.next();
            CacheKey cacheKey = entry.getKey();
            ChunkEntry chunkEntry = entry.getValue();
            if (now - chunkEntry.lastAccessMillis > TTL_MILLIS) {
                if (cacheKey.equals(targetKey)) {
                    targetRemovedByIdle = true;
                }
                typedIterator.remove();
            }
        }
        return new PruneResult(targetRemovedByIdle);
    }

    private static void enforceLimits(PlayerState state, ServerPlayer player) {
        if (state.entries.size() <= MAX_ENTRIES_TOTAL) {
            Map<ResourceLocation, Integer> dimensionCounts = countByDimension(state);
            if (dimensionCounts.values().stream().allMatch(count -> count <= MAX_ENTRIES_PER_DIMENSION)) {
                return;
            }
        }

        Map<ResourceLocation, Integer> dimensionCounts = countByDimension(state);
        Iterator<Map.Entry<CacheKey, ChunkEntry>> iterator = state.entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (state.entries.size() <= MAX_ENTRIES_TOTAL
                    && dimensionCounts.values().stream().allMatch(count -> count <= MAX_ENTRIES_PER_DIMENSION)) {
                return;
            }

            Map.Entry<CacheKey, ChunkEntry> entry = iterator.next();
            CacheKey cacheKey = entry.getKey();
            int dimensionCount = dimensionCounts.getOrDefault(cacheKey.dimensionId(), 0);
            if (state.entries.size() > MAX_ENTRIES_TOTAL || dimensionCount > MAX_ENTRIES_PER_DIMENSION) {
                iterator.remove();
                dimensionCounts.computeIfPresent(cacheKey.dimensionId(), (ignored, count) -> Math.max(0, count - 1));
            }
        }
    }

    private static Map<ResourceLocation, Integer> countByDimension(PlayerState state) {
        Map<ResourceLocation, Integer> counts = new HashMap<>();
        for (CacheKey key : state.entries.keySet()) {
            counts.merge(key.dimensionId(), 1, Integer::sum);
        }
        return counts;
    }

    private static boolean supportsDeltaReplay(Packet<?> packet) {
        return packet instanceof ClientboundBlockUpdatePacket
                || packet instanceof ClientboundSectionBlocksUpdatePacket
                || packet instanceof ClientboundLightUpdatePacket
                || packet instanceof ClientboundBlockEntityDataPacket;
    }

    private static final class PlayerState {
        private final long sessionId = ThreadLocalRandom.current().nextLong();
        private final Map<CacheKey, ChunkEntry> entries = new LinkedHashMap<>(256, 0.75F, true);
    }

    private record PruneResult(
            boolean targetRemovedByIdle
    ) {
    }

    private record CacheKey(
            ResourceLocation dimensionId,
            long chunkKey
    ) {
    }

    private static final class ChunkEntry {
        private long lastRefreshMillis;
        private long lastAccessMillis;
        private int rawBytes;
        private long deltaPacketsSinceRefresh;
        private long deltaBytesSinceRefresh;

        private ChunkEntry(long lastRefreshMillis, long lastAccessMillis, int rawBytes) {
            this.lastRefreshMillis = lastRefreshMillis;
            this.lastAccessMillis = lastAccessMillis;
            this.rawBytes = rawBytes;
        }
    }
}
