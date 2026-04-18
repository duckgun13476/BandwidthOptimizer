package com.PinkCats.bandwidthoptimizer.Old.network.algorithm;

public final class BatchAlgorithmSupport {

    private BatchAlgorithmSupport() {
    }

    public static int varIntSize(int value) {
        int size = 0;
        do {
            value >>>= 7;
            size++;
        } while (value != 0);
        return size;
    }
}
