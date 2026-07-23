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
import com.PinkCats.bandwidthoptimizer.debug.ServerBoLogExportNetworkChannel;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateNetworkChannel;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportCompressionCaptureManager;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportPacketRankCaptureManager;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsNetworkChannel;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

@Mod(Bandwidthoptimizer.MODID)
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

    public Bandwidthoptimizer(IEventBus modEventBus, ModContainer modContainer) {
        configureNetworkProtocolVersion(readModVersionFromLoaderContainer(modContainer));
        ZstdRuntimeSupport.configureNativeTempFolder();
        if (ChannelCaptureRuntimeConfig.isJsonlCaptureEnabled()) {
            ChannelFrameJsonlLogger.initializeOutputFiles();
        }
        ChunkHotspotVerifyHooks.initializeOutputFiles();
        ChannelTransportRuntimeGuard.initialize();
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            ChunkPersistentClientCache.startAsyncPreload("neoforge_client_startup");
        }

        ChannelTransportNetworkChannel.setModEventBus(modEventBus);
        ChannelTransportNetworkChannel.register();
        ServerBandwidthStatsNetworkChannel.setModEventBus(modEventBus);
        ServerBandwidthStatsNetworkChannel.register();
        IdleGateNetworkChannel.setModEventBus(modEventBus);
        IdleGateNetworkChannel.register();
        ServerBoLogExportNetworkChannel.setModEventBus(modEventBus);
        ServerBoLogExportNetworkChannel.register();
        NeoForge.EVENT_BUS.register(this);
        modEventBus.addListener(this::commonSetup);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientChunkCacheConfig.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        ChannelTransportNetworkChannel.register();
        ServerBandwidthStatsNetworkChannel.register();
        IdleGateNetworkChannel.register();
        ServerBoLogExportNetworkChannel.register();
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        BandwidthOptimizerCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        BandwidthOptimizerLifecycle.onServerTickEnd();
        ChannelTransportCompressionCaptureManager.onServerTick(event);
        ChannelTransportPacketRankCaptureManager.onServerTick(event);
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BandwidthOptimizerLifecycle.onPlayerLogin(player);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BandwidthOptimizerLifecycle.onPlayerLogout(player);
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BandwidthOptimizerLifecycle.onPlayerRespawn(player);
        }
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BandwidthOptimizerLifecycle.onPlayerDimensionChange(player);
        }
    }

    @SubscribeEvent
    public void onChunkWatch(ChunkWatchEvent.Watch event) {
        BandwidthOptimizerLifecycle.onChunkWatch(event.getPlayer(), event.getPos());
    }

    @SubscribeEvent
    public void onChunkUnwatch(ChunkWatchEvent.UnWatch event) {
        BandwidthOptimizerLifecycle.onChunkUnwatch(event.getPlayer(), event.getLevel(), event.getPos());
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            BandwidthOptimizerLifecycle.onChunkUnload(level, event.getChunk().getPos());
        }
    }

    public static void prepareForClientRespawnBoundary(net.minecraft.server.level.ServerPlayer serverPlayer) {
        ChunkLifecycleCoordinator.prepareForClientRespawnBoundary(serverPlayer);
    }

    private static String readModVersionFromLoaderContainer(ModContainer modContainer) {
        if (modContainer == null) {
            return null;
        }
        try {
            return modContainer.getModInfo().getVersion().toString();
        } catch (RuntimeException exception) {
            return null;
        }
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
