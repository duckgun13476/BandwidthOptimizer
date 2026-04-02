package com.PinkCats.bandwidthoptimizer.network.algorithm.zstd;

import com.github.luben.zstd.Zstd;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

final class ZstdAlgorithmSupport {

    private static final int MODE_RAW = 0;
    private static final int MODE_ZSTD = 1;

    private ZstdAlgorithmSupport() {
    }

    static byte[] compressOrStore(byte[] rawBytes, int level) {
        byte[] compressed = Zstd.compress(rawBytes, level);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            if (compressed.length >= rawBytes.length) {
                buffer.writeVarInt(MODE_RAW);
                buffer.writeBytes(rawBytes);
            } else {
                buffer.writeVarInt(MODE_ZSTD);
                buffer.writeVarInt(rawBytes.length);
                buffer.writeBytes(compressed);
            }

            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    static byte[] restore(byte[] encodedBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(encodedBytes));
        try {
            int mode = buffer.readVarInt();
            if (mode == MODE_RAW) {
                byte[] rawBytes = new byte[buffer.readableBytes()];
                buffer.readBytes(rawBytes);
                return rawBytes;
            }
            if (mode == MODE_ZSTD) {
                int rawLength = buffer.readVarInt();
                byte[] compressed = new byte[buffer.readableBytes()];
                buffer.readBytes(compressed);
                return Zstd.decompress(compressed, rawLength);
            }
            throw new IllegalArgumentException("Unknown zstd batch mode: " + mode);
        } finally {
            buffer.release();
        }
    }
}
