package com.PinkCats.bandwidthoptimizer.chunk.patch;

import com.PinkCats.bandwidthoptimizer.chunk.snapshot.lane.ChunkLanePacketSnapshot;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Arrays;

public final class SectionBlocksChunkPatchCodec {

    private static final int FLAG_UNIFORM_STATE = 1;
    private static final int FLAG_APPLY_ALL_ENTRIES = 1 << 1;
    private static final int SECTION_POSITION_MASK = 0x0FFF;
    private static final int SECTION_POSITION_SHIFT = 12;

    private SectionBlocksChunkPatchCodec() {}

    // one section same pos packet
    public static ChunkPatchBuilder.ChunkPatchBuildResult buildPatch(
            String semanticKey,
            ChunkLanePacketSnapshot basePacketSnapshot,
            byte[] targetPacketBytes
    ) {
        if (basePacketSnapshot == null) {
            return ChunkPatchBuilder.ChunkPatchBuildResult.unavailable("missing_base_packet");
        }
        if (!basePacketSnapshot.hasOriginalPacketBytes()) {
            return ChunkPatchBuilder.ChunkPatchBuildResult.unavailable("base_packet_bytes_evicted");
        }
        if (targetPacketBytes == null) {
            return ChunkPatchBuilder.ChunkPatchBuildResult.unavailable("missing_target_packet");
        }

        SectionPacket basePacket = tryParsePacket(basePacketSnapshot.copyOriginalPacketBytes());
        SectionPacket targetPacket = tryParsePacket(targetPacketBytes);
        if (basePacket == null || targetPacket == null) {
            return ChunkPatchBuilder.ChunkPatchBuildResult.unavailable("section_patch_parse_failed");
        }
        if (!canReuseBaseSection(basePacket, targetPacket)) {
            return ChunkPatchBuilder.ChunkPatchBuildResult.unavailable("section_patch_base_shape_changed");
        }

        SectionStateDelta delta = buildStateDelta(basePacket, targetPacket);
        ChunkPatch chunkPatch = new ChunkPatch(
                ChunkPatchMode.SECTION_SAME_POSITIONS,
                semanticKey,
                basePacketSnapshot.payloadHash(),
                targetPacketBytes.length,
                encodeDeltaPayload(delta)
        );
        byte[] encodedPatchBytes = chunkPatch.encode();
        boolean beneficial = encodedPatchBytes.length < targetPacketBytes.length;
        String reason = beneficial ? "patch_smaller_than_full" : "patch_not_smaller_than_full";
        return new ChunkPatchBuilder.ChunkPatchBuildResult(chunkPatch, encodedPatchBytes, beneficial, reason);
    }

    // only block state packet
    public static byte[] applyPatch(ChunkPatch chunkPatch, byte[] basePacketBytes) {
        if (chunkPatch == null) {
            throw new IllegalArgumentException("chunkPatch must not be null");
        }

        SectionPacket basePacket = tryParsePacket(basePacketBytes);
        if (basePacket == null) {
            throw new IllegalStateException("Failed to parse base section packet");
        }

        SectionStateDelta delta = decodeDeltaPayload(chunkPatch.copyPatchPayloadBytes(), basePacket.stateIds.length);
        int[] targetStateIds = Arrays.copyOf(basePacket.stateIds, basePacket.stateIds.length);
        if (delta.applyAllEntries()) {
            applyFullStateReplacement(targetStateIds, delta);
        } else {
            applyPartialStateReplacement(targetStateIds, delta);
        }

        byte[] targetPacketBytes = encodePacket(
                new SectionPacket(
                        basePacket.packetId,
                        basePacket.sectionPosLong,
                        Arrays.copyOf(basePacket.positions, basePacket.positions.length),
                        targetStateIds
                )
        );
        if (targetPacketBytes.length != chunkPatch.targetLength()) {
            throw new IllegalStateException(
                    "Section patch target length mismatch. expected="
                            + chunkPatch.targetLength()
                            + ", actual="
                            + targetPacketBytes.length
            );
        }
        return targetPacketBytes;
    }

    private static boolean canReuseBaseSection(SectionPacket basePacket, SectionPacket targetPacket) {
        return basePacket.packetId == targetPacket.packetId
                && basePacket.sectionPosLong == targetPacket.sectionPosLong
                && Arrays.equals(basePacket.positions, targetPacket.positions)
                && basePacket.stateIds.length == targetPacket.stateIds.length;
    }

