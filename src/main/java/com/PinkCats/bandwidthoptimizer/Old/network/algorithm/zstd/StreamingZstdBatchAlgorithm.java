package com.PinkCats.bandwidthoptimizer.Old.network.algorithm.zstd;

import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithm;
import com.PinkCats.bandwidthoptimizer.Old.network.algorithm.BatchAlgorithmSupport;
import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Deprecated(forRemoval = false)
public final class StreamingZstdBatchAlgorithm implements BatchAlgorithm {

    private static final int DIRECT_BUFFER_BYTES = 64 * 1024;

    @Override
    public String id() {
        return "streaming_zstd";
    }

    @Override
    public Session createSession() {
        return new StreamingSession();
    }

    private static final class StreamingSession implements Session {
        private final ZstdCompressCtx compressCtx = new ZstdCompressCtx().setLevel(StreamingZstdBatchAlgorithmModule.level());
        private final ZstdDecompressCtx decompressCtx = new ZstdDecompressCtx();
        private byte[] pendingDecoded = new byte[0];

        @Override
        public EncodedBatch encode(List<byte[]> payloads) {
            byte[] rawBatch = encodeRawPayloads(payloads);
            byte[] framedBatch = frame(rawBatch);
            byte[] compressed = compressStreaming(framedBatch);

            List<EntryInfo> entryInfos = new ArrayList<>(payloads.size());
            for (int i = 0; i < payloads.size(); i++) {
                byte[] payload = payloads.get(i);
                entryInfos.add(EntryInfo.literal(
                        i,
                        payload.length,
                        BatchAlgorithmSupport.varIntSize(payload.length) + payload.length
                ));
            }
            return new EncodedBatch(compressed, List.copyOf(entryInfos), 0, 0);
        }

        @Override
        public List<byte[]> decode(byte[] encodedBytes) {
            appendDecoded(decompressStreaming(encodedBytes));
            byte[] frame = extractNextFrame();
            if (frame == null) {
                throw new IllegalStateException("Streaming zstd decode produced no complete batch frame");
            }
            return decodeRawPayloads(frame);
        }

        @Override
        public void reset() {
            this.compressCtx.reset();
            this.compressCtx.setLevel(StreamingZstdBatchAlgorithmModule.level());
            this.decompressCtx.reset();
            this.pendingDecoded = new byte[0];
        }

        private byte[] compressStreaming(byte[] input) {
            ByteBuffer src = ByteBuffer.allocateDirect(input.length);
            src.put(input);
            src.flip();

            ByteBuffer dst = ByteBuffer.allocateDirect(DIRECT_BUFFER_BYTES);
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            while (src.hasRemaining()) {
                dst.clear();
                boolean flushed = this.compressCtx.compressDirectByteBufferStream(dst, src, EndDirective.CONTINUE);
                writeBuffer(output, dst);
                if (flushed && !src.hasRemaining()) {
                    break;
                }
            }

            // End the current frame for every outer batch packet. The decoder expects
            // exactly one complete frame per received batch payload.
            while (true) {
                dst.clear();
                boolean flushed = this.compressCtx.compressDirectByteBufferStream(dst, src, EndDirective.END);
                writeBuffer(output, dst);
                if (flushed) {
                    break;
                }
            }

            return output.toByteArray();
        }

        private byte[] decompressStreaming(byte[] input) {
            ByteBuffer src = ByteBuffer.allocateDirect(input.length);
            src.put(input);
            src.flip();

            ByteBuffer dst = ByteBuffer.allocateDirect(DIRECT_BUFFER_BYTES);
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            while (src.hasRemaining()) {
                dst.clear();
                this.decompressCtx.decompressDirectByteBufferStream(dst, src);
                writeBuffer(output, dst);
            }

            return output.toByteArray();
        }

        private void appendDecoded(byte[] decoded) {
            if (decoded.length == 0) {
                return;
            }
            byte[] merged = Arrays.copyOf(this.pendingDecoded, this.pendingDecoded.length + decoded.length);
            System.arraycopy(decoded, 0, merged, this.pendingDecoded.length, decoded.length);
            this.pendingDecoded = merged;
        }

        private byte[] extractNextFrame() {
            if (this.pendingDecoded.length == 0) {
                return null;
            }

            VarIntRead lengthRead = readVarInt(this.pendingDecoded, 0);
            if (lengthRead == null) {
                return null;
            }

            int frameLength = lengthRead.value();
            int frameStart = lengthRead.nextIndex();
            int frameEnd = frameStart + frameLength;
            if (frameLength < 0 || frameEnd > this.pendingDecoded.length) {
                return null;
            }

            byte[] frame = Arrays.copyOfRange(this.pendingDecoded, frameStart, frameEnd);
            this.pendingDecoded = Arrays.copyOfRange(this.pendingDecoded, frameEnd, this.pendingDecoded.length);
            return frame;
        }

        private static void writeBuffer(ByteArrayOutputStream output, ByteBuffer buffer) {
            int written = buffer.position();
            if (written <= 0) {
                return;
            }
            byte[] bytes = new byte[written];
            buffer.flip();
            buffer.get(bytes);
            output.write(bytes, 0, bytes.length);
        }
    }

    private static byte[] frame(byte[] rawBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(rawBytes.length);
            buffer.writeBytes(rawBytes);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static byte[] encodeRawPayloads(List<byte[]> payloads) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(payloads.size());
            for (byte[] payload : payloads) {
                buffer.writeVarInt(payload.length);
                buffer.writeBytes(payload);
            }
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(0, bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static List<byte[]> decodeRawPayloads(byte[] rawBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(rawBytes));
        try {
            int size = buffer.readVarInt();
            List<byte[]> payloads = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                int payloadLength = buffer.readVarInt();
                byte[] payload = new byte[payloadLength];
                buffer.readBytes(payload);
                payloads.add(payload);
            }
            return payloads;
        } finally {
            buffer.release();
        }
    }

    private static VarIntRead readVarInt(byte[] data, int start) {
        int value = 0;
        int position = 0;
        int index = start;
        while (index < data.length) {
            int next = data[index++] & 0xFF;
            value |= (next & 0x7F) << position;
            if ((next & 0x80) == 0) {
                return new VarIntRead(value, index);
            }
            position += 7;
            if (position > 28) {
                throw new IllegalArgumentException("VarInt too large in streaming zstd frame");
            }
        }
        return null;
    }

    private record VarIntRead(int value, int nextIndex) {
    }
}
