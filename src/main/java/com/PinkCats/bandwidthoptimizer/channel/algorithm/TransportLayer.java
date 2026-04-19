package com.PinkCats.bandwidthoptimizer.channel.algorithm;

public interface TransportLayer {

    byte[] encode(byte[] inputBytes);

    byte[] decode(byte[] inputBytes);

    default void reset() {
    }
}
