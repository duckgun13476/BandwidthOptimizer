package com.PinkCats.bandwidthoptimizer;

import com.PinkCats.bandwidthoptimizer.command.PacketTrafficCommand;
import com.PinkCats.bandwidthoptimizer.network.ModNetwork;
import com.PinkCats.bandwidthoptimizer.network.runtime.ZstdRuntimeSupport;
import com.PinkCats.bandwidthoptimizer.optimise.chunkcache.ServerChunkCacheManager;
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
        PacketTrafficCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void syncConfigOnLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            ServerChunkCacheManager.resetPlayer(serverPlayer);
            ModNetwork.sendServerConfigToPlayer(serverPlayer);
        }
    }

    @SubscribeEvent
    public static void clearStateOnLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            ServerChunkCacheManager.removePlayer(serverPlayer);
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
