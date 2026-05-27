package com.PinkCats.bandwidthoptimizer.server.stat;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public final class ServerBandwidthStatsHudSync {

    private static final int SYNC_INTERVAL_TICKS = 5;

    private ServerBandwidthStatsHudSync() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        ServerBandwidthStatsPersistence.onServerTick(server);
        if (server.getTickCount() % SYNC_INTERVAL_TICKS != 0) {
            return;
        }

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }

        ServerBandwidthStatsPayload payload =
                ServerBandwidthStatsPayload.fromTotals(ServerBandwidthStatsRegistry.snapshotSessionTotals());
        for (ServerPlayer player : players) {
            ServerBandwidthStatsNetworkChannel.sendToPlayer(player, payload);
        }
    }
}

