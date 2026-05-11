package com.PinkCats.bandwidthoptimizer.server.stat;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = Bandwidthoptimizer.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerBandwidthStatsHudSync {

    private static final int SYNC_INTERVAL_TICKS = 5;

    private ServerBandwidthStatsHudSync() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) {
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
                ServerBandwidthStatsPayload.fromTotals(ServerBandwidthStatsPersistence.snapshotTotals(server));
        for (ServerPlayer player : players) {
            ServerBandwidthStatsNetworkChannel.sendToPlayer(player, payload);
        }
    }
}
