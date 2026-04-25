package com.PinkCats.bandwidthoptimizer.chunk.patch;

import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkLanePacketSnapshot;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Arrays;

public final class BlockEntityDataChunkPatchCodec {

    private BlockEntityDataChunkPatchCodec() {}

    // for less change most same big packet
    public static ChunkPatchBuilder.ChunkPatchBuildResult buildPatch(
            String semanticKey,
            ChunkLanePacketSnapshot basePacketSnapshot,
            byte[] targetPacketBytes
    ) {
        if (basePacketSnapshot == null) {
            return ChunkPatchBuilder.ChunkPatchBuildResult.unavailable("missing_base_packet");
        }
        if (targetPacketBytes == null) {
            return ChunkPatchBuilder.ChunkPatchBuildResult.unavailable("missing_target_packet");
        }

        BlockEntityPacket basePacket = tryParsePacket(basePacketSnapshot.copyOriginalPacketBytes());
        BlockEntityPacket targetPacket = tryParsePacket(targetPacketBytes);
        if (basePacket == null || targetPacket == null) {
            return ChunkPatchBuilder.ChunkPatchBuildResult.unavailable("block_entity_patch_parse_failed");
        }
        if (!canReuseBasePacket(basePacket, targetPacket)) {
            return ChunkPatchBuilder.ChunkPatchBuildResult.unavailable("block_entity_patch_base_shape_changed");
        }

        ReplacePayload replacePayload = buildReplacePayload(basePacket.nbtBytes(), targetPacket.nbtBytes());
        ChunkPatch chunkPatch = new ChunkPatch(
                ChunkPatchMode.BLOCK_ENTITY_NBT_REPLACE,
                semanticKey,
                basePacketSnapshot.payloadHash(),
                targetPacketBytes.length,
                encodeReplacePayload(replacePayload)
        );
        byte[] encodedPatchBytes = chunkPatch.encode();
        boolean beneficial = encodedPatchBytes.length < targetPacketBytes.length;
        String reason = beneficial ? "patch_smaller_than_full" : "patch_not_smaller_than_full";
        return new ChunkPatchBuilder.ChunkPatchBuildResult(chunkPatch, encodedPatchBytes, beneficial, reason);
    }


    public static byte[] applyPatch(ChunkPatch chunkPatch, byte[] basePacketBytes) {
        if (chunkPatch == null) {
            throw new IllegalArgumentException("chunkPatch must not be null");
        }

        BlockEntityPacket basePacket = tryParsePacket(basePacketBytes);
        if (basePacket == null) {
            throw new IllegalStateException("Failed to parse base block entity packet");
        }

        ReplacePayload replacePayload = decodeReplacePayload(chunkPatch.copyPatchPayloadBytes());
        byte[] targetNbtBytes = applyReplacePayload(basePacket.nbtBytes(), replacePayload);
        byte[] targetPacketBytes = encodePacket(
                new BlockEntityPacket(
                        basePacket.packetId(),
                        basePacket.blockPosLong(),
                        basePacket.typeId(),
                        targetNbtBytes
                )
        );
        if (targetPacketBytes.length != chunkPatch.targetLength()) {
            throw new IllegalStateException(
                    "Block entity patch target length mismatch. expected="
                            + chunkPatch.targetLength()
                            + ", actual="
                            + targetPacketBytes.length
            );
        }
        return targetPacketBytes;
    }

    private static boolean canReuseBasePacket(BlockEntityPacket basePacket, BlockEntityPacket targetPacket) {
        return basePacket.packetId() == targetPacket.packetId()
                && basePacket.blockPosLong() == targetPacket.blockPosLong()
                && basePacket.typeId() == targetPacket.typeId();
    }

    private static ReplacePayload buildReplacePayload(byte[] baseBytes, byte[] targetBytes) {
        byte[] safeBaseBytes = baseBytes == null ? new byte[0] : Arrays.copyOf(baseBytes, baseBytes.length);
        byte[] safeTargetBytes = targetBytes == null ? new byte[0] : Arrays.copyOf(targetBytes, targetBytes.length);
        int prefixLength = resolveCommonPrefixLength(safeBaseBytes, safeTargetBytes);
        int suffixLength = resolveCommonSuffixLength(safeBaseBytes, safeTargetBytes, prefixLength);
        int targetReplaceEndExclusive = safeTargetBytes.length - suffixLength;
        int baseReplaceLength = safeBaseBytes.length - prefixLength - suffixLength;
        byte[] replacementBytes = Arrays.copyOfRange(safeTargetBytes, prefixLength, targetReplaceEndExclusive);
        return new ReplacePayload(prefixLength, baseReplaceLength, replacementBytes);
    }

    private static byte[] applyReplacePayload(byte[] baseBytes, ReplacePayload replacePayload) {
        byte[] safeBaseBytes = baseBytes == null ? new byte[0] : Arrays.copyOf(baseBytes, baseBytes.length);
        if (replacePayload.prefixLength() + replacePayload.baseReplaceLength() > safeBaseBytes.length) {
            throw new IllegalStateException(
                    "Block entity patch base range overflow. prefix="
                            + replacePayload.prefixLength()
                            + ", replace="
                            + replacePayload.baseReplaceLength()
                            + ", baseLength="
                            + safeBaseBytes.length
            );
        }

        int baseSuffixStart = replacePayload.prefixLength() + replacePayload.baseReplaceLength();
        int suffixLength = safeBaseBytes.length - baseSuffixStart;
        byte[] targetBytes = new byte[replacePayload.prefixLength() + replacePayload.replacementBytes().length + suffixLength];
        System.arraycopy(safeBaseBytes, 0, targetBytes, 0, replacePayload.prefixLength());
        System.arraycopy(
                replacePayload.replacementBytes(),
                0,
                targetBytes,
                replacePayload.prefixLength(),
                replacePayload.replacementBytes().length
        );
        System.arraycopy(
                safeBaseBytes,
                baseSuffixStart,
                targetBytes,
                replacePayload.prefixLength() + replacePayload.replacementBytes().length,
                suffixLength
        );
        return targetBytes;
    }

