package com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.TransportLayer;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;


public final class KineticStreamingLayer implements TransportLayer {

    private static final int DIRECT_BUFFER_BYTES = 64 * 1024;

    private final int compressionLevel;
    private final ZstdRuntimeBridge.Context zstdContext;
    private byte[] pendingDecodedBytes = new byte[0];

    public KineticStreamingLayer(int compressionLevel) {
        this.compressionLevel = compressionLevel;
        this.zstdContext = ZstdRuntimeBridge.createContext(compressionLevel);
    }

    @Override
    public byte[] encode(byte[] inputBytes) {
        byte[] safeInputBytes = copyBytesOrEmpty(inputBytes);
        byte[] framedPacketBytes = ChannelStreamingPacketCodec.encodeFramedPacket(safeInputBytes);
        return compressStreaming(framedPacketBytes);
    }

    @Override
    public byte[] decode(byte[] inputBytes) {
        byte[] safeInputBytes = copyBytesOrEmpty(inputBytes);
        appendDecodedBytes(decompressStreaming(safeInputBytes));
        byte[] nextPacketBatch = extractNextPacketBatch();
        if (nextPacketBatch == null) {
            throw new IllegalStateException("Channel streaming zstd decode produced no complete packet frame");
        }
        rejectTrailingDecodedBytesAfterFrame();
        return ChannelStreamingPacketCodec.decodeSinglePacketBatch(nextPacketBatch);
    }

    @Override
    public void reset() {
        this.zstdContext.reset(this.compressionLevel);
        this.pendingDecodedBytes = new byte[0];
    }

    // This function compresses one framed packet batch into a complete zstd stream chunk.
    private byte[] compressStreaming(byte[] inputBytes) {
        ByteBuffer sourceBuffer = ByteBuffer.allocateDirect(inputBytes.length);
        sourceBuffer.put(inputBytes);
        sourceBuffer.flip();

        ByteBuffer targetBuffer = ByteBuffer.allocateDirect(DIRECT_BUFFER_BYTES);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        while (sourceBuffer.hasRemaining()) {
            targetBuffer.clear();
            boolean flushed = this.zstdContext.compressDirectByteBufferStream(targetBuffer, sourceBuffer, false);
            writeBuffer(output, targetBuffer);
            if (flushed && !sourceBuffer.hasRemaining()) {
                break;
            }
        }

        while (true) {
            targetBuffer.clear();
            boolean flushed = this.zstdContext.compressDirectByteBufferStream(targetBuffer, sourceBuffer, true);
            writeBuffer(output, targetBuffer);
            if (flushed) {
                break;
            }
        }

        return output.toByteArray();
    }

    // This function inflates one transport body into newly produced clear-text bytes.
    private byte[] decompressStreaming(byte[] inputBytes) {
        ByteBuffer sourceBuffer = ByteBuffer.allocateDirect(inputBytes.length);
        sourceBuffer.put(inputBytes);
        sourceBuffer.flip();

        ByteBuffer targetBuffer = ByteBuffer.allocateDirect(DIRECT_BUFFER_BYTES);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        while (sourceBuffer.hasRemaining()) {
            targetBuffer.clear();
            this.zstdContext.decompressDirectByteBufferStream(targetBuffer, sourceBuffer);
            writeBuffer(output, targetBuffer);
            ensureDecodedSizeWithinLimit(output);
        }

        return output.toByteArray();
    }

    // This function appends the latest decoded clear-text bytes into the pending buffer.
    private void appendDecodedBytes(byte[] decodedBytes) {
        if (decodedBytes.length == 0) {
            return;
        }
        if (decodedBytes.length > ChannelStreamingPacketCodec.MAX_DECODED_PACKET_BATCH_BYTES
                || this.pendingDecodedBytes.length + decodedBytes.length > ChannelStreamingPacketCodec.MAX_DECODED_PACKET_BATCH_BYTES) {
            throw new IllegalStateException("Channel streaming pending decoded bytes out of range");
        }

        byte[] mergedBytes = Arrays.copyOf(this.pendingDecodedBytes, this.pendingDecodedBytes.length + decodedBytes.length);
        System.arraycopy(decodedBytes, 0, mergedBytes, this.pendingDecodedBytes.length, decodedBytes.length);
        this.pendingDecodedBytes = mergedBytes;
    }

    // This function tries to cut the next complete packet batch from the pending clear-text buffer.
    private byte[] extractNextPacketBatch() {
        if (this.pendingDecodedBytes.length == 0) {
            return null;
        }

        ChannelStreamingPacketCodec.VarIntRead lengthRead = ChannelStreamingPacketCodec.tryReadVarInt(this.pendingDecodedBytes);
        if (lengthRead == null) {
            return null;
        }

        int packetBatchLength = lengthRead.value();
        int packetBatchStart = lengthRead.nextIndex();
        int packetBatchEnd = packetBatchStart + packetBatchLength;
        if (packetBatchLength < 0 || packetBatchLength > ChannelStreamingPacketCodec.MAX_DECODED_PACKET_BATCH_BYTES) {
            throw new IllegalStateException("Channel streaming packet batch length out of range: " + packetBatchLength);
        }
        if (packetBatchEnd > this.pendingDecodedBytes.length) {
            return null;
        }

        byte[] packetBatchBytes = Arrays.copyOfRange(this.pendingDecodedBytes, packetBatchStart, packetBatchEnd);
        this.pendingDecodedBytes = Arrays.copyOfRange(this.pendingDecodedBytes, packetBatchEnd, this.pendingDecodedBytes.length);
        return packetBatchBytes;
    }

    // One transport body may deliver only one clear-text frame.
    private void rejectTrailingDecodedBytesAfterFrame() {
        int trailingBytes = this.pendingDecodedBytes.length;
        if (trailingBytes <= 0) {
            return;
        }

        this.pendingDecodedBytes = new byte[0];
        throw new IllegalStateException("Channel streaming frame left trailing decoded bytes: " + trailingBytes);
    }


    private static void ensureDecodedSizeWithinLimit(ByteArrayOutputStream output) {
        if (output.size() > ChannelStreamingPacketCodec.MAX_DECODED_PACKET_BATCH_BYTES) {
            throw new IllegalStateException("Channel streaming decoded bytes out of range: " + output.size());
        }
    }

    // This function copies the written part of a direct ByteBuffer into the output stream.
    private static void writeBuffer(ByteArrayOutputStream output, ByteBuffer buffer) {
        int writtenBytes = buffer.position();
        if (writtenBytes <= 0) {
            return;
        }

        byte[] chunkBytes = new byte[writtenBytes];
        buffer.flip();
        buffer.get(chunkBytes);
        output.write(chunkBytes, 0, chunkBytes.length);
    }

    // This function turns a nullable byte array into a private immutable working copy.
    private static byte[] copyBytesOrEmpty(byte[] sourceBytes) {
        return sourceBytes == null ? new byte[0] : Arrays.copyOf(sourceBytes, sourceBytes.length);
    }
}
