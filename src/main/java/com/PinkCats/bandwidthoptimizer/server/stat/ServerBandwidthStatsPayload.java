package com.PinkCats.bandwidthoptimizer.server.stat;

import com.PinkCats.bandwidthoptimizer.client.hud.ClientServerBandwidthHudStats;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

public record ServerBandwidthStatsPayload(
        long capturedAtMillis,
        int activeChannels,
        int boundPlayers,
        long outboundRawEncodedBytes,
        long outboundTransportFrameBytes,
        long outboundBypassBytes,
        long outboundWireBytes,
        long inboundWireBytes,
        long outboundSavedBytes
) {

    public static ServerBandwidthStatsPayload fromTotals(ServerBandwidthStatsRegistry.TotalsSnapshot totals) {
        if (totals == null) {
            return empty();
        }
        return new ServerBandwidthStatsPayload(
                System.currentTimeMillis(),
                totals.activeChannels(),
                totals.boundPlayers(),
                totals.outboundRawEncodedBytes(),
                totals.outboundTransportFrameBytes(),
                totals.outboundBypassBytes(),
                totals.outboundWireBytes(),
                totals.inboundWireBytes(),
                totals.outboundSavedBytes()
        );
    }


    public static ServerBandwidthStatsPayload empty() {
        return new ServerBandwidthStatsPayload(System.currentTimeMillis(), 0, 0, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    public static void encode(ServerBandwidthStatsPayload payload, FriendlyByteBuf buffer) {
        ServerBandwidthStatsPayload safePayload = payload == null ? empty() : payload;
        buffer.writeLong(safePayload.capturedAtMillis());
        buffer.writeVarInt(Math.max(safePayload.activeChannels(), 0));
        buffer.writeVarInt(Math.max(safePayload.boundPlayers(), 0));
        buffer.writeVarLong(Math.max(safePayload.outboundRawEncodedBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.outboundTransportFrameBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.outboundBypassBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.outboundWireBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.inboundWireBytes(), 0L));
        buffer.writeVarLong(safePayload.outboundSavedBytes());
    }

    public static ServerBandwidthStatsPayload decode(FriendlyByteBuf buffer) {
        return new ServerBandwidthStatsPayload(
                buffer.readLong(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong()
        );
    }

    public byte[] toBytes() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            encode(this, buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    public static ServerBandwidthStatsPayload fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return empty();
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            return decode(buffer);
        } finally {
            buffer.release();
        }
    }

    public static void handleClientBytes(byte[] bytes) {
        ClientServerBandwidthHudStats.accept(fromBytes(bytes));
    }
}
