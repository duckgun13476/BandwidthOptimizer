package com.PinkCats.bandwidthoptimizer.channel.algorithm.mes;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportAlgorithmId;

// packet report.
public record ChannelTransportOperationTelemetry(
        ChannelTransportAlgorithmId algorithmId,
        boolean mappingEnabled,
        boolean zstdEnabled,
        int mappingStageBytes,
        int literalEntryCount,
        int exactReferenceCount,
        int templateReferenceCount,
        int exactAdditionCount,
        int templateAdditionCount,
        int exactRemovalCount,
        int templateRemovalCount
) {

    public static ChannelTransportOperationTelemetry passthrough(
            ChannelTransportAlgorithmId algorithmId,
            boolean mappingEnabled,
            boolean zstdEnabled,
            int mappingStageBytes
    ) {
        return new ChannelTransportOperationTelemetry(
                algorithmId,
                mappingEnabled,
                zstdEnabled,
                Math.max(mappingStageBytes, 0),
                0,
                0,
                0,
                0,
                0,
                0,
                0
        );
    }
}
