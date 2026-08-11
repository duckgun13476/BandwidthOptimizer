package com.PinkCats.bandwidthoptimizer.client.hud;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportRuntimeGuard;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.batch.ChannelTransportBatchRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelTransportTelemetry;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportRuntimeConfig;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkLocalCacheReuseStats;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.shadow.ChunkShadowSnapshotManager;
import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotReport;
import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotStats;
import com.PinkCats.bandwidthoptimizer.gate.IdleGateServerState;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

public final class BandwidthOptimizerHudStats {

    private static final long RECENT_WINDOW_MILLIS = 120_000L;
    private static final long SAMPLE_INTERVAL_MILLIS = 1_000L;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<Sample> RECENT_SAMPLES = new ArrayDeque<>();

    private static long lastSampleAtMillis;
    private static long lastClientRateSampleAtMillis;
    private static long lastClientRateOutboundWireBytes;
    private static long lastClientRateInboundWireBytes;
    private static long clientOutboundWireBytesPerSecond;
    private static long clientInboundWireBytesPerSecond;
    private static long lastServerRateSampleAtMillis;
    private static long lastServerRateOutboundWireBytes;
    private static long lastServerRateInboundWireBytes;
    private static long serverOutboundWireBytesPerSecond;
    private static long serverInboundWireBytesPerSecond;

    private BandwidthOptimizerHudStats() {}

    public static void reset() {
        synchronized (LOCK) {
            RECENT_SAMPLES.clear();
            lastSampleAtMillis = 0L;
            lastClientRateSampleAtMillis = 0L;
            lastClientRateOutboundWireBytes = 0L;
            lastClientRateInboundWireBytes = 0L;
            clientOutboundWireBytesPerSecond = 0L;
            clientInboundWireBytesPerSecond = 0L;
            lastServerRateSampleAtMillis = 0L;
            lastServerRateOutboundWireBytes = 0L;
            lastServerRateInboundWireBytes = 0L;
            serverOutboundWireBytesPerSecond = 0L;
            serverInboundWireBytesPerSecond = 0L;
        }
        ChannelTransportTelemetry.reset();
        ChunkHotspotStats.reset();
        ChunkLocalCacheReuseStats.reset();
        ClientServerBandwidthHudStats.reset();
    }


