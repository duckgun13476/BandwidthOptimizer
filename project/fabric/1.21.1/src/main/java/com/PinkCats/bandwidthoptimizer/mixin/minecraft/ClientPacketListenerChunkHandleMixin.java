package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.debug.DebugRuntimeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicInteger;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerChunkHandleMixin {

    @Shadow
    private ClientLevel level;

    @Unique
    private static final int bandwidthoptimizer$MAX_HANDLE_LOGS = 96;
    @Unique
    private static final AtomicInteger bandwidthoptimizer$HANDLE_LOG_COUNT = new AtomicInteger();
    @Unique
    private static final int bandwidthoptimizer$MAX_POSITION_LOGS = 32;
    @Unique
    private static final AtomicInteger bandwidthoptimizer$POSITION_LOG_COUNT = new AtomicInteger();

    @Inject(method = "handleSetChunkCacheCenter", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            shift = At.Shift.AFTER
    ))
    private void bandwidthoptimizer$logChunkCacheCenterHandle(ClientboundSetChunkCacheCenterPacket packet, CallbackInfo ci) {
        bandwidthoptimizer$logHandle(
                "center",
                packet == null ? 0 : packet.getX(),
                packet == null ? 0 : packet.getZ(),
                0
        );
    }


    @Inject(method = "handleSetChunkCacheRadius", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            shift = At.Shift.AFTER
    ))
    private void bandwidthoptimizer$logChunkCacheRadiusHandle(ClientboundSetChunkCacheRadiusPacket packet, CallbackInfo ci) {
        bandwidthoptimizer$logHandle(
                "radius",
                0,
                0,
                packet == null ? 0 : packet.getRadius()
        );
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void bandwidthoptimizer$logLevelChunkHandle(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        boolean cached = bandwidthoptimizer$isChunkCached(packet);
        bandwidthoptimizer$logHandle(
                "chunk",
                packet == null ? 0 : packet.getX(),
                packet == null ? 0 : packet.getZ(),
                0,
                cached
        );
    }

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void bandwidthoptimizer$logPlayerPositionHandle(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        if (!DebugRuntimeConfig.isDiagnoseEnabled()) {
            return;
        }
        int index = bandwidthoptimizer$POSITION_LOG_COUNT.incrementAndGet();
        if (index > bandwidthoptimizer$MAX_POSITION_LOGS) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        ChunkPos playerChunk = player == null ? null : player.chunkPosition();
        Bandwidthoptimizer.LOGGER.info(
                "[PlayerPositionHandle] index={}, packetPos=({}, {}, {}), packetId={}, relative={}, playerPos=({}, {}, {}), playerChunk=({}, {})",
                index,
                packet == null ? 0.0D : packet.getX(),
                packet == null ? 0.0D : packet.getY(),
                packet == null ? 0.0D : packet.getZ(),
                packet == null ? 0 : packet.getId(),
                packet == null ? "<null>" : packet.getRelativeArguments(),
                player == null ? 0.0D : player.getX(),
                player == null ? 0.0D : player.getY(),
                player == null ? 0.0D : player.getZ(),
                playerChunk == null ? 0 : playerChunk.x,
                playerChunk == null ? 0 : playerChunk.z
        );
    }

    @Unique
    private boolean bandwidthoptimizer$isChunkCached(ClientboundLevelChunkWithLightPacket packet) {
        if (packet == null || this.level == null) {
            return false;
        }
        try {
            ClientChunkCache chunkCache = this.level.getChunkSource();
            LevelChunk chunk = chunkCache.getChunk(packet.getX(), packet.getZ(), ChunkStatus.FULL, false);
            return chunk != null;
        } catch (Throwable throwable) {
            return false;
        }
    }

    @Unique
    private static void bandwidthoptimizer$logHandle(String kind, int chunkX, int chunkZ, int radius) {
        bandwidthoptimizer$logHandle(kind, chunkX, chunkZ, radius, false);
    }

    @Unique
    private static void bandwidthoptimizer$logHandle(String kind, int chunkX, int chunkZ, int radius, boolean cached) {
        if (!DebugRuntimeConfig.isDiagnoseEnabled())
            return;

        int index = bandwidthoptimizer$HANDLE_LOG_COUNT.incrementAndGet();
        if (index > bandwidthoptimizer$MAX_HANDLE_LOGS) {
            return;
        }
        Bandwidthoptimizer.LOGGER.info(
                "[ChunkClientHandle] index={}, kind={}, chunk=({}, {}), radius={}, cached={}",
                index,
                kind,
                chunkX,
                chunkZ,
                radius,
                cached
        );
    }
}