    private static byte[] encodeReplacePayload(ReplacePayload replacePayload) {
        ByteBuf byteBuf = Unpooled.buffer();
        try {
            FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
            friendlyByteBuf.writeVarInt(Math.max(replacePayload.prefixLength(), 0));
            friendlyByteBuf.writeVarInt(Math.max(replacePayload.baseReplaceLength(), 0));
            friendlyByteBuf.writeVarInt(replacePayload.replacementBytes().length);
            friendlyByteBuf.writeBytes(replacePayload.replacementBytes());
            return ByteBufUtil.getBytes(byteBuf);
        } finally {
            byteBuf.release();
        }
    }

    private static ReplacePayload decodeReplacePayload(byte[] payloadBytes) {
        ByteBuf byteBuf = Unpooled.wrappedBuffer(payloadBytes == null ? new byte[0] : payloadBytes);
        try {
            FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
            int prefixLength = friendlyByteBuf.readVarInt();
            int baseReplaceLength = friendlyByteBuf.readVarInt();
            int replacementLength = friendlyByteBuf.readVarInt();
            byte[] replacementBytes = new byte[replacementLength];
            friendlyByteBuf.readBytes(replacementBytes);
            if (friendlyByteBuf.isReadable()) {
                throw new IllegalStateException("Block entity patch left extra bytes: " + friendlyByteBuf.readableBytes());
            }
            return new ReplacePayload(prefixLength, baseReplaceLength, replacementBytes);
        } finally {
            byteBuf.release();
        }
    }

    private static BlockEntityPacket tryParsePacket(byte[] packetBytes) {
        if (packetBytes == null) {
            return null;
        }

        ByteBuf byteBuf = Unpooled.wrappedBuffer(packetBytes);
        try {
            FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
            int packetId = friendlyByteBuf.readVarInt();
            long blockPosLong = friendlyByteBuf.readBlockPos().asLong();
            int typeId = friendlyByteBuf.readVarInt();
            int nbtStartIndex = friendlyByteBuf.readerIndex();
            Tag ignored = friendlyByteBuf.readNbt();
            if (ignored == null && nbtStartIndex == packetBytes.length) {
                return null;
            }
            if (friendlyByteBuf.isReadable()) {
                return null;
            }
            byte[] nbtBytes = Arrays.copyOfRange(packetBytes, nbtStartIndex, packetBytes.length);
            return new BlockEntityPacket(packetId, blockPosLong, typeId, nbtBytes);
        } catch (RuntimeException exception) {
            return null;
        } finally {
            byteBuf.release();
        }
    }

    private static byte[] encodePacket(BlockEntityPacket blockEntityPacket) {
        ByteBuf byteBuf = Unpooled.buffer();
        try {
            FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
            friendlyByteBuf.writeVarInt(blockEntityPacket.packetId());
            friendlyByteBuf.writeBlockPos(BlockPos.of(blockEntityPacket.blockPosLong()));
            friendlyByteBuf.writeVarInt(blockEntityPacket.typeId());
            friendlyByteBuf.writeBytes(blockEntityPacket.nbtBytes());
            return ByteBufUtil.getBytes(byteBuf);
        } finally {
            byteBuf.release();
        }
    }

    private static int resolveCommonPrefixLength(byte[] baseBytes, byte[] targetBytes) {
        int maxCommonLength = Math.min(baseBytes.length, targetBytes.length);
        int prefixLength = 0;
        while (prefixLength < maxCommonLength && baseBytes[prefixLength] == targetBytes[prefixLength]) {
            prefixLength++;
        }
        return prefixLength;
    }

    private static int resolveCommonSuffixLength(byte[] baseBytes, byte[] targetBytes, int prefixLength) {
        int baseRemainingLength = baseBytes.length - prefixLength;
        int targetRemainingLength = targetBytes.length - prefixLength;
        int maxCommonLength = Math.min(baseRemainingLength, targetRemainingLength);
        int suffixLength = 0;
        while (suffixLength < maxCommonLength) {
            int baseIndex = baseBytes.length - 1 - suffixLength;
            int targetIndex = targetBytes.length - 1 - suffixLength;
            if (baseBytes[baseIndex] != targetBytes[targetIndex]) {
                break;
            }
            suffixLength++;
        }
        return suffixLength;
    }

    private record BlockEntityPacket(
            int packetId,
            long blockPosLong,
            int typeId,
            byte[] nbtBytes
    ) {

        private BlockEntityPacket {
            nbtBytes = nbtBytes == null ? new byte[0] : Arrays.copyOf(nbtBytes, nbtBytes.length);
        }
    }

    private record ReplacePayload(
            int prefixLength,
            int baseReplaceLength,
            byte[] replacementBytes
    ) {

        private ReplacePayload {
            prefixLength = Math.max(prefixLength, 0);
            baseReplaceLength = Math.max(baseReplaceLength, 0);
            replacementBytes = replacementBytes == null ? new byte[0] : Arrays.copyOf(replacementBytes, replacementBytes.length);
        }
    }
}
