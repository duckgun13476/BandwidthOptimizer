package com.PinkCats.bandwidthoptimizer.channel.algorithm;

import com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd.KineticStreaming;

// All Channel Algorithms Entry
public final class ChannelTransportAlgorithms {

    private static final TransportAlgorithm DEFAULT_ALGORITHM = new KineticStreaming();

    private ChannelTransportAlgorithms() {}

    public static TransportAlgorithm defaultAlgorithm() {
        return DEFAULT_ALGORITHM;
    }
}
