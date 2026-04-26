package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportControlFrameSender;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import io.netty.channel.ChannelHandlerContext;

import java.util.concurrent.TimeUnit;

public final class ExperientChunkHotspotAckDelayController {

    private ExperientChunkHotspotAckDelayController() {}

    public static boolean maybeDelayAck(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame,
            String reason
    ) {
        if (!ExperientRuntimeFlags.isEnabled()
                || !ExperientChunkHotspotAckDelayRuntimeConfig.isEnabled()
                || context == null
                || frame == null
                || frame.operation() != ChunkHotspotFrameOp.PUBLISH_FULL) {
            return false;
        }

        long delayMillis = ExperientChunkHotspotAckDelayRuntimeConfig.readDelayMillis();
        if (delayMillis <= 0L) {
            return false;
        }

        context.executor().schedule(
                () -> ChunkTransportControlFrameSender.sendAck(context, frame, reason),
                delayMillis,
                TimeUnit.MILLISECONDS
        );
        return true;
    }
}