    public static Snapshot snapshot() {
        ChannelTransportTelemetry.Snapshot transportSnapshot = ChannelTransportTelemetry.snapshot();
        ChunkHotspotReport hotspotReport = ChunkHotspotStats.snapshotReport();
        ChunkShadowSnapshotManager.Snapshot shadowCacheSnapshot = ChunkShadowSnapshotManager.snapshot();
        ChunkLocalCacheReuseStats.Snapshot localReuseSnapshot = ChunkLocalCacheReuseStats.snapshot();
        long now = System.currentTimeMillis();

        Totals totals = buildTotals(transportSnapshot, hotspotReport, shadowCacheSnapshot, localReuseSnapshot);
        Totals recentTotals = computeRecentTotals(now, totals);
        String algorithmDisplayName = resolveAlgorithmDisplayName(
                transportSnapshot.mappingEnabled(),
                transportSnapshot.zstdEnabled()
        );
        ClientServerBandwidthHudStats.Snapshot serverSnapshot = ClientServerBandwidthHudStats.snapshot();
        ServerBandwidthStatsRegistry.TotalsSnapshot clientWireSnapshot = ServerBandwidthStatsRegistry.snapshotSessionTotals();
        WireRate clientWireRate = updateClientWireRate(now, clientWireSnapshot);
        WireRate serverWireRate = updateServerWireRate(now, serverSnapshot);

        return new Snapshot(
                totals.effectiveRawBytes(),
                totals.effectiveVanillaBaselineBytes(),
                totals.effectiveSentBytes(),
                recentTotals.effectiveRawBytes(),
                recentTotals.effectiveVanillaBaselineBytes(),
                recentTotals.effectiveSentBytes(),
                totals.optimizeRawBytes(),
                totals.optimizeVanillaBaselineBytes(),
                totals.optimizeVanillaActualBytes(),
                totals.optimizeSentBytes(),
                recentTotals.optimizeRawBytes(),
                recentTotals.optimizeVanillaBaselineBytes(),
                recentTotals.optimizeVanillaActualBytes(),
                recentTotals.optimizeSentBytes(),
                totals.chunkCacheSavedBytes(),
                recentTotals.chunkCacheSavedBytes(),
                totals.temporaryCacheSavedBytes(),
                totals.offlineCacheSavedBytes(),
                totals.temporaryCacheReusePackets(),
                totals.offlineCacheReusePackets(),
                totals.serverTemporaryCacheReusePackets(),
                totals.serverTemporaryCacheSavedBytes(),
                totals.serverOfflineCacheReusePackets(),
                totals.serverOfflineCacheSavedBytes(),
                totals.chunkCacheReusePackets(),
                totals.chunkCacheFullPackets(),
                totals.chunkCacheReuseWireBytes(),
                totals.chunkCacheFullWireBytes(),
                totals.localCacheBytes(),
                totals.localCachePacketCount(),
                totals.localCacheChunkCount(),
                totals.totalBatchCount(),
                totals.totalPacketCount(),
                totals.totalBypassPacketCount(),
                totals.totalBypassPacketBytes(),
                recentTotals.totalBypassPacketCount(),
                recentTotals.totalBypassPacketBytes(),
                totals.outboundBypassPacketBytes(),
                totals.inboundBypassPacketBytes(),
                totals.outboundBypassPacketCount(),
                totals.inboundBypassPacketCount(),
                totals.totalMapLiteralEntries(),
                totals.totalMapExactReferences(),
                totals.totalMapTemplateReferences(),
                totals.totalMapExactAdditions(),
                totals.totalMapTemplateAdditions(),
                algorithmDisplayName,
                transportSnapshot.algorithmId(),
                ChannelTransportBatchRuntimeConfig.windowMillis(),
                ChunkTransportRuntimeConfig.isEnabled(),
                ChannelTransportRuntimeGuard.isTransportAvailable(),
                ChannelTransportRuntimeGuard.unavailableReason(),
                serverSnapshot.fresh(),
                serverSnapshot.activeChannels(),
                serverSnapshot.boundPlayers(),
                serverSnapshot.outboundRawEncodedBytes(),
                serverSnapshot.outboundVanillaCompressedEstimateBytes(),
                serverSnapshot.outboundVanillaEstimateWireBytes(),
                serverSnapshot.outboundTransportFrameBytes(),
                serverSnapshot.outboundBypassBytes(),
                serverSnapshot.outboundWireBytes(),
                serverSnapshot.inboundWireBytes(),
                serverSnapshot.outboundSavedBytes(),
                serverSnapshot.serverOfflineReuseConfirmedFrames(),
                serverSnapshot.serverOfflineReuseConfirmedSavedBytes(),
                serverSnapshot.serverOfflineReuseConfirmedWireBytes(),
                serverSnapshot.serverTemporaryReuseSavedBytes(),
                serverSnapshot.serverCreateGateObservedBytes(),
                serverSnapshot.serverCreateGateSavedBytes(),
                serverSnapshot.serverCreateGateSavedPackets(),
                serverSnapshot.serverCreateGateReleasedPackets(),
                serverSnapshot.createGateSavedRatioPercent(),
                serverSnapshot.serverCreateTransportRawBytes(),
                serverSnapshot.serverCreateTransportActualBytes(),
                serverSnapshot.serverCreateTransportSavedBytes(),
                serverSnapshot.serverCreateTransportPackets(),
                serverSnapshot.serverIdleGateSavedBytes(),
                serverSnapshot.serverIdleGateSavedPackets(),
                serverSnapshot.idlePlayers(),
                serverSnapshot.recentOutboundRawEncodedBytes(),
                serverSnapshot.recentOutboundWireBytes(),
                serverSnapshot.serverShadowRetainedOriginalBytes(),
                serverSnapshot.serverShadowRetainedOriginalPacketCount(),
                serverSnapshot.serverShadowMetadataPacketCount(),
                serverSnapshot.serverShadowChunkCount(),
                serverSnapshot.serverShadowOriginalBytesBudget(),
                serverSnapshot.serverShadowMetadataEntryLimit(),
                serverSnapshot.serverShadowEvictedOriginalBytes(),
                serverSnapshot.serverShadowEvictedOriginalPackets(),
                serverSnapshot.serverShadowEvictedMetadataPackets(),
                serverSnapshot.serverJvmUsedBytes(),
                serverSnapshot.serverJvmMaxBytes(),
                clientWireRate.outboundBytesPerSecond(),
                clientWireRate.inboundBytesPerSecond(),
                serverWireRate.outboundBytesPerSecond(),
                serverWireRate.inboundBytesPerSecond(),
                serverSnapshot.outboundWireRatioPercent(),
                serverSnapshot.vanillaCompressionEstimateEnabled()
        );
    }

