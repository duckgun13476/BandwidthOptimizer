package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.batch.KineticBatchLayer;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.mes.ChannelTransportOperationTelemetry;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Wrap and Unwrap.
public final class ChannelTransportPacketCodec {

    private static final int MAGIC_PACKET_ID = 0x1F_FFFF;
    private static final int FRAME_VERSION = 2;
    private static final KineticBatchLayer BATCH_LAYER = new KineticBatchLayer();

    private ChannelTransportPacketCodec() {}

    public static WrappedTransportFrame wrapPacket(ChannelTransportSession transportSession, byte[] originalPacketBytes) {
        if (transportSession == null || originalPacketBytes == null) {
            return null;
        }

        byte[] safePacketBytes = Arrays.copyOf(originalPacketBytes, originalPacketBytes.length);
        ChannelTransportSession.PacketResult packetResult = transportSession.encodeSinglePacketWithTelemetry(safePacketBytes);
        return wrapTransportBody(
                FrameKind.SINGLE,
                safePacketBytes.length,
                1,
                packetResult.bytes(),
                packetResult.telemetry()
        );
    }

    // Packet as transport batch frame 10ms，
    public static WrappedTransportFrame wrapBatchPackets(ChannelTransportSession transportSession, List<byte[]> originalPacketBytesList) {
        List<byte[]> safePacketBytesList = copyPacketBytesList(originalPacketBytesList);
        if (transportSession == null || safePacketBytesList.isEmpty()) {
            return null;
        }

        byte[] batchPayloadBytes = BATCH_LAYER.encodePacketBatch(safePacketBytesList);
        ChannelTransportSession.PacketResult packetResult = transportSession.encodeSinglePacketWithTelemetry(batchPayloadBytes);
        return wrapTransportBody(
                FrameKind.BATCH,
                totalPacketBytes(safePacketBytesList),
                safePacketBytesList.size(),
                packetResult.bytes(),
                packetResult.telemetry()
        );
    }

    // Unwarp batch frame 10ms.
    public static UnwrappedTransportFrame tryUnwrapPacket(ChannelTransportSession transportSession, byte[] inboundPacketBytes) {
        if (transportSession == null || inboundPacketBytes == null || inboundPacketBytes.length == 0) {
            return null;
        }

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(inboundPacketBytes));
        try {
            int packetId = buffer.readVarInt();
            if (packetId != MAGIC_PACKET_ID) {
                return null;
            }

            int frameVersion = buffer.readVarInt();
            if (frameVersion != 1 && frameVersion != FRAME_VERSION) {
                throw new IllegalStateException("Unsupported transport frame version: " + frameVersion);
            }

            FrameKind frameKind = frameVersion == 1 ? FrameKind.SINGLE : FrameKind.fromId(buffer.readVarInt());

            byte[] transportBodyBytes = new byte[buffer.readableBytes()];
            buffer.readBytes(transportBodyBytes);
            ChannelTransportSession.PacketResult packetResult = transportSession.decodeSinglePacketWithTelemetry(transportBodyBytes);
            List<byte[]> restoredPacketBytesList = switch (frameKind) {
                case SINGLE -> List.of(copyBytesOrEmpty(packetResult.bytes()));
                case BATCH -> BATCH_LAYER.decodePacketBatch(packetResult.bytes());
            };
            return new UnwrappedTransportFrame(
                    frameKind,
                    restoredPacketBytesList,
                    totalPacketBytes(restoredPacketBytesList),
                    restoredPacketBytesList.size(),
                    inboundPacketBytes.length,
                    transportBodyBytes.length,
                    packetResult.telemetry()
            );
        } finally {
            buffer.release();
        }
    }

    private static WrappedTransportFrame wrapTransportBody(
            FrameKind frameKind,
            int originalPacketBytes,
            int originalPacketCount,
            byte[] transportBodyBytes,
            ChannelTransportOperationTelemetry telemetry
    ) {
        byte[] safeTransportBodyBytes = copyBytesOrEmpty(transportBodyBytes);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(MAGIC_PACKET_ID);
            buffer.writeVarInt(FRAME_VERSION);
            buffer.writeVarInt(frameKind.id());
            buffer.writeBytes(safeTransportBodyBytes);
            byte[] wrappedBytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, wrappedBytes);
            return new WrappedTransportFrame(
                    frameKind,
                    wrappedBytes,
                    Math.max(originalPacketBytes, 0),
                    Math.max(originalPacketCount, 0),
                    safeTransportBodyBytes.length,
                    telemetry
            );
        } finally {
            buffer.release();
        }
    }

    private static int totalPacketBytes(List<byte[]> packetBytesList) {
        int totalBytes = 0;
        for (byte[] packetBytes : packetBytesList) {
            totalBytes += packetBytes == null ? 0 : packetBytes.length;
        }
        return totalBytes;
    }

    private static List<byte[]> copyPacketBytesList(List<byte[]> packetBytesList) {
        if (packetBytesList == null || packetBytesList.isEmpty()) {
            return List.of();
        }

        List<byte[]> copiedPacketBytesList = new ArrayList<>(packetBytesList.size());
        for (byte[] packetBytes : packetBytesList) {
            copiedPacketBytesList.add(copyBytesOrEmpty(packetBytes));
        }
        return List.copyOf(copiedPacketBytesList);
    }

    private static byte[] copyBytesOrEmpty(byte[] sourceBytes) {
        return sourceBytes == null ? new byte[0] : Arrays.copyOf(sourceBytes, sourceBytes.length);
    }

    public record WrappedTransportFrame(
            FrameKind frameKind,
            byte[] transportFrameBytes,
            int originalPacketBytes,
            int originalPacketCount,
            int zstdBodyBytes,
            ChannelTransportOperationTelemetry telemetry
    ) {
        public int transportFrameLength() {
            return this.transportFrameBytes.length;
        }
    }

    public record UnwrappedTransportFrame(
            FrameKind frameKind,
            List<byte[]> restoredPacketBytesList,
            int restoredPacketBytes,
            int restoredPacketCount,
            int inboundFrameBytes,
            int zstdBodyBytes,
            ChannelTransportOperationTelemetry telemetry
    ) { }

    public enum FrameKind {
        SINGLE(0),
        BATCH(1);

        private final int id;

        FrameKind(int id) {
            this.id = id;
        }

        public int id() {
            return this.id;
        }

        public static FrameKind fromId(int id) {
            for (FrameKind frameKind : values()) {
                if (frameKind.id == id) {
                    return frameKind;
                }
            }
            throw new IllegalStateException("Unsupported transport frame kind: " + id);
        }
    }
}
