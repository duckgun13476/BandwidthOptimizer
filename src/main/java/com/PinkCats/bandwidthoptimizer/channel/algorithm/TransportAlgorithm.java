package com.PinkCats.bandwidthoptimizer.channel.algorithm;

public interface TransportAlgorithm {

    ChannelTransportAlgorithmId id();
    ChannelTransportAlgorithmSession createSession();
}
