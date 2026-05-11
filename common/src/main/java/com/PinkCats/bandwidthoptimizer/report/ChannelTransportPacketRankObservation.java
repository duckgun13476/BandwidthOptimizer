package com.PinkCats.bandwidthoptimizer.report;

import java.util.Arrays;

public record ChannelTransportPacketRankObservation(
        long captureIndex,
        long capturedAtMillis,
        String channelId,
        String packetClassName,
        String sourceKey,
        int packetId,
        int rawPacketBytes,
        byte[] transportInputPacketBytes,
        boolean chunkProtocolApplied,
        String actualPath,
        String actualFrameKind,
        int actualFrameBytes,
        boolean actualFrameBytesEstimated,
        int batchPacketCount
) {

    public ChannelTransportPacketRankObservation {
        sourceKey = sourceKey == null || sourceKey.isBlank() ? "packet:<unknown>" : sourceKey;
        transportInputPacketBytes = transportInputPacketBytes == null
                ? new byte[0]
                : Arrays.copyOf(transportInputPacketBytes, transportInputPacketBytes.length);
    }

    public int transportInputBytes() {
        return this.transportInputPacketBytes.length;
    }

    public byte[] copyTransportInputPacketBytes() {
        return Arrays.copyOf(this.transportInputPacketBytes, this.transportInputPacketBytes.length);
    }
}
