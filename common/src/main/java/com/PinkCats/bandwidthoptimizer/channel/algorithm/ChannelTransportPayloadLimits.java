package com.PinkCats.bandwidthoptimizer.channel.algorithm;

// Shared transport payload caps keep encoder and decoder limits aligned.
public final class ChannelTransportPayloadLimits {

    public static final int MAX_SINGLE_PACKET_BYTES = 8 * 1024 * 1024;
    public static final int MAX_BATCH_PAYLOAD_BYTES = 16 * 1024 * 1024;
    public static final int MAX_STREAMING_FRAME_PAYLOAD_BYTES = MAX_BATCH_PAYLOAD_BYTES + 1024 * 1024;
    public static final int MAX_FRAGMENTED_TRANSPORT_FRAME_BYTES = MAX_STREAMING_FRAME_PAYLOAD_BYTES + 64 * 1024;

    private ChannelTransportPayloadLimits() {
    }
}
