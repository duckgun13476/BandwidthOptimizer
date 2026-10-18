package com.PinkCats.bandwidthoptimizer.channel.algorithm;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

/** Fixed output storage: the returned array is owned by the caller, never pooled. */
public final class ExactEncoding {
    private ExactEncoding() { }

    public static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7f) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }

    public static int add(int current, int extra) {
        if (current < 0 || extra < 0 || current > Integer.MAX_VALUE - extra) {
            throw new IllegalArgumentException("Encoded length overflow");
        }
        return current + extra;
    }

    public static FriendlyByteBuf buffer(int length) {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[length]).clear());
    }

    public static byte[] finish(FriendlyByteBuf buffer) {
        if (buffer.writerIndex() != buffer.capacity()) {
            throw new IllegalStateException("Encoded length differs from calculated length");
        }
        return buffer.array();
    }
}
