package loadedrefreshprobe;
import java.util.*;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import com.PinkCats.bandwidthoptimizer.chunk.packet.ClientboundPlayPacketCodec;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.ChunkSnapshotFingerprintService;
public final class RefreshWireProbe {
    public static boolean rebuilding;
    public static int forbiddenConstructors;
    public static void forcedMiss(Map<String,Object> receipt) { RefreshProbe.write("forced-miss.json",receipt); }
    private static final List<Map<String,Object>> ROWS=new ArrayList<>();
    private static final List<Map<String,Object>> PLANS=new ArrayList<>();
    public static synchronized void planned(int x,int z,Object fingerprint,Object snapshot,Object decision) {
        if(x!=RefreshProbe.TARGET_X||z!=RefreshProbe.TARGET_Z||PLANS.size()>=32)return;
        var row=new LinkedHashMap<String,Object>();row.put("enabled",com.PinkCats.bandwidthoptimizer.chunk.refresh.UpdatedChunkCacheStats.ENABLED);row.put("fingerprint",fingerprint);row.put("snapshot",snapshot);row.put("decision",decision);
        PLANS.add(row);RefreshProbe.write("target-plans.json",PLANS);
    }
    public static void observe(net.minecraft.network.protocol.Packet<?> packet, int depth) {
        if(depth>4) return;
        if(packet instanceof ClientboundLevelChunkWithLightPacket full && full.getX()==RefreshProbe.TARGET_X && full.getZ()==RefreshProbe.TARGET_Z) sent(full);
        else if(packet instanceof net.minecraft.network.protocol.BundlePacket<?> bundle) {
            int count=0;
            for(var child:bundle.subPackets()) { if(++count>4096) break; observe(child,depth+1); }
        }
    }
    public static synchronized void sent(ClientboundLevelChunkWithLightPacket packet) {
        if(ROWS.size()>=16) return;
        byte[] bytes=ClientboundPlayPacketCodec.encodePacket(packet,net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer().registryAccess());
        if(bytes==null) { RefreshProbe.write("native-encode-failure.json",Map.of("targetX",packet.getX(),"targetZ",packet.getZ())); return; }
        ROWS.add(Map.of("hash",ChunkSnapshotFingerprintService.fingerprintOutboundPacket(bytes).hashHex(),"bytes",bytes.length));
        RefreshProbe.write("native-full-sends.json",ROWS);
    }
}