    private static SectionStateDelta buildStateDelta(SectionPacket basePacket, SectionPacket targetPacket) {
        int[] changedIndices = new int[targetPacket.stateIds.length];
        int[] changedStateIds = new int[targetPacket.stateIds.length];
        int changedCount = 0;
        boolean uniformState = true;
        int uniformStateId = -1;

        for (int index = 0; index < targetPacket.stateIds.length; index++) {
            int targetStateId = targetPacket.stateIds[index];
            if (basePacket.stateIds[index] == targetStateId) {
                continue;
            }

            changedIndices[changedCount] = index;
            changedStateIds[changedCount] = targetStateId;
            if (uniformStateId < 0) {
                uniformStateId = targetStateId;
            } else if (uniformStateId != targetStateId) {
                uniformState = false;
            }
            changedCount++;
        }

        int[] trimmedIndices = Arrays.copyOf(changedIndices, changedCount);
        int[] trimmedStateIds = Arrays.copyOf(changedStateIds, changedCount);
        boolean applyAllEntries = changedCount == targetPacket.stateIds.length && targetPacket.stateIds.length > 0;
        return new SectionStateDelta(
                uniformState && changedCount > 0,
                applyAllEntries,
                trimmedIndices,
                trimmedStateIds,
                uniformStateId
        );
    }

    private static byte[] encodeDeltaPayload(SectionStateDelta delta) {
        ByteBuf byteBuf = Unpooled.buffer();
        try {
            FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
            int flags = 0;
            if (delta.uniformState()) {
                flags |= FLAG_UNIFORM_STATE;
            }
            if (delta.applyAllEntries()) {
                flags |= FLAG_APPLY_ALL_ENTRIES;
            }
            friendlyByteBuf.writeByte(flags);

            if (delta.uniformState()) {
                friendlyByteBuf.writeVarInt(Math.max(delta.uniformStateId(), 0));
            }

            if (!delta.applyAllEntries()) {
                friendlyByteBuf.writeVarInt(delta.changedIndices().length);
                writeDeltaEncodedIndices(friendlyByteBuf, delta.changedIndices());
            }

            if (!delta.uniformState()) {
                int updateCount = delta.applyAllEntries()
                        ? delta.changedStateIds().length
                        : delta.changedIndices().length;
                for (int index = 0; index < updateCount; index++) {
                    friendlyByteBuf.writeVarInt(Math.max(delta.changedStateIds()[index], 0));
                }
            }

            return ByteBufUtil.getBytes(byteBuf);
        } finally {
            byteBuf.release();
        }
    }

    private static SectionStateDelta decodeDeltaPayload(byte[] payloadBytes, int baseEntryCount) {
        ByteBuf byteBuf = Unpooled.wrappedBuffer(payloadBytes == null ? new byte[0] : payloadBytes);
        try {
            FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
            int flags = friendlyByteBuf.readUnsignedByte();
            boolean uniformState = (flags & FLAG_UNIFORM_STATE) != 0;
            boolean applyAllEntries = (flags & FLAG_APPLY_ALL_ENTRIES) != 0;
            int uniformStateId = uniformState ? friendlyByteBuf.readVarInt() : -1;

            int[] changedIndices;
            if (applyAllEntries) {
                changedIndices = new int[Math.max(baseEntryCount, 0)];
                for (int index = 0; index < changedIndices.length; index++) {
                    changedIndices[index] = index;
                }
            } else {
                int changedCount = friendlyByteBuf.readVarInt();
                changedIndices = readDeltaEncodedIndices(friendlyByteBuf, changedCount, baseEntryCount);
            }

            int[] changedStateIds = new int[changedIndices.length];
            if (uniformState) {
                Arrays.fill(changedStateIds, uniformStateId);
            } else {
                for (int index = 0; index < changedStateIds.length; index++) {
                    changedStateIds[index] = friendlyByteBuf.readVarInt();
                }
            }

            if (friendlyByteBuf.isReadable()) {
                throw new IllegalStateException("Section patch left extra bytes: " + friendlyByteBuf.readableBytes());
            }
            return new SectionStateDelta(
                    uniformState,
                    applyAllEntries,
                    changedIndices,
                    changedStateIds,
                    uniformStateId
            );
        } finally {
            byteBuf.release();
        }
    }

    private static void writeDeltaEncodedIndices(FriendlyByteBuf friendlyByteBuf, int[] changedIndices) {
        int previousIndex = -1;
        for (int changedIndex : changedIndices) {
            friendlyByteBuf.writeVarInt(changedIndex - previousIndex - 1);
            previousIndex = changedIndex;
        }
    }

