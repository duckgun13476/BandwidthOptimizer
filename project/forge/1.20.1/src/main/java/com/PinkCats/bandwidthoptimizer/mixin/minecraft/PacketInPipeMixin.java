package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.channel.access.PacketDecoderFlowAccess;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCaptureHooks;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportHooks;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCapturedFrame;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelTransportTelemetry;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkInboundObservationService;
import com.PinkCats.bandwidthoptimizer.connection.ConnectionDisconnectClassifier;
import com.PinkCats.bandwidthoptimizer.debug.MovementDiagnosticProbe;
import com.PinkCats.bandwidthoptimizer.debug.PacketClassTraceDiagnostic;
import com.PinkCats.bandwidthoptimizer.integration.trueuuid.TrueUuidLateLoginQueryGuard;
import com.PinkCats.bandwidthoptimizer.server.stat.ChannelBandwidthStats;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(PacketDecoder.class)
public abstract class PacketInPipeMixin<T extends PacketListener> implements PacketDecoderFlowAccess {

    @Shadow
    @Final
    private PacketFlow flow;

    @Unique
    private int bandwidthoptimizer$outputSizeBeforeDecode;

    @Unique
    private ChannelCapturedFrame bandwidthoptimizer$pendingInboundFrame;


    // 透出当前 PacketDecoder 的方向，供 BO 入站解包时判断这是客户端收包还是服务端收包。
    @Override
    public PacketFlow bandwidthoptimizer$getPacketFlow() {
        return this.flow;
    }

    // 客户端入站最早的 BO 还原点：在原版 PacketDecoder 读取 packet id 之前先尝试识别 BO transport 帧。
    // 如果这里成功消费帧，方法会把还原出的原版 packet 对象放回同一个 out 列表，并取消原版解码。
    // 注意：这个时间点只代表 Netty 解码阶段已经还原 packet，后续真正执行 packet.handle(...) 仍然要经过原版连接分发。
    @Inject(method = "decode", at = @At("HEAD"), cancellable = true)
    private void bandwidthoptimizer$unwrapAndCapture(ChannelHandlerContext context, ByteBuf in, List<Object> out, CallbackInfo ci) throws Exception {
        this.bandwidthoptimizer$outputSizeBeforeDecode = out.size();
        this.bandwidthoptimizer$pendingInboundFrame = ChannelCaptureHooks.beginInboundPreDecode(context, in);
        if (TrueUuidLateLoginQueryGuard.tryDropInboundPlayCustomQueryAck(context, this.flow, in)) {
            ChannelCaptureHooks.clearInboundDecodeCandidate(context.channel());
            this.bandwidthoptimizer$pendingInboundFrame = null;
            ci.cancel();
            return;
        }
        if (ChannelTransportHooks.tryDecodeInboundTransportFrame(context, in, out, this)) {
            ChannelCaptureHooks.clearInboundDecodeCandidate(context.channel());
            this.bandwidthoptimizer$pendingInboundFrame = null;
            ci.cancel();
            return;
        }
        // 记录原始入站字节，后续 RETURN 阶段如果发现是 carrier，会用这些上下文完成观测和替换。
        bandwidthoptimizer$recordInboundRawEncoded(context, in);
    }

    // 兼容已经被原版解成 custom payload 的 carrier：RETURN 阶段把 carrier 替换成 BO 还原出的原版 packet 列表。
    // 替换只调整 PacketDecoder 本次输出队列的内容和顺序，不直接调用 packet.handle(...)。
    @Inject(method = "decode", at = @At("RETURN"))
    private void bandwidthoptimizer$finishDecodeFrame(ChannelHandlerContext context, ByteBuf in, List<Object> out, CallbackInfo ci) throws Exception {
        if (ChannelTransportHooks.expandDecodedTransportCarrierPackets(
                context,
                out,
                this.bandwidthoptimizer$outputSizeBeforeDecode,
                this
        )) {
            ConnectionDisconnectClassifier.observeInboundDecodedPackets(
                    context,
                    out,
                    this.bandwidthoptimizer$outputSizeBeforeDecode
            );
            MovementDiagnosticProbe.BO_Diag_movementBurst(
                    context,
                    this.flow,
                    out,
                    this.bandwidthoptimizer$outputSizeBeforeDecode
            );
            ChannelCaptureHooks.clearInboundDecodeCandidate(context.channel());
            this.bandwidthoptimizer$pendingInboundFrame = null;
            return;
        }

        ConnectionDisconnectClassifier.observeInboundDecodedPackets(
                context,
                out,
                this.bandwidthoptimizer$outputSizeBeforeDecode
        );
        TrueUuidLateLoginQueryGuard.observeInboundDecodedPackets(context, out, this.bandwidthoptimizer$outputSizeBeforeDecode);
        ChunkInboundObservationService.observeInboundDecodedPackets(
                context,
                this.bandwidthoptimizer$pendingInboundFrame,
                out,
                this.bandwidthoptimizer$outputSizeBeforeDecode
        );
        MovementDiagnosticProbe.BO_Diag_movementBurst(
                context,
                this.flow,
                out,
                this.bandwidthoptimizer$outputSizeBeforeDecode
        );
        PacketClassTraceDiagnostic.recordInboundDirect(
                context,
                this.flow,
                this.bandwidthoptimizer$pendingInboundFrame,
                out,
                this.bandwidthoptimizer$outputSizeBeforeDecode
        );

        // 完成普通包或旁路包的入站观测，供抓包、带宽统计和区块诊断共用。
        ChannelCaptureHooks.finishInboundDecode(context, this.bandwidthoptimizer$pendingInboundFrame, out, this.bandwidthoptimizer$outputSizeBeforeDecode);
        bandwidthoptimizer$recordInboundBypass(context, out);
        this.bandwidthoptimizer$pendingInboundFrame = null;
    }
    @Unique
    private void bandwidthoptimizer$recordInboundBypass(ChannelHandlerContext context, List<Object> out) {
        if (this.bandwidthoptimizer$pendingInboundFrame == null || out == null) {
            return;
        }

        int decodedPacketCount = Math.max(out.size() - this.bandwidthoptimizer$outputSizeBeforeDecode, 0);
        if (decodedPacketCount <= 0) {
            return;
        }

        ChannelTransportTelemetry.recordInboundBypass(
                this.bandwidthoptimizer$pendingInboundFrame.protocolName(),
                this.bandwidthoptimizer$pendingInboundFrame.byteLength(),
                decodedPacketCount
        );
        ChannelBandwidthStats stats = ServerBandwidthStatsRegistry.getOrCreate(context);
        if (stats != null) {
            stats.recordInboundBypass(this.bandwidthoptimizer$pendingInboundFrame.byteLength(), decodedPacketCount);
        }
    }

    @Unique
    private void bandwidthoptimizer$recordInboundRawEncoded(ChannelHandlerContext context, ByteBuf in) {
        ChannelBandwidthStats stats = ServerBandwidthStatsRegistry.getOrCreate(context);
        if (stats != null && in != null) {
            stats.recordInboundRawEncoded(in.readableBytes(), 1);
        }
    }
}
