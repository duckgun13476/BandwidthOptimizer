package com.PinkCats.bandwidthoptimizer.channel.algorithm.mes;

// packet report.
public record ChannelTransportOperationTelemetry(
        String algorithmId,
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
            String algorithmId,
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
