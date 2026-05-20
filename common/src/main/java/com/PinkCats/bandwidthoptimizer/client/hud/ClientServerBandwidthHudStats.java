package com.PinkCats.bandwidthoptimizer.client.hud;

import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsPayload;

public final class ClientServerBandwidthHudStats {

    private static final long STALE_AFTER_MILLIS = 1_500L;

    private static volatile Snapshot latestSnapshot = Snapshot.empty();

    private ClientServerBandwidthHudStats() {}


    public static void accept(ServerBandwidthStatsPayload payload) {
        if (payload == null) {
            latestSnapshot = Snapshot.empty();
            return;
        }
        latestSnapshot = new Snapshot(
                System.currentTimeMillis(),
                payload.capturedAtMillis(),
                payload.activeChannels(),
                payload.boundPlayers(),
                payload.outboundRawEncodedBytes(),
                payload.outboundVanillaCompressedEstimateBytes(),
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
                payload.serverCreateGateReleasedPackets()
        );
    }


    public static void reset() {
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
            long serverCreateGateReleasedPackets
    ) {

        private static Snapshot empty() {
            return new Snapshot(0L, 0L, 0, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }

        public boolean fresh() {
            return receivedAtMillis > 0L && System.currentTimeMillis() - receivedAtMillis <= STALE_AFTER_MILLIS;
        }

        public double outboundWireRatioPercent() {
            if (outboundVanillaCompressedEstimateBytes <= 0L) {
                return 0.0D;
            }
            return (double) outboundWireBytes * 100.0D / (double) outboundVanillaCompressedEstimateBytes;
        }

        public double createGateSavedRatioPercent() {
            if (serverCreateGateObservedBytes <= 0L) {
                return 0.0D;
            }
            return (double) serverCreateGateSavedBytes * 100.0D / (double) serverCreateGateObservedBytes;
        }
    }
}