    private static WireRate updateClientWireRate(long nowMillis, ServerBandwidthStatsRegistry.TotalsSnapshot clientWireSnapshot) {
        if (clientWireSnapshot == null) {
            synchronized (LOCK) {
                lastClientRateSampleAtMillis = 0L;
                clientOutboundWireBytesPerSecond = 0L;
                clientInboundWireBytesPerSecond = 0L;
            }
            return WireRate.empty();
        }

        long outboundWireBytes = Math.max(clientWireSnapshot.outboundWireBytes(), 0L);
        long inboundWireBytes = Math.max(clientWireSnapshot.inboundWireBytes(), 0L);
        synchronized (LOCK) {
            if (lastClientRateSampleAtMillis <= 0L
                    || outboundWireBytes < lastClientRateOutboundWireBytes
                    || inboundWireBytes < lastClientRateInboundWireBytes) {
                lastClientRateSampleAtMillis = nowMillis;
                lastClientRateOutboundWireBytes = outboundWireBytes;
                lastClientRateInboundWireBytes = inboundWireBytes;
                clientOutboundWireBytesPerSecond = 0L;
                clientInboundWireBytesPerSecond = 0L;
                return new WireRate(clientOutboundWireBytesPerSecond, clientInboundWireBytesPerSecond);
            }

            long elapsedMillis = nowMillis - lastClientRateSampleAtMillis;
            if (elapsedMillis >= SAMPLE_INTERVAL_MILLIS) {
                clientOutboundWireBytesPerSecond = Math.round((double) positiveDelta(outboundWireBytes, lastClientRateOutboundWireBytes) * 1000.0D / elapsedMillis);
                clientInboundWireBytesPerSecond = Math.round((double) positiveDelta(inboundWireBytes, lastClientRateInboundWireBytes) * 1000.0D / elapsedMillis);
                lastClientRateSampleAtMillis = nowMillis;
                lastClientRateOutboundWireBytes = outboundWireBytes;
                lastClientRateInboundWireBytes = inboundWireBytes;
            }
            return new WireRate(clientOutboundWireBytesPerSecond, clientInboundWireBytesPerSecond);
        }
    }

