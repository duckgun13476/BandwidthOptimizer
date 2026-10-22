package compactrefprobe;

import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotStats;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@Mod("compactrefprobe")
public final class CompactRefProbe {
    private static final Path ROOT = Path.of(System.getProperty("compactrefprobe.root"));
    private static MinecraftServer server;
    private static int ticks, stage, stageTick, clientTicks;

    public CompactRefProbe(IEventBus ignored) {
        if (FMLEnvironment.dist == Dist.CLIENT) installClient();
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> server = event.getServer());
        NeoForge.EVENT_BUS.addListener(CompactRefProbe::tick);
    }

    private static void tick(ServerTickEvent.Post event) {
        if (server == null) return;
        ticks++;
        if (Files.exists(ROOT.resolve("client-result.json"))) { server.halt(false); return; }
        if (ticks > 3600) { write("server-timeout.json", Map.of("stage", stage)); server.halt(false); return; }
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        var player = players.getFirst();
        int elapsed = ticks - stageTick;
        if (stage == 0) {
            player.setGameMode(GameType.SPECTATOR);
            player.teleportTo(1.5, 66, 1.5);
            advance();
        } else if (stage == 1 && elapsed >= 160) {
            player.teleportTo(513.5, 66, 513.5); advance();
        } else if (stage == 2 && elapsed >= 220) {
            player.teleportTo(1.5, 66, 1.5); advance();
        } else if (stage == 3 && elapsed >= 120) {
            player.teleportTo(server.getLevel(Level.NETHER), 1.5, 100, 1.5, Set.of(), 0, 0); advance();
        } else if (stage == 4 && elapsed >= 120) {
            player.teleportTo(server.overworld(), 1.5, 66, 1.5, Set.of(), 0, 0); advance();
        } else if (stage == 5 && elapsed >= 160) {
            var channel=((com.PinkCats.bandwidthoptimizer.mixin.minecraft.ConnectionAccessor)player.connection.getConnection()).bandwidthoptimizer$getChannel();
            boolean streaming=com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStateManager.getOrCreateSession(channel).isCrossFrameZstdEnabled();
            write("server-result.json", Map.of("completed", true, "ticks", ticks, "dimensionRoundTrip", true,
                    "streaming",streaming,"chunkStats", ChunkHotspotStats.snapshotReport()));
            advance();
        }
    }

    private static void advance() { stage++; stageTick = ticks; }

    private static void installClient() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            if (++clientTicks % 20 != 0) return;
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (Files.exists(ROOT.resolve("server-result.json")) && mc.level != null) {
                boolean loaded = mc.level.dimension() == Level.OVERWORLD && mc.level.hasChunk(0, 0);
                write("client-result.json", Map.of("finalChunkLoaded", loaded, "chunkStats", ChunkHotspotStats.snapshotReport()));
                mc.stop();
            } else if (clientTicks > 4200) {
                write("client-timeout.json", Map.of("ticks", clientTicks)); mc.stop();
            }
        });
    }

    public static void write(String name, Object value) {
        try { Files.writeString(ROOT.resolve(name), new GsonBuilder().setPrettyPrinting().create().toJson(value)); }
        catch (java.io.IOException failure) { throw new RuntimeException(failure); }
    }
}
