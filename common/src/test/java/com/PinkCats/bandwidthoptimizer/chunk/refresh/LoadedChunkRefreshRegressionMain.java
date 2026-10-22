package loadedrefreshregression;

import com.PinkCats.bandwidthoptimizer.chunk.refresh.LoadedChunkUpdateTracker;
import com.PinkCats.bandwidthoptimizer.chunk.refresh.UpdatedChunkCacheStats;

import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;

public final class LoadedChunkRefreshRegressionMain {
    public static void main(String[] args) throws Exception {
        System.setProperty("bandwidthoptimizer.experimental.loadedChunkRefresh", "true");
        var tracker = new LoadedChunkUpdateTracker(4096, 2);
        tracker.changed(1); check(!tracker.forget(1, 1), "unloaded update cannot create a candidate");
        tracker.loaded(1); check(!tracker.forget(1, 1), "unchanged full is not updated");
        tracker.loaded(1); for (int i = 0; i < 100000; i++) tracker.changed(1);
        check(tracker.size() == 1 && tracker.forget(1, 2), "coalesced metadata, no update history");
        check(!tracker.forget(1, 2), "forget consumes candidate");
        for (long i = 0; i < 100000; i++) tracker.loaded(i);
        check(tracker.size() == 4096, "loaded metadata bounded");
        tracker.changed(0); check(!tracker.forget(0, 3), "evicted entry cannot refresh");
        for (int i = 1; i <= 3; i++) { tracker.loaded(i); tracker.changed(i); }
        check(tracker.forget(1, 4) && tracker.forget(2, 4) && !tracker.forget(3, 4), "capture burst bounded");
        tracker.loaded(3); tracker.changed(3); tracker.loaded(3);
        check(!tracker.forget(3, 5), "new FULL replaces dirty generation");
        tracker.clear(); check(tracker.size() == 0, "scope cleanup");
        for (int i = 1; i <= 3; i++) { tracker.loaded(i); tracker.changed(i); }
        var captured = new java.util.ArrayList<Long>();
        tracker.captureDirty(9, captured::add);
        check(captured.equals(java.util.List.of(1L, 2L)), "center change captures only bounded dirty entries");
        check(!tracker.forget(1, 9) && !tracker.forget(3, 9), "center and forget share the tick budget");
        tracker.captureDirty(10, captured::add);
        check(captured.size() == 2, "budget-rejected forgotten entry is not retained");
        tracker.clear();
        String hash = "a".repeat(64), other = "b".repeat(64);
        var coordinate = ChunkPacketCoordinate.ofChunk(1, -1);
        UpdatedChunkCacheStats.reset();
        UpdatedChunkCacheStats.candidate("scope:1", coordinate, hash);
        check(UpdatedChunkCacheStats.reuses() == 0, "store alone never claims reuse");
        UpdatedChunkCacheStats.referenceRestored("scope:2", coordinate, hash);
        UpdatedChunkCacheStats.referenceRestored("scope:1", coordinate, other);
        check(UpdatedChunkCacheStats.reuses() == 0, "stale scope/hash cannot count");
        UpdatedChunkCacheStats.referenceRestored("scope:1", coordinate, hash);
        check(UpdatedChunkCacheStats.reuses() == 1, "exact successful reference counts");
        for (int i = 0; i < 100000; i++) UpdatedChunkCacheStats.candidate("scope:1", ChunkPacketCoordinate.ofChunk(i, 0), hash);
        check(UpdatedChunkCacheStats.entries() == 4096, "provenance bounded without cache body ownership");
        UpdatedChunkCacheStats.reset(); check(UpdatedChunkCacheStats.entries() == 0 && UpdatedChunkCacheStats.reuses() == 0, "session reset");
        plannerRegression();
        System.out.println("Loaded chunk refresh regression PASS");
    }
    private static void plannerRegression() throws Exception {
        String target="a".repeat(64), old="b".repeat(64);
        var descriptor=new com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor("PLAY", "net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket",
                com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind.FULL_CHUNK,
                com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind.FULL, ChunkPacketCoordinate.ofChunk(1,0));
        var fingerprint=new com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprint("SHA-256",target,target.substring(0,12),19328);
        for(boolean matching:new boolean[]{true,false}) {
            String candidate=matching?target:"c".repeat(64);
            var peer=new com.PinkCats.bandwidthoptimizer.chunk.peer.ChunkPeerChunkStateSnapshot(
                    new com.PinkCats.bandwidthoptimizer.chunk.peer.ChunkPeerChunkKey(1,0),1L,true,false,true,
                    1L,1L,0L,1L,0L,0L,0L,0L,0L,0L,"full_chunk","full",old,old.substring(0,12),"",target,target.substring(0,12),
                    19328,19326,2L,1L,1L,0L,1L,false,true,candidate,candidate.substring(0,12),19328);
            var decision=com.PinkCats.bandwidthoptimizer.chunk.plan.ChunkTransportPlanner.planOutboundTransport(descriptor,fingerprint,peer,null);
            check(decision.decisionKind()==(matching?com.PinkCats.bandwidthoptimizer.chunk.plan.ChunkPlanDecisionKind.PUBLISH_REF:com.PinkCats.bandwidthoptimizer.chunk.plan.ChunkPlanDecisionKind.PUBLISH_FULL),"authoritative target hash controls refreshed watch-boundary candidate");
            if(matching) {
                var method=com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportDispatcher.class.getDeclaredMethod("resolveBaseSnapshotHash",com.PinkCats.bandwidthoptimizer.chunk.plan.ChunkPlanDecision.class);
                method.setAccessible(true);
                check(target.equals(method.invoke(null,decision)),"REF must name refreshed target, not stale runtime base");
            }
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
