package com.PinkCats.bandwidthoptimizer;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportRuntimeGuard;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportNetworkChannel;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd.ZstdRuntimeSupport;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCaptureRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.channel.mes.ChannelFrameJsonlLogger;
import com.PinkCats.bandwidthoptimizer.client.config.ClientChunkCacheConfig;
import com.PinkCats.bandwidthoptimizer.chunk.lifecycle.ChunkLifecycleCoordinator;
import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotVerifyHooks;
import com.PinkCats.bandwidthoptimizer.command.BandwidthOptimizerCommand;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportCompressionCaptureManager;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportPacketRankCaptureManager;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsNetworkChannel;
import com.mojang.logging.LogUtils;
import com.pinkcats.torque.layer.TorqueLayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(Bandwidthoptimizer.MODID)
public class Bandwidthoptimizer {

    public static final String MODID = "bandwidthoptimizer";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static volatile String networkProtocolVersion = "dev";

    // 返回当前发布版本对应的网络协议版本，用于让不同 mod 版本在握手阶段互斥。
    public static String networkProtocolVersion() {
        return networkProtocolVersion;
    }

    // 把基础通道名拼成带版本号的通道路径，避免旧版客户端继续声明同一个通道。
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
        BandwidthOptimizerLifecycle.register(TorqueLayer.platform());

        ChannelTransportNetworkChannel.setModEventBus(modEventBus);
        ChannelTransportNetworkChannel.register();
        ServerBandwidthStatsNetworkChannel.setModEventBus(modEventBus);
        ServerBandwidthStatsNetworkChannel.register();
        NeoForge.EVENT_BUS.register(this);
        modEventBus.addListener(this::commonSetup);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientChunkCacheConfig.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        ChannelTransportNetworkChannel.register();
        ServerBandwidthStatsNetworkChannel.register();
        BandwidthOptimizerLifecycle.register(TorqueLayer.platform());
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        BandwidthOptimizerCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        ChannelTransportCompressionCaptureManager.onServerTick(event);
        ChannelTransportPacketRankCaptureManager.onServerTick(event);
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
