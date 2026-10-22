package loadedrefreshregression;
import com.PinkCats.bandwidthoptimizer.chunk.refresh.*;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;

public final class LoadedRefreshMemoryRegressionMain {
    public static void main(String[] args) throws Exception {
        var tracker = new LoadedChunkUpdateTracker(4096, 2);
        for(int i=0;i<100000;i++)tracker.loaded(i);
        UpdatedChunkCacheStats.reset();
        for(int i=0;i<100000;i++) UpdatedChunkCacheStats.candidate("scope:1",ChunkPacketCoordinate.ofChunk(i,0),String.format("%064x",i));
        var method=Class.forName("compactrefregression.CompactReferenceMemoryAgent").getDeclaredMethod("retainedMetadataBytes",Object.class);
        method.setAccessible(true);
        var field=UpdatedChunkCacheStats.class.getDeclaredField("CANDIDATES");field.setAccessible(true);
        long track=(long)method.invoke(null,tracker), provenance=(long)method.invoke(null,field.get(null));
        if(track+provenance>2*1024*1024L)throw new AssertionError("Metadata budget regression");
        tracker.clear();UpdatedChunkCacheStats.reset();
        long empty=(long)method.invoke(null,tracker)+(long)method.invoke(null,field.get(null));
        if(tracker.size()!=0||UpdatedChunkCacheStats.entries()!=0)throw new AssertionError("Cleanup");
        tracker.loaded(1);for(int i=0;i<100000;i++)tracker.changed(1);
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        long id=Thread.currentThread().getId(),a0=bean.getThreadAllocatedBytes(id),c0=bean.getCurrentThreadCpuTime(),t0=System.nanoTime(),count=0;
        do { for(int i=0;i<100000;i++)tracker.changed(1);count+=100000; } while(System.nanoTime()-t0<600000000L);
        System.out.println("Loaded refresh metadata graph PASS tracker="+track+" provenance="+provenance+" clearedCapacity="+empty
            +" updateOnlyAllocB/op="+((bean.getThreadAllocatedBytes(id)-a0)/(double)count)+" updateOnlyCpuNs/op="+((bean.getCurrentThreadCpuTime()-c0)/(double)count));
    }
}
