package com.PinkCats.bandwidthoptimizer.chunk.patch;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Arrays;
import java.util.HexFormat;

public record ChunkPatch(
        ChunkPatchMode patchMode,
        String semanticKey,
        String basePayloadHash,
        int targetLength,
        byte[] patchPayloadBytes
) {

    private static final int PATCH_CODEC_VERSION = 2;

    public ChunkPatch {
        patchMode = patchMode == null ? ChunkPatchMode.GENERIC_REPLACE : patchMode;
        semanticKey = semanticKey == null || semanticKey.isBlank() ? "default" : semanticKey;
        basePayloadHash = basePayloadHash == null ? "" : basePayloadHash;
        targetLength = Math.max(targetLength, 0);
        patchPayloadBytes = patchPayloadBytes == null
                ? new byte[0]
                : Arrays.copyOf(patchPayloadBytes, patchPayloadBytes.length);
    }

    public byte[] encode() {
        ByteBuf byteBuf = Unpooled.buffer();
        try {
            FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
            friendlyByteBuf.writeVarInt(PATCH_CODEC_VERSION);
            friendlyByteBuf.writeVarInt(this.patchMode.codecId());
            friendlyByteBuf.writeUtf(this.semanticKey);
            writeHashBytes(friendlyByteBuf, this.basePayloadHash);
            friendlyByteBuf.writeVarInt(this.targetLength);
            friendlyByteBuf.writeVarInt(this.patchPayloadBytes.length);
            friendlyByteBuf.writeBytes(this.patchPayloadBytes);
            return ByteBufUtil.getBytes(byteBuf);
        } finally {
            byteBuf.release();
        }
    }


    public static ChunkPatch decode(byte[] encodedPatchBytes) {
        if (encodedPatchBytes == null) {
            throw new IllegalArgumentException("encodedPatchBytes must not be null");
        }

        ByteBuf byteBuf = Unpooled.wrappedBuffer(encodedPatchBytes);
        try {
            FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
            int codecVersion = friendlyByteBuf.readVarInt();
            if (codecVersion != PATCH_CODEC_VERSION) {
                throw new IllegalArgumentException("Unsupported chunk patch codec version: " + codecVersion);
            }

            ChunkPatch chunkPatch = new ChunkPatch(
                    ChunkPatchMode.fromCodecId(friendlyByteBuf.readVarInt()),
                    friendlyByteBuf.readUtf(),
                    readHashHex(friendlyByteBuf),
                    friendlyByteBuf.readVarInt(),
                    readPatchPayloadBytes(friendlyByteBuf)
            );
            if (friendlyByteBuf.isReadable()) {
                throw new IllegalArgumentException("Chunk patch left extra bytes: " + friendlyByteBuf.readableBytes());
            }
            return chunkPatch;
        } finally {
            byteBuf.release();
        }
    }

    public byte[] copyPatchPayloadBytes() {
        return Arrays.copyOf(this.patchPayloadBytes, this.patchPayloadBytes.length);
    }

    public String summaryText() {
        return "mode=" + this.patchMode.logName()
                + ", semanticKey=" + this.semanticKey
                + ", targetLength=" + this.targetLength
                + ", payloadBytes=" + this.patchPayloadBytes.length
                + ", basePayloadHash=" + shortenHash(this.basePayloadHash);
    }

    private static void writeHashBytes(FriendlyByteBuf friendlyByteBuf, String hashHex) {
        byte[] hashBytes = decodeHashHex(hashHex);
        friendlyByteBuf.writeVarInt(hashBytes.length);
        friendlyByteBuf.writeBytes(hashBytes);
    }

    private static String readHashHex(FriendlyByteBuf friendlyByteBuf) {
        int hashLength = friendlyByteBuf.readVarInt();
        if (hashLength <= 0) {
            return "";
        }

        byte[] hashBytes = new byte[hashLength];
        friendlyByteBuf.readBytes(hashBytes);
        return HexFormat.of().formatHex(hashBytes);
    }

    private static byte[] readPatchPayloadBytes(FriendlyByteBuf friendlyByteBuf) {
        int payloadLength = friendlyByteBuf.readVarInt();
        byte[] payloadBytes = new byte[payloadLength];
        friendlyByteBuf.readBytes(payloadBytes);
        return payloadBytes;
    }

    private static byte[] decodeHashHex(String hashHex) {
        if (hashHex == null || hashHex.isBlank()) {
            return new byte[0];
        }
        return HexFormat.of().parseHex(hashHex);
    }

    private static String shortenHash(String hashHex) {
        if (hashHex == null || hashHex.isBlank()) {
            return "<none>";
        }
        return hashHex.length() <= 12 ? hashHex : hashHex.substring(0, 12);
    }
}
