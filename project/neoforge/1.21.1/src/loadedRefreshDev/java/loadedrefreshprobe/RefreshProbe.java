package loadedrefreshprobe;

import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotStats;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod("loadedrefreshprobe")
public final class RefreshProbe {
    public static final Path ROOT = Path.of(System.getProperty("refreshprobe.root"));
    static final boolean FACTORY = Boolean.getBoolean("refreshprobe.factory");
    public static final int TARGET_X = FACTORY ? 11 : 1, TARGET_Z = FACTORY ? 28 : 0;
    static final BlockPos TEST_BLOCK = new BlockPos(TARGET_X*16+1, FACTORY ? 200 : 63, TARGET_Z*16+1);
    private static final double START_X = FACTORY ? 161.5 : 1.5, START_Z = FACTORY ? 449.5 : 1.5;
    private static MinecraftServer server;
    private static int ticks, stage, stageTick;
    private static net.minecraft.server.level.ServerPlayer beforeReconnect;
    public RefreshProbe(IEventBus ignored) {
        if (FMLEnvironment.dist == Dist.CLIENT) RefreshClient.install();
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            server = e.getServer();
            for (int z = -8; z <= 8; z++) for (int x = -8; x <= 24; x++)
                server.overworld().setBlock(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState(), 3);
            if (Boolean.getBoolean("refreshprobe.chest"))
                server.overworld().setBlock(new BlockPos(TARGET_X * 16 + 4, 63, TARGET_Z * 16 + 1), Blocks.CHEST.defaultBlockState(), 3);
            if (FACTORY) {
                var entities=new java.util.TreeMap<String,Integer>();
                for(int z=26;z<=30;z++)for(int x=9;x<=13;x++) {
                    var chunk=server.overworld().getChunk(x,z);
                    for(var be:chunk.getBlockEntities().values()) {
                        String id=net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString();
                        entities.merge(id,1,Integer::sum);
                    }
                }
                write("factory-loaded.json",Map.of("blockEntities",entities,"targetBlockEntities",server.overworld().getChunk(TARGET_X,TARGET_Z).getBlockEntities().size()));
            }
        });
        NeoForge.EVENT_BUS.addListener(RefreshProbe::tick);
    }
    private static void tick(ServerTickEvent.Post ignored) {
        if (server == null) return;
        ticks++;
        if (Files.exists(ROOT.resolve("client-result.json"))) { server.halt(false); return; }
        if (ticks > 2400) { write("server-timeout.json", Map.of("stage", stage)); server.halt(false); return; }
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        var player = players.getFirst();
        int elapsed = ticks - stageTick;
        if (stage == 0) { player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR); player.teleportTo(START_X, FACTORY ? 200 : 66, START_Z); advance(); }
        else if (stage == 1 && elapsed >= 160) {
            server.overworld().setBlock(TEST_BLOCK, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            advance();
        } else if (stage == 2 && elapsed >= 100) { player.teleportTo(START_X+512, FACTORY ? 200 : 66, START_Z+512); advance(); }
        else if (stage == 3 && elapsed >= Integer.getInteger("refreshprobe.returnDelay",320)) {
            var channel=((com.PinkCats.bandwidthoptimizer.mixin.minecraft.ConnectionAccessor)player.connection.getConnection()).bandwidthoptimizer$getChannel();
            var context=channel.pipeline().firstContext();
            var peer=com.PinkCats.bandwidthoptimizer.chunk.peer.ChunkPeerStateManager.snapshotOutboundChannel(context);
            write("peer-before-return.json", com.PinkCats.bandwidthoptimizer.chunk.peer.ChunkPeerStateManager.snapshotOutboundChunk(context,peer.epoch(),com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate.ofChunk(TARGET_X,TARGET_Z)));
            player.teleportTo(START_X, FACTORY ? 200 : 66, START_Z); advance();
        }
        else if (stage == 4 && elapsed >= 120) {
            if (Boolean.getBoolean("refreshprobe.lifecycle")) {
                player.teleportTo(server.getLevel(net.minecraft.world.level.Level.NETHER), 1.5, 100, 1.5, java.util.Set.of(), 0, 0);
                advance();return;
            }
            finish();
        } else if(stage==5 && elapsed>=100){
            player.teleportTo(server.overworld(), START_X,66,START_Z,java.util.Set.of(),0,0);advance();
        } else if(stage==6 && elapsed>=100){
            beforeReconnect=player;write("request-reconnect.json",Map.of("dimensionRoundTrip",true));advance();
        } else if(stage==7 && player!=beforeReconnect && elapsed>=60){
            player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);player.teleportTo(START_X,66,START_Z);advance();
        } else if(stage==8 && elapsed>=120){finish();}
    }
    private static void finish(){
            snapshot("server-full.bin", server.overworld().getChunk(TARGET_X, TARGET_Z), server.overworld().getLightEngine());
            write("server-result.json", Map.of("completed", true, "ticks", ticks, "chunkStats", ChunkHotspotStats.snapshotReport()));
            advance();
    }
    private static void advance() { stage++; stageTick = ticks; }
    static void snapshot(String name, net.minecraft.world.level.chunk.LevelChunk chunk, net.minecraft.world.level.lighting.LevelLightEngine light) {
        try {
            if(!chunk.getBlockEntities().isEmpty()) {
                write(name+".skipped.json",Map.of("reason","update-tag writers are not pure", "blockEntities",chunk.getBlockEntities().size()));
                return;
            }
            long start=System.nanoTime();
            var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
            long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            var packet = new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(chunk, light, null, null);
            byte[] bytes=com.PinkCats.bandwidthoptimizer.chunk.packet.ClientboundPlayPacketCodec.encodePacket(packet,chunk.getLevel().registryAccess());
            if (bytes == null) throw new IllegalStateException("Registry-bound FULL encoding returned null for " + name);
            long allocation=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocated;
            long nanos=System.nanoTime()-start;
            Files.write(ROOT.resolve(name), bytes);
            write(name+".cost.json",Map.of("bytes",bytes.length,"nanos",nanos,"allocatedBytes",allocation,"blockEntities",chunk.getBlockEntities().size()));
            var encoder=com.PinkCats.bandwidthoptimizer.chunk.packet.ClientboundPlayPacketCodec.encoderForRegistry(chunk.getLevel().registryAccess());
            for(int i=0;i<20;i++) encoder.apply(new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(chunk,light,null,null));
            int count=0;long thread=Thread.currentThread().threadId();
            long a0=bean.getThreadAllocatedBytes(thread),c0=bean.getCurrentThreadCpuTime(),t0=System.nanoTime();
            long totalBytes=0;
            do {
                byte[] encoded=encoder.apply(new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(chunk,light,null,null));
                if(encoded==null)throw new IllegalStateException("Warm FULL encoding failed");
                totalBytes+=encoded.length;
                count++;
            } while(count<100000 && System.nanoTime()-t0<600_000_000L);
            write(name+".warm-cost.json",Map.of("iterations",count,"bytesPerCapture",totalBytes/count,
                    "aggregateWallNanos",System.nanoTime()-t0,"constructionAndEncodingOnly",true,
                    "wallNanosPerCapture",(System.nanoTime()-t0)/count,"cpuNanosPerCapture",(bean.getCurrentThreadCpuTime()-c0)/count,
                    "allocatedBytesPerCapture",(bean.getThreadAllocatedBytes(thread)-a0)/count));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    public static void write(String name, Object value) {
        try { Files.writeString(ROOT.resolve(name), new GsonBuilder().setPrettyPrinting().create().toJson(value)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
