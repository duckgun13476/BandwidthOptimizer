package com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd;

import com.PinkCats.bandwidthoptimizer.Config;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.AlgorithmInterface;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportAlgorithmSession;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.KineticChannel;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.batch.KineticBatchLayer;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.mapping.KineticMapTableLayer;

import java.util.Arrays;

// This algorithm module owns the streaming-zstd implementation used by transport.
public final class KineticStreaming implements AlgorithmInterface {
    private static final int DEFAULT_COMPRESSION_LEVEL = 4;

    @Override
    public String id() {
        return "channel_streaming_zstd";
    }

    // This function creates a fresh per-connection streaming-zstd session.
    @Override
    public ChannelTransportAlgorithmSession createSession() {
        return new StreamingSession();
    }

    // This function reads the configured streaming-zstd compression level.
    private static int compressionLevel() {
        return Config.batchStreamingZstdLevel > 0 ? Config.batchStreamingZstdLevel : DEFAULT_COMPRESSION_LEVEL;
    }

    // This session stores the compression and decompression state for one connection.
    private static final class StreamingSession implements ChannelTransportAlgorithmSession {

        private final KineticBatchLayer batchLayer = new KineticBatchLayer();
        private final KineticMapTableLayer mapTableLayer = new KineticMapTableLayer();
        private final KineticStreamingLayer zstdLayer = new KineticStreamingLayer(compressionLevel());

        @Override
        public byte[] encodePacket(byte[] packetBytes) {
            byte[] safePacketBytes = copyBytesOrEmpty(packetBytes);
            return KineticChannel.encodeAlgorithmPipeline(safePacketBytes, this.batchLayer, this.mapTableLayer, this.zstdLayer);
        }

        @Override
        public byte[] decodePacket(byte[] encodedBytes) {
            byte[] safeEncodedBytes = copyBytesOrEmpty(encodedBytes);
            return KineticChannel.decodeAlgorithmPipeline(safeEncodedBytes, this.batchLayer, this.mapTableLayer, this.zstdLayer);
        }

        @Override
        public void reset() {
            this.batchLayer.reset();
            this.mapTableLayer.reset();
            this.zstdLayer.reset();
        }
    }

    // This function turns a nullable byte array into a private immutable working copy.
    private static byte[] copyBytesOrEmpty(byte[] sourceBytes) {
        return sourceBytes == null ? new byte[0] : Arrays.copyOf(sourceBytes, sourceBytes.length);
    }
}
