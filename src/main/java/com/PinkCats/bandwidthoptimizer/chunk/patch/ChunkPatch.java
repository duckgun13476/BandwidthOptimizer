package com.PinkCats.bandwidthoptimizer.chunk.patch;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Arrays;

public record ChunkPatch(
        String semanticKey,
        String basePayloadHash,
        String targetPayloadHash,
        int targetLength,
        int prefixLength,
        int baseReplaceLength,
        byte[] replacementBytes
) {

    private static final int PATCH_CODEC_VERSION = 1;

    public ChunkPatch {
        semanticKey = semanticKey == null || semanticKey.isBlank() ? "default" : semanticKey;
        basePayloadHash = basePayloadHash == null ? "" : basePayloadHash;
        targetPayloadHash = targetPayloadHash == null ? "" : targetPayloadHash;
        targetLength = Math.max(targetLength, 0);
        prefixLength = Math.max(prefixLength, 0);
        baseReplaceLength = Math.max(baseReplaceLength, 0);
        replacementBytes = replacementBytes == null
                ? new byte[0]
                : Arrays.copyOf(replacementBytes, replacementBytes.length);
    }



    public byte[] encode() {
        ByteBuf byteBuf = Unpooled.buffer();
        try {
            FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
            friendlyByteBuf.writeVarInt(PATCH_CODEC_VERSION);
            friendlyByteBuf.writeUtf(this.semanticKey);
            friendlyByteBuf.writeUtf(this.basePayloadHash);
            friendlyByteBuf.writeUtf(this.targetPayloadHash);
            friendlyByteBuf.writeVarInt(this.targetLength);
            friendlyByteBuf.writeVarInt(this.prefixLength);
            friendlyByteBuf.writeVarInt(this.baseReplaceLength);
            friendlyByteBuf.writeVarInt(this.replacementBytes.length);
            friendlyByteBuf.writeBytes(this.replacementBytes);
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
                    friendlyByteBuf.readUtf(),
                    friendlyByteBuf.readUtf(),
                    friendlyByteBuf.readUtf(),
                    friendlyByteBuf.readVarInt(),
                    friendlyByteBuf.readVarInt(),
                    friendlyByteBuf.readVarInt(),
                    readReplacementBytes(friendlyByteBuf)
            );
            if (friendlyByteBuf.isReadable()) {
                throw new IllegalArgumentException("Chunk patch left extra bytes: " + friendlyByteBuf.readableBytes());
            }
            return chunkPatch;
        } finally {
            byteBuf.release();
        }
    }



    public byte[] copyReplacementBytes() {
        return Arrays.copyOf(this.replacementBytes, this.replacementBytes.length);
    }

    public String summaryText() {
        return "semanticKey=" + this.semanticKey
                + ", targetLength=" + this.targetLength
                + ", prefixLength=" + this.prefixLength
                + ", baseReplaceLength=" + this.baseReplaceLength
                + ", replacementBytes=" + this.replacementBytes.length
                + ", basePayloadHash=" + shortenHash(this.basePayloadHash)
                + ", targetPayloadHash=" + shortenHash(this.targetPayloadHash);
    }

    private static byte[] readReplacementBytes(FriendlyByteBuf friendlyByteBuf) {
        int replacementLength = friendlyByteBuf.readVarInt();
        byte[] replacementBytes = new byte[replacementLength];
        friendlyByteBuf.readBytes(replacementBytes);
        return replacementBytes;
    }

    private static String shortenHash(String hashHex) {
        if (hashHex == null || hashHex.isBlank()) {
            return "<none>";
        }
        return hashHex.length() <= 12 ? hashHex : hashHex.substring(0, 12);
    }
}
