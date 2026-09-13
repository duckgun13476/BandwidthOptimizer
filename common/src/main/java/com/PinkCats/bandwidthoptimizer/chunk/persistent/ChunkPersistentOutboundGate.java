package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;

public final class ChunkPersistentOutboundGate {

    private ChunkPersistentOutboundGate() {}

    public static boolean tryQueuePendingCoordinatePacket(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet,
            int encodedBytes
    ) {
        return ChunkPersistentPrepareGate.tryQueuePendingCoordinatePacket(
                context,
                protocolName,
                packet,
                encodedBytes
        );
    }

    public static boolean tryQueueWaitingPacket(
            ChannelHandlerContext context,
            Packet<?> packet,
            int encodedBytes,
            String traceReason
    ) {
        return ChunkPersistentManifestGate.tryQueueWaitingPacket(context, packet, encodedBytes, traceReason)
                || ChunkPersistentPrepareGate.tryQueueWaitingPacket(context, packet, traceReason);
    }
}
