package com.PinkCats.bandwidthoptimizer.channel.algorithm;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.batch.KineticBatchLayer;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.mapping.KineticMapTableLayer;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd.KineticStreamingLayer;

// Algorithm Entry
public final class KineticChannel {

    private KineticChannel() {
    }



    // Output Queue
    public static byte[] encodeAlgorithmPipeline(
            byte[] originalPacketBytes,
            KineticBatchLayer batchLayer,
            KineticMapTableLayer mapTableLayer,
            KineticStreamingLayer zstdLayer
    ) {
        byte[] currentBytes = originalPacketBytes;
        currentBytes = encodeBatchLayer(currentBytes, batchLayer);
        currentBytes = encodeMapTableLayer(currentBytes, mapTableLayer);
        currentBytes = encodeZstdLayer(currentBytes, zstdLayer);
        return currentBytes;
    }

    // Input Queue
    public static byte[] decodeAlgorithmPipeline(
            byte[] encodedBytes,
            KineticBatchLayer batchLayer,
            KineticMapTableLayer mapTableLayer,
            KineticStreamingLayer zstdLayer
    ) {
        byte[] currentBytes = encodedBytes;
        currentBytes = decodeZstdLayer(currentBytes, zstdLayer);
        currentBytes = decodeMapTableLayer(currentBytes, mapTableLayer);
        currentBytes = decodeBatchLayer(currentBytes, batchLayer);
        return currentBytes;
    }



    // Output Layer ----
    private static byte[] encodeBatchLayer(byte[] packetBytes, KineticBatchLayer batchLayer) {
        return isBatchLayerEnabled() ? batchLayer.encode(packetBytes) : packetBytes;
    }

    private static byte[] encodeMapTableLayer(byte[] packetBytes, KineticMapTableLayer mapTableLayer) {
        return isMapTableLayerEnabled() ? mapTableLayer.encode(packetBytes) : packetBytes;
    }

    private static byte[] encodeZstdLayer(byte[] packetBytes, KineticStreamingLayer zstdLayer) {
        return isZstdLayerEnabled() ? zstdLayer.encode(packetBytes) : packetBytes;
    }

    // Input Layer ----
    private static byte[] decodeBatchLayer(byte[] encodedBytes, KineticBatchLayer batchLayer) {
        return isBatchLayerEnabled() ? batchLayer.decode(encodedBytes) : encodedBytes;
    }

    private static byte[] decodeMapTableLayer(byte[] encodedBytes, KineticMapTableLayer mapTableLayer) {
        return isMapTableLayerEnabled() ? mapTableLayer.decode(encodedBytes) : encodedBytes;
    }

    private static byte[] decodeZstdLayer(byte[] encodedBytes, KineticStreamingLayer zstdLayer) {
        return isZstdLayerEnabled() ? zstdLayer.decode(encodedBytes) : encodedBytes;
    }


    // Verify
    private static boolean isBatchLayerEnabled() {return false;}
    private static boolean isMapTableLayerEnabled() {return ChannelTransportLayerRuntimeConfig.isMappingEnabled();}
    private static boolean isZstdLayerEnabled() {return ChannelTransportLayerRuntimeConfig.isZstdEnabled();}


    // -> transport frame。
    public static ChannelTransportPacketCodec.WrappedTransportFrame processOutboundPacket(
            ChannelTransportSession transportSession,
            byte[] originalPacketBytes
    ) {
        return ChannelTransportPacketCodec.wrapPacket(transportSession, originalPacketBytes);
    }

    // transport frame ->
    public static ChannelTransportPacketCodec.UnwrappedTransportFrame tryUnpackInboundPacket(
            ChannelTransportSession transportSession,
            byte[] inboundPacketBytes
    ) {
        return ChannelTransportPacketCodec.tryUnwrapPacket(transportSession, inboundPacketBytes);
    }
}
