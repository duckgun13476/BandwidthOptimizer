package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.compat.bungeecord.BungeeCordPluginMessageCompat;
import com.PinkCats.bandwidthoptimizer.compat.minecraft.CustomPayloadPacketCompat;
import com.PinkCats.bandwidthoptimizer.compat.sable.SableChunkSyncCompat;
import com.PinkCats.bandwidthoptimizer.compat.valkyrienskies.ValkyrienSkiesChunkSyncCompat;
import com.PinkCats.bandwidthoptimizer.debug.DebugRuntimeConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.Packet;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ChannelTransportControlPlane {

    private static final AttributeKey<ControlState> CONTROL_STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:channel_transport_control_state");

    private static final int LISTENER_DIRECT_PACKETS = 1;
    private static final long LISTENER_DIRECT_NANOS = 0L;
    private static final int MAX_TRACKED_LISTENER_PACKETS = 256;
    private static final Set<String> DIRECT_CUSTOM_PAYLOAD_CHANNELS = Set.of(
            Bandwidthoptimizer.MODID + ":" + Bandwidthoptimizer.versionedNetworkPath("transport"),
            "minecraft:register",
            "minecraft:unregister",
            "minecraft:brand",
            "forge:handshake",
            "forge:tier_sorting",
            "fml:handshake",
            "neoforge:network",
            "neoforge:modded_network_setup",
            "neoforge:register",
            "neoforge:unregister"
    );

    private ChannelTransportControlPlane() {}

    public static void observeConnectionSend(Channel channel, Packet<?> packet, Object listener) {
        if (channel == null || packet == null || !isProxySafeControlEnabled()) {
            return;
        }

        ControlState controlState = getOrCreateControlState(channel);
        if (listener != null) {
            ListenerTransportPolicy listenerTransportPolicy = classifyListenerTransportPolicy(packet);
            controlState.rememberListenerPacket(packet, listenerTransportPolicy);
            if (DebugRuntimeConfig.isDiagnoseEnabled()) {
                Bandwidthoptimizer.LOGGER.info(
                        "[Transport][ListenerPolicy][Observe] action={}, packetClass={}, channel={}",
                        listenerTransportPolicy.logAction(),
                        packetClassName(packet),
                        com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.shortText(channel)
                );
            }
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

        // Dynamic structure payloads are timing boundaries.
        SableChunkSyncCompat.PayloadDecision sablePayloadDecision =
                SableChunkSyncCompat.observeOutboundPayload(context, packet);
        if (sablePayloadDecision.forceDirectTransport()) {
            return TransportControlDecision.forceDirect(sablePayloadDecision.reason());
        }
        ValkyrienSkiesChunkSyncCompat.PayloadDecision valkyrienSkiesPayloadDecision =
                ValkyrienSkiesChunkSyncCompat.observeOutboundPayload(context, packet);
        if (valkyrienSkiesPayloadDecision.forceDirectTransport()) {
            return TransportControlDecision.forceDirect(valkyrienSkiesPayloadDecision.reason());
        }

        ImmediateTransportProfile immediateTransportProfile = classifyImmediateTransport(packet);
        if (immediateTransportProfile != ImmediateTransportProfile.NONE) {
            if (DebugRuntimeConfig.isDiagnoseEnabled()) {
                Bandwidthoptimizer.LOGGER.info(
                        "[Transport][ImmediatePolicy][Consume] reason={}, packetClass={}, channel={}",
                        immediateTransportProfile.reason(),
                        packetClassName(packet),
                        com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.shortText(context.channel())
                );
            }
            return TransportControlDecision.forceImmediateTransport(immediateTransportProfile.reason());
        }

        BoundaryProfile boundaryProfile = classifyBoundary(packet);
        if (boundaryProfile != BoundaryProfile.NONE) {
            return TransportControlDecision.forceDirect(boundaryProfile.reason());
        }
        ListenerTransportPolicy listenerTransportPolicy = getOrCreateControlState(context.channel()).consumeListenerPolicy(packet);
        if (listenerTransportPolicy == ListenerTransportPolicy.IMMEDIATE_TRANSPORT) {
            if (DebugRuntimeConfig.isDiagnoseEnabled()) {
                Bandwidthoptimizer.LOGGER.info(
                        "[Transport][ListenerPolicy][Consume] action={}, packetClass={}, channel={}",
                        listenerTransportPolicy.logAction(),
                        packetClassName(packet),
                        com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.shortText(context.channel())
                );
            }
            return TransportControlDecision.forceImmediateTransport("packet_send_listener_immediate_transport");
        }
        if (listenerTransportPolicy == ListenerTransportPolicy.DIRECT) {
            if (DebugRuntimeConfig.isDiagnoseEnabled()) {
                Bandwidthoptimizer.LOGGER.info(
                        "[Transport][ListenerPolicy][Consume] action={}, packetClass={}, channel={}",
                        listenerTransportPolicy.logAction(),
                        packetClassName(packet),
                        com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.shortText(context.channel())
                );
            }
            return TransportControlDecision.forceDirect("packet_send_listener");
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

    private static ListenerTransportPolicy classifyListenerTransportPolicy(Packet<?> packet) {
        String packetClassName = packetClassName(packet);
        if (packetClassName.endsWith("ClientboundUpdateRecipesPacket")
                || packetClassName.endsWith("ClientboundRecipePacket")) {
            return ListenerTransportPolicy.IMMEDIATE_TRANSPORT;
        }
        return ListenerTransportPolicy.DIRECT;
    }

    private static ImmediateTransportProfile classifyImmediateTransport(Packet<?> packet) {
        String packetClassName = packetClassName(packet);
        if (packetClassName.endsWith("ClientboundUpdateRecipesPacket")) {
            return new ImmediateTransportProfile("packet_class_immediate_transport:" + packetClassName);
        }
        if (packetClassName.endsWith("ClientboundRecipePacket")) {
            return new ImmediateTransportProfile("packet_class_immediate_transport:" + packetClassName);
        }
        return ImmediateTransportProfile.NONE;
    }

    private static BoundaryProfile classifyBoundary(Packet<?> packet) {
        String packetClassName = packetClassName(packet);
        if (packetClassName.endsWith("ClientboundLoginPacket")
                || packetClassName.endsWith("ClientboundRespawnPacket")
                || packetClassName.endsWith("ClientboundPlayerPositionPacket")
                || packetClassName.endsWith("ClientboundCommandsPacket")
                || packetClassName.endsWith("ClientboundCommandSuggestionsPacket")) {
            return BoundaryProfile.strong(packetClassName);
        }
        if (packetClassName.endsWith("ClientboundSetChunkCacheCenterPacket")
                || packetClassName.endsWith("ClientboundSetChunkCacheRadiusPacket")
                || packetClassName.endsWith("ClientboundForgetLevelChunkPacket")) {
            return BoundaryProfile.small(packetClassName);
        }
        BoundaryProfile customPayloadBoundary = classifyCustomPayloadBoundary(packet, packetClassName);
        if (customPayloadBoundary != BoundaryProfile.NONE) {
            return customPayloadBoundary;
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

    private static BoundaryProfile classifyCustomPayloadBoundary(Packet<?> packet, String packetClassName) {
        if (!isCustomPayloadPacketClass(packetClassName)) {
            return BoundaryProfile.NONE;
        }
        String payloadChannel = normalizePayloadChannel(readCustomPayloadChannel(packet));
        if (payloadChannel.isBlank()) {
            return BoundaryProfile.customPayload("unknown_channel", packetClassName, "<unknown>");
        }
        if (payloadChannel.startsWith(Bandwidthoptimizer.MODID + ":")) {
            return BoundaryProfile.customPayload("internal_channel", packetClassName, payloadChannel);
        }
        if (DIRECT_CUSTOM_PAYLOAD_CHANNELS.contains(payloadChannel)) {
            return BoundaryProfile.customPayload("protocol_channel", packetClassName, payloadChannel);
        }
        if (BungeeCordPluginMessageCompat.isProxyControlChannel(payloadChannel)) {
            return BoundaryProfile.customPayload("proxy_control_channel", packetClassName, payloadChannel);
        }
        if (packetClassName.endsWith("ServerboundCustomPayloadPacket")) {
            return BoundaryProfile.customPayload("serverbound_channel", packetClassName, payloadChannel);
        }
        return BoundaryProfile.NONE;
    }

    private static boolean isCustomPayloadPacketClass(String packetClassName) {
        return packetClassName != null
                && (packetClassName.endsWith("ClientboundCustomPayloadPacket")
                || packetClassName.endsWith("ServerboundCustomPayloadPacket"));
    }

    private static String readCustomPayloadChannel(Packet<?> packet) {
        return CustomPayloadPacketCompat.payloadChannel(packet);
    }

    private static String normalizePayloadChannel(String payloadChannel) {
        return payloadChannel == null ? "" : payloadChannel.trim().toLowerCase(Locale.ROOT);
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

    public record TransportControlDecision(boolean forceDirectTransport, boolean forceImmediateTransport, String reason) {
        public TransportControlDecision {
            reason = reason == null ? "" : reason;
        }

        private static TransportControlDecision allow() {
            return new TransportControlDecision(false, false, "");
        }

        private static TransportControlDecision forceDirect(String reason) {
            return new TransportControlDecision(true, false, reason);
        }

        private static TransportControlDecision forceImmediateTransport(String reason) {
            return new TransportControlDecision(false, true, reason);
        }
    }

    private record ImmediateTransportProfile(String reason) {
        private static final ImmediateTransportProfile NONE = new ImmediateTransportProfile("");
    }

    private record BoundaryProfile(String reason) {
        private static final BoundaryProfile NONE = new BoundaryProfile("");

        private static BoundaryProfile strong(String packetClassName) {
            return new BoundaryProfile("connection_strong_boundary:" + packetClassName);
        }

        private static BoundaryProfile small(String packetClassName) {
            return new BoundaryProfile("connection_interaction_boundary:" + packetClassName);
        }

        private static BoundaryProfile customPayload(String reason, String packetClassName, String payloadChannel) {
            return new BoundaryProfile("custom_payload_boundary:" + reason + ":" + payloadChannel + ":" + packetClassName);
        }
    }

    private enum ListenerTransportPolicy {
        NONE("none"),
        DIRECT("direct_bypass"),
        IMMEDIATE_TRANSPORT("immediate_transport");

        private final String logAction;

        ListenerTransportPolicy(String logAction) {
            this.logAction = logAction;
        }

        private String logAction() {
            return this.logAction;
        }
    }

    private record TrackedListenerPacket(Packet<?> packet, ListenerTransportPolicy policy) {}

    private static final class ControlState {
        private int directPackets;
        private long directUntilNanos;
        private String directReason = "";
        private final List<TrackedListenerPacket> listenerPackets = new ArrayList<>();

        private synchronized void rememberListenerPacket(Packet<?> packet, ListenerTransportPolicy policy) {
            if (packet == null || policy == null || policy == ListenerTransportPolicy.NONE) {
                return;
            }
            while (this.listenerPackets.size() >= MAX_TRACKED_LISTENER_PACKETS) {
                this.listenerPackets.remove(0);
            }
            this.listenerPackets.add(new TrackedListenerPacket(packet, policy));
        }
        
        private synchronized ListenerTransportPolicy consumeListenerPolicy(Packet<?> packet) {
            if (packet == null || this.listenerPackets.isEmpty()) {
                return ListenerTransportPolicy.NONE;
            }
            Iterator<TrackedListenerPacket> iterator = this.listenerPackets.iterator();
            while (iterator.hasNext()) {
                TrackedListenerPacket trackedListenerPacket = iterator.next();
                if (trackedListenerPacket.packet() == packet) {
                    iterator.remove();
                    return trackedListenerPacket.policy();
                }
            }
            return ListenerTransportPolicy.NONE;
        }

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

    private static String packetClassName(Packet<?> packet) {
        return packet == null ? "<null>" : packet.getClass().getName();
    }
}
