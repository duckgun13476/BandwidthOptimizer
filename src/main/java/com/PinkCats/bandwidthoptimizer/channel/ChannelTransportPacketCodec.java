package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.mes.ChannelTransportOperationTelemetry;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

// Wrap and Unwrap.
public final class ChannelTransportPacketCodec {

    private static final int MAGIC_PACKET_ID = 0x1F_FFFF;
    private static final int FRAME_VERSION = 1;

    private ChannelTransportPacketCodec() {}

    public static WrappedTransportFrame wrapPacket(ChannelTransportSession transportSession, byte[] originalPacketBytes) {
        if (transportSession == null || originalPacketBytes == null)
            return null;


        ChannelTransportSession.PacketResult packetResult = transportSession.encodeSinglePacketWithTelemetry(originalPacketBytes);
        byte[] transportBodyBytes = packetResult.bytes();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(MAGIC_PACKET_ID);
            buffer.writeVarInt(FRAME_VERSION);
            buffer.writeBytes(transportBodyBytes);
            byte[] wrappedBytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, wrappedBytes);
            return new WrappedTransportFrame(
                    wrappedBytes,
                    originalPacketBytes.length,
                    transportBodyBytes.length,
                    packetResult.telemetry()
            );
        } finally {
            buffer.release();
        }
    }


    public static UnwrappedTransportFrame tryUnwrapPacket(ChannelTransportSession transportSession, byte[] inboundPacketBytes) {
        if (transportSession == null || inboundPacketBytes == null || inboundPacketBytes.length == 0)
            return null;


        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(inboundPacketBytes));
        try {
            int packetId = buffer.readVarInt();
            if (packetId != MAGIC_PACKET_ID)
                return null;

            int frameVersion = buffer.readVarInt();

            if (frameVersion != FRAME_VERSION)
                throw new IllegalStateException("Unsupported transport frame version: " + frameVersion);

            byte[] transportBodyBytes = new byte[buffer.readableBytes()];
            buffer.readBytes(transportBodyBytes);
            ChannelTransportSession.PacketResult packetResult = transportSession.decodeSinglePacketWithTelemetry(transportBodyBytes);
            return new UnwrappedTransportFrame(
                    packetResult.bytes(),
                    inboundPacketBytes.length,
                    transportBodyBytes.length,
                    packetResult.telemetry()
            );
        } finally {
            buffer.release();
        }
    }


    public record WrappedTransportFrame(
            byte[] transportFrameBytes,
            int originalPacketBytes,
            int zstdBodyBytes,
            ChannelTransportOperationTelemetry telemetry
    ) {
        public int transportFrameLength() {
            return this.transportFrameBytes.length;
        }
    }

    public record UnwrappedTransportFrame(
            byte[] restoredPacketBytes,
            int inboundFrameBytes,
            int zstdBodyBytes,
            ChannelTransportOperationTelemetry telemetry
    ) { }
}
