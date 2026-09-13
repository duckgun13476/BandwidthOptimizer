package com.PinkCats.bandwidthoptimizer.chunk.integration.transport.Envelope;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportPayloadLimits;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ChunkTransportEnvelopeCodec {

    private static final byte[] MAGIC_PREFIX = "BOCHKENV".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_FRAME_BYTES = 1024 * 1024;

    private ChunkTransportEnvelopeCodec() {}

    public static byte[] encodeEnvelope(ChunkTransportEnvelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("envelope must not be null");
        }

        byte[] frameBytes = ChunkHotspotFrameCodec.encodeFrame(envelope.frame());
        byte[] originalPacketBytes = envelope.copyOriginalPacketBytes();
        ByteBuf byteBuf = Unpooled.buffer();
        try {
            FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
            friendlyByteBuf.writeBytes(MAGIC_PREFIX);
            friendlyByteBuf.writeVarInt(frameBytes.length);
            friendlyByteBuf.writeBytes(frameBytes);
            friendlyByteBuf.writeVarInt(originalPacketBytes.length);
            friendlyByteBuf.writeBytes(originalPacketBytes);
            return ByteBufUtil.getBytes(byteBuf);
        } finally {
            byteBuf.release();
        }
    }

    public static ChunkTransportEnvelope decodeEnvelope(byte[] encodedEnvelopeBytes) {
        if (encodedEnvelopeBytes == null) {
            throw new IllegalArgumentException("encodedEnvelopeBytes must not be null");
        }

        ByteBuf byteBuf = Unpooled.wrappedBuffer(encodedEnvelopeBytes);
        try {
            FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
            verifyMagic(friendlyByteBuf);
            byte[] frameBytes = readBoundedBytes(friendlyByteBuf, MAX_FRAME_BYTES, "frame");
            ChunkHotspotFrame frame = ChunkHotspotFrameCodec.decodeFrame(frameBytes);
            byte[] originalPacketBytes = readBoundedBytes(
                    friendlyByteBuf,
                    ChannelTransportPayloadLimits.MAX_SINGLE_PACKET_BYTES,
                    "payload"
            );
            if (friendlyByteBuf.isReadable()) {
                throw new IllegalArgumentException("Chunk transport envelope left extra bytes: " + friendlyByteBuf.readableBytes());
            }
            return new ChunkTransportEnvelope(frame, originalPacketBytes);
        } finally {
            byteBuf.release();
        }
    }

    public static boolean looksLikeEnvelope(byte[] packetBytes) {
        if (packetBytes == null || packetBytes.length < MAGIC_PREFIX.length) {
            return false;
        }
        for (int i = 0; i < MAGIC_PREFIX.length; i++) {
            if (packetBytes[i] != MAGIC_PREFIX[i]) {
                return false;
            }
        }
        return true;
    }

    private static void verifyMagic(FriendlyByteBuf friendlyByteBuf) {
        if (friendlyByteBuf.readableBytes() < MAGIC_PREFIX.length) {
            throw new IllegalArgumentException("Truncated chunk transport envelope magic");
        }
        byte[] actualMagic = new byte[MAGIC_PREFIX.length];
        friendlyByteBuf.readBytes(actualMagic);
        if (!Arrays.equals(actualMagic, MAGIC_PREFIX)) {
            throw new IllegalArgumentException("Unknown chunk transport envelope magic");
        }
    }

    private static byte[] readBoundedBytes(FriendlyByteBuf buffer, int maxLength, String fieldName) {
        final int length;
        try {
            length = buffer.readVarInt();
        } catch (IndexOutOfBoundsException exception) {
            throw new IllegalArgumentException("Truncated chunk transport envelope " + fieldName + " length", exception);
        }
        if (length < 0 || length > maxLength) {
            throw new IllegalArgumentException(
                    "Invalid chunk transport envelope " + fieldName + " length: " + length + " (max " + maxLength + ")"
            );
        }
        if (length > buffer.readableBytes()) {
            throw new IllegalArgumentException(
                    "Truncated chunk transport envelope " + fieldName + ": declared " + length
                            + " bytes, only " + buffer.readableBytes() + " readable"
            );
        }
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        return bytes;
    }
}
