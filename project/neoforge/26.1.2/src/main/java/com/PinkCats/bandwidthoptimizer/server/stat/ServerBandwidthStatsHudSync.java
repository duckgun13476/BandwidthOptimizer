package com.PinkCats.bandwidthoptimizer.server.stat;

import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.List;

@EventBusSubscriber(modid = Bandwidthoptimizer.MODID)
public final class ServerBandwidthStatsHudSync {

    private static final int SYNC_INTERVAL_TICKS = 5;

    private ServerBandwidthStatsHudSync() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer() == null) {
            return;
        }

        MinecraftServer server = event.getServer();
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
