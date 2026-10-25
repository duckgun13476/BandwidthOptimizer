package com.PinkCats.bandwidthoptimizer.networkprobe;

import java.util.UUID;

/** Published immutable event-loop sample; UI never reads mutable session counters. */
public record NetworkProbeProgress(UUID id,String phase,long sentBytes,long receivedBytes,long budgetBytes,long elapsedNs) {}
