package com.PinkCats.bandwidthoptimizer.network;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.network.algorithm.play.ClientboundPlayPacketBatchPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientToServerAttachmentPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientToServerChunkCacheMissPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientToServerOptimizationTelemetrySubscriptionPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheBatchPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheDeltaPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheRefreshPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ClientboundChunkCacheUsePacket;
import com.PinkCats.bandwidthoptimizer.network.message.ServerOptimizationTelemetryPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ServerOverallOptimizationTelemetryPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ServerToClientAttachmentBatchPacket;
import com.PinkCats.bandwidthoptimizer.network.message.ServerToClientAttachmentPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ModNetwork {

    public static final boolean LEGACY_TRANSPORT_ENABLED = false;

    private static final AtomicBoolean DISABLED_LOGGED = new AtomicBoolean();

    private ModNetwork() {
    }

    public static boolean isLegacyTransportEnabled() {
        return LEGACY_TRANSPORT_ENABLED;
    }

    public static void register() {
        logLegacyTransportDisabled("register");
    }

    public static void sendToPlayer(ServerPlayer player, ServerToClientAttachmentPacket packet) {
        logLegacyTransportDisabled("sendToPlayer");
    }

    public static void sendToServer(ClientToServerAttachmentPacket packet) {
        logLegacyTransportDisabled("sendToServerAttachment");
    }

    public static void sendToServer(ClientToServerChunkCacheMissPacket packet) {
        logLegacyTransportDisabled("sendToServerChunkCacheMiss");
    }

    public static void sendToServer(ClientToServerOptimizationTelemetrySubscriptionPacket packet) {
        logLegacyTransportDisabled("sendToServerTelemetrySubscription");
    }

    public static void sendBatchToPlayerDirect(ServerPlayer player, ServerToClientAttachmentBatchPacket packet) {
        logLegacyTransportDisabled("sendAttachmentBatch");
    }

    public static void sendPlayBatchToPlayerDirect(ServerPlayer player, ClientboundPlayPacketBatchPacket packet) {
        logLegacyTransportDisabled("sendPlayBatch");
    }

    public static void sendChunkCacheBatchToPlayerDirect(ServerPlayer player, ClientboundChunkCacheBatchPacket packet) {
        logLegacyTransportDisabled("sendChunkCacheBatch");
    }

    public static void sendChunkCacheRefreshToPlayer(ServerPlayer player, ClientboundChunkCacheRefreshPacket packet) {
        logLegacyTransportDisabled("sendChunkCacheRefresh");
    }

    public static void sendChunkCacheUseToPlayer(ServerPlayer player, ClientboundChunkCacheUsePacket packet, long rawBaselineBytes) {
        logLegacyTransportDisabled("sendChunkCacheUse");
    }

    public static void sendChunkCacheDeltaToPlayer(ServerPlayer player, ClientboundChunkCacheDeltaPacket packet) {
        logLegacyTransportDisabled("sendChunkCacheDelta");
    }

    public static void sendServerConfigToPlayer(ServerPlayer player) {
        logLegacyTransportDisabled("sendServerConfig");
    }

    public static void sendOptimizationTelemetryToPlayer(ServerPlayer player, ServerOptimizationTelemetryPacket packet) {
        logLegacyTransportDisabled("sendOptimizationTelemetry");
    }

    public static void sendOverallOptimizationTelemetryToPlayer(ServerPlayer player, ServerOverallOptimizationTelemetryPacket packet) {
        logLegacyTransportDisabled("sendOverallOptimizationTelemetry");
    }

    // Keeps the old network facade compiled while the transport stack is redesigned from scratch.
    private static void logLegacyTransportDisabled(String action) {
        if (DISABLED_LOGGED.compareAndSet(false, true)) {
            Bandwidthoptimizer.LOGGER.info(
                    "[TransportRefactor] Legacy transport stack is disabled. Dropping old network action '{}'.",
                    action
            );
        }
    }
}