    private static int[] readDeltaEncodedIndices(FriendlyByteBuf friendlyByteBuf, int changedCount, int baseEntryCount) {
        int[] changedIndices = new int[Math.max(changedCount, 0)];
        int previousIndex = -1;
        for (int index = 0; index < changedIndices.length; index++) {
            int deltaIndex = friendlyByteBuf.readVarInt();
            int resolvedIndex = previousIndex + deltaIndex + 1;
            if (resolvedIndex < 0 || resolvedIndex >= baseEntryCount) {
                throw new IllegalStateException(
                        "Section patch index overflow. index=" + resolvedIndex + ", baseEntryCount=" + baseEntryCount
                );
            }
            changedIndices[index] = resolvedIndex;
            previousIndex = resolvedIndex;
        }
        return changedIndices;
    }

    private static void applyFullStateReplacement(int[] targetStateIds, SectionStateDelta delta) {
        if (delta.uniformState()) {
            Arrays.fill(targetStateIds, delta.uniformStateId());
            return;
        }

        if (delta.changedStateIds().length != targetStateIds.length) {
            throw new IllegalStateException(
                    "Section patch full replacement size mismatch. expected="
                            + targetStateIds.length
                            + ", actual="
                            + delta.changedStateIds().length
            );
        }
        System.arraycopy(delta.changedStateIds(), 0, targetStateIds, 0, targetStateIds.length);
    }

    private static void applyPartialStateReplacement(int[] targetStateIds, SectionStateDelta delta) {
        for (int index = 0; index < delta.changedIndices().length; index++) {
            int changedIndex = delta.changedIndices()[index];
            if (changedIndex < 0 || changedIndex >= targetStateIds.length) {
                throw new IllegalStateException(
                        "Section patch partial index overflow. index="
                                + changedIndex
                                + ", targetLength="
                                + targetStateIds.length
                );
            }
            targetStateIds[changedIndex] = delta.changedStateIds()[index];
        }
    }

    private static SectionPacket tryParsePacket(byte[] packetBytes) {
        if (packetBytes == null) {
            return null;
        }

        ByteBuf byteBuf = Unpooled.wrappedBuffer(packetBytes);
        try {
            FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
            int packetId = friendlyByteBuf.readVarInt();
            long sectionPosLong = friendlyByteBuf.readLong();
            int entryCount = friendlyByteBuf.readVarInt();
            short[] positions = new short[Math.max(entryCount, 0)];
            int[] stateIds = new int[Math.max(entryCount, 0)];
            for (int index = 0; index < entryCount; index++) {
                long packedState = friendlyByteBuf.readVarLong();
                positions[index] = (short) (packedState & SECTION_POSITION_MASK);
                stateIds[index] = (int) (packedState >>> SECTION_POSITION_SHIFT);
            }
            if (friendlyByteBuf.isReadable()) {
                return null;
            }
            return new SectionPacket(packetId, sectionPosLong, positions, stateIds);
        } catch (RuntimeException exception) {
            return null;
        } finally {
            byteBuf.release();
        }
    }

    private static byte[] encodePacket(SectionPacket sectionPacket) {
        ByteBuf byteBuf = Unpooled.buffer();
        try {
            FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
            friendlyByteBuf.writeVarInt(sectionPacket.packetId);
            friendlyByteBuf.writeLong(sectionPacket.sectionPosLong);
            friendlyByteBuf.writeVarInt(sectionPacket.positions.length);
            for (int index = 0; index < sectionPacket.positions.length; index++) {
                long packedState = ((long) sectionPacket.stateIds[index] << SECTION_POSITION_SHIFT)
                        | (sectionPacket.positions[index] & SECTION_POSITION_MASK);
                friendlyByteBuf.writeVarLong(packedState);
            }
            return ByteBufUtil.getBytes(byteBuf);
        } finally {
            byteBuf.release();
        }
    }

    private record SectionPacket(
            int packetId,
            long sectionPosLong,
            short[] positions,
            int[] stateIds
    ) {

        private SectionPacket {
            positions = positions == null ? new short[0] : Arrays.copyOf(positions, positions.length);
            stateIds = stateIds == null ? new int[0] : Arrays.copyOf(stateIds, stateIds.length);
        }
    }

    private record SectionStateDelta(
            boolean uniformState,
            boolean applyAllEntries,
            int[] changedIndices,
            int[] changedStateIds,
            int uniformStateId
    ) {

        private SectionStateDelta {
            changedIndices = changedIndices == null ? new int[0] : Arrays.copyOf(changedIndices, changedIndices.length);
            changedStateIds = changedStateIds == null ? new int[0] : Arrays.copyOf(changedStateIds, changedStateIds.length);
        }
    }
}
