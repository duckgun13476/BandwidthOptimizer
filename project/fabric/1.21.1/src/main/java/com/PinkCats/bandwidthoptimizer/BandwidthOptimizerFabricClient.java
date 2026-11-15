package com.PinkCats.bandwidthoptimizer;

import com.PinkCats.bandwidthoptimizer.client.config.ClientChunkCacheConfig;
import com.PinkCats.bandwidthoptimizer.client.hud.BandwidthOptimizerHudOverlay;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportClientReceiver;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkPersistentClientCache;
import com.PinkCats.bandwidthoptimizer.command.ClientHudCommand;
import com.PinkCats.bandwidthoptimizer.connection.ClientReconnectCoordinator;
import com.PinkCats.bandwidthoptimizer.connection.ClientReconnectPlatform;
import com.PinkCats.bandwidthoptimizer.experimental.runall.ExperientAutoConnectController;
import com.PinkCats.bandwidthoptimizer.experimental.runall.ExperientClientCaptureResetHooks;
import com.PinkCats.bandwidthoptimizer.debug.PacketClassTraceClientReceiver;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateClientController;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateClientNetworkSender;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsClientReceiver;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

public class BandwidthOptimizerFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientChunkCacheConfig.applyRuntimeConfig(ClientChunkCacheConfig.currentRuntimeConfig());
        ChannelTransportClientReceiver.register();
        ClientReconnectCoordinator.install(ClientReconnectPlatform.INSTANCE);
        IdleGateClientNetworkSender.register();
        ServerBandwidthStatsClientReceiver.register();
        PacketClassTraceClientReceiver.register();
        ChunkPersistentClientCache.startAsyncPreload("fabric_client_startup");
        ClientLifecycleEvents.CLIENT_STOPPING.register(client ->
                ChunkPersistentClientCache.flushAsync("fabric_client_stopping"));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                ClientHudCommand.register(dispatcher));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                BandwidthOptimizerHudOverlay.onLoggingIn());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
        {
            IdleGateClientController.onDisconnected();
            ChunkPersistentClientCache.flushAsync("fabric_client_disconnect");
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientReconnectCoordinator.onClientTick();
            IdleGateClientController.onClientTick(client);
            com.PinkCats.bandwidthoptimizer.experimental.runall.ExperientClientCaptureResetHooks.onClientTick();
            com.PinkCats.bandwidthoptimizer.experimental.runall.ExperientAutoConnectController.onClientTick(client);
            com.PinkCats.bandwidthoptimizer.experimental.runall.ExperientSingleplayerCarrierRegressionController.onClientTick(client);
        });
        HudRenderCallback.EVENT.register((guiGraphics, tickDelta) ->
                BandwidthOptimizerHudOverlay.render(guiGraphics));
    }
}

