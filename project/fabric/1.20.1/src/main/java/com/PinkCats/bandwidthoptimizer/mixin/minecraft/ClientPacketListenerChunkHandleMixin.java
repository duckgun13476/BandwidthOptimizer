package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.debug.ClientPacketHandleDiagnosticProbe;
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
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerChunkHandleMixin {

    @Shadow
    private ClientLevel level;

    @Inject(method = "handleSetChunkCacheCenter", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            shift = At.Shift.AFTER
    ))
    private void bandwidthoptimizer$logChunkCacheCenterHandle(ClientboundSetChunkCacheCenterPacket packet, CallbackInfo ci) {
        ClientPacketHandleDiagnosticProbe.BO_Diag_cacheChunkHandle(
                "center",
                packet == null ? 0 : packet.getX(),
                packet == null ? 0 : packet.getZ(),
                0,
                false
        );
    }


    @Inject(method = "handleSetChunkCacheRadius", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            shift = At.Shift.AFTER
    ))
    private void bandwidthoptimizer$logChunkCacheRadiusHandle(ClientboundSetChunkCacheRadiusPacket packet, CallbackInfo ci) {
        ClientPacketHandleDiagnosticProbe.BO_Diag_cacheChunkHandle(
                "radius",
                0,
                0,
                packet == null ? 0 : packet.getRadius(),
                false
        );
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void bandwidthoptimizer$logLevelChunkHandle(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        boolean cached = bandwidthoptimizer$isChunkCached(packet);
        ClientPacketHandleDiagnosticProbe.BO_Diag_cacheChunkHandle(
                "chunk",
                packet == null ? 0 : packet.getX(),
                packet == null ? 0 : packet.getZ(),
                0,
                cached
        );
    }

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void bandwidthoptimizer$logPlayerPositionHandle(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        ChunkPos playerChunk = player == null ? null : player.chunkPosition();
        ClientPacketHandleDiagnosticProbe.BO_Diag_movementPositionHandle(
                packet,
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

}
