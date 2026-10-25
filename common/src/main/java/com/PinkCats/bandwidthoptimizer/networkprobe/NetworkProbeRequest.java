package com.PinkCats.bandwidthoptimizer.networkprobe;

import java.util.UUID;

/** Immutable identity and a published status; never retains the connection or a UI callback. */
public final class NetworkProbeRequest {
    private final UUID id;
    private volatile String status="queued";
    NetworkProbeRequest(UUID id) { this.id=id; }
    public UUID id() { return id; }
    public String status() { return status; }
    void status(String value) { status=value; }
    boolean waiting() { return status.equals("waiting_for_authorization"); }
}
