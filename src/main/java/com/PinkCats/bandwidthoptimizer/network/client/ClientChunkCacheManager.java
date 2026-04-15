package com.PinkCats.bandwidthoptimizer.network.client;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ConnectionReplayInvokerMixin;
import com.PinkCats.bandwidthoptimizer.network.ModNetwork;
import com.PinkCats.bandwidthoptimizer.network.message.ClientToServerChunkCacheMissPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheRefreshPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheUsePacket;
import com.PinkCats.bandwidthoptimizer.network.algorithm.play.PlayPacketReplaySupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ClientChunkCacheManager {

    private static final long TTL_MILLIS = 240_000L;
    private static final long MAX_TOTAL_BYTES = 100L * 1024L * 1024L;
    private static final int MAX_ENTRIES_TOTAL = 8_192;
    private static final int MAX_ENTRIES_PER_DIMENSION = 4_096;
    private static final Object LOCK = new Object();
    private static final Map<CacheKey, CacheEntry> CACHE = new LinkedHashMap<>(256, 0.75F, true);
    private static volatile long currentSessionId = Long.MIN_VALUE;
    private static long currentTotalBytes;

    private ClientChunkCacheManager() {
    }

    public static Snapshot snapshot() {
        synchronized (LOCK) {
            return new Snapshot(currentSessionId, currentTotalBytes, CACHE.size(), countByDimension().size());
        }
    }

    public static void handleRefresh(ClientboundChunkCacheRefreshPacket packet) {
        long now = System.currentTimeMillis();
        CacheKey key = new CacheKey(packet.dimensionId(), new ChunkPos(packet.chunkX(), packet.chunkZ()).toLong());
        synchronized (LOCK) {
            resetIfNeeded(packet.sessionId());
            prune(now);
            CacheEntry previous = CACHE.put(key, new CacheEntry(packet.encodedPacketBytes(), now + TTL_MILLIS, now));
            currentTotalBytes += packet.encodedPacketBytes().length;
            if (previous != null) {
                currentTotalBytes -= previous.byteSize();
            }
            trace("Refresh", packet.dimensionId(), packet.chunkX(), packet.chunkZ(), "sessionId=" + packet.sessionId() + ", bytes=" + packet.encodedPacketBytes().length);
            enforceLimits();
        }
        replayEncodedPacket(packet.encodedPacketBytes());
    }

    public static void handleUse(ClientboundChunkCacheUsePacket packet) {
        long now = System.currentTimeMillis();
        CacheEntry entry;
        synchronized (LOCK) {
            resetIfNeeded(packet.sessionId());
            prune(now);
            CacheKey key = new CacheKey(packet.dimensionId(), new ChunkPos(packet.chunkX(), packet.chunkZ()).toLong());
            entry = CACHE.get(key);
            if (entry != null && entry.expiresAtMillis < now) {
                currentTotalBytes -= entry.byteSize();
                CACHE.remove(key);
                entry = null;
            }
            if (entry == null) {
                trace("Miss", packet.dimensionId(), packet.chunkX(), packet.chunkZ(), "sessionId=" + packet.sessionId());
            } else {
                entry.lastAccessMillis = now;
                entry.expiresAtMillis = now + TTL_MILLIS;
                trace("Use", packet.dimensionId(), packet.chunkX(), packet.chunkZ(), "sessionId=" + packet.sessionId() + ", bytes=" + entry.byteSize());
            }
        }
        if (entry == null) {
            ModNetwork.sendToServer(new ClientToServerChunkCacheMissPacket(packet.sessionId(), packet.dimensionId(), packet.chunkX(), packet.chunkZ()));
            return;
        }
        replayEncodedPacket(entry.encodedPacketBytes);
    }

    private static void replayEncodedPacket(byte[] encodedPacketBytes) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener listener = minecraft.getConnection();
        if (listener == null) {
            return;
        }
        Packet<ClientGamePacketListener> packet = PlayPacketReplaySupport.decodePacket(encodedPacketBytes);
        Connection connection = listener.getConnection();
        try {
            ((ConnectionReplayInvokerMixin) (Object) connection).bandwidthoptimizer$invokeChannelRead0(null, packet);
        } catch (Throwable error) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[ChunkCache][Client][ReplayFallback] packet={}, reason={}",
                    packet.getClass().getName(),
                    error.toString()
            );
            packet.handle(listener);
        }
    }

    private static void resetIfNeeded(long sessionId) {
        if (currentSessionId == sessionId) {
            return;
        }
        currentSessionId = sessionId;
        CACHE.clear();
        currentTotalBytes = 0L;
        trace("SessionReset", null, 0, 0, "sessionId=" + sessionId);
    }

    private static void prune(long now) {
        Iterator<Map.Entry<CacheKey, CacheEntry>> iterator = CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<CacheKey, CacheEntry> entry = iterator.next();
            CacheEntry cacheEntry = entry.getValue();
            if (now - cacheEntry.lastAccessMillis > TTL_MILLIS) {
                iterator.remove();
                currentTotalBytes -= cacheEntry.byteSize();
                trace(
                        "PruneExpired",
                        entry.getKey().dimensionId(),
                        new ChunkPos(entry.getKey().chunkKey()).x,
                        new ChunkPos(entry.getKey().chunkKey()).z,
                        "ageMillis=" + (now - cacheEntry.lastAccessMillis)
                );
            }
        }
    }

    private static void enforceLimits() {
        if (currentTotalBytes <= MAX_TOTAL_BYTES && CACHE.size() <= MAX_ENTRIES_TOTAL) {
            Map<ResourceLocation, Integer> dimensionCounts = countByDimension();
            if (dimensionCounts.values().stream().allMatch(count -> count <= MAX_ENTRIES_PER_DIMENSION)) {
                return;
            }
        }

        Map<ResourceLocation, Integer> dimensionCounts = countByDimension();
        Iterator<Map.Entry<CacheKey, CacheEntry>> iterator = CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            if (currentTotalBytes <= MAX_TOTAL_BYTES
                    && CACHE.size() <= MAX_ENTRIES_TOTAL
                    && dimensionCounts.values().stream().allMatch(count -> count <= MAX_ENTRIES_PER_DIMENSION)) {
                return;
            }

            Map.Entry<CacheKey, CacheEntry> entry = iterator.next();
            CacheKey key = entry.getKey();
            CacheEntry cacheEntry = entry.getValue();
            int dimensionCount = dimensionCounts.getOrDefault(key.dimensionId(), 0);
            if (currentTotalBytes > MAX_TOTAL_BYTES || CACHE.size() > MAX_ENTRIES_TOTAL || dimensionCount > MAX_ENTRIES_PER_DIMENSION) {
                iterator.remove();
                currentTotalBytes -= cacheEntry.byteSize();
                dimensionCounts.computeIfPresent(key.dimensionId(), (ignored, count) -> Math.max(0, count - 1));
                trace(
                        "Evict",
                        key.dimensionId(),
                        new ChunkPos(key.chunkKey()).x,
                        new ChunkPos(key.chunkKey()).z,
                        "reason="
                                + (currentTotalBytes > MAX_TOTAL_BYTES ? "byte_limit" : dimensionCount > MAX_ENTRIES_PER_DIMENSION ? "dimension_limit" : "total_limit")
                                + ", bytes=" + cacheEntry.byteSize()
                                + ", totalBytes=" + currentTotalBytes
                                + ", totalEntries=" + CACHE.size()
                );
            }
        }
    }

    private static Map<ResourceLocation, Integer> countByDimension() {
        Map<ResourceLocation, Integer> counts = new HashMap<>();
        for (CacheKey key : CACHE.keySet()) {
            counts.merge(key.dimensionId(), 1, Integer::sum);
        }
        return counts;
    }

    private static void trace(String action, ResourceLocation dimensionId, int chunkX, int chunkZ, String detail) {
        if (!Config.optimizerDebugLoggingEnabled()) {
            return;
        }
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkCache][Client][{}] dimension={}, chunk=({}, {}), {}",
                action,
                dimensionId,
                chunkX,
                chunkZ,
                detail
        );
    }

    private static final class CacheEntry {
        private final byte[] encodedPacketBytes;
        private long expiresAtMillis;
        private long lastAccessMillis;

        private CacheEntry(byte[] encodedPacketBytes, long expiresAtMillis, long lastAccessMillis) {
            this.encodedPacketBytes = encodedPacketBytes;
            this.expiresAtMillis = expiresAtMillis;
            this.lastAccessMillis = lastAccessMillis;
        }

        private int byteSize() {
            return this.encodedPacketBytes.length;
        }
    }

    private record CacheKey(
            ResourceLocation dimensionId,
            long chunkKey
    ) {
    }

    public record Snapshot(
            long sessionId,
            long totalBytes,
            int totalEntries,
            int dimensions
    ) {
    }
}
