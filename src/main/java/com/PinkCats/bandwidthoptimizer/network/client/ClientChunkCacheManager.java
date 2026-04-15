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
import net.minecraft.world.level.ChunkPos;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientChunkCacheManager {

    private static final long TTL_MILLIS = 240_000L;
    private static final Map<Long, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static volatile long currentSessionId = Long.MIN_VALUE;

    private ClientChunkCacheManager() {
    }

    public static void handleRefresh(ClientboundChunkCacheRefreshPacket packet) {
        resetIfNeeded(packet.sessionId());
        long now = System.currentTimeMillis();
        prune(now);
        long key = new ChunkPos(packet.chunkX(), packet.chunkZ()).toLong();
        CACHE.put(key, new CacheEntry(packet.encodedPacketBytes(), now + TTL_MILLIS));
        replayEncodedPacket(packet.encodedPacketBytes());
    }

    public static void handleUse(ClientboundChunkCacheUsePacket packet) {
        resetIfNeeded(packet.sessionId());
        long now = System.currentTimeMillis();
        prune(now);
        long key = new ChunkPos(packet.chunkX(), packet.chunkZ()).toLong();
        CacheEntry entry = CACHE.get(key);
        if (entry == null || entry.expiresAtMillis < now) {
            if (Config.optimizerDebugLoggingEnabled() && Bandwidthoptimizer.LOGGER.isDebugEnabled()) {
                Bandwidthoptimizer.LOGGER.debug(
                        "[ChunkCache][Client][Miss] chunk=({}, {}), sessionId={}",
                        packet.chunkX(),
                        packet.chunkZ(),
                        packet.sessionId()
                );
            }
            ModNetwork.sendToServer(new ClientToServerChunkCacheMissPacket(packet.sessionId(), packet.chunkX(), packet.chunkZ()));
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
    }

    private static void prune(long now) {
        Iterator<Map.Entry<Long, CacheEntry>> iterator = CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAtMillis < now) {
                iterator.remove();
            }
        }
    }

    private static final class CacheEntry {
        private final byte[] encodedPacketBytes;
        private final long expiresAtMillis;

        private CacheEntry(byte[] encodedPacketBytes, long expiresAtMillis) {
            this.encodedPacketBytes = encodedPacketBytes;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
