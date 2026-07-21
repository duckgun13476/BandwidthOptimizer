package com.PinkCats.bandwidthoptimizer.server.stat;

import com.PinkCats.bandwidthoptimizer.client.hud.ClientServerBandwidthHudStats;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.shadow.ChunkShadowSnapshotManager;
import com.PinkCats.bandwidthoptimizer.gate.compat.create.CreateBlockEntityUpdateGate;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportSourceRankCore;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

public record ServerBandwidthStatsPayload(
        long capturedAtMillis,
        int activeChannels,
        int boundPlayers,
        long outboundRawEncodedBytes,
        long outboundVanillaCompressedEstimateBytes,
        long outboundVanillaEstimateWireBytes,
        long outboundTransportFrameBytes,
        long outboundBypassBytes,
        long outboundWireBytes,
        long inboundWireBytes,
        long outboundSavedBytes,
        long serverOfflineReuseConfirmedFrames,
        long serverOfflineReuseConfirmedSavedBytes,
        long serverOfflineReuseConfirmedWireBytes,
        long serverTemporaryReuseSavedBytes,
        long serverCreateGateObservedBytes,
        long serverCreateGateSavedBytes,
        long serverCreateGateSavedPackets,
        long serverCreateGateReleasedPackets,
        long recentOutboundRawEncodedBytes,
        long recentOutboundWireBytes,
        long serverShadowRetainedOriginalBytes,
        long serverShadowRetainedOriginalPacketCount,
        long serverShadowMetadataPacketCount,
        long serverShadowChunkCount,
        long serverShadowOriginalBytesBudget,
        long serverShadowMetadataEntryLimit,
        long serverShadowEvictedOriginalBytes,
        long serverShadowEvictedOriginalPackets,
        long serverShadowEvictedMetadataPackets,
        long serverJvmUsedBytes,
        long serverJvmMaxBytes,
        boolean vanillaCompressionEstimateEnabled,
        long serverCreateTransportRawBytes,
        long serverCreateTransportActualBytes,
        long serverCreateTransportSavedBytes,
        long serverCreateTransportPackets,
        long serverIdleGateSavedBytes,
        long serverIdleGateSavedPackets
) {

    public static ServerBandwidthStatsPayload fromTotals(ServerBandwidthStatsRegistry.TotalsSnapshot totals) {
        return fromTotals(totals, ServerBandwidthRecentWindow.update(totals));
    }

    public static ServerBandwidthStatsPayload fromTotals(
            ServerBandwidthStatsRegistry.TotalsSnapshot totals,
            ServerBandwidthRecentWindow.Snapshot recentWindow
    ) {
        if (totals == null) {
            return empty();
        }
        CreateBlockEntityUpdateGate.Snapshot createGateSnapshot = CreateBlockEntityUpdateGate.snapshotStats();
        ChunkShadowSnapshotManager.Snapshot shadowSnapshot = ChunkShadowSnapshotManager.snapshot();
        ChannelTransportSourceRankCore.CreateBlockEntityTransportSnapshot createTransportSnapshot =
                ChannelTransportSourceRankCore.snapshotCreateBlockEntityTransportStats();
        Runtime runtime = Runtime.getRuntime();
        long serverJvmUsedBytes = Math.max(runtime.totalMemory() - runtime.freeMemory(), 0L);
        long serverJvmMaxBytes = Math.max(runtime.maxMemory(), 0L);
        ServerBandwidthRecentWindow.Snapshot safeRecentWindow =
                recentWindow == null ? ServerBandwidthRecentWindow.Snapshot.empty() : recentWindow;
        long createGateObservedBytes = totals.serverCreateGateObservedBytes() > 0L
                ? totals.serverCreateGateObservedBytes()
                : createGateSnapshot.observedBytes();
        long createGateSavedBytes = totals.serverCreateGateSavedBytes() > 0L
                ? totals.serverCreateGateSavedBytes()
                : createGateSnapshot.savedBytes();
        long createGateSavedPackets = totals.serverCreateGateSavedPackets() > 0L
                ? totals.serverCreateGateSavedPackets()
                : createGateSnapshot.savedPackets();
        long createGateReleasedPackets = totals.serverCreateGateReleasedPackets() > 0L
                ? totals.serverCreateGateReleasedPackets()
                : createGateSnapshot.releasedPackets();
        return new ServerBandwidthStatsPayload(
                System.currentTimeMillis(),
                totals.activeChannels(),
                totals.boundPlayers(),
                totals.outboundRawEncodedBytes(),
                totals.outboundVanillaCompressedEstimateBytes(),
                totals.outboundVanillaEstimateWireBytes(),
                totals.outboundTransportFrameBytes(),
                totals.outboundBypassBytes(),
                totals.outboundWireBytes(),
                totals.inboundWireBytes(),
                totals.outboundSavedBytes(),
                totals.serverOfflineReuseConfirmedFrames(),
                totals.serverOfflineReuseConfirmedSavedBytes(),
                totals.serverOfflineReuseConfirmedWireBytes(),
                totals.serverTemporaryReuseSavedBytes(),
                createGateObservedBytes,
                createGateSavedBytes,
                createGateSavedPackets,
                createGateReleasedPackets,
                safeRecentWindow.outboundRawEncodedBytes(),
                safeRecentWindow.outboundWireBytes(),
                shadowSnapshot.retainedOriginalBytes(),
                shadowSnapshot.retainedOriginalPacketCount(),
                shadowSnapshot.packetCount(),
                shadowSnapshot.chunkCount(),
                shadowSnapshot.serverOriginalBytesBudget(),
                shadowSnapshot.serverMetadataEntryLimit(),
                shadowSnapshot.serverEvictedOriginalBytes(),
                shadowSnapshot.serverEvictedOriginalPackets(),
                shadowSnapshot.serverEvictedMetadataPackets(),
                serverJvmUsedBytes,
                serverJvmMaxBytes,
                VanillaCompressionEstimator.isEnabled(),
                createTransportSnapshot.rawBytes(),
                createTransportSnapshot.actualBytes(),
                createTransportSnapshot.savedBytes(),
                createTransportSnapshot.packets(),
                totals.serverIdleGateSavedBytes(),
                totals.serverIdleGateSavedPackets()
        );
    }


    public static ServerBandwidthStatsPayload empty() {
        return new ServerBandwidthStatsPayload(System.currentTimeMillis(), 0, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, false, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    public static void encode(ServerBandwidthStatsPayload payload, FriendlyByteBuf buffer) {
        ServerBandwidthStatsPayload safePayload = payload == null ? empty() : payload;
        buffer.writeLong(safePayload.capturedAtMillis());
        buffer.writeVarInt(Math.max(safePayload.activeChannels(), 0));
        buffer.writeVarInt(Math.max(safePayload.boundPlayers(), 0));
        buffer.writeVarLong(Math.max(safePayload.outboundRawEncodedBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.outboundTransportFrameBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.outboundBypassBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.outboundWireBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.inboundWireBytes(), 0L));
        buffer.writeVarLong(safePayload.outboundSavedBytes());
        buffer.writeVarLong(Math.max(safePayload.serverOfflineReuseConfirmedFrames(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverOfflineReuseConfirmedSavedBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverOfflineReuseConfirmedWireBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverTemporaryReuseSavedBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverCreateGateObservedBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverCreateGateSavedBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverCreateGateSavedPackets(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverCreateGateReleasedPackets(), 0L));
        buffer.writeVarLong(Math.max(safePayload.outboundVanillaCompressedEstimateBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.outboundVanillaEstimateWireBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.recentOutboundRawEncodedBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.recentOutboundWireBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverShadowRetainedOriginalBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverShadowRetainedOriginalPacketCount(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverShadowMetadataPacketCount(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverShadowChunkCount(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverShadowOriginalBytesBudget(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverShadowMetadataEntryLimit(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverShadowEvictedOriginalBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverShadowEvictedOriginalPackets(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverShadowEvictedMetadataPackets(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverJvmUsedBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverJvmMaxBytes(), 0L));
        buffer.writeBoolean(safePayload.vanillaCompressionEstimateEnabled());
        buffer.writeVarLong(Math.max(safePayload.serverCreateTransportRawBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverCreateTransportActualBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverCreateTransportSavedBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverCreateTransportPackets(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverIdleGateSavedBytes(), 0L));
        buffer.writeVarLong(Math.max(safePayload.serverIdleGateSavedPackets(), 0L));
    }

    public static ServerBandwidthStatsPayload decode(FriendlyByteBuf buffer) {
        long capturedAtMillis = buffer.readLong();
        int activeChannels = buffer.readVarInt();
        int boundPlayers = buffer.readVarInt();
        long outboundRawEncodedBytes = buffer.readVarLong();
        long outboundTransportFrameBytes = buffer.readVarLong();
        long outboundBypassBytes = buffer.readVarLong();
        long outboundWireBytes = buffer.readVarLong();
        long inboundWireBytes = buffer.readVarLong();
        long outboundSavedBytes = buffer.readVarLong();
        long serverOfflineReuseConfirmedFrames = buffer.readableBytes() > 0 ? buffer.readVarLong() : 0L;
        long serverOfflineReuseConfirmedSavedBytes = buffer.readableBytes() > 0 ? buffer.readVarLong() : 0L;
        long serverOfflineReuseConfirmedWireBytes = buffer.readableBytes() > 0 ? buffer.readVarLong() : 0L;
        long serverTemporaryReuseSavedBytes = buffer.readableBytes() > 0 ? buffer.readVarLong() : 0L;
        long serverCreateGateObservedBytes = buffer.readableBytes() > 0 ? buffer.readVarLong() : 0L;
        long serverCreateGateSavedBytes = buffer.readableBytes() > 0 ? buffer.readVarLong() : 0L;
        long serverCreateGateSavedPackets = buffer.readableBytes() > 0 ? buffer.readVarLong() : 0L;
        long serverCreateGateReleasedPackets = buffer.readableBytes() > 0 ? buffer.readVarLong() : 0L;
        long outboundVanillaCompressedEstimateBytes = buffer.readableBytes() > 0 ? buffer.readVarLong() : outboundRawEncodedBytes;
        long outboundVanillaEstimateWireBytes = buffer.readableBytes() > 1 ? buffer.readVarLong() : outboundWireBytes;
        long recentOutboundRawEncodedBytes = buffer.readableBytes() > 1 ? buffer.readVarLong() : 0L;
        long recentOutboundWireBytes = buffer.readableBytes() > 1 ? buffer.readVarLong() : 0L;
        long serverShadowRetainedOriginalBytes = buffer.readableBytes() > 1 ? buffer.readVarLong() : 0L;
        long serverShadowRetainedOriginalPacketCount = buffer.readableBytes() > 1 ? buffer.readVarLong() : 0L;
        long serverShadowMetadataPacketCount = buffer.readableBytes() > 1 ? buffer.readVarLong() : 0L;
        long serverShadowChunkCount = buffer.readableBytes() > 1 ? buffer.readVarLong() : 0L;
        long serverShadowOriginalBytesBudget = buffer.readableBytes() > 1 ? buffer.readVarLong() : 0L;
        long serverShadowMetadataEntryLimit = buffer.readableBytes() > 1 ? buffer.readVarLong() : 0L;
        long serverShadowEvictedOriginalBytes = buffer.readableBytes() > 1 ? buffer.readVarLong() : 0L;
        long serverShadowEvictedOriginalPackets = buffer.readableBytes() > 1 ? buffer.readVarLong() : 0L;
        long serverShadowEvictedMetadataPackets = buffer.readableBytes() > 1 ? buffer.readVarLong() : 0L;
        long serverJvmUsedBytes = buffer.readableBytes() > 1 ? buffer.readVarLong() : 0L;
        long serverJvmMaxBytes = buffer.readableBytes() > 1 ? buffer.readVarLong() : 0L;
        boolean vanillaCompressionEstimateEnabled = buffer.readableBytes() > 0 && buffer.readBoolean();
        long serverCreateTransportRawBytes = buffer.readableBytes() > 0 ? buffer.readVarLong() : 0L;
        long serverCreateTransportActualBytes = buffer.readableBytes() > 0 ? buffer.readVarLong() : 0L;
        long serverCreateTransportSavedBytes = buffer.readableBytes() > 0 ? buffer.readVarLong() : 0L;
        long serverCreateTransportPackets = buffer.readableBytes() > 0 ? buffer.readVarLong() : 0L;
        long serverIdleGateSavedBytes = buffer.readableBytes() > 0 ? buffer.readVarLong() : 0L;
        long serverIdleGateSavedPackets = buffer.readableBytes() > 0 ? buffer.readVarLong() : 0L;
        return new ServerBandwidthStatsPayload(
                capturedAtMillis,
                activeChannels,
                boundPlayers,
                outboundRawEncodedBytes,
                outboundVanillaCompressedEstimateBytes,
                outboundVanillaEstimateWireBytes,
                outboundTransportFrameBytes,
                outboundBypassBytes,
                outboundWireBytes,
                inboundWireBytes,
                outboundSavedBytes,
                serverOfflineReuseConfirmedFrames,
                serverOfflineReuseConfirmedSavedBytes,
                serverOfflineReuseConfirmedWireBytes,
                serverTemporaryReuseSavedBytes,
                serverCreateGateObservedBytes,
                serverCreateGateSavedBytes,
                serverCreateGateSavedPackets,
                serverCreateGateReleasedPackets,
                recentOutboundRawEncodedBytes,
                recentOutboundWireBytes,
                serverShadowRetainedOriginalBytes,
                serverShadowRetainedOriginalPacketCount,
                serverShadowMetadataPacketCount,
                serverShadowChunkCount,
                serverShadowOriginalBytesBudget,
                serverShadowMetadataEntryLimit,
                serverShadowEvictedOriginalBytes,
                serverShadowEvictedOriginalPackets,
                serverShadowEvictedMetadataPackets,
                serverJvmUsedBytes,
                serverJvmMaxBytes,
                vanillaCompressionEstimateEnabled,
                serverCreateTransportRawBytes,
                serverCreateTransportActualBytes,
                serverCreateTransportSavedBytes,
                serverCreateTransportPackets,
                serverIdleGateSavedBytes,
                serverIdleGateSavedPackets
        );
    }

    public byte[] toBytes() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            encode(this, buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    public static ServerBandwidthStatsPayload fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return empty();
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            return decode(buffer);
        } finally {
            buffer.release();
        }
    }

    public static void handleClientBytes(byte[] bytes) {
        ClientServerBandwidthHudStats.accept(fromBytes(bytes));
    }
}
