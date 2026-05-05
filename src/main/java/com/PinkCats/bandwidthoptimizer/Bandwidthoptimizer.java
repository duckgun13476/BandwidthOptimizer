package com.PinkCats.bandwidthoptimizer;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportRuntimeGuard;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportNetworkChannel;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd.ZstdRuntimeSupport;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCaptureRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.channel.mes.ChannelFrameJsonlLogger;
import com.PinkCats.bandwidthoptimizer.compat.minecraft.ForgeModLoadingContextCompat;
import com.PinkCats.bandwidthoptimizer.client.config.ClientChunkCacheConfig;
import com.PinkCats.bandwidthoptimizer.chunk.lifecycle.ChunkLifecycleCoordinator;
import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotVerifyHooks;
import com.PinkCats.bandwidthoptimizer.command.BandwidthOptimizerCommand;
import com.PinkCats.bandwidthoptimizer.experient.ExperientChunkWatchEventTracker;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsNetworkChannel;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(Bandwidthoptimizer.MODID)
@Mod.EventBusSubscriber(modid = Bandwidthoptimizer.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class Bandwidthoptimizer {

    public static final String MODID = "bandwidthoptimizer";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Bandwidthoptimizer() {
        ZstdRuntimeSupport.configureNativeTempFolder();
        ChannelTransportNetworkChannel.register();
        ServerBandwidthStatsNetworkChannel.register();
        if (ChannelCaptureRuntimeConfig.isJsonlCaptureEnabled()) {
            ChannelFrameJsonlLogger.initializeOutputFiles();
        }
        ChunkHotspotVerifyHooks.initializeOutputFiles();
        ChannelTransportRuntimeGuard.initialize();

        ModLoadingContext modLoadingContext = ForgeModLoadingContextCompat.getCurrentModLoadingContext();
        FMLJavaModLoadingContext modContext = modLoadingContext.extension();

        MinecraftForge.EVENT_BUS.register(this);
        modContext.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modContext.registerConfig(ModConfig.Type.CLIENT, ClientChunkCacheConfig.SPEC);
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        BandwidthOptimizerCommand.register(event.getDispatcher());
    }

    @SubscribeEvent public static void syncConfigOnLogin(PlayerEvent.PlayerLoggedInEvent event) {

        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            ServerBandwidthStatsRegistry.bindPlayer(serverPlayer);
            ExperientChunkWatchEventTracker.clearPlayer(serverPlayer);
            ChunkLifecycleCoordinator.onPlayerLogin(serverPlayer);
        }
    }

    @SubscribeEvent public static void resetStateOnRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            ChunkLifecycleCoordinator.onPlayerRespawn(serverPlayer);
        }
    }

    @SubscribeEvent public static void resetStateOnDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            ChunkLifecycleCoordinator.onPlayerDimensionChange(serverPlayer);
        }
    }

    @SubscribeEvent public static void clearStateOnLogout(PlayerEvent.PlayerLoggedOutEvent event) {

        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            ChunkLifecycleCoordinator.onPlayerLogout(serverPlayer);
            ExperientChunkWatchEventTracker.clearPlayer(serverPlayer);
            ServerBandwidthStatsRegistry.unbindPlayer(serverPlayer);
        }
    }

    @SubscribeEvent
    public static void recordStateOnChunkWatch(ChunkWatchEvent.Watch event) {
        if (event.getPlayer() != null && event.getPos() != null) {
            ExperientChunkWatchEventTracker.recordWatch(event.getPlayer(), event.getPos());
        }
    }

    @SubscribeEvent
    public static void invalidateStateOnChunkUnwatch(ChunkWatchEvent.UnWatch event) {
        if (event.getPlayer() != null && event.getLevel() != null && event.getPos() != null) {
            ExperientChunkWatchEventTracker.recordUnwatch(event.getPlayer(), event.getPos());
            ChunkLifecycleCoordinator.onPlayerStopWatchingChunk(event.getPlayer(), event.getPos(), event.getLevel());
        }
    }

    @SubscribeEvent
    public static void invalidateStateOnChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel
                && event.getChunk() != null
                && event.getChunk().getPos() != null) {
            ChunkLifecycleCoordinator.onServerChunkUnload(serverLevel, event.getChunk().getPos());
        }
    }

    public static void prepareForClientRespawnBoundary(net.minecraft.server.level.ServerPlayer serverPlayer) {
        ChunkLifecycleCoordinator.prepareForClientRespawnBoundary(serverPlayer);
    }
}
