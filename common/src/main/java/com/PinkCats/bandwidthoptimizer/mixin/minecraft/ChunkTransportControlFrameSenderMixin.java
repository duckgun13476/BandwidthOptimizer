package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.KineticChannel;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportControlFrameSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = ChunkTransportControlFrameSender.class, remap = false)
public abstract class ChunkTransportControlFrameSenderMixin {

    @Unique
    private static final ThreadLocal<Boolean> bandwidthoptimizer$independentFrame =
            ThreadLocal.withInitial(() -> false);

    @Redirect(
            method = "sendEnvelopeFrame(Lio/netty/channel/Channel;Lcom/PinkCats/bandwidthoptimizer/chunk/protocol/hotspot/ChunkHotspotFrame;[BI)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/PinkCats/bandwidthoptimizer/channel/algorithm/KineticChannel;processOutboundPacket(Lcom/PinkCats/bandwidthoptimizer/channel/ChannelTransportSession;[B)Lcom/PinkCats/bandwidthoptimizer/channel/ChannelTransportPacketCodec$WrappedTransportFrame;"
            )
    )
    private static ChannelTransportPacketCodec.WrappedTransportFrame bandwidthoptimizer$wrapClosedEpochIndependently(
            ChannelTransportSession session,
            byte[] packetBytes
    ) {
        boolean independent = session.isOutboundStreamingEpochClosed();
        bandwidthoptimizer$independentFrame.set(independent);
        return independent
                ? ChannelTransportPacketCodec.wrapIndependentBatchPackets(session, List.of(packetBytes))
                : KineticChannel.processOutboundPacket(session, packetBytes);
    }

    @Redirect(
            method = "sendEnvelopeFrame(Lio/netty/channel/Channel;Lcom/PinkCats/bandwidthoptimizer/chunk/protocol/hotspot/ChunkHotspotFrame;[BI)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/PinkCats/bandwidthoptimizer/channel/ChannelTransportSession;outboundStreamingEpochBoundary()Lcom/PinkCats/bandwidthoptimizer/channel/ChannelTransportSession$StreamingEpochBoundary;"
            )
    )
    private static ChannelTransportSession.StreamingEpochBoundary bandwidthoptimizer$skipGateForIndependentFrame(
            ChannelTransportSession session
    ) {
        boolean independent = bandwidthoptimizer$independentFrame.get();
        bandwidthoptimizer$independentFrame.remove();
        return independent ? null : session.outboundStreamingEpochBoundary();
    }

    @Inject(
            method = "sendEnvelopeFrame(Lio/netty/channel/Channel;Lcom/PinkCats/bandwidthoptimizer/chunk/protocol/hotspot/ChunkHotspotFrame;[BI)Z",
            at = @At("RETURN")
    )
    private static void bandwidthoptimizer$clearClosedEpochMarker(CallbackInfoReturnable<Boolean> callback) {
        bandwidthoptimizer$independentFrame.remove();
    }
}
