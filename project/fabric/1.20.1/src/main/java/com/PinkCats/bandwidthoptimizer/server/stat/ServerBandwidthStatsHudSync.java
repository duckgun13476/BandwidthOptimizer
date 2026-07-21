package com.PinkCats.bandwidthoptimizer.server.stat;

import com.PinkCats.bandwidthoptimizer.gate.compat.minecraft.IdleGateHudSyncPolicy;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public final class ServerBandwidthStatsHudSync {

    private ServerBandwidthStatsHudSync() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        ServerBandwidthStatsPersistence.onServerTick(server);
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }

        ServerBandwidthStatsPayload payload =
                ServerBandwidthStatsPayload.fromTotals(ServerBandwidthStatsRegistry.snapshotSessionTotals());
        for (ServerPlayer player : players) {
            if (IdleGateHudSyncPolicy.shouldSend(player, server.getTickCount())) {
                ServerBandwidthStatsNetworkChannel.sendToPlayer(player, payload);
            }
        }
    }
}

