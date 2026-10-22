package loadedrefreshprobe;

import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkLocalCacheReuseStats;
import com.PinkCats.bandwidthoptimizer.chunk.refresh.UpdatedChunkCacheStats;
import java.nio.file.Files;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;

final class RefreshClient {
    private static int ticks;
    private static int reconnectTicks;
    static void install() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            var mc = Minecraft.getInstance();
            if(Boolean.getBoolean("refreshprobe.lifecycle")) {
                if(reconnectTicks==0 && Files.exists(RefreshProbe.ROOT.resolve("request-reconnect.json"))) {
                    RefreshProbe.write("before-reconnect.json",Map.of("updatedReuses",UpdatedChunkCacheStats.reuses(),"candidates",UpdatedChunkCacheStats.entries()));
                    mc.disconnect();reconnectTicks=1;return;
                }
                if(reconnectTicks>0 && reconnectTicks<21){
                    if(++reconnectTicks==21)net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new net.minecraft.client.gui.screens.TitleScreen(),mc,
                        net.minecraft.client.multiplayer.resolver.ServerAddress.parseString("127.0.0.1:25849"),
                        new net.minecraft.client.multiplayer.ServerData("Refresh reconnect","127.0.0.1:25849",net.minecraft.client.multiplayer.ServerData.Type.OTHER),false,null);
                    return;
                }
                if(reconnectTicks==21 && mc.level!=null && mc.player!=null){
                    RefreshProbe.write("after-reconnect.json",Map.of("updatedReuses",UpdatedChunkCacheStats.reuses(),"candidates",UpdatedChunkCacheStats.entries()));reconnectTicks++;
                }
            }
            if (++ticks % 20 != 0) return;
            if(mc.level!=null) {
                try {
                    var core=Class.forName("com.PinkCats.bandwidthoptimizer.client.hud.BandwidthOptimizerHudOverlayCore");
                    var enabled=core.getDeclaredField("enabled");enabled.setAccessible(true);enabled.setBoolean(null,true);
                } catch(ReflectiveOperationException failure){throw new RuntimeException(failure);}
            }
            if (mc.level != null && mc.level.hasChunk(RefreshProbe.TARGET_X, RefreshProbe.TARGET_Z)
                    && mc.level.getBlockState(RefreshProbe.TEST_BLOCK).is(Blocks.GOLD_BLOCK)
                    && !Files.exists(RefreshProbe.ROOT.resolve("client-full.bin"))
                    && !Files.exists(RefreshProbe.ROOT.resolve("client-full.bin.skipped.json"))) {
                RefreshProbe.snapshot("client-full.bin", mc.level.getChunk(RefreshProbe.TARGET_X, RefreshProbe.TARGET_Z), mc.level.getLightEngine());
            }
            if (Files.exists(RefreshProbe.ROOT.resolve("server-result.json")) && mc.level != null) {
                boolean state = mc.level.getBlockState(RefreshProbe.TEST_BLOCK).is(Blocks.GOLD_BLOCK);
                try {
                    var core=Class.forName("com.PinkCats.bandwidthoptimizer.client.hud.BandwidthOptimizerHudOverlayCore");
                    var current=core.getDeclaredMethod("currentHud",Minecraft.class);current.setAccessible(true);
                    var hud=current.invoke(null,mc);var lines=hud.getClass().getDeclaredMethod("lines");lines.setAccessible(true);
                    RefreshProbe.write("hud-lines.json",lines.invoke(hud));
                    try(var image=net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                        image.writeToFile(RefreshProbe.ROOT.resolve("hud.png"));
                    }
                    var field = UpdatedChunkCacheStats.class.getDeclaredField("CANDIDATES");
                    field.setAccessible(true);
                    synchronized (UpdatedChunkCacheStats.class) {
                        RefreshProbe.write("candidate-provenance.json", Map.copyOf((Map<?, ?>) field.get(null)));
                    }
                } catch (ReflectiveOperationException | java.io.IOException failure) { throw new RuntimeException(failure); }
                RefreshProbe.write("client-result.json", Map.of("finalBlockCorrect", state,
                        "enabled", UpdatedChunkCacheStats.ENABLED, "updatedReuses", UpdatedChunkCacheStats.reuses(),
                        "candidateStores", UpdatedChunkCacheStats.candidateStores(), "captureFailures", UpdatedChunkCacheStats.captureFailures(),
                        "blockEntitySkips", UpdatedChunkCacheStats.blockEntitySkips(),
                        "provenanceEntries", UpdatedChunkCacheStats.entries(), "cacheReuse", ChunkLocalCacheReuseStats.snapshot(),
                        "chunkStats", com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotStats.snapshotReport()));
                mc.stop();
            } else if (ticks > 3000) {
                RefreshProbe.write("client-timeout.json", Map.of("ticks", ticks, "connected", mc.level != null));
                mc.stop();
            }
        });
    }
}
