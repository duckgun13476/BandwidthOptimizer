package com.PinkCats.bandwidthoptimizer.chunk.protocol;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkPacketCoordinate;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

public final class ChunkHotspotFrameCodec {

    public static final int PROTOCOL_VERSION = 1;

    private ChunkHotspotFrameCodec() {
    }

    // Encode chunk frame
    public static byte[] encodeFrame(ChunkHotspotFrame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("ChunkHotspotFrame must not be null");
        }

        ByteBuf byteBuf = Unpooled.buffer();
        try {
            FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
            friendlyByteBuf.writeVarInt(frame.protocolVersion());
            friendlyByteBuf.writeUtf(frame.operation().logName());
            friendlyByteBuf.writeLong(frame.epoch());
            friendlyByteBuf.writeLong(frame.observedPacketCount());
            friendlyByteBuf.writeUtf(frame.protocolName());
            friendlyByteBuf.writeUtf(frame.packetClassName());
            friendlyByteBuf.writeUtf(frame.hotspotKind().logName());
            friendlyByteBuf.writeUtf(frame.laneKind().logName());
            writeCoordinate(friendlyByteBuf, frame.coordinate());
            friendlyByteBuf.writeVarInt(Math.max(frame.originalEncodedBytes(), 0));
            friendlyByteBuf.writeLong(frame.fullSnapshotVersion());
            friendlyByteBuf.writeLong(frame.laneVersion());
            friendlyByteBuf.writeUtf(frame.baseSnapshotHash() == null ? "" : frame.baseSnapshotHash());
            friendlyByteBuf.writeUtf(frame.payloadHash() == null ? "" : frame.payloadHash());
            friendlyByteBuf.writeLong(frame.deltaBytesSinceFullSnapshot());
            friendlyByteBuf.writeUtf(frame.reason() == null ? "" : frame.reason());
            return ByteBufUtil.getBytes(byteBuf);
        } finally {
            byteBuf.release();
        }
    }

    // Decode chunk frame
    public static ChunkHotspotFrame decodeFrame(byte[] encodedFrameBytes) {
        if (encodedFrameBytes == null) {
            throw new IllegalArgumentException("encodedFrameBytes must not be null");
        }

        ByteBuf byteBuf = Unpooled.wrappedBuffer(encodedFrameBytes);
        try {
            FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
            int protocolVersion = friendlyByteBuf.readVarInt();
            if (protocolVersion != PROTOCOL_VERSION) {
                throw new IllegalArgumentException("Unsupported chunk hotspot protocol version: " + protocolVersion);
            }

            ChunkHotspotFrame frame = new ChunkHotspotFrame(
                    protocolVersion,
                    ChunkHotspotFrameOp.fromLogName(friendlyByteBuf.readUtf()),
                    friendlyByteBuf.readLong(),
                    friendlyByteBuf.readLong(),
                    friendlyByteBuf.readUtf(),
                    friendlyByteBuf.readUtf(),
                    ChunkHotspotKind.fromLogName(friendlyByteBuf.readUtf()),
                    ChunkLaneKind.fromLogName(friendlyByteBuf.readUtf()),
                    readCoordinate(friendlyByteBuf),
                    friendlyByteBuf.readVarInt(),
                    friendlyByteBuf.readLong(),
                    friendlyByteBuf.readLong(),
                    friendlyByteBuf.readUtf(),
                    friendlyByteBuf.readUtf(),
                    friendlyByteBuf.readLong(),
                    friendlyByteBuf.readUtf()
            );
            if (friendlyByteBuf.isReadable()) {
                throw new IllegalArgumentException("Chunk hotspot frame left extra bytes: " + friendlyByteBuf.readableBytes());
            }
            return frame;
        } finally {
            byteBuf.release();
        }
    }


    private static void writeCoordinate(FriendlyByteBuf friendlyByteBuf, ChunkPacketCoordinate coordinate) {
        ChunkPacketCoordinate resolvedCoordinate = coordinate == null ? ChunkPacketCoordinate.unknown() : coordinate;
        friendlyByteBuf.writeBoolean(resolvedCoordinate.present());
        if (resolvedCoordinate.present()) {
            friendlyByteBuf.writeVarInt(resolvedCoordinate.chunkX());
            friendlyByteBuf.writeVarInt(resolvedCoordinate.chunkZ());
        }
    }

    private static ChunkPacketCoordinate readCoordinate(FriendlyByteBuf friendlyByteBuf) {
        boolean present = friendlyByteBuf.readBoolean();
        if (!present) {
            return ChunkPacketCoordinate.unknown();
        }
        return ChunkPacketCoordinate.ofChunk(friendlyByteBuf.readVarInt(), friendlyByteBuf.readVarInt());
    }
}
