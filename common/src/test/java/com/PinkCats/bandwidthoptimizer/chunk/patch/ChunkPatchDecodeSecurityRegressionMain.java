package com.PinkCats.bandwidthoptimizer.chunk.patch;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ChunkPatchDecodeSecurityRegressionMain {

    private ChunkPatchDecodeSecurityRegressionMain() {}

    public static void main(String[] args) throws Exception {
        verifyOuterLengthsRejectBeforeAllocation();
        verifyReplaceLengthsRejectBeforeAllocation();
        verifySectionCountsRejectBeforeAllocation();
        verifyCheckedRangeArithmetic();
        verifyLegalGenericPatchRoundTrip();
        System.out.println("Chunk patch decode security regression passed");
    }

    private static void verifyOuterLengthsRejectBeforeAllocation() {
        expectRejected("oversized target", () -> ChunkPatch.decode(encodedPatch(Integer.MAX_VALUE, new byte[0])));
        expectRejected("oversized payload", () -> ChunkPatch.decode(encodedPatch(1, Integer.MAX_VALUE)));
        expectRejected("oversized hash", () -> ChunkPatch.decode(encodedPatchWithHashLength(Integer.MAX_VALUE)));
    }

    private static void verifyReplaceLengthsRejectBeforeAllocation() throws Exception {
        byte[] oversizedReplacement = replacePayload(0, 0, Integer.MAX_VALUE, new byte[0]);
        expectReflectedRejection(
                ChunkGenericReplacePatchCodec.class,
                "decodePayload",
                new Class<?>[]{byte[].class, int.class},
                new Object[]{oversizedReplacement, 1}
        );
        expectReflectedRejection(
                BlockEntityDataChunkPatchCodec.class,
                "decodeReplacePayload",
                new Class<?>[]{byte[].class, int.class},
                new Object[]{oversizedReplacement, 1}
        );
    }

    private static void verifySectionCountsRejectBeforeAllocation() throws Exception {
        expectReflectedRejection(
                SectionBlocksChunkPatchCodec.class,
                "decodeDeltaPayload",
                new Class<?>[]{byte[].class, int.class},
                new Object[]{sectionDeltaPayload(0, Integer.MAX_VALUE), 1}
        );

        Method parser = SectionBlocksChunkPatchCodec.class.getDeclaredMethod("tryParsePacket", byte[].class);
        parser.setAccessible(true);
        Object parsed = parser.invoke(null, sectionPacketWithEntryCount(Integer.MAX_VALUE));
        require(parsed == null, "Oversized section entry count was accepted");
    }

    private static void verifyCheckedRangeArithmetic() {
        expectRejected(
                "generic range overflow",
                () -> ChunkGenericReplacePatchCodec.applyPatch(
                        new ChunkPatch(
                                ChunkPatchMode.GENERIC_REPLACE,
                                "security",
                                "",
                                1,
                                replacePayload(Integer.MAX_VALUE, 1, 0, new byte[0])
                        ),
                        new byte[]{1}
                )
        );
    }

    private static void verifyLegalGenericPatchRoundTrip() {
        byte[] payload = replacePayload(3, 1, 1, new byte[]{'Y'});
        ChunkPatch encoded = new ChunkPatch(ChunkPatchMode.GENERIC_REPLACE, "security", "", 7, payload);
        ChunkPatch decoded = ChunkPatch.decode(encoded.encode());
        byte[] restored = ChunkGenericReplacePatchCodec.applyPatch(
                decoded,
                "abcXdef".getBytes(StandardCharsets.US_ASCII)
        );
        require(Arrays.equals(restored, "abcYdef".getBytes(StandardCharsets.US_ASCII)),
                "Legal generic patch changed bytes");
    }

    private static byte[] encodedPatch(int targetLength, byte[] payload) {
        return writeBuffer(buffer -> {
            buffer.writeVarInt(2);
            buffer.writeVarInt(ChunkPatchMode.GENERIC_REPLACE.codecId());
            buffer.writeUtf("security");
            buffer.writeVarInt(0);
            buffer.writeVarInt(targetLength);
            buffer.writeVarInt(payload.length);
            buffer.writeBytes(payload);
        });
    }

    private static byte[] encodedPatch(int targetLength, int payloadLength) {
        return writeBuffer(buffer -> {
            buffer.writeVarInt(2);
            buffer.writeVarInt(ChunkPatchMode.GENERIC_REPLACE.codecId());
            buffer.writeUtf("security");
            buffer.writeVarInt(0);
            buffer.writeVarInt(targetLength);
            buffer.writeVarInt(payloadLength);
        });
    }

    private static byte[] encodedPatchWithHashLength(int hashLength) {
        return writeBuffer(buffer -> {
            buffer.writeVarInt(2);
            buffer.writeVarInt(ChunkPatchMode.GENERIC_REPLACE.codecId());
            buffer.writeUtf("security");
            buffer.writeVarInt(hashLength);
        });
    }

    private static byte[] replacePayload(int prefix, int baseReplace, int replacementLength, byte[] replacement) {
        return writeBuffer(buffer -> {
            buffer.writeVarInt(prefix);
            buffer.writeVarInt(baseReplace);
            buffer.writeVarInt(replacementLength);
            buffer.writeBytes(replacement);
        });
    }

    private static byte[] sectionDeltaPayload(int flags, int changedCount) {
        return writeBuffer(buffer -> {
            buffer.writeByte(flags);
            buffer.writeVarInt(changedCount);
        });
    }

    private static byte[] sectionPacketWithEntryCount(int entryCount) {
        return writeBuffer(buffer -> {
            buffer.writeVarInt(1);
            buffer.writeLong(0L);
            buffer.writeVarInt(entryCount);
        });
    }

    private static byte[] writeBuffer(BufferWriter writer) {
        ByteBuf byteBuf = Unpooled.buffer();
        try {
            FriendlyByteBuf buffer = new FriendlyByteBuf(byteBuf);
            writer.write(buffer);
            return ByteBufUtil.getBytes(byteBuf);
        } finally {
            byteBuf.release();
        }
    }

    private static void expectReflectedRejection(
            Class<?> owner,
            String methodName,
            Class<?>[] parameterTypes,
            Object[] arguments
    ) throws Exception {
        Method method = owner.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        try {
            method.invoke(null, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof OutOfMemoryError) {
                throw new AssertionError(methodName + " attempted an oversized allocation", cause);
            }
            if (cause instanceof RuntimeException) {
                return;
            }
            throw exception;
        }
        throw new AssertionError(methodName + " accepted hostile lengths");
    }

    private static void expectRejected(String label, Runnable action) {
        try {
            action.run();
        } catch (OutOfMemoryError error) {
            throw new AssertionError(label + " attempted an oversized allocation", error);
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(label + " was accepted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface BufferWriter {
        void write(FriendlyByteBuf buffer);
    }
}
