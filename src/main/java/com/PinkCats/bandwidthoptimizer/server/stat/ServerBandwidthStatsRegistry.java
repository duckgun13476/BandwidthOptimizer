package com.PinkCats.bandwidthoptimizer.server.stat;

import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ConnectionAccessor;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ServerGamePacketListenerImplAccessor;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerBandwidthStatsRegistry {

    private static final AttributeKey<ChannelBandwidthStats> CHANNEL_STATS_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:server_bandwidth_channel_stats");
    private static final AttributeKey<Boolean> CHANNEL_CLOSE_CLEANUP_ATTACHED_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:server_bandwidth_close_cleanup_attached");

    private static final ConcurrentHashMap<String, ChannelBandwidthStats> ACTIVE_CHANNELS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> PLAYER_CHANNELS = new ConcurrentHashMap<>();

    private ServerBandwidthStatsRegistry() {}


    public static ChannelBandwidthStats getOrCreate(ChannelHandlerContext context) {
        return context == null ? null : getOrCreate(context.channel());
    }


    public static ChannelBandwidthStats getOrCreate(Channel channel) {
        if (channel == null) {
            return null;
        }

        ChannelBandwidthStats existingStats = channel.attr(CHANNEL_STATS_KEY).get();
        if (existingStats != null) {
            return existingStats;
        }

        String channelId = channel.id() == null ? "<unknown-channel>" : channel.id().asLongText();
        ChannelBandwidthStats newStats = new ChannelBandwidthStats(channelId);
        ChannelBandwidthStats racedStats = channel.attr(CHANNEL_STATS_KEY).setIfAbsent(newStats);
        ChannelBandwidthStats resolvedStats = racedStats == null ? newStats : racedStats;
        ACTIVE_CHANNELS.putIfAbsent(channelId, resolvedStats);
        ensureCloseCleanup(channel, channelId);
        return resolvedStats;
    }

    public static void bindPlayer(ServerPlayer player) {
        Channel channel = readPlayerChannel(player);
        if (player == null || channel == null) {
            return;
        }

        ChannelBandwidthStats stats = getOrCreate(channel);
        if (stats == null)
            return;

        ServerBandwidthStatsPersistence.flushBeforeBind(player, stats);
        UUID playerId = player.getUUID();
        String playerName = player.getGameProfile() == null ? "<unknown-player>" : player.getGameProfile().getName();
        stats.bindPlayer(playerId, playerName);
        PLAYER_CHANNELS.put(playerId, channel.id().asLongText());
    }


    public static void unbindPlayer(ServerPlayer player) {
        if (player == null) {
            return;
        }

        UUID playerId = player.getUUID();
        String channelId = PLAYER_CHANNELS.remove(playerId);
        Channel channel = readPlayerChannel(player);
        ChannelBandwidthStats stats = channel == null ? null : channel.attr(CHANNEL_STATS_KEY).get();
        if (stats == null && channelId != null) {
            stats = ACTIVE_CHANNELS.get(channelId);
        }
        if (stats != null) {
            ServerBandwidthStatsPersistence.flushBeforeUnbind(player, stats);
            stats.unbindPlayer(playerId);
        }
    }


    public static void resetAll() {
        for (ChannelBandwidthStats stats : ACTIVE_CHANNELS.values()) {
            if (stats != null) {
                stats.resetCounters();
            }
        }
    }

    public static List<ChannelBandwidthStats.Snapshot> snapshotChannels() {
        List<ChannelBandwidthStats.Snapshot> snapshots = new ArrayList<>();
        for (ChannelBandwidthStats stats : ACTIVE_CHANNELS.values()) {
            if (stats != null) {
                snapshots.add(stats.snapshot());
            }
        }
        snapshots.sort(Comparator
                .comparingLong(ChannelBandwidthStats.Snapshot::outboundWireBytes)
                .reversed()
                .thenComparing(ChannelBandwidthStats.Snapshot::channelId));
        return List.copyOf(snapshots);
    }

    public static TotalsSnapshot snapshotTotals() {
        return snapshotSessionTotals();
    }

    public static TotalsSnapshot snapshotSessionTotals() {
        List<ChannelBandwidthStats.Snapshot> channelSnapshots = snapshotChannels();
        long outboundRawPackets = 0L;
        long outboundRawBytes = 0L;
        long inboundRawPackets = 0L;
        long inboundRawBytes = 0L;
        long outboundTransportFrames = 0L;
        long outboundTransportBytes = 0L;
        long inboundTransportFrames = 0L;
        long inboundTransportBytes = 0L;
        long outboundBypassPackets = 0L;
        long outboundBypassBytes = 0L;
        long inboundBypassPackets = 0L;
        long inboundBypassBytes = 0L;
        long outboundWireBytes = 0L;
        long inboundWireBytes = 0L;
        int boundPlayers = 0;

        for (ChannelBandwidthStats.Snapshot snapshot : channelSnapshots) {
            outboundRawPackets += snapshot.outboundRawEncodedPackets();
            outboundRawBytes += snapshot.outboundRawEncodedBytes();
            inboundRawPackets += snapshot.inboundRawEncodedPackets();
            inboundRawBytes += snapshot.inboundRawEncodedBytes();
            outboundTransportFrames += snapshot.outboundTransportFrames();
            outboundTransportBytes += snapshot.outboundTransportFrameBytes();
            inboundTransportFrames += snapshot.inboundTransportFrames();
            inboundTransportBytes += snapshot.inboundTransportFrameBytes();
            outboundBypassPackets += snapshot.outboundBypassPackets();
            outboundBypassBytes += snapshot.outboundBypassBytes();
            inboundBypassPackets += snapshot.inboundBypassPackets();
            inboundBypassBytes += snapshot.inboundBypassBytes();
            outboundWireBytes += snapshot.outboundWireBytes();
            inboundWireBytes += snapshot.inboundWireBytes();
            if (snapshot.playerId() != null) {
                boundPlayers++;
            }
        }

        return new TotalsSnapshot(
                channelSnapshots.size(),
                boundPlayers,
                outboundRawPackets,
                outboundRawBytes,
                inboundRawPackets,
                inboundRawBytes,
                outboundTransportFrames,
                outboundTransportBytes,
                inboundTransportFrames,
                inboundTransportBytes,
                outboundBypassPackets,
                outboundBypassBytes,
                inboundBypassPackets,
                inboundBypassBytes,
                outboundWireBytes,
                inboundWireBytes
        );
    }


    public static int activeChannelCount() {
        return ACTIVE_CHANNELS.size();
    }

    public static int boundPlayerCount() {
        return PLAYER_CHANNELS.size();
    }

    private static void ensureCloseCleanup(Channel channel, String channelId) {
        Boolean alreadyAttached = channel.attr(CHANNEL_CLOSE_CLEANUP_ATTACHED_KEY).get();
        if (Boolean.TRUE.equals(alreadyAttached)) {
            return;
        }

        Boolean raced = channel.attr(CHANNEL_CLOSE_CLEANUP_ATTACHED_KEY).setIfAbsent(Boolean.TRUE);
        if (Boolean.TRUE.equals(raced)) {
            return;
        }

        channel.closeFuture().addListener(future -> removeChannel(channel, channelId));
    }

    private static void removeChannel(Channel channel, String channelId) {
        ChannelBandwidthStats stats = channel == null ? null : channel.attr(CHANNEL_STATS_KEY).get();
        if (stats != null)
            ServerBandwidthStatsPersistence.flushOnChannelClose(stats.snapshot());

        if (channelId != null) {
            ACTIVE_CHANNELS.remove(channelId);
            PLAYER_CHANNELS.entrySet().removeIf(entry -> channelId.equals(entry.getValue()));
        }
        if (channel != null) {
            channel.attr(CHANNEL_STATS_KEY).set(null);
            channel.attr(CHANNEL_CLOSE_CLEANUP_ATTACHED_KEY).set(null);
        }
    }


    private static Channel readPlayerChannel(ServerPlayer player) {
        if (player == null) {
            return null;
        }

        ServerGamePacketListenerImpl listener = player.connection;

        Connection connection = ((ServerGamePacketListenerImplAccessor) listener).bandwidthoptimizer$getConnection();
        if (connection == null) {
            return null;
        }

        return ((ConnectionAccessor) connection).bandwidthoptimizer$getChannel();
    }

    public record TotalsSnapshot(
            int activeChannels,
            int boundPlayers,
            long outboundRawEncodedPackets,
            long outboundRawEncodedBytes,
            long inboundRawEncodedPackets,
            long inboundRawEncodedBytes,
            long outboundTransportFrames,
            long outboundTransportFrameBytes,
            long inboundTransportFrames,
            long inboundTransportFrameBytes,
            long outboundBypassPackets,
            long outboundBypassBytes,
            long inboundBypassPackets,
            long inboundBypassBytes,
            long outboundWireBytes,
            long inboundWireBytes
    ) {
        public long outboundSavedBytes() {
            if (outboundTransportFrameBytes <= 0L && outboundBypassBytes <= 0L) {
                return 0L;
            }
            return outboundRawEncodedBytes - outboundTransportFrameBytes - outboundBypassBytes;
        }
    }
}
