package com.PinkCats.bandwidthoptimizer.network.client;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ConnectionReplayInvokerMixin;
import com.PinkCats.bandwidthoptimizer.network.ModNetwork;
import com.PinkCats.bandwidthoptimizer.network.message.ClientToServerChunkCacheMissPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheDeltaPacket;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ClientChunkCacheManager {

    private static final long TTL_MILLIS = 240_000L;
    private static final long MAX_TOTAL_BYTES = 100L * 1024L * 1024L;
    private static final long MAX_DELTA_BYTES_PER_ENTRY = 4L * 1024L * 1024L;
    private static final int MAX_ENTRIES_TOTAL = 8_192;
    private static final int MAX_ENTRIES_PER_DIMENSION = 4_096;
    private static final Object LOCK = new Object();
    private static final Map<CacheKey, CacheEntry> CACHE = new LinkedHashMap<>(256, 0.75F, true);
    private static volatile long currentSessionId = Long.MIN_VALUE;
    private static long currentTotalBytes;
    private static long totalDeltaPacketsReceived;
    private static long totalDeltaBytesReceived;
    private static long totalUses;
    private static long totalUsesWithDelta;
    private static long totalUseDeltaPacketsReplayed;
    private static long totalUseDeltaBytesReplayed;
    private static long recentDeltaPacketsReceived;
    private static long recentDeltaBytesReceived;
    private static long recentUses;
    private static long recentUsesWithDelta;
    private static long recentUseDeltaPacketsReplayed;
    private static long recentUseDeltaBytesReplayed;
    private static long lastLogMillis;

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
            CacheEntry replacement = CacheEntry.base(packet.encodedPacketBytes(), now + TTL_MILLIS, now);
            CacheEntry previous = CACHE.put(key, replacement);
            currentTotalBytes += replacement.byteSize();
            if (previous != null) {
                currentTotalBytes -= previous.byteSize();
            }
            enforceLimits();
            maybeLog(now);
        }
        replayEncodedPacket(packet.encodedPacketBytes());
    }

    public static void handleDelta(ClientboundChunkCacheDeltaPacket packet) {
        long now = System.currentTimeMillis();
        CacheKey key = new CacheKey(packet.dimensionId(), new ChunkPos(packet.chunkX(), packet.chunkZ()).toLong());
        synchronized (LOCK) {
            resetIfNeeded(packet.sessionId());
            prune(now);
            CacheEntry entry = CACHE.get(key);
            if (entry == null || entry.expiresAtMillis < now) {
                if (entry != null) {
                    currentTotalBytes -= entry.byteSize();
                    CACHE.remove(key);
                }
                return;
            }

            entry.deltaPackets.add(packet.encodedPacketBytes());
            entry.deltaBytes += packet.encodedPacketBytes().length;
            entry.lastAccessMillis = now;
            entry.expiresAtMillis = now + TTL_MILLIS;
            currentTotalBytes += packet.encodedPacketBytes().length;
            totalDeltaPacketsReceived++;
            totalDeltaBytesReceived += packet.encodedPacketBytes().length;
            recentDeltaPacketsReceived++;
            recentDeltaBytesReceived += packet.encodedPacketBytes().length;

            if (entry.deltaBytes > MAX_DELTA_BYTES_PER_ENTRY) {
                currentTotalBytes -= entry.byteSize();
                CACHE.remove(key);
                return;
            }

            enforceLimits();
            maybeLog(now);
        }
    }

    public static void handleUse(ClientboundChunkCacheUsePacket packet) {
        long now = System.currentTimeMillis();
        byte[] basePacket;
        List<byte[]> deltaPackets;
        synchronized (LOCK) {
            resetIfNeeded(packet.sessionId());
            prune(now);
            CacheKey key = new CacheKey(packet.dimensionId(), new ChunkPos(packet.chunkX(), packet.chunkZ()).toLong());
            CacheEntry entry = CACHE.get(key);
            if (entry != null && entry.expiresAtMillis < now) {
                currentTotalBytes -= entry.byteSize();
                CACHE.remove(key);
                entry = null;
            }
            if (entry == null) {
                basePacket = null;
                deltaPackets = List.of();
            } else {
                entry.lastAccessMillis = now;
                entry.expiresAtMillis = now + TTL_MILLIS;
                basePacket = entry.basePacketBytes;
                deltaPackets = new ArrayList<>(entry.deltaPackets);
                totalUses++;
                recentUses++;
                if (!deltaPackets.isEmpty()) {
                    long deltaBytes = 0L;
                    for (byte[] deltaPacket : deltaPackets) {
                        deltaBytes += deltaPacket.length;
                    }
                    totalUsesWithDelta++;
                    totalUseDeltaPacketsReplayed += deltaPackets.size();
                    totalUseDeltaBytesReplayed += deltaBytes;
                    recentUsesWithDelta++;
                    recentUseDeltaPacketsReplayed += deltaPackets.size();
                    recentUseDeltaBytesReplayed += deltaBytes;
                }
            }
            maybeLog(now);
        }
        if (basePacket == null) {
            ModNetwork.sendToServer(new ClientToServerChunkCacheMissPacket(packet.sessionId(), packet.dimensionId(), packet.chunkX(), packet.chunkZ()));
            return;
        }
        replayEncodedPacket(basePacket);
        for (byte[] deltaPacket : deltaPackets) {
            replayEncodedPacket(deltaPacket);
        }
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
        totalDeltaPacketsReceived = 0L;
        totalDeltaBytesReceived = 0L;
        totalUses = 0L;
        totalUsesWithDelta = 0L;
        totalUseDeltaPacketsReplayed = 0L;
        totalUseDeltaBytesReplayed = 0L;
        recentDeltaPacketsReceived = 0L;
        recentDeltaBytesReceived = 0L;
        recentUses = 0L;
        recentUsesWithDelta = 0L;
        recentUseDeltaPacketsReplayed = 0L;
        recentUseDeltaBytesReplayed = 0L;
        lastLogMillis = 0L;
    }

    private static void prune(long now) {
        Iterator<Map.Entry<CacheKey, CacheEntry>> iterator = CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<CacheKey, CacheEntry> entry = iterator.next();
            CacheEntry cacheEntry = entry.getValue();
            if (now - cacheEntry.lastAccessMillis > TTL_MILLIS) {
                iterator.remove();
                currentTotalBytes -= cacheEntry.byteSize();
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
            }
        }
    }

    private static void maybeLog(long now) {
        if (!Config.enableOptimizerStatsLogs || !Config.enableTestMode) {
            return;
        }
        long logIntervalMillis = Config.optimizerStatsLogIntervalMillis();
        if (now - lastLogMillis < logIntervalMillis) {
            return;
        }
        lastLogMillis = now;
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkCache][Client] uses={}, usesWithDelta={}, useDeltaPacketsReplayed={}, useDeltaBytesReplayed={}, deltaPacketsReceived={}, deltaBytesReceived={} | recent uses={}, recentUsesWithDelta={}, recentUseDeltaPacketsReplayed={}, recentUseDeltaBytesReplayed={}, recentDeltaPacketsReceived={}, recentDeltaBytesReceived={}",
                totalUses,
                totalUsesWithDelta,
                totalUseDeltaPacketsReplayed,
                totalUseDeltaBytesReplayed,
                totalDeltaPacketsReceived,
                totalDeltaBytesReceived,
                recentUses,
                recentUsesWithDelta,
                recentUseDeltaPacketsReplayed,
                recentUseDeltaBytesReplayed,
                recentDeltaPacketsReceived,
                recentDeltaBytesReceived
        );
        recentDeltaPacketsReceived = 0L;
        recentDeltaBytesReceived = 0L;
        recentUses = 0L;
        recentUsesWithDelta = 0L;
        recentUseDeltaPacketsReplayed = 0L;
        recentUseDeltaBytesReplayed = 0L;
    }

    private static Map<ResourceLocation, Integer> countByDimension() {
        Map<ResourceLocation, Integer> counts = new HashMap<>();
        for (CacheKey key : CACHE.keySet()) {
            counts.merge(key.dimensionId(), 1, Integer::sum);
        }
        return counts;
    }

    private static final class CacheEntry {
        private final byte[] basePacketBytes;
        private final List<byte[]> deltaPackets;
        private long deltaBytes;
        private long expiresAtMillis;
        private long lastAccessMillis;

        private CacheEntry(byte[] basePacketBytes, List<byte[]> deltaPackets, long deltaBytes, long expiresAtMillis, long lastAccessMillis) {
            this.basePacketBytes = basePacketBytes;
            this.deltaPackets = deltaPackets;
            this.deltaBytes = deltaBytes;
            this.expiresAtMillis = expiresAtMillis;
            this.lastAccessMillis = lastAccessMillis;
        }

        private static CacheEntry base(byte[] basePacketBytes, long expiresAtMillis, long lastAccessMillis) {
            return new CacheEntry(basePacketBytes, new ArrayList<>(), 0L, expiresAtMillis, lastAccessMillis);
        }

        private int byteSize() {
            return this.basePacketBytes.length + Math.toIntExact(this.deltaBytes);
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
