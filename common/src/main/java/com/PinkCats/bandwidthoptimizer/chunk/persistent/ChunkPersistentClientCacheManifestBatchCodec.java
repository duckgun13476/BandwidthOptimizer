package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameCodec;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

public final class ChunkPersistentClientCacheManifestBatchCodec {

    private static final int BATCH_VERSION = 1;

    private ChunkPersistentClientCacheManifestBatchCodec() {}

    // Packs many manifest entries into one control payload.
    public static byte[] encode(List<ChunkPersistentClientCache.ManifestEntry> entries, String reason) {
        return encode(entries,reason,false);
    }

    public static byte[] encode(List<ChunkPersistentClientCache.ManifestEntry> entries, String reason, boolean coreOverlay) {
        List<ChunkPersistentClientCache.ManifestEntry> safeEntries = entries == null ? List.of() : entries;
        ByteBuf byteBuf = Unpooled.buffer();
        try {
            FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
            friendlyByteBuf.writeVarInt(coreOverlay ? 2 : BATCH_VERSION);
            friendlyByteBuf.writeUtf(reason == null || reason.isBlank() ? "persistent_client_cache_manifest_batch" : reason);
            friendlyByteBuf.writeVarInt(safeEntries.size());
            for (ChunkPersistentClientCache.ManifestEntry entry : safeEntries) {
                writeEntry(friendlyByteBuf, entry);

            }
            if(coreOverlay) {
                int claims=0;
                for(var entry:safeEntries)if(entry!=null && com.PinkCats.bandwidthoptimizer.chunk.refresh.ChunkCoreClaims.hash(entry.coreHash()))claims++;
                friendlyByteBuf.writeVarInt(claims);
                for(int i=0;i<safeEntries.size();i++) {
                    var entry=safeEntries.get(i);
                    if(entry!=null && com.PinkCats.bandwidthoptimizer.chunk.refresh.ChunkCoreClaims.hash(entry.coreHash())) {
                        friendlyByteBuf.writeVarInt(i);friendlyByteBuf.writeBytes(java.util.HexFormat.of().parseHex(entry.coreHash()));
                    }
                }
            }
            return ByteBufUtil.getBytes(byteBuf);
        } finally {
            byteBuf.release();
        }
    }

    // Expands a batch back into the existing per-entry frame shape.
    public static List<ChunkHotspotFrame> decode(byte[] payloadBytes, String fallbackReason) {
        if (payloadBytes == null || payloadBytes.length == 0) {
            return List.of();
        }

        ByteBuf byteBuf = Unpooled.wrappedBuffer(payloadBytes);
        try {
            FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(byteBuf);
            int version = friendlyByteBuf.readVarInt();
            if (version != BATCH_VERSION && version != 2) {
                throw new IllegalArgumentException("Unsupported chunk manifest batch version: " + version);
            }
            String reason = friendlyByteBuf.readUtf();
            if (reason == null || reason.isBlank()) {
                reason = fallbackReason == null || fallbackReason.isBlank()
                        ? "persistent_client_cache_manifest_batch"
                        : fallbackReason;
            }
            int count = friendlyByteBuf.readVarInt();
            if (count < 0 || count > 16_384) {
                throw new IllegalArgumentException("Invalid chunk manifest batch count: " + count);
            }
            ArrayList<ChunkHotspotFrame> frames = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                frames.add(readEntry(friendlyByteBuf, reason));
            }
            if(version==2) {
                int claims=friendlyByteBuf.readVarInt(),previous=-1;
                if(claims<0 || claims>count)throw new IllegalArgumentException("Invalid core claim count");
                for(int i=0;i<claims;i++) {
                    int index=friendlyByteBuf.readVarInt();
                    if(index<=previous || index>=count)throw new IllegalArgumentException("Invalid core claim index");
                    previous=index;byte[] hash=new byte[32];friendlyByteBuf.readBytes(hash);
                    var f=frames.get(index);
                    frames.set(index,new ChunkHotspotFrame(f.protocolVersion(),f.operation(),f.epoch(),f.observedPacketCount(),
                            f.protocolName(),f.packetClassName(),f.hotspotKind(),f.laneKind(),f.coordinate(),f.originalEncodedBytes(),
                            f.fullSnapshotVersion(),f.laneVersion(),java.util.HexFormat.of().formatHex(hash),f.payloadHash(),f.deltaBytesSinceFullSnapshot(),f.reason()));
                }
            }
            if (friendlyByteBuf.isReadable()) {
                throw new IllegalArgumentException("Chunk manifest batch left extra bytes: " + friendlyByteBuf.readableBytes());
            }
            return List.copyOf(frames);
        } finally {
            byteBuf.release();
        }
    }

    private static void writeEntry(
            FriendlyByteBuf friendlyByteBuf,
            ChunkPersistentClientCache.ManifestEntry entry
    ) {
        ChunkPersistentClientCache.ManifestEntry safeEntry = entry == null
                ? new ChunkPersistentClientCache.ManifestEntry(ChunkPacketCoordinate.unknown(), "", "PLAY", "", 1L, 0, 0L)
                : entry;
        ChunkPacketCoordinate coordinate = safeEntry.coordinate() == null
                ? ChunkPacketCoordinate.unknown()
                : safeEntry.coordinate();
        friendlyByteBuf.writeBoolean(coordinate.present());
        if (coordinate.present()) {
            friendlyByteBuf.writeVarInt(coordinate.chunkX());
            friendlyByteBuf.writeVarInt(coordinate.chunkZ());
        }
        friendlyByteBuf.writeUtf(safeText(safeEntry.payloadHash()));
        friendlyByteBuf.writeUtf(safeText(safeEntry.protocolName()));
        friendlyByteBuf.writeUtf(safeText(safeEntry.packetClassName()));
        friendlyByteBuf.writeLong(Math.max(safeEntry.fullSnapshotVersion(), 1L));
        friendlyByteBuf.writeVarInt(Math.max(safeEntry.encodedBytes(), 0));
        friendlyByteBuf.writeLong(Math.max(safeEntry.lastUsedAtMillis(), 0L));
    }

    private static ChunkHotspotFrame readEntry(FriendlyByteBuf friendlyByteBuf, String reason) {
        boolean present = friendlyByteBuf.readBoolean();
        ChunkPacketCoordinate coordinate = present
                ? ChunkPacketCoordinate.ofChunk(friendlyByteBuf.readVarInt(), friendlyByteBuf.readVarInt())
                : ChunkPacketCoordinate.unknown();
        String payloadHash = friendlyByteBuf.readUtf();
        String protocolName = friendlyByteBuf.readUtf();
        String packetClassName = friendlyByteBuf.readUtf();
        long fullSnapshotVersion = friendlyByteBuf.readLong();
        int encodedBytes = friendlyByteBuf.readVarInt();
        friendlyByteBuf.readLong();
        return new ChunkHotspotFrame(
                ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                ChunkHotspotFrameOp.CLIENT_CACHE_MANIFEST,
                0L,
                0L,
                protocolName == null || protocolName.isBlank() ? "PLAY" : protocolName,
                packetClassName == null ? "" : packetClassName,
                ChunkHotspotKind.FULL_CHUNK,
                ChunkLaneKind.FULL,
                coordinate,
                Math.max(encodedBytes, 0),
                Math.max(fullSnapshotVersion, 1L),
                0L,
                payloadHash == null ? "" : payloadHash,
                payloadHash == null ? "" : payloadHash,
                0L,
                reason == null ? "" : reason
        );
    }

    private static String safeText(String text) {
        return text == null ? "" : text;
    }
}
