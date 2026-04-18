package com.PinkCats.bandwidthoptimizer.Old.network.payload;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Arrays;

public final class BlockEntityPayloadSupport {

    private static final int ENTRY_LITERAL = 0;
    private static final int ENTRY_BLOCK_ENTITY = 1;

    private BlockEntityPayloadSupport() {
    }

    public static ParsedBlockEntityPayload tryParse(byte[] payloadBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payloadBytes));
        try {
            BlockPos blockPos = buffer.readBlockPos();
            int typeId = buffer.readVarInt();
            int nbtStart = buffer.readerIndex();
            CompoundTag ignored = buffer.readNbt();
            if (buffer.readableBytes() != 0) {
                return ParsedBlockEntityPayload.unparsed();
            }
            byte[] nbtBytes = Arrays.copyOfRange(payloadBytes, nbtStart, payloadBytes.length);
            return new ParsedBlockEntityPayload(true, blockPos.asLong(), typeId, nbtBytes);
        } catch (Exception ignored) {
            return ParsedBlockEntityPayload.unparsed();
        } finally {
            buffer.release();
        }
    }

    public static byte[] toCanonical(byte[] payloadBytes) {
        ParsedBlockEntityPayload parsed = tryParse(payloadBytes);
        if (!parsed.parsed()) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeVarInt(ENTRY_LITERAL);
                buffer.writeBytes(payloadBytes);
                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.getBytes(0, bytes);
                return bytes;
            } finally {
                buffer.release();
            }
        }

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(ENTRY_BLOCK_ENTITY);
            buffer.writeVarInt(parsed.typeId());
            buffer.writeVarInt(parsed.nbtBytes().length);
            buffer.writeBytes(parsed.nbtBytes());
            buffer.writeLong(parsed.blockPosLong());
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    public static byte[] fromCanonical(byte[] canonicalBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(canonicalBytes));
        try {
            int entryType = buffer.readVarInt();
            if (entryType == ENTRY_LITERAL) {
                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.readBytes(bytes);
                return bytes;
            }
            if (entryType != ENTRY_BLOCK_ENTITY) {
                throw new IllegalArgumentException("Unknown block entity canonical entry type: " + entryType);
            }

            int typeId = buffer.readVarInt();
            int nbtLength = buffer.readVarInt();
            byte[] nbtBytes = new byte[nbtLength];
            buffer.readBytes(nbtBytes);
            long blockPosLong = buffer.readLong();
            if (buffer.readableBytes() != 0) {
                throw new IllegalArgumentException("Unexpected trailing bytes in block entity canonical entry");
            }

            FriendlyByteBuf restored = new FriendlyByteBuf(Unpooled.buffer());
            try {
                restored.writeBlockPos(BlockPos.of(blockPosLong));
                restored.writeVarInt(typeId);
                restored.writeBytes(nbtBytes);
                byte[] bytes = new byte[restored.readableBytes()];
                restored.getBytes(0, bytes);
                return bytes;
            } finally {
                restored.release();
            }
        } finally {
            buffer.release();
        }
    }

    public record ParsedBlockEntityPayload(
            boolean parsed,
            long blockPosLong,
            int typeId,
            byte[] nbtBytes
    ) {
        private static ParsedBlockEntityPayload unparsed() {
            return new ParsedBlockEntityPayload(false, 0L, -1, new byte[0]);
        }
    }
}
