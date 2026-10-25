package com.PinkCats.bandwidthoptimizer.networkprobe;

import java.util.UUID;

/** One process-wide active bulk test, with a global cooldown and no per-player map. */
final class ProbeAdmission {
    static final long COOLDOWN=30_000_000_000L;
    private static final UUID[] owners=new UUID[2];
    private static final long[] nextStart=new long[2];
    private static final boolean[] used=new boolean[2];
    static synchronized boolean acquire(UUID id,long now,boolean server) {
        int side=server?1:0;
        if (owners[side]!=null || (used[side] && now-nextStart[side]<0)) return false;
        owners[side]=id; used[side]=true; return true;
    }
    static synchronized void release(UUID id,long now,boolean server) {
        int side=server?1:0;
        if (id.equals(owners[side])) { owners[side]=null; nextStart[side]=now+COOLDOWN; }
    }
    static synchronized boolean handover(UUID previous,UUID replacement,boolean server) {
        int side=server?1:0;
        if(!previous.equals(owners[side])) return false;
        owners[side]=replacement; return true;
    }
}
