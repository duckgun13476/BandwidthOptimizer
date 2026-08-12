package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;

public final class ChunkPersistentOutboundGate {

    private ChunkPersistentOutboundGate() {}

    public static boolean tryQueueWaitingPacket(
            ChannelHandlerContext context,
            Packet<?> packet,
            String traceReason
    ) {
        return ChunkPersistentManifestGate.tryQueueWaitingPacket(context, packet, traceReason)
                || ChunkPersistentPrepareGate.tryQueueWaitingPacket(context, packet, traceReason);
    }
}
