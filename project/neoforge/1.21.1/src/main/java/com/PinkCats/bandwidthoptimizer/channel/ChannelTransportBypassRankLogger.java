package com.PinkCats.bandwidthoptimizer.channel;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

public final class ChannelTransportBypassRankLogger {

    private ChannelTransportBypassRankLogger() {}

    public static void recordPacket(
            ChannelHandlerContext context,
            String reason,
            String protocolName,
            Packet<?> packet,
            PacketFlow packetFlow,
            byte[] packetBytes
    ) {
        if (packet == null) {
            recordEncodedPacket(
                    context,
                    reason,
                    protocolName,
                    packetFlow,
                    "<unknown-packet>",
                    null,
                    ChannelTransportBypassRankCore.tryReadLeadingVarInt(packetBytes),
                    ChannelTransportBypassRankCore.lengthOf(packetBytes)
            );
            return;
        }

        recordEncodedPacket(
                context,
                reason,
                protocolName,
                packetFlow,
                packet.getClass().getName(),
                customPayloadChannel(packet),
                ChannelTransportBypassRankCore.tryReadLeadingVarInt(packetBytes),
                ChannelTransportBypassRankCore.lengthOf(packetBytes)
        );
    }

    public static void recordEncodedPacket(
            ChannelHandlerContext context,
            String reason,
            String protocolName,
            PacketFlow packetFlow,
            String packetClassName,
            String payloadChannel,
            int rawPacketId,
            int packetBytes
    ) {
        ChannelTransportBypassRankCore.recordEncodedPacket(
                reason,
                protocolName,
                flowName(packetFlow),
                packetClassName,
                payloadChannel,
                rawPacketId,
                packetBytes,
                channelIdText(context)
        );
    }

    public static void dumpNow(String reason) {
        ChannelTransportBypassRankCore.dumpNow(reason);
    }

    private static String customPayloadChannel(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket clientboundCustomPayloadPacket) {
            return clientboundCustomPayloadPacket.payload().type().id().toString();
        }
        if (packet instanceof ServerboundCustomPayloadPacket serverboundCustomPayloadPacket) {
            return serverboundCustomPayloadPacket.payload().type().id().toString();
        }
        return null;
    }

    private static String flowName(PacketFlow packetFlow) {
        return packetFlow == null ? null : packetFlow.name();
    }

    private static String channelIdText(ChannelHandlerContext context) {
        if (context == null || context.channel() == null) {
            return "<no-channel>";
        }
        try {
            return context.channel().id().asShortText();
        } catch (Throwable ignored) {
            return "<unknown-channel>";
        }
    }
}