    private static WireRate updateServerWireRate(long nowMillis, ClientServerBandwidthHudStats.Snapshot serverSnapshot) {
        if (serverSnapshot == null || !serverSnapshot.fresh()) {
            synchronized (LOCK) {
                lastServerRateSampleAtMillis = 0L;
                serverOutboundWireBytesPerSecond = 0L;
                serverInboundWireBytesPerSecond = 0L;
            }
            return WireRate.empty();
        }

        long outboundWireBytes = Math.max(serverSnapshot.outboundWireBytes(), 0L);
        long inboundWireBytes = Math.max(serverSnapshot.inboundWireBytes(), 0L);
        synchronized (LOCK) {
            if (lastServerRateSampleAtMillis <= 0L
                    || outboundWireBytes < lastServerRateOutboundWireBytes
                    || inboundWireBytes < lastServerRateInboundWireBytes) {
                lastServerRateSampleAtMillis = nowMillis;
                lastServerRateOutboundWireBytes = outboundWireBytes;
                lastServerRateInboundWireBytes = inboundWireBytes;
                serverOutboundWireBytesPerSecond = 0L;
                serverInboundWireBytesPerSecond = 0L;
                return new WireRate(serverOutboundWireBytesPerSecond, serverInboundWireBytesPerSecond);
            }

            long elapsedMillis = nowMillis - lastServerRateSampleAtMillis;
            if (elapsedMillis >= SAMPLE_INTERVAL_MILLIS) {
                serverOutboundWireBytesPerSecond = Math.round((double) positiveDelta(outboundWireBytes, lastServerRateOutboundWireBytes) * 1000.0D / elapsedMillis);
                serverInboundWireBytesPerSecond = Math.round((double) positiveDelta(inboundWireBytes, lastServerRateInboundWireBytes) * 1000.0D / elapsedMillis);
                lastServerRateSampleAtMillis = nowMillis;
                lastServerRateOutboundWireBytes = outboundWireBytes;
                lastServerRateInboundWireBytes = inboundWireBytes;
            }
            return new WireRate(serverOutboundWireBytesPerSecond, serverInboundWireBytesPerSecond);
        }
    }


