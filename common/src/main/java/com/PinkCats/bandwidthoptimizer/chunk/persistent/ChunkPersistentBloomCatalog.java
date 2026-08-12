package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collection;

public final class ChunkPersistentBloomCatalog {

    private static final int MAGIC = 0x424F424C;
    private static final int VERSION = 1;
    private static final int BITS_PER_ENTRY = 12;
    private static final int HASH_FUNCTIONS = 8;
    private static final int MIN_BITS = 1 << 10;
    private static final int MAX_BITS = 1 << 23;

    private final int entryCount;
    private final int bitCount;
    private final int hashFunctions;
    private final long[] words;

    private ChunkPersistentBloomCatalog(int entryCount, int bitCount, int hashFunctions, long[] words) {
        this.entryCount = Math.max(entryCount, 0);
        this.bitCount = bitCount;
        this.hashFunctions = hashFunctions;
        this.words = words;
    }

    public static ChunkPersistentBloomCatalog build(Collection<ChunkPacketCoordinate> coordinates) {
        int size = coordinates == null ? 0 : coordinates.size();
        int bitCount = normalizeBitCount(Math.max((long) size * BITS_PER_ENTRY, MIN_BITS));
        long[] words = new long[(bitCount + Long.SIZE - 1) / Long.SIZE];
        ChunkPersistentBloomCatalog catalog = new ChunkPersistentBloomCatalog(size, bitCount, HASH_FUNCTIONS, words);
        if (coordinates != null) {
            for (ChunkPacketCoordinate coordinate : coordinates) {
                catalog.add(coordinate);
            }
        }
        return catalog;
    }

    public static ChunkPersistentBloomCatalog decode(byte[] payloadBytes) {
        if (payloadBytes == null || payloadBytes.length == 0) {
            throw new IllegalArgumentException("Bloom catalog payload is empty");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payloadBytes))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
                throw new IllegalArgumentException("Unsupported persistent cache Bloom catalog");
            }
            int entryCount = input.readInt();
            int bitCount = input.readInt();
            int hashFunctions = input.readUnsignedByte();
            int wordCount = input.readInt();
            if (entryCount < 0
                    || bitCount < MIN_BITS
                    || bitCount > MAX_BITS
                    || Integer.bitCount(bitCount) != 1
                    || hashFunctions <= 0
                    || hashFunctions > 16
                    || wordCount != (bitCount + Long.SIZE - 1) / Long.SIZE) {
                throw new IllegalArgumentException("Invalid persistent cache Bloom catalog dimensions");
            }
            long[] words = new long[wordCount];
            for (int index = 0; index < wordCount; index++) {
                words[index] = input.readLong();
            }
            if (input.available() != 0) {
                throw new IllegalArgumentException("Persistent cache Bloom catalog has trailing bytes");
            }
            return new ChunkPersistentBloomCatalog(entryCount, bitCount, hashFunctions, words);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to decode persistent cache Bloom catalog", exception);
        }
    }

    public byte[] encode() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(18 + this.words.length * Long.BYTES);
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeByte(VERSION);
            output.writeInt(this.entryCount);
            output.writeInt(this.bitCount);
            output.writeByte(this.hashFunctions);
            output.writeInt(this.words.length);
            for (long word : this.words) {
                output.writeLong(word);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to encode persistent cache Bloom catalog", exception);
        }
    }

    public boolean mightContain(ChunkPacketCoordinate coordinate) {
        if (coordinate == null || !coordinate.present() || this.entryCount == 0) {
            return false;
        }
        long first = mix64(coordinateKey(coordinate));
        long second = mix64(first ^ 0x9E3779B97F4A7C15L) | 1L;
        for (int index = 0; index < this.hashFunctions; index++) {
            int bit = (int) ((first + index * second) & (this.bitCount - 1L));
            if ((this.words[bit >>> 6] & (1L << (bit & 63))) == 0L) {
                return false;
            }
        }
        return true;
    }

    public int entryCount() {
        return this.entryCount;
    }

    private void add(ChunkPacketCoordinate coordinate) {
        if (coordinate == null || !coordinate.present()) {
            return;
        }
        long first = mix64(coordinateKey(coordinate));
        long second = mix64(first ^ 0x9E3779B97F4A7C15L) | 1L;
        for (int index = 0; index < this.hashFunctions; index++) {
            int bit = (int) ((first + index * second) & (this.bitCount - 1L));
            this.words[bit >>> 6] |= 1L << (bit & 63);
        }
    }

    private static int normalizeBitCount(long requestedBits) {
        long bounded = Math.max(MIN_BITS, Math.min(requestedBits, MAX_BITS));
        int bits = MIN_BITS;
        while (bits < bounded && bits < MAX_BITS) {
            bits <<= 1;
        }
        return bits;
    }

    private static long coordinateKey(ChunkPacketCoordinate coordinate) {
        return ((long) coordinate.chunkX() << 32) ^ (coordinate.chunkZ() & 0xFFFFFFFFL);
    }

    private static long mix64(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53l;
        return value ^ (value >>> 33);
    }
}
