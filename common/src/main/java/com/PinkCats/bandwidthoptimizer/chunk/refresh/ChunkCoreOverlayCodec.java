package com.PinkCats.bandwidthoptimizer.chunk.refresh;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Exact wire-byte splicing only. The version adapter supplies validated BE
 * boundaries; no native NBT writers or additional cached packet bodies are used.
 * Not a negotiated transport operation until the caller supplies scope/index
 * ownership and FULL recovery. */
public final class ChunkCoreOverlayCodec {
    public static final int MAX_FULL_BYTES = 1024 * 1024;
    private static final int MAGIC = 0x424f4245;
    private static final int HEADER_BYTES = 69;
    private static final int MAX_FRAME_BYTES = MAX_FULL_BYTES + 79;

    private ChunkCoreOverlayCodec() {}

    /** Metadata never owns the body used to compute it. */
    public static final class Core {
        private final int prefixLength;
        private final int suffixOffset;
        private final int fullLength;
        private final byte[] hash;

        private Core(int prefixLength, int suffixOffset, int fullLength, byte[] hash) {
            this.prefixLength = prefixLength;
            this.suffixOffset = suffixOffset;
            this.fullLength = fullLength;
            this.hash = hash;
        }
        public byte[] hash() { return hash.clone(); }
    }

    public static Core inspect(byte[] full, int beStart, int beEnd) {
        requireFull(full);
        if (beStart < 1 || beEnd <= beStart || beEnd > full.length)
            throw new IllegalArgumentException("Invalid block entity boundaries");
        return new Core(beStart, beEnd, full.length, coreHash(full, beStart, beEnd));
    }

    /** Server receives the authoritative target and advertised core identity.
     * A stale identity returns no candidate, allowing the original FULL path. */
    public static byte[] encode(byte[] target, Core targetCore, byte[] advertisedCoreHash) {
        requireCurrentCore(target, targetCore);
        if (advertisedCoreHash == null || advertisedCoreHash.length != 32
                || !MessageDigest.isEqual(targetCore.hash, advertisedCoreHash)) return null;
        int beBytes = targetCore.suffixOffset - targetCore.prefixLength;
        int size = HEADER_BYTES + varSize(target.length) + varSize(beBytes) + beBytes;
        ByteBuffer out = ByteBuffer.allocate(size);
        out.putInt(MAGIC).put((byte) 1).put(targetCore.hash).put(digest(target));
        putVarInt(out, target.length);
        putVarInt(out, beBytes);
        out.put(target, targetCore.prefixLength, beBytes);
        return out.array();
    }

    /** Missing/corrupt baselines reject before publishing a native packet.
     * Caller must request FULL through the existing bounded recovery owner. */
    public static byte[] restore(byte[] frame, byte[] base, Core cachedCore) {
        requireCurrentCore(base, cachedCore);
        if (frame == null || frame.length < HEADER_BYTES + 3 || frame.length > MAX_FRAME_BYTES)
            throw new IllegalArgumentException("Invalid overlay frame length");
        ByteBuffer in = ByteBuffer.wrap(frame).asReadOnlyBuffer();
        if (in.getInt() != MAGIC || in.get() != 1) throw new IllegalArgumentException("Unsupported overlay format");
        byte[] advertisedHash = new byte[32];
        byte[] targetHash = new byte[32];
        in.get(advertisedHash).get(targetHash);
        if (!MessageDigest.isEqual(advertisedHash, cachedCore.hash))
            throw new IllegalArgumentException("Overlay core mismatch");
        int targetLength = getVarInt(in);
        int beBytes = getVarInt(in);
        long expectedLength = (long) cachedCore.prefixLength + beBytes + base.length - cachedCore.suffixOffset;
        if (targetLength < 1 || targetLength > MAX_FULL_BYTES || beBytes < 1
                || beBytes != in.remaining() || targetLength != expectedLength)
            throw new IllegalArgumentException("Invalid overlay target length");
        byte[] restored = new byte[targetLength];
        System.arraycopy(base, 0, restored, 0, cachedCore.prefixLength);
        in.get(restored, cachedCore.prefixLength, beBytes);
        System.arraycopy(base, cachedCore.suffixOffset, restored, cachedCore.prefixLength + beBytes,
                base.length - cachedCore.suffixOffset);
        if (!MessageDigest.isEqual(digest(restored), targetHash))
            throw new IllegalArgumentException("Overlay target hash mismatch");
        return restored;
    }

    private static void requireFull(byte[] full) {
        if (full == null || full.length < 1 || full.length > MAX_FULL_BYTES)
            throw new IllegalArgumentException("Invalid FULL length");
    }
    private static void requireCurrentCore(byte[] full, Core core) {
        requireFull(full);
        if (core == null || core.fullLength != full.length
                || !MessageDigest.isEqual(core.hash, coreHash(full, core.prefixLength, core.suffixOffset)))
            throw new IllegalArgumentException("Stale core metadata");
    }
    private static byte[] coreHash(byte[] full, int start, int end) {
        MessageDigest hash = sha256();
        // Include both boundaries so unequal splits cannot share an identity.
        hash.update(ByteBuffer.allocate(8).putInt(start).putInt(full.length - end).array());
        hash.update(full, 0, start);
        hash.update(full, end, full.length - end);
        return hash.digest();
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static byte[] digest(byte[] bytes) { return sha256().digest(bytes); }
    private static int varSize(int value) {
        int size = 1;
        while ((value >>>= 7) != 0) size++;
        return size;
    }
    private static void putVarInt(ByteBuffer out, int value) {
        while ((value & ~127) != 0) { out.put((byte) ((value & 127) | 128)); value >>>= 7; }
        out.put((byte) value);
    }
    private static int getVarInt(ByteBuffer in) {
        int value = 0;
        for (int index = 0; index < 5; index++) {
            if (!in.hasRemaining()) throw new IllegalArgumentException("Truncated overlay length");
            int next = in.get() & 255;
            if (index == 4 && (next & 240) != 0) throw new IllegalArgumentException("Overflow overlay length");
            value |= (next & 127) << (index * 7);
            if ((next & 128) == 0) {
                if (value < 0 || varSize(value) != index + 1)
                    throw new IllegalArgumentException("Noncanonical overlay length");
                return value;
            }
        }
        throw new IllegalArgumentException("Oversized overlay length");
    }
}
