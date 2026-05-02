package com.PinkCats.bandwidthoptimizer.client.debug;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "bandwidthoptimizer", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientChunkRenderProbe {

    private static final int SAMPLE_INTERVAL_TICKS = 20;
    private static final int MAX_SAMPLE_LOGS = 180;
    private static int tickCounter;
    private static int sampleLogs;

    private ClientChunkRenderProbe() {}


    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || sampleLogs >= MAX_SAMPLE_LOGS) {
            return;
        }
        tickCounter++;
        if (tickCounter % SAMPLE_INTERVAL_TICKS != 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || minecraft.levelRenderer == null) {
            return;
        }
        logClientChunkRenderState(minecraft, level, player);
    }

    private static void logClientChunkRenderState(Minecraft minecraft, ClientLevel level, LocalPlayer player) {
        try {
            ClientChunkCache chunkCache = level.getChunkSource();
            ChunkPos playerChunk = player.chunkPosition();
            int nearbyLoaded = countNearbyLoadedChunks(chunkCache, playerChunk, 2);
            boolean currentCached = isChunkCached(chunkCache, playerChunk.x, playerChunk.z);
            int loadedChunks = chunkCache.getLoadedChunksCount();
            int renderedChunks = minecraft.levelRenderer.countRenderedChunks();
            boolean renderedAll = minecraft.levelRenderer.hasRenderedAllChunks();
            sampleLogs++;
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkRenderProbe] sample={}, playerChunk=({}, {}), currentCached={}, nearbyCached5x5={}, clientLoadedChunks={}, renderedChunks={}, renderedAll={}",
                    sampleLogs,
                    playerChunk.x,
                    playerChunk.z,
                    currentCached,
                    nearbyLoaded,
                    loadedChunks,
                    renderedChunks,
                    renderedAll
            );
        } catch (Throwable throwable) {
            sampleLogs++;
            Bandwidthoptimizer.LOGGER.warn("[ChunkRenderProbe] sample failed", throwable);
        }
    }


    private static int countNearbyLoadedChunks(ClientChunkCache chunkCache, ChunkPos center, int radius) {
        int loaded = 0;
        for (int chunkX = center.x - radius; chunkX <= center.x + radius; chunkX++) {
            for (int chunkZ = center.z - radius; chunkZ <= center.z + radius; chunkZ++) {
                if (isChunkCached(chunkCache, chunkX, chunkZ)) {
                    loaded++;
                }
            }
        }
        return loaded;
    }


    private static boolean isChunkCached(ClientChunkCache chunkCache, int chunkX, int chunkZ) {
        LevelChunk chunk = chunkCache.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        return chunk != null;
    }
}
