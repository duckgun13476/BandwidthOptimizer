package com.PinkCats.bandwidthoptimizer;

import com.PinkCats.bandwidthoptimizer.Old.network.ModNetwork;
import com.PinkCats.bandwidthoptimizer.channel.mes.ChannelFrameJsonlLogger;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportRuntimeGuard;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd.ZstdRuntimeSupport;
import com.PinkCats.bandwidthoptimizer.chunk.lifecycle.ChunkLifecycleCoordinator;
import com.PinkCats.bandwidthoptimizer.command.BandwidthOptimizerCommand;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.lang.reflect.Field;

@Mod(Bandwidthoptimizer.MODID)
@Mod.EventBusSubscriber(modid = Bandwidthoptimizer.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class Bandwidthoptimizer {

    public static final String MODID = "bandwidthoptimizer";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Bandwidthoptimizer() {
        ZstdRuntimeSupport.configureNativeTempFolder();
        ChannelFrameJsonlLogger.initializeOutputFiles();
        ChannelTransportRuntimeGuard.initialize();


        // Only for 1.20.1 forge
        ModLoadingContext modLoadingContext = getModLoadingContextViaReflection();
        FMLJavaModLoadingContext modContext = modLoadingContext.extension();
        //IEventBus modEventBus = modContext.getModEventBus();

        MinecraftForge.EVENT_BUS.register(this);
        modContext.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        ModNetwork.register();
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        BandwidthOptimizerCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void syncConfigOnLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            ChunkLifecycleCoordinator.onPlayerLogin(serverPlayer);
            resetLegacyRuntimeState(serverPlayer);
            if (ModNetwork.isLegacyTransportEnabled()) {
                ModNetwork.sendServerConfigToPlayer(serverPlayer);
            }
        }
    }

    @SubscribeEvent
    public static void resetStateOnRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            ChunkLifecycleCoordinator.onPlayerRespawn(serverPlayer);
            resetLegacyRuntimeState(serverPlayer);
        }
    }

    @SubscribeEvent
    public static void resetStateOnDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            ChunkLifecycleCoordinator.onPlayerDimensionChange(serverPlayer);
            resetLegacyRuntimeState(serverPlayer);
        }
    }

    @SubscribeEvent
    public static void clearStateOnLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            ChunkLifecycleCoordinator.onPlayerLogout(serverPlayer);
            resetLegacyRuntimeState(serverPlayer);
        }
    }

    public static void prepareForClientRespawnBoundary(net.minecraft.server.level.ServerPlayer serverPlayer) {
        ChunkLifecycleCoordinator.prepareForClientRespawnBoundary(serverPlayer);
        resetLegacyRuntimeState(serverPlayer);
    }

    private static void resetLegacyRuntimeState(net.minecraft.server.level.ServerPlayer serverPlayer) {
        if (!ModNetwork.isLegacyTransportEnabled()) {
            return;
        }
    }



    //Tool Func
    @SuppressWarnings("unchecked")
    private static ModLoadingContext getModLoadingContextViaReflection() {
        try {
            Field contextField = ModLoadingContext.class.getDeclaredField("context");
            contextField.setAccessible(true);
            ThreadLocal<ModLoadingContext> contextThreadLocal = (ThreadLocal<ModLoadingContext>) contextField.get(null);
            return contextThreadLocal.get();

        } catch (Exception e) {
            throw new RuntimeException("CreateLazyTick got ERROR in Init:", e);
        }
    }

}
