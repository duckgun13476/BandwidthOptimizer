package com.PinkCats.bandwidthoptimizer.debug;

import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;

import java.util.concurrent.atomic.AtomicInteger;

public final class ClientPacketHandleDiagnosticProbe {
    private static final int MAX_POSITION_LOGS = 32;
    private static final int MAX_CHUNK_HANDLE_LOGS = 96;
    private static final AtomicInteger POSITION_LOG_COUNT = new AtomicInteger();
    private static final AtomicInteger CHUNK_HANDLE_LOG_COUNT = new AtomicInteger();

    private ClientPacketHandleDiagnosticProbe() {}

    public static void BO_Diag_movementPositionHandle(
            ClientboundPlayerPositionPacket packet,
            double playerX,
            double playerY,
            double playerZ,
            int playerChunkX,
            int playerChunkZ
    ) {
        if (!isEnabled(DiagnosticToolRegistry.Tool.MOVEMENT_POSITION_HANDLE)) {
            return;
        }
        int index = POSITION_LOG_COUNT.incrementAndGet();
        if (index > MAX_POSITION_LOGS) {
            return;
        }
        DiagnosticLog.info(
                DiagnosticToolRegistry.Tool.MOVEMENT_POSITION_HANDLE,
                "index={}, packetPos=({}, {}, {}), packetId={}, relative={}, playerPos=({}, {}, {}), playerChunk=({}, {})",
                index,
                packet == null ? 0.0D : packet.getX(),
                packet == null ? 0.0D : packet.getY(),
                packet == null ? 0.0D : packet.getZ(),
                packet == null ? 0 : packet.getId(),
                packet == null ? "<null>" : packet.getRelativeArguments(),
                playerX,
                playerY,
                playerZ,
                playerChunkX,
                playerChunkZ
        );
    }

    public static void BO_Diag_cacheChunkHandle(String kind, int chunkX, int chunkZ, int radius, boolean cached) {
        if (!isEnabled(DiagnosticToolRegistry.Tool.CACHE_CHUNK_HANDLE)) {
            return;
        }
        int index = CHUNK_HANDLE_LOG_COUNT.incrementAndGet();
        if (index > MAX_CHUNK_HANDLE_LOGS) {
            return;
        }
        DiagnosticLog.info(
                DiagnosticToolRegistry.Tool.CACHE_CHUNK_HANDLE,
                "index={}, kind={}, chunk=({}, {}), radius={}, cached={}",
                index,
                kind,
                chunkX,
                chunkZ,
                radius,
                cached
        );
    }

    private static boolean isEnabled(DiagnosticToolRegistry.Tool tool) {
        DiagnosticRuntimeSwitch.Topic legacyTopic = tool == DiagnosticToolRegistry.Tool.CACHE_CHUNK_HANDLE
                ? DiagnosticRuntimeSwitch.Topic.CACHE
                : DiagnosticRuntimeSwitch.Topic.MOVEMENT;
        return DiagnosticToolRegistry.isEnabled(tool)
                || DiagnosticRuntimeSwitch.isEnabled(legacyTopic);
    }
}