    private static Totals buildTotals(
            ChannelTransportTelemetry.Snapshot transportSnapshot,
            ChunkHotspotReport hotspotReport,
            ChunkShadowSnapshotManager.Snapshot shadowCacheSnapshot,
            ChunkLocalCacheReuseStats.Snapshot localReuseSnapshot
    ) {
        ChannelTransportTelemetry.DirectionSnapshot outboundTransport =
                transportSnapshot == null ? null : transportSnapshot.outbound();
        ChannelTransportTelemetry.DirectionSnapshot inboundTransport =
                transportSnapshot == null ? null : transportSnapshot.inbound();
        ChunkHotspotReport.DirectionTotals inboundChunk =
                hotspotReport == null ? ChunkHotspotReport.DirectionTotals.empty() : safeDirectionTotals(hotspotReport.inboundTotals());
        ChunkHotspotReport.OperationTotals refTotals =
                safeOperationTotals(inboundChunk.operationTotals(), ChunkHotspotFrameOp.PUBLISH_REF);
        ChunkHotspotReport.OperationTotals patchTotals =
                safeOperationTotals(inboundChunk.operationTotals(), ChunkHotspotFrameOp.PUBLISH_PATCH);
        ChunkHotspotReport.OperationTotals fullTotals =
                safeOperationTotals(inboundChunk.operationTotals(), ChunkHotspotFrameOp.PUBLISH_FULL);

        long optimizeRawBytes = inboundTransport == null ? 0L : inboundTransport.baselineBytes();
        long optimizeVanillaBaselineBytes = inboundTransport == null ? 0L : inboundTransport.vanillaCompressedEstimateBytes();
        long optimizeVanillaActualBytes = inboundTransport == null ? 0L : inboundTransport.vanillaEstimateActualBytes();
        long optimizeSentBytes = inboundTransport == null ? 0L : inboundTransport.transportFrameBytes();
        long chunkCacheSavedBytes = savedBytes(
                inboundChunk.totalLogicalPacketBytes(),
                inboundChunk.totalWireFrameBytes()
        );
        ChunkLocalCacheReuseStats.Snapshot safeLocalReuseSnapshot =
                localReuseSnapshot == null ? ChunkLocalCacheReuseStats.Snapshot.empty() : localReuseSnapshot;
        long totalBatchCount = inboundTransport == null ? 0L : inboundTransport.frameCount();
        long totalPacketCount = inboundTransport == null ? 0L : inboundTransport.packetCount();
        long outboundBypassPacketCount = outboundTransport == null ? 0L : outboundTransport.bypassPacketCount();
        long inboundBypassPacketCount = inboundTransport == null ? 0L : inboundTransport.bypassPacketCount();
        long outboundBypassPacketBytes = outboundTransport == null ? 0L : outboundTransport.bypassPacketBytes();
        long inboundBypassPacketBytes = inboundTransport == null ? 0L : inboundTransport.bypassPacketBytes();
        long totalBypassPacketCount = sum(outboundBypassPacketCount, inboundBypassPacketCount);
        long totalBypassPacketBytes = sum(outboundBypassPacketBytes, inboundBypassPacketBytes);
        long localCacheBytes = shadowCacheSnapshot == null ? 0L : shadowCacheSnapshot.totalEncodedBytes();
        long localCachePacketCount = shadowCacheSnapshot == null ? 0L : shadowCacheSnapshot.packetCount();
        long localCacheChunkCount = shadowCacheSnapshot == null ? 0L : shadowCacheSnapshot.chunkCount();
        long totalMapLiteralEntries = sum(
                outboundTransport == null ? 0L : outboundTransport.literalEntryCount(),
                inboundTransport == null ? 0L : inboundTransport.literalEntryCount()
        );
        long totalMapExactReferences = sum(
                outboundTransport == null ? 0L : outboundTransport.exactReferenceCount(),
                inboundTransport == null ? 0L : inboundTransport.exactReferenceCount()
        );
        long totalMapTemplateReferences = sum(
                outboundTransport == null ? 0L : outboundTransport.templateReferenceCount(),
                inboundTransport == null ? 0L : inboundTransport.templateReferenceCount()
        );
        long totalMapExactAdditions = sum(
                outboundTransport == null ? 0L : outboundTransport.exactAdditionCount(),
                inboundTransport == null ? 0L : inboundTransport.exactAdditionCount()
        );
        long totalMapTemplateAdditions = sum(
                outboundTransport == null ? 0L : outboundTransport.templateAdditionCount(),
                inboundTransport == null ? 0L : inboundTransport.templateAdditionCount()
        );

        return new Totals(
                optimizeRawBytes + chunkCacheSavedBytes,
                optimizeVanillaBaselineBytes + chunkCacheSavedBytes,
                optimizeSentBytes,
                optimizeRawBytes,
                optimizeVanillaBaselineBytes,
                optimizeVanillaActualBytes,
                optimizeSentBytes,
                chunkCacheSavedBytes,
                safeLocalReuseSnapshot.temporaryReuseSavedBytes(),
                safeLocalReuseSnapshot.offlineReuseSavedBytes(),
                safeLocalReuseSnapshot.temporaryReusePackets(),
                safeLocalReuseSnapshot.offlineReusePackets(),
                safeLocalReuseSnapshot.serverTemporaryReusePackets(),
                safeLocalReuseSnapshot.serverTemporaryReuseSavedBytes(),
                safeLocalReuseSnapshot.serverOfflineReusePackets(),
                safeLocalReuseSnapshot.serverOfflineReuseSavedBytes(),
                Math.max(refTotals.frameCount() + patchTotals.frameCount(), 0L),
                Math.max(fullTotals.frameCount(), 0L),
                Math.max(refTotals.wireFrameBytes() + patchTotals.wireFrameBytes(), 0L),
                Math.max(fullTotals.wireFrameBytes(), 0L),
                localCacheBytes,
                localCachePacketCount,
                localCacheChunkCount,
                totalBatchCount,
                totalPacketCount,
                totalBypassPacketCount,
                totalBypassPacketBytes,
                outboundBypassPacketBytes,
                inboundBypassPacketBytes,
                outboundBypassPacketCount,
                inboundBypassPacketCount,
                totalMapLiteralEntries,
                totalMapExactReferences,
                totalMapTemplateReferences,
                totalMapExactAdditions,
                totalMapTemplateAdditions
        );
    }


