package com.PinkCats.bandwidthoptimizer;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportNetworkChannel;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportRuntimeGuard;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd.ZstdRuntimeSupport;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCaptureRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.channel.mes.ChannelFrameJsonlLogger;
import com.PinkCats.bandwidthoptimizer.chunk.lifecycle.ChunkLifecycleCoordinator;
import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotVerifyHooks;
import com.PinkCats.bandwidthoptimizer.command.BandwidthOptimizerCommand;
import com.PinkCats.bandwidthoptimizer.experient.ExperientChunkHotspotPathController;
import com.PinkCats.bandwidthoptimizer.experient.ExperientRunAllProbeHooks;
import com.PinkCats.bandwidthoptimizer.experient.ExperientServerCommandController;
import com.PinkCats.bandwidthoptimizer.experient.ExperientWatchBoundaryRefreshPatchController;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportCompressionCaptureManager;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportPacketRankCaptureManager;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsHudSync;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsLifecycleHooks;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsNetworkChannel;
import com.mojang.logging.LogUtils;
import com.pinkcats.torque.layer.TorqueLayer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;

public class Bandwidthoptimizer implements ModInitializer {

    public static final String MODID = "bandwidthoptimizer";
    public static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        ZstdRuntimeSupport.configureNativeTempFolder();
        if (ChannelCaptureRuntimeConfig.isJsonlCaptureEnabled()) {
            ChannelFrameJsonlLogger.initializeOutputFiles();
        }
        ChunkHotspotVerifyHooks.initializeOutputFiles();
        ChannelTransportRuntimeGuard.initialize();
        Config.applyRuntimeConfig(Config.currentLocalRuntimeConfig());
        BandwidthOptimizerLifecycle.register(TorqueLayer.platform());
        ChannelTransportNetworkChannel.register();
        ServerBandwidthStatsNetworkChannel.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                BandwidthOptimizerCommand.register(dispatcher));
        ServerLifecycleEvents.SERVER_STOPPING.register(ServerBandwidthStatsLifecycleHooks::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ServerBandwidthStatsHudSync.onServerTick(server);
            ChannelTransportCompressionCaptureManager.onServerTick();
            ChannelTransportPacketRankCaptureManager.onServerTick();
            ExperientWatchBoundaryRefreshPatchController.onServerTick(server);
            ExperientServerCommandController.onServerTick(server);
            ExperientRunAllProbeHooks.onServerTick(server);
            ExperientChunkHotspotPathController.onServerTick(server);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ExperientWatchBoundaryRefreshPatchController.onPlayerLoggedIn(handler.player);
            ExperientServerCommandController.onPlayerLoggedIn(handler.player);
            ExperientRunAllProbeHooks.onPlayerLoggedIn(handler.player);
            ExperientChunkHotspotPathController.onPlayerLoggedIn(handler.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ExperientWatchBoundaryRefreshPatchController.onPlayerLoggedOut(handler.player);
            ExperientServerCommandController.onPlayerLoggedOut(handler.player);
            ExperientChunkHotspotPathController.onPlayerLoggedOut(handler.player);
        });
    }

    public static void prepareForClientRespawnBoundary(net.minecraft.server.level.ServerPlayer serverPlayer) {
        ChunkLifecycleCoordinator.prepareForClientRespawnBoundary(serverPlayer);
    }
}

