package com.PinkCats.bandwidthoptimizer.client.hud;

import com.PinkCats.bandwidthoptimizer.gate.IdleGateServerState;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsPayload;
import com.PinkCats.bandwidthoptimizer.server.stat.VanillaCompressionEstimator;

import java.util.List;

public final class ClientServerBandwidthHudStats {

    private static final long STALE_AFTER_MILLIS = 1_500L;

    private static volatile Snapshot latestSnapshot = Snapshot.empty();

    private ClientServerBandwidthHudStats() {}


    public static void accept(ServerBandwidthStatsPayload payload) {
        if (payload == null) {
            VanillaCompressionEstimator.setEnabled(false);
            latestSnapshot = Snapshot.empty();
            return;
        }
        VanillaCompressionEstimator.setEnabled(payload.vanillaCompressionEstimateEnabled());
        latestSnapshot = new Snapshot(
                System.currentTimeMillis(),
                payload.capturedAtMillis(),
                payload.activeChannels(),
                payload.boundPlayers(),
                payload.outboundRawEncodedBytes(),
                payload.outboundVanillaCompressedEstimateBytes(),
                payload.outboundVanillaEstimateWireBytes(),
                payload.outboundTransportFrameBytes(),
                payload.outboundBypassBytes(),
                payload.outboundWireBytes(),
                payload.inboundWireBytes(),
                payload.outboundSavedBytes(),
                payload.serverOfflineReuseConfirmedFrames(),
                payload.serverOfflineReuseConfirmedSavedBytes(),
                payload.serverOfflineReuseConfirmedWireBytes(),
                payload.serverTemporaryReuseSavedBytes(),
                payload.serverCreateGateObservedBytes(),
                payload.serverCreateGateSavedBytes(),
                payload.serverCreateGateSavedPackets(),
                payload.serverCreateGateReleasedPackets(),
                payload.recentOutboundRawEncodedBytes(),
                payload.recentOutboundWireBytes(),
                payload.serverShadowRetainedOriginalBytes(),
                payload.serverShadowRetainedOriginalPacketCount(),
                payload.serverShadowMetadataPacketCount(),
                payload.serverShadowChunkCount(),
                payload.serverShadowOriginalBytesBudget(),
                payload.serverShadowMetadataEntryLimit(),
                payload.serverShadowEvictedOriginalBytes(),
                payload.serverShadowEvictedOriginalPackets(),
                payload.serverShadowEvictedMetadataPackets(),
                payload.serverJvmUsedBytes(),
                payload.serverJvmMaxBytes(),
                payload.vanillaCompressionEstimateEnabled(),
                payload.serverCreateTransportRawBytes(),
                payload.serverCreateTransportActualBytes(),
                payload.serverCreateTransportSavedBytes(),
                payload.serverCreateTransportPackets(),
                payload.serverIdleGateSavedBytes(),
                payload.serverIdleGateSavedPackets(),
                payload.idlePlayers(),
                payload.serverChunkReuseLogicalBytes()
        );
    }


    public static void reset() {
        VanillaCompressionEstimator.setEnabled(false);
        latestSnapshot = Snapshot.empty();
    }


    public static Snapshot snapshot() {
        return latestSnapshot;
    }

    public record Snapshot(
            long receivedAtMillis,
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
            long serverIdleGateSavedPackets,
            List<IdleGateServerState.IdlePlayerSnapshot> idlePlayers,
            long serverChunkReuseLogicalBytes
    ) {

        public Snapshot {
            idlePlayers = idlePlayers == null ? List.of() : List.copyOf(idlePlayers);
        }

        private static Snapshot empty() {
            return new Snapshot(0L, 0L, 0, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, false, 0L, 0L, 0L, 0L, 0L, 0L, List.of(), 0L);
        }

        public boolean fresh() {
            return receivedAtMillis > 0L && System.currentTimeMillis() - receivedAtMillis <= STALE_AFTER_MILLIS;
        }

        public double outboundWireRatioPercent() {
            if (outboundVanillaCompressedEstimateBytes <= 0L) {
                return 0.0D;
            }
            return (double) outboundVanillaEstimateWireBytes * 100.0D / (double) outboundVanillaCompressedEstimateBytes;
        }

        public double createGateSavedRatioPercent() {
            if (serverCreateGateObservedBytes <= 0L) {
                return 0.0D;
            }
            return (double) serverCreateGateSavedBytes * 100.0D / (double) serverCreateGateObservedBytes;
        }
    }
}