    private static Totals computeRecentTotals(long now, Totals totals) {
        synchronized (LOCK) {
            if (now - lastSampleAtMillis >= SAMPLE_INTERVAL_MILLIS || RECENT_SAMPLES.isEmpty()) {
                RECENT_SAMPLES.addLast(new Sample(
                        now,
                        totals.effectiveRawBytes(),
                        totals.effectiveVanillaBaselineBytes(),
                        totals.effectiveSentBytes(),
                        totals.optimizeRawBytes(),
                        totals.optimizeVanillaBaselineBytes(),
                        totals.optimizeVanillaActualBytes(),
                        totals.optimizeSentBytes(),
                        totals.chunkCacheSavedBytes(),
                        totals.totalBatchCount(),
                        totals.totalPacketCount(),
                        totals.totalBypassPacketCount(),
                        totals.totalBypassPacketBytes()
                ));
                lastSampleAtMillis = now;
            }

            pruneSamples(now);
            Sample firstSample = RECENT_SAMPLES.peekFirst();
            if (firstSample == null) {
                return Totals.empty();
            }

            return new Totals(
                    positiveDelta(totals.effectiveRawBytes(), firstSample.effectiveRawBytes()),
                    positiveDelta(totals.effectiveVanillaBaselineBytes(), firstSample.effectiveVanillaBaselineBytes()),
                    positiveDelta(totals.effectiveSentBytes(), firstSample.effectiveSentBytes()),
                    positiveDelta(totals.optimizeRawBytes(), firstSample.optimizeRawBytes()),
                    positiveDelta(totals.optimizeVanillaBaselineBytes(), firstSample.optimizeVanillaBaselineBytes()),
                    positiveDelta(totals.optimizeVanillaActualBytes(), firstSample.optimizeVanillaActualBytes()),
                    positiveDelta(totals.optimizeSentBytes(), firstSample.optimizeSentBytes()),
                    positiveDelta(totals.chunkCacheSavedBytes(), firstSample.chunkCacheSavedBytes()),
                    totals.temporaryCacheSavedBytes(),
                    totals.offlineCacheSavedBytes(),
                    totals.temporaryCacheReusePackets(),
                    totals.offlineCacheReusePackets(),
                    totals.serverTemporaryCacheReusePackets(),
                    totals.serverTemporaryCacheSavedBytes(),
                    totals.serverOfflineCacheReusePackets(),
                    totals.serverOfflineCacheSavedBytes(),
                    0L,
                    0L,
                    0L,
                    0L,
                    totals.localCacheBytes(),
                    totals.localCachePacketCount(),
                    totals.localCacheChunkCount(),
                    positiveDelta(totals.totalBatchCount(), firstSample.totalBatchCount()),
                    positiveDelta(totals.totalPacketCount(), firstSample.totalPacketCount()),
                    positiveDelta(totals.totalBypassPacketCount(), firstSample.totalBypassPacketCount()),
                    positiveDelta(totals.totalBypassPacketBytes(), firstSample.totalBypassPacketBytes()),
                    totals.outboundBypassPacketBytes(),
                    totals.inboundBypassPacketBytes(),
                    totals.outboundBypassPacketCount(),
                    totals.inboundBypassPacketCount(),
                    totals.totalMapLiteralEntries(),
                    totals.totalMapExactReferences(),
                    totals.totalMapTemplateReferences(),
                    totals.totalMapExactAdditions(),
                    totals.totalMapTemplateAdditions()
            );
        }
    }


    private static void pruneSamples(long now) {
        while (RECENT_SAMPLES.size() > 1 && now - RECENT_SAMPLES.peekFirst().timestampMillis() > RECENT_WINDOW_MILLIS) {
            RECENT_SAMPLES.removeFirst();
        }
    }


