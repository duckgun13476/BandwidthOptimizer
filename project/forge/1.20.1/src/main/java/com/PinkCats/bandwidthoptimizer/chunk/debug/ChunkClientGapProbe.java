package com.PinkCats.bandwidthoptimizer.chunk.debug;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Mod.EventBusSubscriber(modid = Bandwidthoptimizer.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ChunkClientGapProbe {
    private static final String ENABLED_PROPERTY = "bandwidthoptimizer.chunk.gapProbe";
    private static final String VERBOSE_PROPERTY = "bandwidthoptimizer.chunk.gapProbeVerbose";
    private static final String INTERVAL_TICKS_PROPERTY = "bandwidthoptimizer.chunk.gapProbeIntervalTicks";
    private static final String MAX_EVENTS_PROPERTY = "bandwidthoptimizer.chunk.gapProbeMaxEvents";
    private static final AtomicInteger EVENT_COUNT = new AtomicInteger();
    private static final Set<Long> RECEIVED_THIS_ROUND = ConcurrentHashMap.newKeySet();
    private static volatile int currentRound;
    private static volatile String roundReason = "startup";
    private static volatile int cacheCenterX;
    private static volatile int cacheCenterZ;
    private static volatile boolean cacheCenterKnown;
    private static volatile int cacheRadius = 8;
    private static int ticks;
    private static long lastPlayerChunkKey = Long.MIN_VALUE;
    private static String lastSignature = "";

    private ChunkClientGapProbe() {}

    public static void recordCacheCenter(int chunkX, int chunkZ) {
        if (!isEnabled()) {
            return;
        }
        cacheCenterX = chunkX;
        cacheCenterZ = chunkZ;
        cacheCenterKnown = true;
        startRound("cache_center", chunkX, chunkZ);
        logVerboseMarker("cache_center", chunkX, chunkZ, cacheRadius, false);
    }

    public static void recordCacheRadius(int radius) {
        if (!isEnabled()) {
            return;
        }
        cacheRadius = Math.max(1, Math.min(radius, 32));
        logVerboseMarker("cache_radius", cacheCenterX, cacheCenterZ, cacheRadius, cacheCenterKnown);
    }

    public static void recordTeleport(ChunkPos playerChunk) {
        if (!isEnabled()) {
            return;
        }
        int chunkX = playerChunk == null ? 0 : playerChunk.x;
        int chunkZ = playerChunk == null ? 0 : playerChunk.z;
        startRound("player_position", chunkX, chunkZ);
        lastPlayerChunkKey = Long.MIN_VALUE;
    }

    public static void recordHandledChunk(int chunkX, int chunkZ) {
        if (!isEnabled()) {
            return;
        }
        RECEIVED_THIS_ROUND.add(chunkKey(chunkX, chunkZ));
        boolean cached = isChunkCached(Minecraft.getInstance().level, chunkX, chunkZ);
        if (!cached) {
            logMarker("chunk_handle_uncached", chunkX, chunkZ, cacheRadius, cacheCenterKnown);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isEnabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            return;
        }
        ticks++;
        ChunkPos playerChunk = minecraft.player.chunkPosition();
        long playerChunkKey = chunkKey(playerChunk.x, playerChunk.z);
        boolean movedChunk = playerChunkKey != lastPlayerChunkKey;
        int intervalTicks = readInt(INTERVAL_TICKS_PROPERTY, 20, 1, 20 * 60);
        if (!movedChunk && ticks % intervalTicks != 0) {
            return;
        }
        lastPlayerChunkKey = playerChunkKey;

        Snapshot playerSnapshot = snapshot(minecraft.level, "player", playerChunk.x, playerChunk.z);
        Snapshot centerSnapshot = cacheCenterKnown ? snapshot(minecraft.level, "center", cacheCenterX, cacheCenterZ) : null;
        logIfMissing(playerSnapshot, movedChunk);
        if (centerSnapshot != null && (centerSnapshot.anchorX() != playerSnapshot.anchorX()
                || centerSnapshot.anchorZ() != playerSnapshot.anchorZ())) {
            logIfMissing(centerSnapshot, movedChunk);
        }
    }

    private static void startRound(String reason, int anchorX, int anchorZ) {
        currentRound++;
        roundReason = reason == null || reason.isBlank() ? "unknown" : reason;
        RECEIVED_THIS_ROUND.clear();
        lastSignature = "";
        if (!isVerbose()) {
            return;
        }
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkGapProbe] round_start round={}, reason={}, anchor=({}, {}), center=({}, {}), radius={}",
                currentRound,
                roundReason,
                anchorX,
                anchorZ,
                cacheCenterX,
                cacheCenterZ,
                cacheRadius
        );
    }

    private static void logIfMissing(Snapshot snapshot, boolean movedChunk) {
        if (snapshot.missingCount() <= 0) {
            return;
        }
        String signature = snapshot.signature();
        if (!movedChunk && signature.equals(lastSignature)) {
            return;
        }
        lastSignature = signature;
        int eventIndex = EVENT_COUNT.incrementAndGet();
        if (eventIndex > readInt(MAX_EVENTS_PROPERTY, 512, 1, 100_000)) {
            return;
        }
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkGapProbe] missing_3x3 event={}, round={}, reason={}, target={}, anchor=({}, {}), missing={}, received={}, cached={}, matrix={}, missingChunks={}, center=({}, {}), radius={}",
                eventIndex,
                currentRound,
                roundReason,
                snapshot.target(),
                snapshot.anchorX(),
                snapshot.anchorZ(),
                snapshot.missingCount(),
                snapshot.receivedCount(),
                snapshot.cachedCount(),
                snapshot.matrix(),
                snapshot.missingChunks(),
                cacheCenterX,
                cacheCenterZ,
                cacheRadius
        );
    }

    private static void logVerboseMarker(String kind, int chunkX, int chunkZ, int radius, boolean centerKnown) {
        if (!isVerbose()) {
            return;
        }
        logMarker(kind, chunkX, chunkZ, radius, centerKnown);
    }

    private static void logMarker(String kind, int chunkX, int chunkZ, int radius, boolean centerKnown) {
        int eventIndex = EVENT_COUNT.incrementAndGet();
        if (eventIndex > readInt(MAX_EVENTS_PROPERTY, 512, 1, 100_000)) {
            return;
        }
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkGapProbe] marker event={}, round={}, reason={}, kind={}, chunk=({}, {}), centerKnown={}, center=({}, {}), radius={}",
                eventIndex,
                currentRound,
                roundReason,
                kind,
                chunkX,
                chunkZ,
                centerKnown,
                cacheCenterX,
                cacheCenterZ,
                radius
        );
    }

    private static Snapshot snapshot(ClientLevel level, String target, int anchorX, int anchorZ) {
        StringBuilder matrix = new StringBuilder(11);
        StringBuilder missingChunks = new StringBuilder();
        int missing = 0;
        int cached = 0;
        int received = 0;
        for (int dz = -1; dz <= 1; dz++) {
            if (dz > -1) {
                matrix.append('/');
            }
            for (int dx = -1; dx <= 1; dx++) {
                int chunkX = anchorX + dx;
                int chunkZ = anchorZ + dz;
                boolean isCached = isChunkCached(level, chunkX, chunkZ);
                boolean wasReceived = RECEIVED_THIS_ROUND.contains(chunkKey(chunkX, chunkZ));
                if (isCached) {
                    cached++;
                    matrix.append('C');
                } else if (wasReceived) {
                    received++;
                    missing++;
                    matrix.append('R');
                    appendChunk(missingChunks, chunkX, chunkZ);
                } else {
                    missing++;
                    matrix.append('.');
                    appendChunk(missingChunks, chunkX, chunkZ);
                }
            }
        }
        return new Snapshot(target, anchorX, anchorZ, missing, cached, received, matrix.toString(), missingChunks.toString());
    }

    private static boolean isChunkCached(ClientLevel level, int chunkX, int chunkZ) {
        if (level == null) {
            return false;
        }
        try {
            ClientChunkCache chunkCache = level.getChunkSource();
            return chunkCache.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void appendChunk(StringBuilder builder, int chunkX, int chunkZ) {
        if (builder.length() > 0) {
            builder.append(';');
        }
        builder.append(chunkX).append(',').append(chunkZ);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"));
    }

    private static boolean isVerbose() {
        return Boolean.parseBoolean(System.getProperty(VERBOSE_PROPERTY, "false"));
    }

    private static int readInt(String property, int defaultValue, int min, int max) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private record Snapshot(
            String target,
            int anchorX,
            int anchorZ,
            int missingCount,
            int cachedCount,
            int receivedCount,
            String matrix,
            String missingChunks
    ) {
        private String signature() {
            return target + ':' + anchorX + ':' + anchorZ + ':' + matrix;
        }
    }
}
