package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;

import com.PinkCats.bandwidthoptimizer.chunk.protocol.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.ChunkHotspotFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ChunkTransportEnvelopeCodec {

    private static final byte[] MAGIC_PREFIX = "BOCHKENV".getBytes(StandardCharsets.US_ASCII);

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
            int frameLength = friendlyByteBuf.readVarInt();
            byte[] frameBytes = new byte[frameLength];
            friendlyByteBuf.readBytes(frameBytes);
            ChunkHotspotFrame frame = ChunkHotspotFrameCodec.decodeFrame(frameBytes);
            int payloadLength = friendlyByteBuf.readVarInt();
            byte[] originalPacketBytes = new byte[payloadLength];
            friendlyByteBuf.readBytes(originalPacketBytes);
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
        byte[] actualMagic = new byte[MAGIC_PREFIX.length];
        friendlyByteBuf.readBytes(actualMagic);
        if (!Arrays.equals(actualMagic, MAGIC_PREFIX)) {
            throw new IllegalArgumentException("Unknown chunk transport envelope magic");
        }
    }
}
