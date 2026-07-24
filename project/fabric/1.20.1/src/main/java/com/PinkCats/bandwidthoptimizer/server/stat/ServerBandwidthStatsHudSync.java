package com.PinkCats.bandwidthoptimizer.server.stat;

import com.PinkCats.bandwidthoptimizer.gate.integration.minecraft.IdleGateHudSyncPolicy;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
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

        List<ServerPlayer> recipients = new ArrayList<>();
        for (ServerPlayer player : players) {
            if (IdleGateHudSyncPolicy.shouldSend(player, server.getTickCount())) {
                recipients.add(player);
            }
        }
        if (recipients.isEmpty()) {
            return;
        }

        ServerBandwidthStatsPayload payload =
                ServerBandwidthStatsPayload.fromTotals(ServerBandwidthStatsRegistry.snapshotSessionTotals());
        for (ServerPlayer player : recipients) {
            ServerBandwidthStatsNetworkChannel.sendToPlayer(player, payload);
        }
    }
}