    private static String resolveAlgorithmDisplayName(boolean mappingEnabled, boolean zstdEnabled) {
        if (mappingEnabled && zstdEnabled) {
            return "TD_SZ";
        }
        if (mappingEnabled) {
            return "TD";
        }
        if (zstdEnabled) {
            return "SZ";
        }
        return "PASS";
    }


    private static ChunkHotspotReport.DirectionTotals safeDirectionTotals(ChunkHotspotReport.DirectionTotals directionTotals) {
        return directionTotals == null ? ChunkHotspotReport.DirectionTotals.empty() : directionTotals;
    }


    private static ChunkHotspotReport.OperationTotals safeOperationTotals(
            Map<ChunkHotspotFrameOp, ChunkHotspotReport.OperationTotals> operationTotals,
            ChunkHotspotFrameOp operation
    ) {
        if (operationTotals == null || operation == null)
            return ChunkHotspotReport.OperationTotals.empty();
        return operationTotals.getOrDefault(operation, ChunkHotspotReport.OperationTotals.empty());
    }


    private static long savedBytes(long logicalBytes, long wireBytes) {
        return Math.max(logicalBytes - wireBytes, 0L);
    }


    private static long positiveDelta(long currentValue, long previousValue) {
        return Math.max(currentValue - previousValue, 0L);
    }


    private static long sum(long leftValue, long rightValue) {
        return Math.max(leftValue, 0L) + Math.max(rightValue, 0L);
    }

    private record Sample(
            long timestampMillis,
            long effectiveRawBytes,
            long effectiveVanillaBaselineBytes,
            long effectiveSentBytes,
            long optimizeRawBytes,
            long optimizeVanillaBaselineBytes,
            long optimizeVanillaActualBytes,
            long optimizeSentBytes,
            long chunkCacheSavedBytes,
            long totalBatchCount,
            long totalPacketCount,
            long totalBypassPacketCount,
            long totalBypassPacketBytes
    ) {
    }

    private record WireRate(long outboundBytesPerSecond, long inboundBytesPerSecond) {
        private static WireRate empty() {
            return new WireRate(0L, 0L);
        }
    }

