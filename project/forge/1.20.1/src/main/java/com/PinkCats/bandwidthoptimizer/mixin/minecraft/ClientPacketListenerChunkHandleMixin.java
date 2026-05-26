package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.compat.minecraft.BlockEntityTypeKeyCompat;
import com.PinkCats.bandwidthoptimizer.experient.ExperientClientCommandTiming;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerChunkHandleMixin {

    @Unique
    private static final String bandwidthoptimizer$CHUNK_LOAD_PROBE_ENABLED_PROPERTY =
            "bandwidthoptimizer.chunk.loadDelayProbe";
    @Unique
    private static final int bandwidthoptimizer$MAX_CHUNK_LOGS_PER_ROUND = 384;
    @Unique
    private static final int bandwidthoptimizer$WINDOW_PROGRESS_INTERVAL = 16;
    @Unique
    private static final AtomicInteger bandwidthoptimizer$ROUND_SEQUENCE = new AtomicInteger();
    @Unique
    private static int bandwidthoptimizer$currentRound;
    @Unique
    private static long bandwidthoptimizer$roundStartMillis;
    @Unique
    private static long bandwidthoptimizer$firstChunkMillis;
    @Unique
    private static int bandwidthoptimizer$roundChunkCount;
    @Unique
    private static long bandwidthoptimizer$firstBlockEntityMillis;
    @Unique
    private static int bandwidthoptimizer$roundBlockEntityCount;
    @Unique
    private static int bandwidthoptimizer$roundIgnoredBlockEntityCount;
    @Unique
    private static int bandwidthoptimizer$cacheCenterX;
    @Unique
    private static int bandwidthoptimizer$cacheCenterZ;
    @Unique
    private static int bandwidthoptimizer$cacheRadius = 8;
    @Unique
    private static boolean bandwidthoptimizer$fullWindowLogged;
    @Unique
    private static final Set<Long> bandwidthoptimizer$roundReceivedChunks = ConcurrentHashMap.newKeySet();

    // Align chunk window timing from the server center packet.
    @Inject(method = "handleSetChunkCacheCenter", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            shift = At.Shift.AFTER
    ))
    private void bandwidthoptimizer$logChunkCacheCenterHandle(ClientboundSetChunkCacheCenterPacket packet, CallbackInfo ci) {
        if (!bandwidthoptimizer$chunkLoadProbeEnabled() || packet == null) {
            return;
        }
        bandwidthoptimizer$cacheCenterX = packet.getX();
        bandwidthoptimizer$cacheCenterZ = packet.getZ();
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkLoadProbe] cache_center round={}, chunk=({}, {}), sinceTpMs={}",
                bandwidthoptimizer$currentRound,
                bandwidthoptimizer$cacheCenterX,
                bandwidthoptimizer$cacheCenterZ,
                bandwidthoptimizer$sinceRoundStartMillis()
        );
    }

    // Track the radius packet for chunk window diagnostics.
    @Inject(method = "handleSetChunkCacheRadius", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            shift = At.Shift.AFTER
    ))
    private void bandwidthoptimizer$logChunkCacheRadiusHandle(ClientboundSetChunkCacheRadiusPacket packet, CallbackInfo ci) {
        if (!bandwidthoptimizer$chunkLoadProbeEnabled() || packet == null) {
            return;
        }
        bandwidthoptimizer$cacheRadius = Math.max(1, Math.min(packet.getRadius(), 32));
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkLoadProbe] cache_radius round={}, radius={}, totalWindow={}, sinceTpMs={}",
                bandwidthoptimizer$currentRound,
                bandwidthoptimizer$cacheRadius,
                bandwidthoptimizer$totalWindowChunks(),
                bandwidthoptimizer$sinceRoundStartMillis()
        );
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void bandwidthoptimizer$logLevelChunkHandle(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (!bandwidthoptimizer$chunkLoadProbeEnabled() || packet == null || bandwidthoptimizer$currentRound <= 0) {
            return;
        }
        int count = ++bandwidthoptimizer$roundChunkCount;
        bandwidthoptimizer$roundReceivedChunks.add(bandwidthoptimizer$chunkKey(packet.getX(), packet.getZ()));
        long now = System.currentTimeMillis();
        if (bandwidthoptimizer$firstChunkMillis <= 0L) {
            bandwidthoptimizer$firstChunkMillis = now;
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkLoadProbe] first_chunk round={}, delayMs={}, sinceCommandMs={}, chunk=({}, {}), center=({}, {}), radius={}, inWindow={}, receivedWindow={}/{}",
                    bandwidthoptimizer$currentRound,
                    Math.max(now - bandwidthoptimizer$roundStartMillis, 0L),
                    ExperientClientCommandTiming.millisSinceLastSent(now),
                    packet.getX(),
                    packet.getZ(),
                    bandwidthoptimizer$cacheCenterX,
                    bandwidthoptimizer$cacheCenterZ,
                    bandwidthoptimizer$cacheRadius,
                    bandwidthoptimizer$isChunkInWindow(packet.getX(), packet.getZ()),
                    bandwidthoptimizer$countReceivedWindow(),
                    bandwidthoptimizer$totalWindowChunks()
            );
        }
        if (count <= bandwidthoptimizer$MAX_CHUNK_LOGS_PER_ROUND
                && (count <= 32 || count % bandwidthoptimizer$WINDOW_PROGRESS_INTERVAL == 0)) {
            int receivedWindow = bandwidthoptimizer$countReceivedWindow();
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkLoadProbe] chunk_progress round={}, count={}, sinceTpMs={}, firstChunkMs={}, chunk=({}, {}), inWindow={}, receivedWindow={}/{}, center=({}, {}), radius={}",
                    bandwidthoptimizer$currentRound,
                    count,
                    Math.max(now - bandwidthoptimizer$roundStartMillis, 0L),
                    Math.max(bandwidthoptimizer$firstChunkMillis - bandwidthoptimizer$roundStartMillis, 0L),
                    packet.getX(),
                    packet.getZ(),
                    bandwidthoptimizer$isChunkInWindow(packet.getX(), packet.getZ()),
                    receivedWindow,
                    bandwidthoptimizer$totalWindowChunks(),
                    bandwidthoptimizer$cacheCenterX,
                    bandwidthoptimizer$cacheCenterZ,
                    bandwidthoptimizer$cacheRadius
            );
        }
        bandwidthoptimizer$logFullWindowIfNeeded(now);
        if (count == bandwidthoptimizer$MAX_CHUNK_LOGS_PER_ROUND) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkLoadProbe] round_log_limit round={}, count={}, sinceTpMs={}",
                    bandwidthoptimizer$currentRound,
                    count,
                    bandwidthoptimizer$sinceRoundStartMillis()
            );
        }
    }

    // Track block entity arrival inside the teleport round.
    @Inject(method = "handleBlockEntityData", at = @At("RETURN"))
    private void bandwidthoptimizer$logBlockEntityHandle(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
        if (!bandwidthoptimizer$chunkLoadProbeEnabled() || packet == null || bandwidthoptimizer$currentRound <= 0) {
            return;
        }
        BlockPos blockPos = packet.getPos();
        if (blockPos == null) {
            return;
        }
        String blockEntityTypeKey = bandwidthoptimizer$blockEntityTypeKey(packet);
        if (bandwidthoptimizer$isIgnoredBlockEntityType(blockEntityTypeKey)) {
            int ignoredCount = ++bandwidthoptimizer$roundIgnoredBlockEntityCount;
            if (ignoredCount <= 16 || ignoredCount % 64 == 0) {
                Bandwidthoptimizer.LOGGER.info(
                        "[ChunkLoadProbe] block_entity_filtered round={}, ignoredCount={}, sinceTpMs={}, sinceCommandMs={}, type={}, block=({}, {}, {})",
                        bandwidthoptimizer$currentRound,
                        ignoredCount,
                        bandwidthoptimizer$sinceRoundStartMillis(),
                        bandwidthoptimizer$sinceClientCommandMillis(),
                        blockEntityTypeKey,
                        blockPos.getX(),
                        blockPos.getY(),
                        blockPos.getZ()
                );
            }
            return;
        }
        int chunkX = blockPos.getX() >> 4;
        int chunkZ = blockPos.getZ() >> 4;
        int count = ++bandwidthoptimizer$roundBlockEntityCount;
        long now = System.currentTimeMillis();
        if (bandwidthoptimizer$firstBlockEntityMillis <= 0L) {
            bandwidthoptimizer$firstBlockEntityMillis = now;
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkLoadProbe] first_block_entity round={}, delayMs={}, sinceCommandMs={}, firstChunkMs={}, type={}, block=({}, {}, {}), chunk=({}, {}), inWindow={}, receivedWindow={}/{}, ignoredBlockEntities={}",
                    bandwidthoptimizer$currentRound,
                    Math.max(now - bandwidthoptimizer$roundStartMillis, 0L),
                    ExperientClientCommandTiming.millisSinceLastSent(now),
                    bandwidthoptimizer$firstChunkDelayMillis(),
                    blockEntityTypeKey,
                    blockPos.getX(),
                    blockPos.getY(),
                    blockPos.getZ(),
                    chunkX,
                    chunkZ,
                    bandwidthoptimizer$isChunkInWindow(chunkX, chunkZ),
                    bandwidthoptimizer$countReceivedWindow(),
                    bandwidthoptimizer$totalWindowChunks(),
                    bandwidthoptimizer$roundIgnoredBlockEntityCount
            );
        }
        if (count <= 32 || count % 8 == 0) {
            Bandwidthoptimizer.LOGGER.info(
                    "[ChunkLoadProbe] block_entity_progress round={}, count={}, sinceTpMs={}, firstChunkMs={}, firstBlockEntityMs={}, type={}, block=({}, {}, {}), chunk=({}, {}), inWindow={}, receivedWindow={}/{}, ignoredBlockEntities={}",
                    bandwidthoptimizer$currentRound,
                    count,
                    Math.max(now - bandwidthoptimizer$roundStartMillis, 0L),
                    bandwidthoptimizer$firstChunkDelayMillis(),
                    Math.max(bandwidthoptimizer$firstBlockEntityMillis - bandwidthoptimizer$roundStartMillis, 0L),
                    blockEntityTypeKey,
                    blockPos.getX(),
                    blockPos.getY(),
                    blockPos.getZ(),
                    chunkX,
                    chunkZ,
                    bandwidthoptimizer$isChunkInWindow(chunkX, chunkZ),
                    bandwidthoptimizer$countReceivedWindow(),
                    bandwidthoptimizer$totalWindowChunks(),
                    bandwidthoptimizer$roundIgnoredBlockEntityCount
            );
        }
    }

    // Start a lightweight round for each server position sync.
    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void bandwidthoptimizer$logPlayerPositionHandle(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        if (!bandwidthoptimizer$chunkLoadProbeEnabled()) {
            return;
        }
        int round = bandwidthoptimizer$ROUND_SEQUENCE.incrementAndGet();
        bandwidthoptimizer$currentRound = round;
        bandwidthoptimizer$roundStartMillis = System.currentTimeMillis();
        bandwidthoptimizer$firstChunkMillis = 0L;
        bandwidthoptimizer$roundChunkCount = 0;
        bandwidthoptimizer$firstBlockEntityMillis = 0L;
        bandwidthoptimizer$roundBlockEntityCount = 0;
        bandwidthoptimizer$roundIgnoredBlockEntityCount = 0;
        bandwidthoptimizer$fullWindowLogged = false;
        bandwidthoptimizer$roundReceivedChunks.clear();
        LocalPlayer player = Minecraft.getInstance().player;
        ChunkPos playerChunk = player == null ? null : player.chunkPosition();
        long now = bandwidthoptimizer$roundStartMillis;
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkLoadProbe] tp_start round={}, sinceCommandMs={}, commandSequence={}, command={}, packetPos=({}, {}, {}), packetId={}, relative={}, playerPos=({}, {}, {}), playerChunk=({}, {}), center=({}, {}), radius={}",
                round,
                ExperientClientCommandTiming.millisSinceLastSent(now),
                ExperientClientCommandTiming.sequence(),
                ExperientClientCommandTiming.lastCommand(),
                packet == null ? 0.0D : packet.getX(),
                packet == null ? 0.0D : packet.getY(),
                packet == null ? 0.0D : packet.getZ(),
                packet == null ? 0 : packet.getId(),
                packet == null ? "<null>" : packet.getRelativeArguments(),
                player == null ? 0.0D : player.getX(),
                player == null ? 0.0D : player.getY(),
                player == null ? 0.0D : player.getZ(),
                playerChunk == null ? 0 : playerChunk.x,
                playerChunk == null ? 0 : playerChunk.z,
                bandwidthoptimizer$cacheCenterX,
                bandwidthoptimizer$cacheCenterZ,
                bandwidthoptimizer$cacheRadius
        );
    }

    @Unique
    private static void bandwidthoptimizer$logFullWindowIfNeeded(long now) {
        if (bandwidthoptimizer$fullWindowLogged) {
            return;
        }
        int receivedWindow = bandwidthoptimizer$countReceivedWindow();
        int totalWindow = bandwidthoptimizer$totalWindowChunks();
        if (receivedWindow < totalWindow) {
            return;
        }
        bandwidthoptimizer$fullWindowLogged = true;
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkLoadProbe] full_window_received round={}, sinceTpMs={}, sinceCommandMs={}, firstChunkMs={}, firstBlockEntityMs={}, receivedWindow={}/{}, totalChunks={}, blockEntities={}, ignoredBlockEntities={}",
                bandwidthoptimizer$currentRound,
                Math.max(now - bandwidthoptimizer$roundStartMillis, 0L),
                ExperientClientCommandTiming.millisSinceLastSent(now),
                bandwidthoptimizer$firstChunkDelayMillis(),
                bandwidthoptimizer$firstBlockEntityMillis <= 0L ? -1L : Math.max(bandwidthoptimizer$firstBlockEntityMillis - bandwidthoptimizer$roundStartMillis, 0L),
                receivedWindow,
                totalWindow,
                bandwidthoptimizer$roundChunkCount,
                bandwidthoptimizer$roundBlockEntityCount,
                bandwidthoptimizer$roundIgnoredBlockEntityCount
        );
    }

    @Unique
    private static String bandwidthoptimizer$blockEntityTypeKey(ClientboundBlockEntityDataPacket packet) {
        if (packet == null || packet.getType() == null) {
            return "<unknown>";
        }
        ResourceLocation typeKey = BlockEntityTypeKeyCompat.keyOf(packet.getType());
        return typeKey == null ? packet.getType().toString() : typeKey.toString();
    }

    @Unique
    private static boolean bandwidthoptimizer$isIgnoredBlockEntityType(String blockEntityTypeKey) {
        if (blockEntityTypeKey == null || blockEntityTypeKey.isBlank()) {
            return false;
        }
        String ignoredNamespaces = System.getProperty(
                "bandwidthoptimizer.chunk.loadDelayProbeIgnoredBlockEntityNamespaces",
                "create"
        );
        for (String namespace : ignoredNamespaces.split(",")) {
            String trimmedNamespace = namespace.trim();
            if (!trimmedNamespace.isEmpty() && blockEntityTypeKey.startsWith(trimmedNamespace + ":")) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private static int bandwidthoptimizer$countReceivedWindow() {
        int loaded = 0;
        for (long chunkKey : bandwidthoptimizer$roundReceivedChunks) {
            int chunkX = (int) (chunkKey >> 32);
            int chunkZ = (int) chunkKey;
            if (bandwidthoptimizer$isChunkInWindow(chunkX, chunkZ)) {
                loaded++;
            }
        }
        return loaded;
    }

    @Unique
    private static int bandwidthoptimizer$totalWindowChunks() {
        int width = bandwidthoptimizer$cacheRadius * 2 + 1;
        return width * width;
    }

    @Unique
    private static boolean bandwidthoptimizer$isChunkInWindow(int chunkX, int chunkZ) {
        return Math.abs(chunkX - bandwidthoptimizer$cacheCenterX) <= bandwidthoptimizer$cacheRadius
                && Math.abs(chunkZ - bandwidthoptimizer$cacheCenterZ) <= bandwidthoptimizer$cacheRadius;
    }

    @Unique
    private static long bandwidthoptimizer$chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    @Unique
    private static long bandwidthoptimizer$firstChunkDelayMillis() {
        if (bandwidthoptimizer$firstChunkMillis <= 0L || bandwidthoptimizer$roundStartMillis <= 0L) {
            return -1L;
        }
        return Math.max(bandwidthoptimizer$firstChunkMillis - bandwidthoptimizer$roundStartMillis, 0L);
    }

    @Unique
    private static long bandwidthoptimizer$sinceRoundStartMillis() {
        if (bandwidthoptimizer$roundStartMillis <= 0L) {
            return -1L;
        }
        return Math.max(System.currentTimeMillis() - bandwidthoptimizer$roundStartMillis, 0L);
    }

    @Unique
    private static long bandwidthoptimizer$sinceClientCommandMillis() {
        return ExperientClientCommandTiming.millisSinceLastSent(System.currentTimeMillis());
    }

    @Unique
    private static boolean bandwidthoptimizer$chunkLoadProbeEnabled() {
        return Boolean.parseBoolean(System.getProperty(
                bandwidthoptimizer$CHUNK_LOAD_PROBE_ENABLED_PROPERTY,
                "true"
        ));
    }
}
