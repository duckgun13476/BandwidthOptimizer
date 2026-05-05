package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ExperientChunkHotspotFullChunkTracker {

    private static final ConcurrentHashMap<String, ChannelFullChunkProgress> CHANNEL_PROGRESS = new ConcurrentHashMap<>();

    private ExperientChunkHotspotFullChunkTracker() {}


    public static void recordOutboundFullChunk(ChannelHandlerContext context) {
        if (context == null) {
            return;
        }

        String channelId = context.channel().id().asLongText();
        CHANNEL_PROGRESS.computeIfAbsent(channelId, ignored -> new ChannelFullChunkProgress()).recordNow();
    }

    public static boolean hasPlayerBeenQuietFor(ServerPlayer player, long quietMillis) {
        if (player == null) {
            return true;
        }

        Channel channel = ChunkPeerStateManager.findPlayerChannel(player);
        if (channel == null) {
            return true;
        }

        ChannelFullChunkProgress progress = CHANNEL_PROGRESS.get(channel.id().asLongText());
        if (progress == null) {
            return true;
        }

        long safeQuietMillis = Math.max(quietMillis, 0L);
        return System.currentTimeMillis() - progress.lastObservedAtMillis() >= safeQuietMillis;
    }

    private static final class ChannelFullChunkProgress {

        private final AtomicLong observedCount = new AtomicLong();
        private final AtomicLong lastObservedAtMillis = new AtomicLong();

        private void recordNow() {
            this.observedCount.incrementAndGet();
            this.lastObservedAtMillis.set(System.currentTimeMillis());
        }

        private long lastObservedAtMillis() {
            return this.lastObservedAtMillis.get();
        }
    }
}