    private record Totals(
            long effectiveRawBytes,
            long effectiveVanillaBaselineBytes,
            long effectiveSentBytes,
            long optimizeRawBytes,
            long optimizeVanillaBaselineBytes,
            long optimizeVanillaActualBytes,
            long optimizeSentBytes,
            long chunkCacheSavedBytes,
            long temporaryCacheSavedBytes,
            long offlineCacheSavedBytes,
            long temporaryCacheReusePackets,
            long offlineCacheReusePackets,
            long serverTemporaryCacheReusePackets,
            long serverTemporaryCacheSavedBytes,
            long serverOfflineCacheReusePackets,
            long serverOfflineCacheSavedBytes,
            long chunkCacheReusePackets,
            long chunkCacheFullPackets,
            long chunkCacheReuseWireBytes,
            long chunkCacheFullWireBytes,
            long localCacheBytes,
            long localCachePacketCount,
            long localCacheChunkCount,
            long totalBatchCount,
            long totalPacketCount,
            long totalBypassPacketCount,
            long totalBypassPacketBytes,
            long outboundBypassPacketBytes,
            long inboundBypassPacketBytes,
            long outboundBypassPacketCount,
            long inboundBypassPacketCount,
            long totalMapLiteralEntries,
            long totalMapExactReferences,
            long totalMapTemplateReferences,
            long totalMapExactAdditions,
            long totalMapTemplateAdditions
    ) {
        private static Totals empty() {
            return new Totals(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
    }

    public record Snapshot(
            long effectiveTotalRawBytes,
            long effectiveTotalVanillaBaselineBytes,
            long effectiveTotalSentBytes,
            long effectiveRecentRawBytes,
            long effectiveRecentVanillaBaselineBytes,
            long effectiveRecentSentBytes,
            long optimizeTotalRawBytes,
            long optimizeTotalVanillaBaselineBytes,
            long optimizeTotalVanillaActualBytes,
            long optimizeTotalSentBytes,
            long optimizeRecentRawBytes,
            long optimizeRecentVanillaBaselineBytes,
            long optimizeRecentVanillaActualBytes,
            long optimizeRecentSentBytes,
            long chunkCacheSavedTotalBytes,
            long chunkCacheSavedRecentBytes,
            long temporaryCacheSavedTotalBytes,
            long offlineCacheSavedTotalBytes,
            long temporaryCacheReuseTotalPackets,
            long offlineCacheReuseTotalPackets,
            long serverTemporaryCacheReuseTotalPackets,
            long serverTemporaryCacheSavedTotalBytes,
            long serverOfflineCacheReuseTotalPackets,
            long serverOfflineCacheSavedTotalBytes,
            long chunkCacheReuseTotalPackets,
            long chunkCacheFullTotalPackets,
            long chunkCacheReuseWireTotalBytes,
            long chunkCacheFullWireTotalBytes,
            long localCacheBytes,
            long localCachePacketCount,
            long localCacheChunkCount,
            long totalBatchCount,
            long totalPacketCount,
            long totalBypassPacketCount,
            long totalBypassPacketBytes,
            long recentBypassPacketCount,
            long recentBypassPacketBytes,
            long outboundBypassPacketBytes,
            long inboundBypassPacketBytes,
            long outboundBypassPacketCount,
            long inboundBypassPacketCount,
            long totalMapLiteralEntries,
            long totalMapExactReferences,
            long totalMapTemplateReferences,
            long totalMapExactAdditions,
            long totalMapTemplateAdditions,
            String algorithmDisplayName,
            String algorithmId,
            long batchWindowMillis,
            boolean chunkTransportEnabled,
            boolean transportAvailable,
            String transportUnavailableReason,
            boolean serverStatsFresh,
            int serverActiveChannels,
            int serverBoundPlayers,
            long serverOutboundRawEncodedBytes,
            long serverOutboundVanillaCompressedEstimateBytes,
            long serverOutboundVanillaEstimateWireBytes,
            long serverOutboundTransportFrameBytes,
            long serverOutboundBypassBytes,
            long serverOutboundWireBytes,
            long serverInboundWireBytes,
            long serverOutboundSavedBytes,
            long serverOfflineReuseConfirmedFrames,
            long serverOfflineReuseConfirmedSavedBytes,
            long serverOfflineReuseConfirmedWireBytes,
            long serverTemporaryReuseSavedBytes,
            long serverCreateGateObservedBytes,
            long serverCreateGateSavedBytes,
            long serverCreateGateSavedPackets,
            long serverCreateGateReleasedPackets,
            double serverCreateGateSavedRatioPercent,
            long serverCreateTransportRawBytes,
            long serverCreateTransportActualBytes,
            long serverCreateTransportSavedBytes,
            long serverCreateTransportPackets,
            long serverIdleGateSavedBytes,
            long serverIdleGateSavedPackets,
            List<IdleGateServerState.IdlePlayerSnapshot> idlePlayers,
            long serverRecentOutboundRawEncodedBytes,
            long serverRecentOutboundWireBytes,
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
            long clientOutboundWireBytesPerSecond,
            long clientInboundWireBytesPerSecond,
            long serverOutboundWireBytesPerSecond,
            long serverInboundWireBytesPerSecond,
            double serverOutboundWireRatioPercent,
            boolean vanillaCompressionEstimateEnabled
    ) {

        public boolean hasData() {
            return this.totalBatchCount > 0L
                    || this.totalPacketCount > 0L
                    || this.totalBypassPacketCount > 0L
                    || this.chunkCacheSavedTotalBytes > 0L
                    || this.localCacheBytes > 0L;
        }
    }
}
