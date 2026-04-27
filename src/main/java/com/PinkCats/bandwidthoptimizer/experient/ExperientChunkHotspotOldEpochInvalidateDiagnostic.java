package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportControlFrameSender;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import io.netty.channel.ChannelHandlerContext;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ExperientChunkHotspotOldEpochInvalidateDiagnostic {

    private static final AtomicBoolean OLD_EPOCH_INVALIDATE_SCHEDULED = new AtomicBoolean();
    private static final long EXTRA_DELAY_MILLIS = 5000L;

    private ExperientChunkHotspotOldEpochInvalidateDiagnostic() {}


    public static void maybeScheduleOldEpochInvalidate(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame
    ) {
        if (!shouldScheduleDiagnostic(context, frame)
                || !OLD_EPOCH_INVALIDATE_SCHEDULED.compareAndSet(false, true)) {
            return;
        }

        long delayMillis = ExperientChunkHotspotAckDelayRuntimeConfig.readDelayMillis() + EXTRA_DELAY_MILLIS;
        Bandwidthoptimizer.LOGGER.info(
                "[Experient][ChunkDiag] schedule old-epoch invalidate, channel={}, epoch={}, chunk={}, fullVersion={}, payloadHash={}, delayMillis={}",
                context.channel().id().asLongText(),
                frame.epoch(),
                frame.coordinate().logText(),
                frame.fullSnapshotVersion(),
                shortenHash(frame.payloadHash()),
                delayMillis
        );
        context.executor().schedule(() -> {
            Bandwidthoptimizer.LOGGER.info(
                    "[Experient][ChunkDiag] send old-epoch invalidate, channel={}, epoch={}, chunk={}, fullVersion={}, payloadHash={}",
                    context.channel().id().asLongText(),
                    frame.epoch(),
                    frame.coordinate().logText(),
                    frame.fullSnapshotVersion(),
                    shortenHash(frame.payloadHash())
            );
            ChunkTransportControlFrameSender.sendClientCacheBudgetInvalidate(
                    context.channel(),
                    frame.epoch(),
                    frame.coordinate(),
                    frame.fullSnapshotVersion(),
                    frame.payloadHash(),
                    "experient_diagnostic_old_epoch_invalidate"
            );
        }, delayMillis, TimeUnit.MILLISECONDS);
    }


    private static boolean shouldScheduleDiagnostic(
            ChannelHandlerContext context,
            ChunkHotspotFrame frame
    ) {
        return ExperientRuntimeFlags.isEnabled()
                && ExperientChunkHotspotAckDelayRuntimeConfig.isEnabled()
                && ExperientChunkHotspotPathRuntimeConfig.isDimensionHopMode()
                && context != null
                && frame != null
                && frame.operation() == ChunkHotspotFrameOp.PUBLISH_FULL
                && frame.epoch() == 1L
                && frame.coordinate() != null
                && frame.coordinate().present()
                && frame.fullSnapshotVersion() > 0L
                && frame.payloadHash() != null
                && !frame.payloadHash().isBlank();
    }

    private static String shortenHash(String hashHex) {
        if (hashHex == null || hashHex.isBlank()) {
            return "<none>";
        }
        return hashHex.length() <= 12 ? hashHex : hashHex.substring(0, 12);
    }
}
