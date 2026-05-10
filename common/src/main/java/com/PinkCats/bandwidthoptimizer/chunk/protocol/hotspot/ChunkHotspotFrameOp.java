package com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot;

public enum ChunkHotspotFrameOp {

    PUBLISH_FULL("publish_full"),
    PUBLISH_REF("publish_ref"),
    PUBLISH_PATCH("publish_patch"),
    ACK("ack"),
    NACK("nack"),
    INVALIDATE("invalidate"),
    SERVER_CACHE_SCOPE("server_cache_scope"),
    CLIENT_CACHE_MANIFEST("client_cache_manifest"),
    BARRIER("barrier"),
    BARRIER_ACK("barrier_ack");

    private final String logName;

    ChunkHotspotFrameOp(String logName) {this.logName = logName;}

    public String logName() {return this.logName;}

    public static ChunkHotspotFrameOp fromLogName(String logName) {
        for (ChunkHotspotFrameOp operation : values()) {
            if (operation.logName.equals(logName)) {
                return operation;
            }
        }
        throw new IllegalArgumentException("Unknown chunk frame operation logName: " + logName);
    }
}
