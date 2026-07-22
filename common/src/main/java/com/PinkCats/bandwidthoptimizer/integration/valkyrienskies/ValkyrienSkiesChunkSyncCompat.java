package com.PinkCats.bandwidthoptimizer.integration.valkyrienskies;

import com.PinkCats.bandwidthoptimizer.integration.minecraft.CustomPayloadPacketCompat;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;

import java.util.Locale;

public final class ValkyrienSkiesChunkSyncCompat {

    private static final String VALKYRIEN_SKIES_PACKET = "valkyrienskies:vs_packet";

    private ValkyrienSkiesChunkSyncCompat() {}

    public static PayloadDecision observeOutboundPayload(ChannelHandlerContext context, Packet<?> packet) {
        String payloadChannel = normalizePayloadChannel(CustomPayloadPacketCompat.payloadChannel(packet));
        if (context == null || !VALKYRIEN_SKIES_PACKET.equals(payloadChannel)) {
            return PayloadDecision.allow();
        }
        return PayloadDecision.forceDirect("valkyrienskies_dynamic_structure_payload_boundary");
    }

    private static String normalizePayloadChannel(String payloadChannel) {
        return payloadChannel == null ? "" : payloadChannel.trim().toLowerCase(Locale.ROOT);
    }

    public record PayloadDecision(boolean forceDirectTransport, String reason) {
        public PayloadDecision {
            reason = reason == null ? "" : reason;
        }

        private static PayloadDecision allow() {
            return new PayloadDecision(false, "");
        }

        private static PayloadDecision forceDirect(String reason) {
            return new PayloadDecision(true, reason);
        }
    }
}
