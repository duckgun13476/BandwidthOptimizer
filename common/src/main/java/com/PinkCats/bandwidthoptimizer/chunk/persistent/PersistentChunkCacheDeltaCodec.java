package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

final class PersistentChunkCacheDeltaCodec {

    private static final int MAGIC = 0x424F4431;
    private static final byte VERSION = 1;
    private static final byte MODE_XOR = 1;
    private static final byte MODE_REPLACE = 2;
    private static final int HEADER_BYTES = 18;

    private PersistentChunkCacheDeltaCodec() {}

    static List<byte[]> candidates(byte[] base, byte[] target) {
        if (base == null || base.length == 0 || target == null || target.length == 0) {
            return List.of();
        }
        byte[] xor = encodeXor(base, target);
        byte[] replacement = encodeReplacement(base, target);
        return java.util.Arrays.equals(xor, replacement) ? List.of(xor) : List.of(xor, replacement);
    }

    static byte[] decode(byte[] base, byte[] encoded, int maximumTargetBytes) throws IOException {
        if (base == null || encoded == null || encoded.length < HEADER_BYTES) {
            throw new IOException("Invalid persistent chunk delta");
        }
        ByteBuffer input = ByteBuffer.wrap(encoded);
        int magic = input.getInt();
        byte version = input.get();
        byte mode = input.get();
        int targetLength = input.getInt();
        int prefixLength = input.getInt();
        int suffixLength = input.getInt();
        if (magic != MAGIC
                || version != VERSION
                || targetLength <= 0
                || targetLength > maximumTargetBytes
                || prefixLength < 0
                || suffixLength < 0
                || prefixLength + suffixLength > targetLength
                || prefixLength + suffixLength > base.length) {
            throw new IOException("Invalid persistent chunk delta header");
        }

        byte[] target = new byte[targetLength];
        if (mode == MODE_XOR) {
            if (prefixLength != 0 || suffixLength != 0 || input.remaining() != targetLength) {
                throw new IOException("Invalid persistent chunk XOR delta");
            }
            for (int index = 0; index < targetLength; index++) {
                byte baseByte = index < base.length ? base[index] : 0;
                target[index] = (byte) (baseByte ^ input.get());
            }
            return target;
        }
        if (mode != MODE_REPLACE || input.remaining() != targetLength - prefixLength - suffixLength) {
            throw new IOException("Invalid persistent chunk replacement delta");
        }
        System.arraycopy(base, 0, target, 0, prefixLength);
        input.get(target, prefixLength, input.remaining());
        if (suffixLength > 0) {
            System.arraycopy(base, base.length - suffixLength, target, target.length - suffixLength, suffixLength);
        }
        return target;
    }

    private static byte[] encodeXor(byte[] base, byte[] target) {
        ByteBuffer output = header(MODE_XOR, target.length, 0, 0, target.length);
        for (int index = 0; index < target.length; index++) {
            byte baseByte = index < base.length ? base[index] : 0;
            output.put((byte) (baseByte ^ target[index]));
        }
        return output.array();
    }

    private static byte[] encodeReplacement(byte[] base, byte[] target) {
        int limit = Math.min(base.length, target.length);
        int prefix = 0;
        while (prefix < limit && base[prefix] == target[prefix]) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < limit - prefix
                && base[base.length - 1 - suffix] == target[target.length - 1 - suffix]) {
            suffix++;
        }
        int middleLength = target.length - prefix - suffix;
        ByteBuffer output = header(MODE_REPLACE, target.length, prefix, suffix, middleLength);
        output.put(target, prefix, middleLength);
        return output.array();
    }

    private static ByteBuffer header(byte mode, int targetLength, int prefix, int suffix, int payloadLength) {
        ByteBuffer output = ByteBuffer.allocate(HEADER_BYTES + payloadLength);
        output.putInt(MAGIC);
        output.put(VERSION);
        output.put(mode);
        output.putInt(targetLength);
        output.putInt(prefix);
        output.putInt(suffix);
        return output;
    }
}
