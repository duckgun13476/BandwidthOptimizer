package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.batch.KineticBatchLayer;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportPayloadLimits;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.mes.ChannelTransportOperationTelemetry;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Wrap and Unwrap.
public final class ChannelTransportPacketCodec {

    private static final int MAGIC_PACKET_ID = 0x1F_FFFF;
    private static final int FRAME_VERSION = 3;
    private static final int BATCH_FRAME_VERSION = 2;
    private static final KineticBatchLayer BATCH_LAYER = new KineticBatchLayer();

    private ChannelTransportPacketCodec() {}

    public static WrappedTransportFrame wrapPacket(ChannelTransportSession transportSession, byte[] originalPacketBytes) {
        if (transportSession == null || originalPacketBytes == null) {
            return null;
        }

        byte[] safePacketBytes = Arrays.copyOf(originalPacketBytes, originalPacketBytes.length);
        validateSinglePacketBytes(safePacketBytes.length);
        ChannelTransportSession.PacketResult packetResult = transportSession.encodeSinglePacketWithTelemetry(safePacketBytes);
        return wrapTransportBody(
                FrameKind.SINGLE,
                safePacketBytes.length,
                1,
                packetResult.bytes(),
                packetResult.telemetry()
        );
    }

    // Packet as transport batch frame.
    public static WrappedTransportFrame wrapBatchPackets(ChannelTransportSession transportSession, List<byte[]> originalPacketBytesList) {
        return wrapBatchPackets(transportSession, originalPacketBytesList, BatchEncodingProfile.LITERAL_MAPPING);
    }

    public static WrappedTransportFrame wrapBatchPacketsLight(ChannelTransportSession transportSession, List<byte[]> originalPacketBytesList) {
        return wrapBatchPackets(transportSession, originalPacketBytesList, BatchEncodingProfile.LITERAL_MAPPING);
    }

    private static WrappedTransportFrame wrapBatchPackets(
            ChannelTransportSession transportSession,
            List<byte[]> originalPacketBytesList,
            BatchEncodingProfile encodingProfile
    ) {
        List<byte[]> safePacketBytesList = copyPacketBytesList(originalPacketBytesList);
        if (transportSession == null || safePacketBytesList.isEmpty()) {
            return null;
        }

        // Batch carriers must be self-contained across session rebuilds.
        byte[] batchPayloadBytes = BATCH_LAYER.encodePacketBatch(safePacketBytesList);
        if (transportSession.isCrossFrameZstdEnabled()) {
            ChannelTransportSession.StreamingPacketResult streamingResult =
                    transportSession.encodeBatchWithStreamingZstd(batchPayloadBytes);
            return wrapStreamingTransportBody(
                    streamingResult.epoch(),
                    streamingResult.sequence(),
                    totalPacketBytes(safePacketBytesList),
                    safePacketBytesList.size(),
                    streamingResult.packetResult().bytes(),
                    streamingResult.packetResult().telemetry()
            );
        }
        ChannelTransportSession.PacketResult packetResult = encodingProfile == BatchEncodingProfile.LITERAL_MAPPING
                ? transportSession.encodeSinglePacketWithLiteralMappingTelemetry(batchPayloadBytes)
                : transportSession.encodeSinglePacketWithTelemetry(batchPayloadBytes);
        return wrapTransportBody(
                FrameKind.BATCH,
                totalPacketBytes(safePacketBytesList),
                safePacketBytesList.size(),
                packetResult.bytes(),
                packetResult.telemetry()
        );
    }

    // Unwarp batch frame.
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
            if (frameVersion != 1 && frameVersion != BATCH_FRAME_VERSION && frameVersion != FRAME_VERSION) {
                throw new IllegalStateException("Unsupported transport frame version: " + frameVersion);
            }

            FrameKind frameKind = frameVersion == 1 ? FrameKind.SINGLE : FrameKind.fromId(buffer.readVarInt());
            int streamingEpoch = 0;
            int streamingSequence = 0;
            if (frameVersion == FRAME_VERSION) {
                if (frameKind != FrameKind.STREAM_BATCH) {
                    throw new IllegalStateException("Transport streaming frame must be a batch");
                }
                streamingEpoch = buffer.readVarInt();
                streamingSequence = buffer.readVarInt();
                if (streamingEpoch <= 0 || streamingSequence <= 0) {
                    throw new IllegalStateException("Invalid transport streaming epoch or sequence");
                }
            }

            byte[] transportBodyBytes = new byte[buffer.readableBytes()];
            buffer.readBytes(transportBodyBytes);
            ChannelTransportSession.PacketResult packetResult = frameVersion == FRAME_VERSION
                    ? transportSession.decodeBatchWithStreamingZstd(transportBodyBytes)
                    : transportSession.decodeSinglePacketWithTelemetry(transportBodyBytes);
            List<byte[]> restoredPacketBytesList = switch (frameKind) {
                case SINGLE -> {
                    byte[] packetBytes = copyBytesOrEmpty(packetResult.bytes());
                    validateSinglePacketBytes(packetBytes.length);
                    yield List.of(packetBytes);
                }
                case BATCH, STREAM_BATCH -> BATCH_LAYER.decodePacketBatch(packetResult.bytes());
            };
            return new UnwrappedTransportFrame(
                    frameKind,
                    restoredPacketBytesList,
                    totalPacketBytes(restoredPacketBytesList),
                    restoredPacketBytesList.size(),
                    inboundPacketBytes.length,
                    transportBodyBytes.length,
                    packetResult.telemetry(),
                    streamingEpoch,
                    streamingSequence
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
            buffer.writeVarInt(BATCH_FRAME_VERSION);
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

    private static WrappedTransportFrame wrapStreamingTransportBody(
            int epoch,
            int sequence,
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
            buffer.writeVarInt(FrameKind.STREAM_BATCH.id());
            buffer.writeVarInt(epoch);
            buffer.writeVarInt(sequence);
            buffer.writeBytes(safeTransportBodyBytes);
            byte[] wrappedBytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, wrappedBytes);
            return new WrappedTransportFrame(
                    FrameKind.STREAM_BATCH,
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

    // Keep SINGLE limits aligned with batch entry limits.
    private static void validateSinglePacketBytes(int packetBytes) {
        if (packetBytes < 0 || packetBytes > ChannelTransportPayloadLimits.MAX_SINGLE_PACKET_BYTES) {
            throw new IllegalArgumentException(
                    "single packet bytes out of range: "
                            + packetBytes
                            + " > "
                            + ChannelTransportPayloadLimits.MAX_SINGLE_PACKET_BYTES
            );
        }
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
            ChannelTransportOperationTelemetry telemetry,
            int streamingEpoch,
            int streamingSequence
    ) {
        public UnwrappedTransportFrame(
                FrameKind frameKind,
                List<byte[]> restoredPacketBytesList,
                int restoredPacketBytes,
                int restoredPacketCount,
                int inboundFrameBytes,
                int zstdBodyBytes,
                ChannelTransportOperationTelemetry telemetry
        ) {
            this(
                    frameKind,
                    restoredPacketBytesList,
                    restoredPacketBytes,
                    restoredPacketCount,
                    inboundFrameBytes,
                    zstdBodyBytes,
                    telemetry,
                    0,
                    0
            );
        }
    }

    public enum FrameKind {
        SINGLE(0),
        BATCH(1),
        STREAM_BATCH(2);

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

    private enum BatchEncodingProfile {
        FULL_TEMPLATE,
        LITERAL_MAPPING
    }
}
