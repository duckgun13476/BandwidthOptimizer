package com.PinkCats.bandwidthoptimizer.client.cache;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketClassifier;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.packet.ClientboundPlayPacketCodec;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkPersistentClientCache;
import com.PinkCats.bandwidthoptimizer.chunk.refresh.LoadedChunkUpdateTracker;
import com.PinkCats.bandwidthoptimizer.chunk.refresh.UpdatedChunkCacheStats;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

/** Opt-in experiment: coalesce updates into one snapshot before native unload. */
public final class LoadedChunkCacheRefresh {
    private static final LoadedChunkUpdateTracker TRACKER = new LoadedChunkUpdateTracker(4096, 2);
    private static String scope = "";
    private static int levelIdentity;
    private static java.util.function.Function<Packet<?>, byte[]> encoder;
    private static java.util.function.Function<byte[], ? extends Packet<?>> decoder;
    private LoadedChunkCacheRefresh() {}

    public static void observe(Object value, boolean full) {
        if (!UpdatedChunkCacheStats.ENABLED || !(value instanceof Packet<?> packet) || !ready()) return;
        var descriptor = ChunkPacketClassifier.classifyOutboundPlayPacket("PLAY", packet);
        if (descriptor == null || !descriptor.coordinate().present()) return;
        var c = descriptor.coordinate();
        long key = LoadedChunkUpdateTracker.key(c.chunkX(), c.chunkZ());
        if (full) TRACKER.loaded(key); else TRACKER.changed(key);
    }
    public static void beforeForget(int x, int z) {
        if (!UpdatedChunkCacheStats.ENABLED || !ready()) return;
        Minecraft mc = Minecraft.getInstance();
        if (!TRACKER.forget(LoadedChunkUpdateTracker.key(x, z), mc.level.getGameTime())) return;
        capture(x, z);
    }
    public static void beforeViewCenterChange() {
        if (!UpdatedChunkCacheStats.ENABLED || !ready()) return;
        TRACKER.captureDirty(Minecraft.getInstance().level.getGameTime(), key -> capture((int) (key >> 32), (int) key));
    }
    private static void capture(int x, int z) {
        Minecraft mc = Minecraft.getInstance();
        try {
            // ClientLevel.hasChunk can report availability without a readable
            // in-range chunk. Never serialize the shared EmptyLevelChunk.
            var chunk = mc.level.getChunkSource().getChunk(x, z, false);
            if (chunk == null || com.PinkCats.bandwidthoptimizer.integration.minecraft.ChunkCoordinateCompat.x(chunk.getPos()) != x
                    || com.PinkCats.bandwidthoptimizer.integration.minecraft.ChunkCoordinateCompat.z(chunk.getPos()) != z) return;
            // Preserve wire-derived BE tags from the existing bounded cache.
            // Never invoke mod update-tag writers to construct a candidate.
            byte[] base = ChunkPersistentClientCache.loadedRefreshBase(scope, x, z);
            var packet = LoadedChunkPacketRebuilder.rebuild(base, chunk, mc.level.getLightEngine(), decoder);
            if (packet == null) return;
            byte[] bytes = encoder.apply(packet);
            if (bytes == null || bytes.length == 0 || bytes.length > 1024 * 1024) {
                UpdatedChunkCacheStats.captureFailed();
                return;
            }
            ChunkPersistentClientCache.storeInboundFullChunkAsync("PLAY", 1L, packet.getClass().getName(),
                    ChunkHotspotKind.FULL_CHUNK, ChunkLaneKind.FULL, ChunkPacketCoordinate.ofChunk(x, z), bytes,
                    UpdatedChunkCacheStats.STORE_REASON);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            // Only this optional cache capture failed. Native unload still runs.
            UpdatedChunkCacheStats.captureFailed();
        }
    }
    private static boolean ready() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !mc.isSameThread() || mc.level == null || mc.getConnection() == null) return false;
        String token = ChunkPersistentClientCache.loadedUpdateScopeToken();
        if (token.isEmpty()) return false;
        int identity = System.identityHashCode(mc.level);
        if (!scope.equals(token) || levelIdentity != identity || encoder == null) {
            try {
                var nextEncoder = ClientboundPlayPacketCodec.encoderForRegistry(mc.level.registryAccess());
                var nextDecoder = ClientboundPlayPacketCodec.decoderForRegistry(mc.level.registryAccess());
                TRACKER.clear(); scope = token; levelIdentity = identity; encoder = nextEncoder; decoder = nextDecoder;
            } catch (RuntimeException failure) {
                UpdatedChunkCacheStats.captureFailed(); return false;
            }
        }
        return true;
    }
    public static void clear() { TRACKER.clear(); scope = ""; levelIdentity = 0; encoder = null; decoder = null; }
}
