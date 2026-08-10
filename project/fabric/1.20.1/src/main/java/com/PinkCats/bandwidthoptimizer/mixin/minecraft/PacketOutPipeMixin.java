package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCaptureHooks;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportHooks;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStreamingEpochGate;
import com.PinkCats.bandwidthoptimizer.channel.access.PacketEncoderFlowAccess;
import com.PinkCats.bandwidthoptimizer.debug.HotpathCostProbe;
import com.PinkCats.bandwidthoptimizer.debug.TransportDiagnosticProbe;
import com.PinkCats.bandwidthoptimizer.integration.trueuuid.TrueUuidLateLoginQueryGuard;
import com.PinkCats.bandwidthoptimizer.gate.integration.minecraft.IdleGateBackgroundPacketGate;
import com.PinkCats.bandwidthoptimizer.gate.integration.minecraft.IdleGateClientPacketGate;
import com.PinkCats.bandwidthoptimizer.server.stat.ChannelBandwidthStats;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry;
import com.PinkCats.bandwidthoptimizer.server.stat.VanillaCompressionEstimator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(PacketEncoder.class)
public abstract class PacketOutPipeMixin<T extends PacketListener> implements PacketEncoderFlowAccess {

    @Shadow
    @Final
    private PacketFlow flow;

    @Unique
    private int bandwidthoptimizer$writerIndexBefore;

    @Unique
    private long bandwidthoptimizer$encodeStartNanos;


    @Override
    public PacketFlow bandwidthoptimizer$getPacketFlow() {
        return this.flow;
    }

    @Inject(method = "encode*", at = @At("HEAD"), cancellable = true)
    private void bandwidthoptimizer$rememberWriterIndex(ChannelHandlerContext context, Packet<T> packet, ByteBuf out, CallbackInfo ci) {

        //Index
        this.bandwidthoptimizer$writerIndexBefore = out.writerIndex();
        this.bandwidthoptimizer$encodeStartNanos = System.nanoTime();
    }


    @Inject(method = "encode*", at = @At("RETURN"))
    private void bandwidthoptimizer$captureAndMaybeWrap(ChannelHandlerContext context, Packet<T> packet, ByteBuf out, CallbackInfo ci) {
        if (TrueUuidLateLoginQueryGuard.tryDropOutboundLateCustomQueryAck(context, packet, this.flow, out, this.bandwidthoptimizer$writerIndexBefore)) {
            return;
        }

        int encodedByteLength = out.writerIndex() - this.bandwidthoptimizer$writerIndexBefore;
        if (IdleGateClientPacketGate.shouldDrop(packet, this.flow)) {
            IdleGateClientPacketGate.recordDroppedPacket(packet, encodedByteLength);
            out.writerIndex(this.bandwidthoptimizer$writerIndexBefore);
            return;
        }
        String backgroundDropKey = IdleGateBackgroundPacketGate.dropKey(context == null ? null : context.channel(), packet, this.flow);
        if (backgroundDropKey != null) {
            IdleGateBackgroundPacketGate.recordDroppedPacket(packet, encodedByteLength, backgroundDropKey);
            out.writerIndex(this.bandwidthoptimizer$writerIndexBefore);
            return;
        }
        IdleGateBackgroundPacketGate.recordPassedPacket(context == null ? null : context.channel(), packet, this.flow, encodedByteLength);
        if (ChannelTransportStreamingEpochGate.deferIfClosed(
                context,
                ChannelTransportHooks.isInternalTransportCarrierPacket(packet),
                out,
                this.bandwidthoptimizer$writerIndexBefore,
                bytes -> ChannelTransportHooks.recordCommittedOutboundPacketStream(context, packet, bytes)
        )) {
            return;
        }
        long returnHookStartNanos = System.nanoTime();
        long vanillaEncodeNanos = this.bandwidthoptimizer$encodeStartNanos <= 0L
                ? 0L
                : Math.max(returnHookStartNanos - this.bandwidthoptimizer$encodeStartNanos, 0L);
        HotpathCostProbe.Trace trace = HotpathCostProbe.begin("transportHook");
        if (trace.isActive()) {
            trace.detail("flow=" + this.flow
                    + ", packetClass=" + packet.getClass().getName()
                    + ", bytes=" + encodedByteLength);
        }
        try (trace) {
            long stageStartNanos = HotpathCostProbe.start();
            ChannelBandwidthStats stats = ServerBandwidthStatsRegistry.getOrCreate(context);
            if (stats != null) {
                stats.recordOutboundRawEncoded(encodedByteLength);
                if (VanillaCompressionEstimator.isEnabled()) {
                    stats.recordOutboundVanillaCompressedEstimate(
                            ByteBufUtil.getBytes(out, this.bandwidthoptimizer$writerIndexBefore, encodedByteLength, false),
                            encodedByteLength
                    );
                }
            }
            HotpathCostProbe.end("stats", stageStartNanos);

            //Patch
            stageStartNanos = HotpathCostProbe.start();
            ChannelCaptureHooks.captureOutboundEncodedPacket(context, packet, out, this.bandwidthoptimizer$writerIndexBefore);
            HotpathCostProbe.end("capture", stageStartNanos);

            //handle
            stageStartNanos = HotpathCostProbe.start();
            ChannelTransportHooks.tryToWrapOutboundPacket(context, packet, out, this.bandwidthoptimizer$writerIndexBefore, this);
            HotpathCostProbe.end("transportWrap", stageStartNanos);
            long hookNanos = System.nanoTime() - returnHookStartNanos;

            stageStartNanos = HotpathCostProbe.start();
            TransportDiagnosticProbe.BO_Diag_transportEncodeCost(
                    context,
                    this.flow,
                    packet,
                    encodedByteLength,
                    vanillaEncodeNanos,
                    hookNanos
            );
            HotpathCostProbe.end("transportDiagnostic", stageStartNanos);
        }
    }
}
