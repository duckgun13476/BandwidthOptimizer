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
import com.PinkCats.bandwidthoptimizer.idle.IdleGateNetworkChannel;
import com.PinkCats.bandwidthoptimizer.idle.IdleGateServerState;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportCompressionCaptureManager;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportPacketRankCaptureManager;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsHudSync;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsLifecycleHooks;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsNetworkChannel;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public class Bandwidthoptimizer implements ModInitializer {

    public static final String MODID = "bandwidthoptimizer";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static volatile String networkProtocolVersion = "dev";

    public static String networkProtocolVersion() {
        return networkProtocolVersion;
    }

    public static String displayVersion() {
        return networkProtocolVersion().replace('_', '.');
    }

    public static String versionedNetworkPath(String basePath) {
        return basePath + "_" + networkProtocolVersion();
    }

    @Override
    public void onInitialize() {
        configureNetworkProtocolVersion(readModVersionFromModMetadata());
        ZstdRuntimeSupport.configureNativeTempFolder();
        if (ChannelCaptureRuntimeConfig.isJsonlCaptureEnabled()) {
            ChannelFrameJsonlLogger.initializeOutputFiles();
        }
        ChunkHotspotVerifyHooks.initializeOutputFiles();
        ChannelTransportRuntimeGuard.initialize();
        Config.applyRuntimeConfig(Config.currentLocalRuntimeConfig());
        FabricBandwidthOptimizerLifecycle.register();
        ChannelTransportNetworkChannel.register();
        ServerBandwidthStatsNetworkChannel.register();
        IdleGateNetworkChannel.register();
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
            FabricBandwidthOptimizerLifecycle.onPlayerLogin(handler.player);
            ExperientWatchBoundaryRefreshPatchController.onPlayerLoggedIn(handler.player);
            ExperientServerCommandController.onPlayerLoggedIn(handler.player);
            ExperientRunAllProbeHooks.onPlayerLoggedIn(handler.player);
            ExperientChunkHotspotPathController.onPlayerLoggedIn(handler.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            IdleGateServerState.onPlayerLoggedOut(handler.player);
            FabricBandwidthOptimizerLifecycle.onPlayerLogout(handler.player);
            ExperientWatchBoundaryRefreshPatchController.onPlayerLoggedOut(handler.player);
            ExperientServerCommandController.onPlayerLoggedOut(handler.player);
            ExperientChunkHotspotPathController.onPlayerLoggedOut(handler.player);
        });
    }

    public static void prepareForClientRespawnBoundary(net.minecraft.server.level.ServerPlayer serverPlayer) {
        ChunkLifecycleCoordinator.prepareForClientRespawnBoundary(serverPlayer);
    }

    private static String readModVersionFromModMetadata() {
        return FabricLoader.getInstance()
                .getModContainer(MODID)
                .map(modContainer -> modContainer.getMetadata().getVersion().getFriendlyString())
                .orElse(null);
    }

    private static void configureNetworkProtocolVersion(String rawVersion) {
        networkProtocolVersion = sanitizeNetworkVersion(rawVersion);
        LOGGER.info("[Transport] Network protocol version resolved from mod metadata: {}", networkProtocolVersion);
    }

    private static String sanitizeNetworkVersion(String rawVersion) {
        if (rawVersion == null) {
            return "dev";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < rawVersion.length(); index++) {
            char character = Character.toLowerCase(rawVersion.charAt(index));
            if ((character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '_'
                    || character == '-'
                    || character == '.') {
                builder.append(character == '.' ? '_' : character);
            } else {
                builder.append('_');
            }
        }
        String sanitized = builder.toString();
        while (sanitized.contains("__")) {
            sanitized = sanitized.replace("__", "_");
        }
        sanitized = sanitized.replaceAll("^_+|_+$", "");
        return sanitized.isEmpty() ? "dev" : sanitized;
    }
}
