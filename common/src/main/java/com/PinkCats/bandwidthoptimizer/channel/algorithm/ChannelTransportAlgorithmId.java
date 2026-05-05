package com.PinkCats.bandwidthoptimizer.channel.algorithm;

public enum ChannelTransportAlgorithmId {

    TDA_SZA("TDA_SZA", true, true),
    TDA("TDA", true, false),
    SZA("SZA", false, true),
    PT("PT", false, false);

    private final String shortCode;
    private final boolean mappingEnabled;
    private final boolean zstdEnabled;

    ChannelTransportAlgorithmId(String shortCode, boolean mappingEnabled, boolean zstdEnabled) {
        this.shortCode = shortCode;
        this.mappingEnabled = mappingEnabled;
        this.zstdEnabled = zstdEnabled;
    }


    public static ChannelTransportAlgorithmId resolve(boolean mappingEnabled, boolean zstdEnabled) {
        if (mappingEnabled && zstdEnabled) {
            return TDA_SZA;
        }
        if (mappingEnabled) {
            return TDA;
        }
        if (zstdEnabled) {
            return SZA;
        }
        return PT;
    }


    public String shortCode() {
        return this.shortCode;
    }

    public boolean usesMapping() {
        return this.mappingEnabled;
    }

    public boolean usesZstd() {
        return this.zstdEnabled;
    }

    @Override
    public String toString() {
        return this.shortCode;
    }
}
