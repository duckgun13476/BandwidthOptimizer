package com.PinkCats.bandwidthoptimizer.chunk.patch;

import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprintService;

import java.util.Arrays;

public final class ChunkPatchApplier {

    private ChunkPatchApplier() {}


    public static byte[] applyPatch(ChunkPatch chunkPatch, byte[] basePacketBytes) {
        if (chunkPatch == null) {
            throw new IllegalArgumentException("chunkPatch must not be null");
        }

        byte[] safeBasePacketBytes = basePacketBytes == null
                ? new byte[0]
                : Arrays.copyOf(basePacketBytes, basePacketBytes.length);
        verifyBasePacket(chunkPatch, safeBasePacketBytes);

        int prefixLength = chunkPatch.prefixLength();
        int baseReplaceLength = chunkPatch.baseReplaceLength();
        int baseSuffixStart = prefixLength + baseReplaceLength;
        int suffixLength = safeBasePacketBytes.length - baseSuffixStart;
        int expectedTargetLength = prefixLength + chunkPatch.replacementBytes().length + suffixLength;
        if (expectedTargetLength != chunkPatch.targetLength()) {
            throw new IllegalStateException(
                    "Chunk patch target length mismatch. expected="
                            + expectedTargetLength
                            + ", target="
                            + chunkPatch.targetLength()
            );
        }

        byte[] targetPacketBytes = new byte[chunkPatch.targetLength()];
        System.arraycopy(safeBasePacketBytes, 0, targetPacketBytes, 0, prefixLength);
        System.arraycopy(chunkPatch.replacementBytes(), 0, targetPacketBytes, prefixLength, chunkPatch.replacementBytes().length);
        System.arraycopy(
                safeBasePacketBytes,
                baseSuffixStart,
                targetPacketBytes,
                prefixLength + chunkPatch.replacementBytes().length,
                suffixLength
        );
        verifyTargetPacket(chunkPatch, targetPacketBytes);
        return targetPacketBytes;
    }



    private static void verifyBasePacket(ChunkPatch chunkPatch, byte[] basePacketBytes) {
        if (chunkPatch.prefixLength() + chunkPatch.baseReplaceLength() > basePacketBytes.length) {
            throw new IllegalStateException(
                    "Chunk patch base range overflow. prefix="
                            + chunkPatch.prefixLength()
                            + ", replace="
                            + chunkPatch.baseReplaceLength()
                            + ", baseLength="
                            + basePacketBytes.length
            );
        }

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



    private static void verifyTargetPacket(ChunkPatch chunkPatch, byte[] targetPacketBytes) {
        if (chunkPatch.targetPayloadHash().isBlank()) {
            return;
        }

        ChunkSnapshotFingerprint targetFingerprint = ChunkSnapshotFingerprintService.fingerprintOutboundPacket(targetPacketBytes);
        if (!chunkPatch.targetPayloadHash().equals(targetFingerprint.hashHex())) {
            throw new IllegalStateException(
                    "Chunk patch target hash mismatch. expected="
                            + chunkPatch.targetPayloadHash()
                            + ", actual="
                            + targetFingerprint.hashHex()
            );
        }
    }
}
