package com.PinkCats.bandwidthoptimizer;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportRuntimeGuard;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportNetworkChannel;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd.ZstdRuntimeSupport;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCaptureRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.channel.mes.ChannelFrameJsonlLogger;
import com.PinkCats.bandwidthoptimizer.client.config.ClientChunkCacheConfig;
import com.PinkCats.bandwidthoptimizer.chunk.lifecycle.ChunkLifecycleCoordinator;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkPersistentClientCache;
import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotVerifyHooks;
import com.PinkCats.bandwidthoptimizer.command.BandwidthOptimizerCommand;
import com.PinkCats.bandwidthoptimizer.debug.PacketClassTraceNetworkChannel;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.ForgeModLoadingContextCompat;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateNetworkChannel;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsNetworkChannel;
import com.mojang.logging.LogUtils;
import com.pinkcats.torque.layer.TorqueLayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(Bandwidthoptimizer.MODID)
@Mod.EventBusSubscriber(modid = Bandwidthoptimizer.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class Bandwidthoptimizer {

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

    public Bandwidthoptimizer() {
        ModLoadingContext modLoadingContext = ForgeModLoadingContextCompat.getCurrentModLoadingContext();
        configureNetworkProtocolVersion(readModVersionFromModList());
        rejectHariChunkTransportStack();
        ZstdRuntimeSupport.configureNativeTempFolder();
        if (ChannelCaptureRuntimeConfig.isJsonlCaptureEnabled()) {
            ChannelFrameJsonlLogger.initializeOutputFiles();
        }
        ChunkHotspotVerifyHooks.initializeOutputFiles();
        ChannelTransportRuntimeGuard.initialize();
        BandwidthOptimizerLifecycle.register(TorqueLayer.platform());
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ChunkPersistentClientCache.startAsyncPreload("forge_client_startup");
        }

        FMLJavaModLoadingContext modContext = modLoadingContext.extension();
        IEventBus modEventBus = modContext.getModEventBus();

        MinecraftForge.EVENT_BUS.register(this);
        modEventBus.addListener(this::commonSetup);
        modContext.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modContext.registerConfig(ModConfig.Type.CLIENT, ClientChunkCacheConfig.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        ChannelTransportNetworkChannel.register();
        ServerBandwidthStatsNetworkChannel.register();
        IdleGateNetworkChannel.register();
        PacketClassTraceNetworkChannel.register();
        BandwidthOptimizerLifecycle.register(TorqueLayer.platform());
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        BandwidthOptimizerCommand.register(event.getDispatcher());
    }

    public static void prepareForClientRespawnBoundary(net.minecraft.server.level.ServerPlayer serverPlayer) {
        ChunkLifecycleCoordinator.prepareForClientRespawnBoundary(serverPlayer);
    }

    private static String readModVersionFromModList() {
        return ModList.get()
                .getModContainerById(MODID)
                .map(modContainer -> modContainer.getModInfo().getVersion().toString())
                .orElse(null);
    }

    private static void rejectHariChunkTransportStack() {
        if (!ModList.get().isLoaded("hariplayer") && !ModList.get().isLoaded("harichunk")) {
            return;
        }
        throw new IllegalStateException(
                "BandwidthOptimizer is incompatible with HariPlayer/HariChunk on Forge 1.20.1: "
                        + "both mods take ownership of chunk sending and Netty scheduling. Remove one before starting."
        );
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
