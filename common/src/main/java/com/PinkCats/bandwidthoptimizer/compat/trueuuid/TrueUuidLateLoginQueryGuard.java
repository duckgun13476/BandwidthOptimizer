package com.PinkCats.bandwidthoptimizer.compat.trueuuid;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.compat.minecraft.ConnectionProtocolNameCompat;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class TrueUuidLateLoginQueryGuard {

    private static final AttributeKey<Boolean> LOGIN_FINISHED_SEEN_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:trueuuid_login_finished_seen");
    private static final int LOGIN_SERVERBOUND_CUSTOM_QUERY_PACKET_ID = 2;
    private static final int TRUEUUID_TRANSACTION_ID_MASK = 0xFF000000;
    private static final int TRUEUUID_TRANSACTION_ID_PREFIX = 0x4F000000;
    private static final int MAX_TRUEUUID_ACK_BODY_BYTES = 32;
    private static final AtomicLong DROPPED_INBOUND_PLAY_ACKS = new AtomicLong();
    private static final AtomicLong DROPPED_OUTBOUND_LATE_ACKS = new AtomicLong();

    private TrueUuidLateLoginQueryGuard() {}

    public static void observeInboundDecodedPackets(ChannelHandlerContext context, List<Object> decodedPackets, int outputSizeBeforeDecode) {
        if (context == null || decodedPackets == null || decodedPackets.size() <= outputSizeBeforeDecode) {
            return;
        }
        for (int index = Math.max(outputSizeBeforeDecode, 0); index < decodedPackets.size(); index++) {
            Object decodedPacket = decodedPackets.get(index);
            if (isClientboundLoginFinishedPacket(decodedPacket)) {
                context.channel().attr(LOGIN_FINISHED_SEEN_KEY).set(Boolean.TRUE);
                return;
            }
        }
    }

    public static boolean tryDropInboundPlayCustomQueryAck(ChannelHandlerContext context, PacketFlow packetFlow, ByteBuf in) {
        if (context == null || in == null || packetFlow != PacketFlow.SERVERBOUND) {
            return false;
        }
        String protocolName = ConnectionProtocolNameCompat.readProtocolName(context.channel());
        if (!"PLAY".equalsIgnoreCase(protocolName) || !looksLikeTrueUuidLoginCustomQueryAck(in)) {
            return false;
        }

        in.readerIndex(in.writerIndex());
        long dropped = DROPPED_INBOUND_PLAY_ACKS.incrementAndGet();
        Bandwidthoptimizer.LOGGER.warn(
                "[TrueUUIDCompat] Dropped late LOGIN custom query ack on PLAY stream. channel={}, droppedInboundPlayAcks={}",
                channelIdText(context.channel()),
                dropped
        );
        return true;
    }

    public static boolean tryDropOutboundLateCustomQueryAck(
            ChannelHandlerContext context,
            Packet<?> packet,
            PacketFlow packetFlow,
            ByteBuf out,
            int startIndexInclusive
    ) {
        if (context == null || packet == null || out == null || packetFlow != PacketFlow.SERVERBOUND) {
            return false;
        }
        if (!Boolean.TRUE.equals(context.channel().attr(LOGIN_FINISHED_SEEN_KEY).get())) {
            return false;
        }
        if (!isServerboundLoginCustomQueryPacket(packet)
                || !looksLikeTrueUuidLoginCustomQueryAck(out, startIndexInclusive, out.writerIndex())) {
            return false;
        }

        out.writerIndex(startIndexInclusive);
        long dropped = DROPPED_OUTBOUND_LATE_ACKS.incrementAndGet();
        Bandwidthoptimizer.LOGGER.warn(
                "[TrueUUIDCompat] Dropped late outbound LOGIN custom query ack after login finished. channel={}, droppedOutboundLateAcks={}",
                channelIdText(context.channel()),
                dropped
        );
        return true;
    }

    private static boolean looksLikeTrueUuidLoginCustomQueryAck(ByteBuf buffer) {
        return buffer != null && looksLikeTrueUuidLoginCustomQueryAck(buffer, buffer.readerIndex(), buffer.writerIndex());
    }

    private static boolean looksLikeTrueUuidLoginCustomQueryAck(ByteBuf buffer, int startIndexInclusive, int endIndexExclusive) {
        if (buffer == null || startIndexInclusive < 0 || endIndexExclusive <= startIndexInclusive) {
            return false;
        }
        int readableBytes = endIndexExclusive - startIndexInclusive;
        if (readableBytes < 6 || readableBytes > MAX_TRUEUUID_ACK_BODY_BYTES) {
            return false;
        }

        VarIntRead packetId = readVarInt(buffer, startIndexInclusive, endIndexExclusive);
        if (!packetId.complete() || packetId.value() != LOGIN_SERVERBOUND_CUSTOM_QUERY_PACKET_ID) {
            return false;
        }
        VarIntRead transactionId = readVarInt(buffer, packetId.nextIndex(), endIndexExclusive);
        if (!transactionId.complete() || !isTrueUuidTransactionId(transactionId.value())) {
            return false;
        }

        int remainingBytes = endIndexExclusive - transactionId.nextIndex();
        if (remainingBytes < 1 || remainingBytes > MAX_TRUEUUID_ACK_BODY_BYTES) {
            return false;
        }
        int hasDataFlag = buffer.getUnsignedByte(transactionId.nextIndex());
        return hasDataFlag == 0 || hasDataFlag == 1;
    }

    private static boolean isTrueUuidTransactionId(int transactionId) {
        return (transactionId & TRUEUUID_TRANSACTION_ID_MASK) == TRUEUUID_TRANSACTION_ID_PREFIX;
    }

    private static VarIntRead readVarInt(ByteBuf buffer, int startIndexInclusive, int endIndexExclusive) {
        int value = 0;
        int position = 0;
        int index = startIndexInclusive;
        while (index < endIndexExclusive && index - startIndexInclusive < 5) {
            int current = buffer.getUnsignedByte(index++);
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return new VarIntRead(value, index, true);
            }
            position += 7;
        }
        return new VarIntRead(value, index, false);
    }

    private static boolean isClientboundLoginFinishedPacket(Object packet) {
        String packetClassName = packetClassName(packet);
        return "net.minecraft.network.protocol.login.ClientboundGameProfilePacket".equals(packetClassName)
                || "net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket".equals(packetClassName);
    }

    private static boolean isServerboundLoginCustomQueryPacket(Packet<?> packet) {
        return "net.minecraft.network.protocol.login.ServerboundCustomQueryPacket".equals(packetClassName(packet));
    }

    private static String packetClassName(Object packet) {
        return packet == null ? "" : packet.getClass().getName();
    }

    private static String channelIdText(Channel channel) {
        return channel == null ? "<no-channel>" : com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel);
    }

    private record VarIntRead(int value, int nextIndex, boolean complete) { }
}
