package com.PinkCats.bandwidthoptimizer.channel.algorithm;

public interface TransportAlgorithm {

    String id();

    ChannelTransportAlgorithmSession createSession();
}
