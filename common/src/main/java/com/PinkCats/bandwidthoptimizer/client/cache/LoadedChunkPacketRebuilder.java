package com.PinkCats.bandwidthoptimizer.client.cache;

import com.PinkCats.bandwidthoptimizer.chunk.packet.ClientboundPlayPacketCodec;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.NbtCompoundCompat;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ChunkSnapshotDataAccessor;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ChunkSnapshotLightAccessor;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import java.util.BitSet;

/** Rebuilds a candidate using wire-derived BE tags, never calling a BE writer.
 * Stale or partial tags only cause a hash miss; the server still verifies the
 * entire authoritative FULL before advertising any reference. */
public final class LoadedChunkPacketRebuilder {
    private LoadedChunkPacketRebuilder() {}

    public static ClientboundLevelChunkWithLightPacket rebuild(byte[] base, LevelChunk chunk,
            LevelLightEngine light, java.util.function.Function<byte[], ? extends net.minecraft.network.protocol.Packet<?>> decoder) throws ReflectiveOperationException {
        if (base == null || base.length == 0 || base.length > 1024 * 1024 || light.hasLightWork()) return null;
        var decoded = decoder.apply(base);
        if (!(decoded instanceof ClientboundLevelChunkWithLightPacket packet)
                || packet.getX() != com.PinkCats.bandwidthoptimizer.integration.minecraft.ChunkCoordinateCompat.x(chunk.getPos())
                || packet.getZ() != com.PinkCats.bandwidthoptimizer.integration.minecraft.ChunkCoordinateCompat.z(chunk.getPos())) return null;
        // Never retain the decoded packet or any BE tag beyond this capture.
        var positions = new java.util.HashSet<net.minecraft.core.BlockPos>();
        packet.getChunkData().getBlockEntitiesTagsConsumer(packet.getX(), packet.getZ())
                .accept((position, type, tag) -> positions.add(position.immutable()));
        if (!positions.equals(chunk.getBlockEntitiesPos())) return null;
        long sectionBytes = 0;
        for (var section : chunk.getSections()) {
            sectionBytes += section.getSerializedSize();
            if (sectionBytes > 1024 * 1024) return null;
        }
        FriendlyByteBuf sections = new FriendlyByteBuf(Unpooled.buffer((int) sectionBytes, (int) sectionBytes));
        try {
            ClientboundLevelChunkPacketData.extractChunkData(sections, chunk);
            if (sections.readableBytes() > 1024 * 1024) return null;
            byte[] bytes = new byte[sections.readableBytes()];
            sections.getBytes(0, bytes);
            ((ChunkSnapshotDataAccessor) packet.getChunkData()).bandwidthoptimizer$replaceBuffer(bytes);
        } finally { sections.release(); }
        Object heightmaps = packet.getChunkData().getHeightmaps();
        if (heightmaps instanceof CompoundTag tag) {
            for (String key : java.util.Set.copyOf(NbtCompoundCompat.keys(tag))) tag.remove(key);
            for (var entry : chunk.getHeightmaps()) if (entry.getKey().sendToClient())
                tag.putLongArray(entry.getKey().getSerializationKey(), entry.getValue().getRawData().clone());
        } else if (heightmaps instanceof java.util.Map<?, ?> map) {
            @SuppressWarnings("unchecked") var target = (java.util.Map<Object,Object>) map;
            target.clear();
            for (var entry : chunk.getHeightmaps()) if (entry.getKey().sendToClient())
                target.put(entry.getKey(), entry.getValue().getRawData().clone());
        } else return null;
        ClientboundLightUpdatePacketData snapshot = null;
        for (var constructor : ClientboundLightUpdatePacketData.class.getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if ((types.length == 4 || types.length == 5) && types[0] == ChunkPos.class
                    && types[1] == LevelLightEngine.class && types[2] == BitSet.class && types[3] == BitSet.class
                    && (types.length == 4 || types[4] == boolean.class)) {
                // Legacy 1.19 light data includes trustEdges. Preserve the
                // received value without depending on an obfuscated name.
                Boolean trustEdges = null;
                if (types.length == 5) {
                    for (var method : ClientboundLightUpdatePacketData.class.getMethods()) {
                        if (method.getDeclaringClass() == ClientboundLightUpdatePacketData.class
                                && method.getParameterCount() == 0 && method.getReturnType() == boolean.class) {
                            if (trustEdges != null) return null;
                            trustEdges = (Boolean) method.invoke(packet.getLightData());
                        }
                    }
                    if (trustEdges == null) return null;
                }
                Object[] args = types.length == 4 ? new Object[]{chunk.getPos(), light, null, null}
                        : new Object[]{chunk.getPos(), light, null, null, trustEdges};
                snapshot = (ClientboundLightUpdatePacketData) constructor.newInstance(args);
                break;
            }
        }
        if (snapshot == null) return null;
        ((ChunkSnapshotLightAccessor) packet).bandwidthoptimizer$replaceLight(snapshot);
        return packet;
    }
}
