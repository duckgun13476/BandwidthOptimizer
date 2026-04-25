package com.PinkCats.bandwidthoptimizer.chunk.patch;

import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprintService;

import java.util.Arrays;

public final class ChunkPatchApplier {

    private ChunkPatchApplier() {}


    public static byte[] applyPatch(ChunkPatch chunkPatch, byte[] basePacketBytes, String expectedTargetPayloadHash) {
        if (chunkPatch == null) {
            throw new IllegalArgumentException("chunkPatch must not be null");
        }

        byte[] safeBasePacketBytes = basePacketBytes == null
                ? new byte[0]
                : Arrays.copyOf(basePacketBytes, basePacketBytes.length);
        verifyBasePacket(chunkPatch, safeBasePacketBytes);

        byte[] targetPacketBytes = switch (chunkPatch.patchMode()) {
            case GENERIC_REPLACE -> ChunkGenericReplacePatchCodec.applyPatch(chunkPatch, safeBasePacketBytes);
            case SECTION_SAME_POSITIONS -> SectionBlocksChunkPatchCodec.applyPatch(chunkPatch, safeBasePacketBytes);
            case BLOCK_ENTITY_NBT_REPLACE -> BlockEntityDataChunkPatchCodec.applyPatch(chunkPatch, safeBasePacketBytes);
        };
        verifyTargetPacket(expectedTargetPayloadHash, targetPacketBytes);
        return targetPacketBytes;
    }

    private static void verifyBasePacket(ChunkPatch chunkPatch, byte[] basePacketBytes) {
        if (chunkPatch.basePayloadHash().isBlank()) {
            return;
        }

        ChunkSnapshotFingerprint baseFingerprint = ChunkSnapshotFingerprintService.fingerprintOutboundPacket(basePacketBytes);
        if (!chunkPatch.basePayloadHash().equals(baseFingerprint.hashHex())) {
            throw new IllegalStateException(
                    "Chunk patch base hash mismatch. expected="
                            + chunkPatch.basePayloadHash()
                            + ", actual="
                            + baseFingerprint.hashHex()
            );
        }
    }

    private static void verifyTargetPacket(String expectedTargetPayloadHash, byte[] targetPacketBytes) {
        if (expectedTargetPayloadHash == null || expectedTargetPayloadHash.isBlank()) {
            return;
        }

        ChunkSnapshotFingerprint targetFingerprint = ChunkSnapshotFingerprintService.fingerprintOutboundPacket(targetPacketBytes);
        if (!expectedTargetPayloadHash.equals(targetFingerprint.hashHex())) {
            throw new IllegalStateException(
                    "Chunk patch target hash mismatch. expected="
                            + expectedTargetPayloadHash
                            + ", actual="
                            + targetFingerprint.hashHex()
            );
        }
    }
}
