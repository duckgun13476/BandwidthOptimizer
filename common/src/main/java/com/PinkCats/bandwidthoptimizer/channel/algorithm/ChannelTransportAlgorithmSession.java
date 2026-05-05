package com.PinkCats.bandwidthoptimizer.channel.algorithm;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.mes.ChannelTransportOperationTelemetry;

public interface ChannelTransportAlgorithmSession {

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


    record OperationResult(byte[] bytes, ChannelTransportOperationTelemetry telemetry) { }
}
