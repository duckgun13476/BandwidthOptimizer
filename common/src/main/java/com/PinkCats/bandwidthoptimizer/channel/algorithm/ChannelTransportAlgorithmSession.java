package com.PinkCats.bandwidthoptimizer.channel.algorithm;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportOperationTelemetry;

/** Synchronous transforms borrow input read-only until return. Callers must not mutate it
 * during the call. Retained state must copy; results own their arrays and must not alias
 * input, dictionary state, workspaces, or later operation results. */
public interface ChannelTransportAlgorithmSession extends AutoCloseable {

    byte[] encodePacket(byte[] packetBytes);

    default OperationResult encodePacketWithTelemetry(byte[] packetBytes) {
        byte[] encodedBytes = encodePacket(packetBytes);
        return new OperationResult(
                encodedBytes,
                ChannelTransportOperationTelemetry.passthrough(
                        ChannelTransportLayerRuntimeConfig.algorithmId(),
                        ChannelTransportLayerRuntimeConfig.isMappingEnabled(),
                        ChannelTransportLayerRuntimeConfig.isZstdEnabled(),
                        packetBytes == null ? 0 : packetBytes.length
                )
        );
    }

    default OperationResult encodePacketWithLiteralMappingTelemetry(byte[] packetBytes) {
        return encodePacketWithTelemetry(packetBytes);
    }





    byte[] decodePacket(byte[] encodedBytes);

    default OperationResult decodePacketWithTelemetry(byte[] encodedBytes) {
        byte[] restoredBytes = decodePacket(encodedBytes);
        return new OperationResult(
                restoredBytes,
                ChannelTransportOperationTelemetry.passthrough(
                        ChannelTransportLayerRuntimeConfig.algorithmId(),
                        ChannelTransportLayerRuntimeConfig.isMappingEnabled(),
                        ChannelTransportLayerRuntimeConfig.isZstdEnabled(),
                        encodedBytes == null ? 0 : encodedBytes.length
                )
        );
    }

    default void reset() {}

    @Override
    default void close() {}


    record OperationResult(byte[] bytes, ChannelTransportOperationTelemetry telemetry) { }
}
