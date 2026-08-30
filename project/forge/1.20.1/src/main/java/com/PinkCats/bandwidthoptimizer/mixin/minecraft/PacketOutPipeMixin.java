package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelCaptureHooks;
import com.PinkCats.bandwidthoptimizer.channel.capture.RawReplayFrameCapture;
import com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportHooks;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStateManager;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStreamingEpochGate;
import com.PinkCats.bandwidthoptimizer.channel.packet.ClientboundCommandTreeDeduplicator;
import com.PinkCats.bandwidthoptimizer.channel.access.PacketEncoderFlowAccess;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.batch.ChannelTransportBatchManager;
import com.PinkCats.bandwidthoptimizer.chunk.budget.ChunkClientTrimmedFullBaseStore;
import com.PinkCats.bandwidthoptimizer.chunk.integration.ChunkRuntimeReferenceStore;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportBoundaryController;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkPersistentClientCache;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.shadow.ChunkShadowSnapshotManager;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.CustomPayloadPacketCompat;
import com.PinkCats.bandwidthoptimizer.integration.trueuuid.TrueUuidLateLoginQueryGuard;
import com.PinkCats.bandwidthoptimizer.debug.HotpathCostProbe;
import com.PinkCats.bandwidthoptimizer.debug.TransportDiagnosticProbe;
import com.PinkCats.bandwidthoptimizer.gate.integration.minecraft.IdleGateBackgroundPacketGate;
import com.PinkCats.bandwidthoptimizer.gate.integration.minecraft.IdleGateClientPacketGate;
import com.PinkCats.bandwidthoptimizer.server.stat.ChannelBandwidthStats;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry;
import com.PinkCats.bandwidthoptimizer.server.stat.VanillaCompressionEstimator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
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

    @Unique
    private static final AttributeKey<Integer> bandwidthoptimizer$SUSPENDED_PLAY_CUSTOM_PAYLOAD_DROPS =
            AttributeKey.valueOf("bandwidthoptimizer:suspended_play_custom_payload_drops");

    @Unique
    private static final AttributeKey<Integer> bandwidthoptimizer$SUSPENDED_LOGIN_CUSTOM_QUERY_ENCODES =
            AttributeKey.valueOf("bandwidthoptimizer:suspended_login_custom_query_encodes");


    @Override
    public PacketFlow bandwidthoptimizer$getPacketFlow() {
        return this.flow;
    }

    @Inject(method = "encode*", at = @At("HEAD"), cancellable = true)
    private void bandwidthoptimizer$rememberWriterIndex(ChannelHandlerContext context, Packet<T> packet, ByteBuf out, CallbackInfo ci) {

        if (bandwidthoptimizer$encodeSuspendedLoginCustomQuery(context, packet, this.flow, out)) {
            ci.cancel();
            return;
        }

        if (bandwidthoptimizer$dropSuspendedPlayCustomPayload(context, packet, this.flow)) {
            ci.cancel();
            return;
        }

        if (bandwidthoptimizer$dropUnregisteredLoginCustomQuery(context, packet, this.flow)) {
            ci.cancel();
            return;
        }

        if (TrueUuidLateLoginQueryGuard.tryDropOutboundLoginCustomQueryOnWrongProtocol(context, packet, this.flow)) {
            ci.cancel();
            return;
        }

        // Capture the vanilla encoded byte range used by stats and wrapping.
        this.bandwidthoptimizer$writerIndexBefore = out.writerIndex();
        this.bandwidthoptimizer$encodeStartNanos = System.nanoTime();
        bandwidthoptimizer$clearBeforeProxyServerSwitch(context, packet);
    }


    @Inject(method = "encode*", at = @At("RETURN"))
    private void bandwidthoptimizer$captureAndMaybeWrap(ChannelHandlerContext context, Packet<T> packet, ByteBuf out, CallbackInfo ci) {
        if (ChannelTransportHooks.isInternalTransportCarrierPacket(packet)) {
            com.PinkCats.bandwidthoptimizer.channel.ChannelOutboundBurstWarning.recordWireFrame(context, this.flow, out.writerIndex() - this.bandwidthoptimizer$writerIndexBefore, true);
            return;
        }
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
        ClientboundCommandTreeDeduplicator.Candidate commandTreeCandidate =
                ClientboundCommandTreeDeduplicator.inspect(context, packet, this.flow, out, this.bandwidthoptimizer$writerIndexBefore);
        if (commandTreeCandidate.dropped()) {
            return;
        }
        IdleGateBackgroundPacketGate.recordPassedPacket(context == null ? null : context.channel(), packet, this.flow, encodedByteLength);
        com.PinkCats.bandwidthoptimizer.channel.ChannelOutboundBurstWarning.recordLogicalPacket(context, this.flow, packet.getClass().getName(), encodedByteLength);
        if (ChannelTransportStreamingEpochGate.deferIfClosed(
                context,
                ChannelTransportHooks.isInternalTransportCarrierPacket(packet),
                out,
                this.bandwidthoptimizer$writerIndexBefore,
                bytes -> ChannelTransportHooks.recordCommittedOutboundPacketStream(context, packet, bytes)
        )) {
            ClientboundCommandTreeDeduplicator.commit(commandTreeCandidate);
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

            stageStartNanos = HotpathCostProbe.start();
            ChannelCaptureHooks.captureOutboundEncodedPacket(context, packet, out, this.bandwidthoptimizer$writerIndexBefore);
            RawReplayFrameCapture.captureOutbound(context, packet, out, this.bandwidthoptimizer$writerIndexBefore);
            HotpathCostProbe.end("capture", stageStartNanos);

            stageStartNanos = HotpathCostProbe.start();
            ChannelTransportHooks.tryToWrapOutboundPacket(context, packet, out, this.bandwidthoptimizer$writerIndexBefore, this);
            com.PinkCats.bandwidthoptimizer.channel.ChannelOutboundBurstWarning.recordWireFrame(context, this.flow, out.writerIndex() - this.bandwidthoptimizer$writerIndexBefore, false);
            ClientboundCommandTreeDeduplicator.commit(commandTreeCandidate);
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

    @Unique
    private static boolean bandwidthoptimizer$dropSuspendedPlayCustomPayload(ChannelHandlerContext context, Packet<?> packet, PacketFlow flow) {
        if (context == null || context.channel() == null || packet == null) {
            return false;
        }
        if (!ChannelTransportStateManager.isProxyServerSwitchBoundaryActive(context.channel())
                || !(packet instanceof ServerboundCustomPayloadPacket)) {
            return false;
        }

        int dropCount = bandwidthoptimizer$incrementSuspendedPlayCustomPayloadDrops(context);
        if (dropCount <= 5 || dropCount % 64 == 0) {
            // Drop stale PLAY payloads before they cross the new login encoder.
            Bandwidthoptimizer.LOGGER.warn(
                    "[ProxySwitchCompat] Dropped suspended outbound PLAY custom payload before vanilla encoder. channel={}, flow={}, payloadChannel={}, dropCount={}, reason={}",
                    ChannelIdentity.longText(context.channel()),
                    flow,
                    CustomPayloadPacketCompat.payloadChannel(packet),
                    dropCount,
                    ChannelTransportStateManager.proxyServerSwitchBoundaryReason(context.channel())
            );
        }
        return true;
    }

    @Unique
    private static int bandwidthoptimizer$incrementSuspendedPlayCustomPayloadDrops(ChannelHandlerContext context) {
        Integer previous = context.channel().attr(bandwidthoptimizer$SUSPENDED_PLAY_CUSTOM_PAYLOAD_DROPS).get();
        int next = previous == null ? 1 : previous + 1;
        context.channel().attr(bandwidthoptimizer$SUSPENDED_PLAY_CUSTOM_PAYLOAD_DROPS).set(next);
        return next;
    }

    @Unique
    private static boolean bandwidthoptimizer$encodeSuspendedLoginCustomQuery(ChannelHandlerContext context, Packet<?> packet, PacketFlow flow, ByteBuf out) {
        if (context == null || context.channel() == null || packet == null || out == null) {
            return false;
        }
        if (!ChannelTransportStateManager.isProxyServerSwitchBoundaryActive(context.channel())
                || !"net.minecraft.network.protocol.login.ServerboundCustomQueryPacket".equals(packet.getClass().getName())) {
            return false;
        }

        ConnectionProtocol protocol = context.channel().attr(Connection.ATTRIBUTE_PROTOCOL).get();
        int packetId = protocol == null ? -1 : protocol.getPacketId(flow, packet);
        if (packetId < 0) {
            packetId = 2;
        }

        // Forge login queries still need to pass during proxy switch boundaries.
        FriendlyByteBuf buffer = new FriendlyByteBuf(out);
        buffer.writeVarInt(packetId);
        packet.write(buffer);
        int encodeCount = bandwidthoptimizer$incrementSuspendedLoginCustomQueryEncodes(context);
        if (encodeCount <= 5 || encodeCount % 64 == 0) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[TrueUUIDCompat] Encoded outbound LOGIN custom query during proxy switch boundary. channel={}, flow={}, protocol={}, packetId={}, encodeCount={}, reason={}",
                    ChannelIdentity.longText(context.channel()),
                    flow,
                    protocol,
                    packetId,
                    encodeCount,
                    ChannelTransportStateManager.proxyServerSwitchBoundaryReason(context.channel())
            );
        }
        return true;
    }

    @Unique
    private static int bandwidthoptimizer$incrementSuspendedLoginCustomQueryEncodes(ChannelHandlerContext context) {
        Integer previous = context.channel().attr(bandwidthoptimizer$SUSPENDED_LOGIN_CUSTOM_QUERY_ENCODES).get();
        int next = previous == null ? 1 : previous + 1;
        context.channel().attr(bandwidthoptimizer$SUSPENDED_LOGIN_CUSTOM_QUERY_ENCODES).set(next);
        return next;
    }

    @Unique
    private static boolean bandwidthoptimizer$dropUnregisteredLoginCustomQuery(ChannelHandlerContext context, Packet<?> packet, PacketFlow flow) {
        if (context == null || context.channel() == null || packet == null) {
            return false;
        }
        if (!"net.minecraft.network.protocol.login.ServerboundCustomQueryPacket".equals(packet.getClass().getName())) {
            return false;
        }
        if (ChannelTransportStateManager.isProxyServerSwitchBoundaryActive(context.channel())) {
            // Drop boundary login queries that would hit the wrong encoder.
            Bandwidthoptimizer.LOGGER.warn(
                    "[TrueUUIDCompat] Dropped outbound LOGIN custom query during proxy switch boundary. channel={}, flow={}, reason={}",
                    ChannelIdentity.longText(context.channel()),
                    flow,
                    ChannelTransportStateManager.proxyServerSwitchBoundaryReason(context.channel())
            );
            return true;
        }
        ConnectionProtocol protocol = context.channel().attr(Connection.ATTRIBUTE_PROTOCOL).get();
        if (protocol == null || protocol.getPacketId(flow, packet) >= 0) {
            return false;
        }

        // Drop stale login query replies rejected by the current protocol table.
        Bandwidthoptimizer.LOGGER.warn(
                "[TrueUUIDCompat] Dropped unregistered outbound LOGIN custom query before vanilla encoder. channel={}, flow={}, protocol={}",
                ChannelIdentity.longText(context.channel()),
                flow,
                protocol
        );
        return true;
    }

    @Unique
    private static void bandwidthoptimizer$clearBeforeProxyServerSwitch(ChannelHandlerContext context, Packet<?> packet) {
        if (!(packet instanceof ServerboundChatCommandPacket commandPacket)) {
            return;
        }
        String command = commandPacket.command();
        if (command == null || (!command.equalsIgnoreCase("server") && !command.toLowerCase(java.util.Locale.ROOT).startsWith("server "))) {
            return;
        }
        if (context == null || context.channel() == null) {
            return;
        }

        // Clear old state for Velocity switches without pausing inbound transport.
        String reason = "proxy_server_switch_command";
        String channelId = ChannelIdentity.longText(context.channel());
        context.channel().attr(bandwidthoptimizer$SUSPENDED_PLAY_CUSTOM_PAYLOAD_DROPS).set(null);
        context.channel().attr(bandwidthoptimizer$SUSPENDED_LOGIN_CUSTOM_QUERY_ENCODES).set(null);
        ChannelTransportStateManager.beginProxyServerSwitchBoundary(context.channel(), reason, 15000L);
        ChannelTransportBatchManager.clearChannelState(context.channel(), reason);
        ChannelTransportStateManager.clearSession(context.channel(), reason);
        ChunkTransportBoundaryController.resetChannelState(context, reason);
        ChunkRuntimeReferenceStore.clearChannel(channelId);
        ChunkShadowSnapshotManager.clearChannel(channelId);
        ChunkClientTrimmedFullBaseStore.clearChannel(channelId);
        ChunkPersistentClientCache.prepareForServerSwitch(context.channel(), reason);
    }
}
