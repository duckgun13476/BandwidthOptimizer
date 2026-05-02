package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Config;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;

public final class ChannelTransportControlPlane {

    private static final AttributeKey<ControlState> CONTROL_STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:channel_transport_control_state");

    private static final int LISTENER_DIRECT_PACKETS = 1;
    private static final long LISTENER_DIRECT_NANOS = 0L;

    private ChannelTransportControlPlane() {}

    public static void observeConnectionSend(Channel channel, Packet<?> packet, PacketSendListener listener) {
        if (channel == null || packet == null || !isProxySafeControlEnabled()) {
            return;
        }

        ControlState controlState = getOrCreateControlState(channel);
        if (listener != null) {
            controlState.armDirectWindow(LISTENER_DIRECT_PACKETS, LISTENER_DIRECT_NANOS, "packet_send_listener", false);
        }

    }


    public static TransportControlDecision beginOutboundPacket(
            ChannelHandlerContext context,
            String protocolName,
            Packet<?> packet
    ) {
        if (context == null || packet == null || !isProxySafeControlEnabled()) {
            return TransportControlDecision.allow();
        }
        if (protocolName == null || !"PLAY".equalsIgnoreCase(protocolName)) {
            return TransportControlDecision.forceDirect("protocol_boundary_non_play");
        }

        BoundaryProfile boundaryProfile = classifyBoundary(packet);
        if (boundaryProfile != BoundaryProfile.NONE) {
            return TransportControlDecision.forceDirect(boundaryProfile.reason());
        }
        return getOrCreateControlState(context.channel()).consumeDirectPermit();
    }

    private static boolean isProxySafeControlEnabled() {
        return Boolean.parseBoolean(
                System.getProperty(
                        Config.RuntimeProperty.Transport.PROXY_SAFE_CONTROL_ENABLED,
                        Boolean.toString(Config.RuntimeProperty.Transport.DEFAULT_PROXY_SAFE_CONTROL_ENABLED)
                )
        );
    }

    private static BoundaryProfile classifyBoundary(Packet<?> packet) {
        String packetClassName = packet == null ? "" : packet.getClass().getName();
        if (packetClassName.endsWith("ClientboundLoginPacket")
                || packetClassName.endsWith("ClientboundRespawnPacket")
                || packetClassName.endsWith("ClientboundPlayerPositionPacket")
                || packetClassName.endsWith("ClientboundCommandsPacket")
                || packetClassName.endsWith("ClientboundCommandSuggestionsPacket")) {
            return BoundaryProfile.strong(packetClassName);
        }
        if (packetClassName.endsWith("ClientboundSetChunkCacheCenterPacket")
                || packetClassName.endsWith("ClientboundSetChunkCacheRadiusPacket")
                || packetClassName.endsWith("ClientboundForgetLevelChunkPacket")
                || packetClassName.endsWith("ClientboundCustomPayloadPacket")
                || packetClassName.endsWith("ServerboundCustomPayloadPacket")) {
            return BoundaryProfile.small(packetClassName);
        }
        if (packetClassName.endsWith("ServerboundAcceptTeleportationPacket")
                || packetClassName.endsWith("ServerboundCommandSuggestionPacket")
                || packetClassName.endsWith("ServerboundPlayerActionPacket")
                || packetClassName.endsWith("ServerboundSwingPacket")
                || packetClassName.endsWith("ServerboundUseItemOnPacket")
                || packetClassName.endsWith("ServerboundUseItemPacket")
                || packetClassName.contains("ServerboundMovePlayerPacket$")) {
            return BoundaryProfile.small(packetClassName);
        }
        return BoundaryProfile.NONE;
    }

    private static ControlState getOrCreateControlState(Channel channel) {
        ControlState existingState = channel.attr(CONTROL_STATE_KEY).get();
        if (existingState != null) {
            return existingState;
        }

        ControlState newState = new ControlState();
        ControlState racedState = channel.attr(CONTROL_STATE_KEY).setIfAbsent(newState);
        return racedState == null ? newState : racedState;
    }

    public record TransportControlDecision(boolean forceDirectTransport, String reason) {
        public TransportControlDecision {
            reason = reason == null ? "" : reason;
        }

        private static TransportControlDecision allow() {
            return new TransportControlDecision(false, "");
        }

        private static TransportControlDecision forceDirect(String reason) {
            return new TransportControlDecision(true, reason);
        }
    }

    private record BoundaryProfile(String reason) {
        private static final BoundaryProfile NONE = new BoundaryProfile("");

        private static BoundaryProfile strong(String packetClassName) {
            return new BoundaryProfile("connection_strong_boundary:" + packetClassName);
        }

        private static BoundaryProfile small(String packetClassName) {
            return new BoundaryProfile("connection_interaction_boundary:" + packetClassName);
        }
    }

    private static final class ControlState {
        private int directPackets;
        private long directUntilNanos;
        private String directReason = "";

        private synchronized void armDirectWindow(int packets, long nanos, String reason, boolean extendByTime) {
            long nowNanos = System.nanoTime();
            this.directPackets = Math.max(this.directPackets, Math.max(packets, 0));
            if (extendByTime && nanos > 0L) {
                this.directUntilNanos = Math.max(this.directUntilNanos, nowNanos + nanos);
            }
            this.directReason = reason == null ? "" : reason;
        }

        private synchronized TransportControlDecision consumeDirectPermit() {
            long nowNanos = System.nanoTime();
            boolean hasPacketPermit = this.directPackets > 0;
            boolean hasTimePermit = nowNanos <= this.directUntilNanos;
            if (!hasPacketPermit && !hasTimePermit) {
                return TransportControlDecision.allow();
            }
            if (this.directPackets > 0) {
                this.directPackets--;
            }
            return TransportControlDecision.forceDirect(this.directReason);
        }
    }
}
