package com.PinkCats.bandwidthoptimizer.report;

import java.util.Arrays;

public record ChannelTransportCapturedPacketSample(
        long captureIndex,
        String channelId,
        String direction,
        String protocolName,
        String packetClassName,
        int packetId,
        byte[] packetBytes,
        long capturedAtMillis
) {

    public ChannelTransportCapturedPacketSample {
        packetBytes = packetBytes == null ? new byte[0] : Arrays.copyOf(packetBytes, packetBytes.length);
    }

    public boolean isOutbound() {
        return "OUTBOUND".equalsIgnoreCase(this.direction);
    }

    public byte[] copyPacketBytes() {
        return Arrays.copyOf(this.packetBytes, this.packetBytes.length);
    }
}
