package com.PinkCats.bandwidthoptimizer.Old.network;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Old.network.message.ClientToServerChunkCacheMissPacket;
import com.PinkCats.bandwidthoptimizer.Old.network.message.ClientToServerOptimizationTelemetrySubscriptionPacket;
import com.PinkCats.bandwidthoptimizer.Old.network.message.ClientboundChunkCacheDeltaPacket;
import com.PinkCats.bandwidthoptimizer.Old.network.message.ClientboundChunkCacheRefreshPacket;
import com.PinkCats.bandwidthoptimizer.Old.network.message.ClientboundChunkCacheUsePacket;
import com.PinkCats.bandwidthoptimizer.Old.network.message.ServerToClientAttachmentPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.atomic.AtomicBoolean;

// 这个类现在只保留当前主包、chunk cache 和少量命令壳仍会触达的旧网络入口，其余 legacy transport 发送面已经整体裁掉。
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

    public static void sendToServer(ClientToServerChunkCacheMissPacket packet) {
        logLegacyTransportDisabled("sendToServerChunkCacheMiss");
    }

    public static void sendToServer(ClientToServerOptimizationTelemetrySubscriptionPacket packet) {
        logLegacyTransportDisabled("sendToServerTelemetrySubscription");
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
