package com.PinkCats.bandwidthoptimizer.chunk.plan;

public enum ChunkPlanDecisionKind {

    BYPASS("bypass"),
    PUBLISH_FULL("publish_full"),
    PUBLISH_PATCH("publish_patch"),
    PUBLISH_REF("publish_ref");
    private final String logName;
    ChunkPlanDecisionKind(String logName) {this.logName = logName;}
    public String logName() {return this.logName;}

}
