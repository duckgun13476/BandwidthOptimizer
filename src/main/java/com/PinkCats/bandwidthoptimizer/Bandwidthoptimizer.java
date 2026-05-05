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
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsNetworkChannel;
import com.mojang.logging.LogUtils;
import com.pinkcats.torque.layer.TorqueLayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
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
        BandwidthOptimizerLifecycle.register(TorqueLayer.platform());

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

    public static void prepareForClientRespawnBoundary(net.minecraft.server.level.ServerPlayer serverPlayer) {
        ChunkLifecycleCoordinator.prepareForClientRespawnBoundary(serverPlayer);
    }
}
