package com.PinkCats.bandwidthoptimizer.network;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.network.attachment.AttachmentDataFlow;
import com.PinkCats.bandwidthoptimizer.network.message.ClientToServerAttachmentPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientToServerChunkCacheMissPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientToServerOptimizationTelemetrySubscriptionPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheRefreshPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundServerConfigPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheUsePacket;
import com.PinkCats.bandwidthoptimizer.network.message.ServerOverallOptimizationTelemetryPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ServerOptimizationTelemetryPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ServerToClientAttachmentBatchPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ServerToClientAttachmentPacket;
import com.PinkCats.bandwidthoptimizer.network.algorithm.play.ClientboundPlayPacketBatchPacket;
import com.PinkCats.bandwidthoptimizer.network.server.ServerToClientBatchingManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ModNetwork {
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(Bandwidthoptimizer.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int nextMessageId;
    private static boolean registered;

    private ModNetwork() {
    }

    public static void register() {
        if (registered) {
            return;
        }

        registered = true;
        CHANNEL.registerMessage(
                nextId(),
                ClientboundPlayPacketBatchPacket.class,
                ClientboundPlayPacketBatchPacket::encode,
                ClientboundPlayPacketBatchPacket::decode,
                ClientboundPlayPacketBatchPacket::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                ClientboundChunkCacheRefreshPacket.class,
                ClientboundChunkCacheRefreshPacket::encode,
                ClientboundChunkCacheRefreshPacket::decode,
                ClientboundChunkCacheRefreshPacket::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                ClientboundChunkCacheUsePacket.class,
                ClientboundChunkCacheUsePacket::encode,
                ClientboundChunkCacheUsePacket::decode,
                ClientboundChunkCacheUsePacket::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                ClientboundServerConfigPacket.class,
                ClientboundServerConfigPacket::encode,
                ClientboundServerConfigPacket::decode,
                ClientboundServerConfigPacket::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                ServerToClientAttachmentBatchPacket.class,
                ServerToClientAttachmentBatchPacket::encode,
                ServerToClientAttachmentBatchPacket::decode,
                ServerToClientAttachmentBatchPacket::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                ServerToClientAttachmentPacket.class,
                ServerToClientAttachmentPacket::encode,
                ServerToClientAttachmentPacket::decode,
                ServerToClientAttachmentPacket::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                ClientToServerAttachmentPacket.class,
                ClientToServerAttachmentPacket::encode,
                ClientToServerAttachmentPacket::decode,
                ClientToServerAttachmentPacket::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                ClientToServerChunkCacheMissPacket.class,
                ClientToServerChunkCacheMissPacket::encode,
                ClientToServerChunkCacheMissPacket::decode,
                ClientToServerChunkCacheMissPacket::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                ClientToServerOptimizationTelemetrySubscriptionPacket.class,
                ClientToServerOptimizationTelemetrySubscriptionPacket::encode,
                ClientToServerOptimizationTelemetrySubscriptionPacket::decode,
                ClientToServerOptimizationTelemetrySubscriptionPacket::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                ServerOptimizationTelemetryPacket.class,
                ServerOptimizationTelemetryPacket::encode,
                ServerOptimizationTelemetryPacket::decode,
                ServerOptimizationTelemetryPacket::handle
        );
        CHANNEL.registerMessage(
                nextId(),
                ServerOverallOptimizationTelemetryPacket.class,
                ServerOverallOptimizationTelemetryPacket::encode,
                ServerOverallOptimizationTelemetryPacket::decode,
                ServerOverallOptimizationTelemetryPacket::handle
        );
    }

    public static void sendToPlayer(ServerPlayer player, ServerToClientAttachmentPacket packet) {
        ServerToClientAttachmentPacket processedPacket = AttachmentDataFlow.beforeServerSend(player, packet);
        if (Config.optimizerDebugLoggingEnabled() && Bandwidthoptimizer.LOGGER.isDebugEnabled()) {
            Bandwidthoptimizer.LOGGER.debug(
                    "[ModChannel][Server][QueueSend] target={}, key={}, correlationId={}, value={}, payload={}",
                    player.getGameProfile().getName(),
                    processedPacket.key(),
                    processedPacket.correlationId(),
                    processedPacket.value(),
                    processedPacket.payload()
            );
        }
        ServerToClientBatchingManager.enqueue(player, processedPacket);
    }

    public static void sendToServer(ClientToServerAttachmentPacket packet) {
        if (Config.optimizerDebugLoggingEnabled() && Bandwidthoptimizer.LOGGER.isDebugEnabled()) {
            Bandwidthoptimizer.LOGGER.debug(
                    "[ModChannel][Client][QueueSend] key={}, correlationId={}, value={}, payload={}",
                    packet.key(),
                    packet.correlationId(),
                    packet.value(),
                    packet.payload()
            );
        }
        CHANNEL.sendToServer(packet);
    }

    public static void sendToServer(ClientToServerChunkCacheMissPacket packet) {
        CHANNEL.sendToServer(packet);
    }

    public static void sendToServer(ClientToServerOptimizationTelemetrySubscriptionPacket packet) {
        CHANNEL.sendToServer(packet);
    }

    private static int nextId() {
        return nextMessageId++;
    }

    public static void sendBatchToPlayerDirect(ServerPlayer player, ServerToClientAttachmentBatchPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendPlayBatchToPlayerDirect(ServerPlayer player, ClientboundPlayPacketBatchPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendChunkCacheRefreshToPlayer(ServerPlayer player, ClientboundChunkCacheRefreshPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendChunkCacheUseToPlayer(ServerPlayer player, ClientboundChunkCacheUsePacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendServerConfigToPlayer(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), ClientboundServerConfigPacket.current());
    }

    public static void sendOptimizationTelemetryToPlayer(ServerPlayer player, ServerOptimizationTelemetryPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendOverallOptimizationTelemetryToPlayer(ServerPlayer player, ServerOverallOptimizationTelemetryPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
