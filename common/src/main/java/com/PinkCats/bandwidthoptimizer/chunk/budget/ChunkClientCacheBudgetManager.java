package com.PinkCats.bandwidthoptimizer.chunk.budget;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.debug.ChunkLoadDelayProbe;
import com.PinkCats.bandwidthoptimizer.chunk.integration.ChunkRuntimeReferenceStore;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.ChunkTransportControlFrameSender;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.shadow.ChunkShadowSnapshotManager;
import com.PinkCats.bandwidthoptimizer.client.config.ClientChunkCacheConfig;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.LoaderEnvironmentCompat;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

import java.util.LinkedHashMap;
import java.util.List;

public final class ChunkClientCacheBudgetManager {

    private ChunkClientCacheBudgetManager() {}

    //Client notice
    public static void enforceInboundBudget(ChannelHandlerContext context, String reason) {
        if (!shouldManageClientBudget(context)) {
            return;
        }

        ChunkClientCacheUsage usageBeforeTrim = readCurrentUsage();
        long maxCacheBytes = ClientChunkCacheConfig.chunkCacheMaxMemoryBytes();
        long recycleTriggerFreeBytes = ClientChunkCacheConfig.chunkCacheRecycleTriggerFreeBytes();
        long recycleTriggerUsedBytes = Math.max(maxCacheBytes - recycleTriggerFreeBytes, 0L);
        if (usageBeforeTrim.totalBytes() <= recycleTriggerUsedBytes) {
            return;
        }

        long recycleTargetFreeBytes = ClientChunkCacheConfig.chunkCacheRecycleTargetFreeBytes();
        long recycleTargetUsedBytes = Math.max(maxCacheBytes - recycleTargetFreeBytes, 0L);

        ChunkRuntimeReferenceStore.TrimResult firstRuntimeTrimResult = recycleRuntimeReferenceCache(recycleTargetUsedBytes);
        ChunkShadowSnapshotManager.TrimResult shadowTrimResult = recycleShadowSnapshotCache(recycleTargetUsedBytes);
        invalidateMatchingRuntimeSnapshots(shadowTrimResult.evictedChunks());
        ChunkRuntimeReferenceStore.TrimResult secondRuntimeTrimResult = recycleRuntimeReferenceCache(recycleTargetUsedBytes);

        ChunkClientCacheUsage usageAfterTrim = readCurrentUsage();
        notifyRemoteForTrimmedFullSnapshots(context.channel(), shadowTrimResult.evictedChunks(), reason);
        notifyRemoteForTrimmedRuntimeFullSnapshots(
                context.channel(),
                mergeRuntimeTrimEvictions(firstRuntimeTrimResult, secondRuntimeTrimResult),
                reason
        );
        logTrimResult(
                reason,
                usageBeforeTrim,
                usageAfterTrim,
                shadowTrimResult,
                countRuntimeEvictedBases(firstRuntimeTrimResult, secondRuntimeTrimResult)
        );
    }

    private static boolean shouldManageClientBudget(ChannelHandlerContext context) {
        return context != null
                && LoaderEnvironmentCompat.isClientSide()
                && context.channel() != null
                && context.channel().isOpen();
    }

    private static ChunkClientCacheUsage readCurrentUsage() {
        ChunkShadowSnapshotManager.Snapshot shadowSnapshot = ChunkShadowSnapshotManager.snapshot();
        ChunkRuntimeReferenceStore.Snapshot runtimeSnapshot = ChunkRuntimeReferenceStore.snapshot();
        return new ChunkClientCacheUsage(
                shadowSnapshot.totalEncodedBytes(),
                runtimeSnapshot.totalBytes()
        );
    }

    private static ChunkRuntimeReferenceStore.TrimResult recycleRuntimeReferenceCache(long totalBudgetTargetBytes) {
        ChunkClientCacheUsage currentUsage = readCurrentUsage();
        if (currentUsage.totalBytes() <= totalBudgetTargetBytes) {
            return new ChunkRuntimeReferenceStore.TrimResult(0L, List.of());
        }

        long runtimeTargetBytes = Math.max(totalBudgetTargetBytes - currentUsage.shadowBytes(), 0L);
        return ChunkRuntimeReferenceStore.trimToTotalBytes(runtimeTargetBytes);
    }

    private static ChunkShadowSnapshotManager.TrimResult recycleShadowSnapshotCache(long totalBudgetTargetBytes) {
        ChunkClientCacheUsage currentUsage = readCurrentUsage();
        if (currentUsage.totalBytes() <= totalBudgetTargetBytes) {
            return new ChunkShadowSnapshotManager.TrimResult(0L, List.of());
        }

        long shadowTargetBytes = Math.max(totalBudgetTargetBytes - currentUsage.runtimeReferenceBytes(), 0L);
        return ChunkShadowSnapshotManager.trimToTotalBytes(shadowTargetBytes);
    }


    private static void invalidateMatchingRuntimeSnapshots(List<ChunkShadowSnapshotManager.EvictedChunkSnapshot> evictedChunks) {
        if (evictedChunks == null || evictedChunks.isEmpty()) {
            return;
        }

        for (ChunkShadowSnapshotManager.EvictedChunkSnapshot evictedChunk : evictedChunks) {
            if (evictedChunk == null || evictedChunk.channelId().isBlank()) {
                continue;
            }
            ChunkRuntimeReferenceStore.invalidateFullSnapshot(
                    evictedChunk.channelId(),
                    evictedChunk.scopeId(),
                    evictedChunk.coordinate()
            );
        }
    }

    private static void notifyRemoteForTrimmedFullSnapshots(
            Channel channel,
            List<ChunkShadowSnapshotManager.EvictedChunkSnapshot> evictedChunks,
            String reason
    ) {
        if (channel == null || evictedChunks == null || evictedChunks.isEmpty()) {
            return;
        }

        String currentChannelId = com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel);
        String safeReason = reason == null || reason.isBlank() ? "client_cache_budget_trim" : reason;
        for (ChunkShadowSnapshotManager.EvictedChunkSnapshot evictedChunk : evictedChunks) {
            if (evictedChunk == null
                    || !evictedChunk.hadFullSnapshot()
                    || !currentChannelId.equals(evictedChunk.channelId())) {
                continue;
            }
            queueClientCacheBudgetInvalidate(
                    channel,
                    evictedChunk.scopeId(),
                    evictedChunk.coordinate(),
                    evictedChunk.fullSnapshotVersion(),
                    evictedChunk.fullSnapshotHash(),
                    safeReason
            );
        }
    }


    private static void notifyRemoteForTrimmedRuntimeFullSnapshots(
            Channel channel,
            List<ChunkRuntimeReferenceStore.EvictedFullSnapshot> evictedFullSnapshots,
            String reason
    ) {
        if (channel == null || evictedFullSnapshots == null || evictedFullSnapshots.isEmpty()) {
            return;
        }

        String currentChannelId = com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel);
        String safeReasonPrefix = reason == null || reason.isBlank() ? "client_cache_budget_trim" : reason;
        for (ChunkRuntimeReferenceStore.EvictedFullSnapshot evictedFullSnapshot : evictedFullSnapshots) {
            if (evictedFullSnapshot == null
                    || !currentChannelId.equals(evictedFullSnapshot.channelId())
                    || evictedFullSnapshot.fullSnapshotVersion() <= 0L
                    || evictedFullSnapshot.payloadHash().isBlank()
                    || hasMatchingLocalShadowFullSnapshot(currentChannelId, evictedFullSnapshot)) {
                continue;
            }
            queueClientCacheBudgetInvalidate(
                    channel,
                    evictedFullSnapshot.scopeId(),
                    evictedFullSnapshot.coordinate(),
                    evictedFullSnapshot.fullSnapshotVersion(),
                    evictedFullSnapshot.payloadHash(),
                    safeReasonPrefix + "_runtime_base"
            );
        }
    }

    private static void queueClientCacheBudgetInvalidate(
            Channel channel,
            long scopeId,
            ChunkPacketCoordinate coordinate,
            long fullSnapshotVersion,
            String fullSnapshotHash,
            String reason
    ) {
        if (channel == null) {
            return;
        }

        boolean queued = ChunkTransportControlFrameSender.sendClientCacheBudgetInvalidate(
                channel,
                scopeId,
                coordinate,
                fullSnapshotVersion,
                fullSnapshotHash,
                reason
        );
        ChunkLoadDelayProbe.logClientBudgetInvalidate(
                com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel),
                scopeId,
                coordinate == null ? "<unknown>" : coordinate.logText(),
                fullSnapshotVersion,
                fullSnapshotHash,
                reason,
                queued
        );
        if (!queued) {
            return;
        }

        ChunkClientTrimmedFullBaseStore.recordTrimmedFullBase(
                com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.longText(channel),
                scopeId,
                coordinate,
                fullSnapshotVersion,
                fullSnapshotHash,
                reason
        );
    }


    private static boolean hasMatchingLocalShadowFullSnapshot(
            String channelId,
            ChunkRuntimeReferenceStore.EvictedFullSnapshot evictedFullSnapshot
    ) {
        if (channelId == null
                || channelId.isBlank()
                || evictedFullSnapshot == null
                || evictedFullSnapshot.coordinate() == null
                || !evictedFullSnapshot.coordinate().present()) {
            return false;
        }

        var chunkSnapshot = ChunkShadowSnapshotManager.snapshotChunk(
                channelId,
                evictedFullSnapshot.scopeId(),
                evictedFullSnapshot.coordinate()
        );
        return chunkSnapshot != null
                && chunkSnapshot.hasFullSnapshot()
                && chunkSnapshot.fullSnapshotVersion() == evictedFullSnapshot.fullSnapshotVersion()
                && evictedFullSnapshot.payloadHash().equals(chunkSnapshot.fullSnapshotHash());
    }


    private static List<ChunkRuntimeReferenceStore.EvictedFullSnapshot> mergeRuntimeTrimEvictions(
            ChunkRuntimeReferenceStore.TrimResult firstRuntimeTrimResult,
            ChunkRuntimeReferenceStore.TrimResult secondRuntimeTrimResult
    ) {
        LinkedHashMap<String, ChunkRuntimeReferenceStore.EvictedFullSnapshot> mergedEvictions = new LinkedHashMap<>();
        appendRuntimeTrimEvictions(mergedEvictions, firstRuntimeTrimResult);
        appendRuntimeTrimEvictions(mergedEvictions, secondRuntimeTrimResult);
        return List.copyOf(mergedEvictions.values());
    }


    private static void appendRuntimeTrimEvictions(
            LinkedHashMap<String, ChunkRuntimeReferenceStore.EvictedFullSnapshot> mergedEvictions,
            ChunkRuntimeReferenceStore.TrimResult runtimeTrimResult
    ) {
        if (mergedEvictions == null || runtimeTrimResult == null || runtimeTrimResult.evictedFullSnapshots().isEmpty()) {
            return;
        }

        for (ChunkRuntimeReferenceStore.EvictedFullSnapshot evictedFullSnapshot : runtimeTrimResult.evictedFullSnapshots()) {
            if (evictedFullSnapshot == null) {
                continue;
            }
            mergedEvictions.put(
                    buildRuntimeEvictionKey(evictedFullSnapshot),
                    evictedFullSnapshot
            );
        }
    }


    private static int countRuntimeEvictedBases(
            ChunkRuntimeReferenceStore.TrimResult firstRuntimeTrimResult,
            ChunkRuntimeReferenceStore.TrimResult secondRuntimeTrimResult
    ) {
        return mergeRuntimeTrimEvictions(firstRuntimeTrimResult, secondRuntimeTrimResult).size();
    }


    private static String buildRuntimeEvictionKey(ChunkRuntimeReferenceStore.EvictedFullSnapshot evictedFullSnapshot) {
        return evictedFullSnapshot.channelId()
                + ":"
                + evictedFullSnapshot.scopeId()
                + ":"
                + evictedFullSnapshot.coordinate().logText()
                + ":"
                + evictedFullSnapshot.fullSnapshotVersion()
                + ":"
                + evictedFullSnapshot.payloadHash();
    }

    private static void logTrimResult(
            String reason,
            ChunkClientCacheUsage usageBeforeTrim,
            ChunkClientCacheUsage usageAfterTrim,
            ChunkShadowSnapshotManager.TrimResult shadowTrimResult,
            int runtimeEvictedBaseCount
    ) {
        long releasedShadowBytes = shadowTrimResult == null ? 0L : shadowTrimResult.releasedBytes();
        int evictedChunkCount = shadowTrimResult == null ? 0 : shadowTrimResult.evictedChunks().size();
        if (usageBeforeTrim.totalBytes() == usageAfterTrim.totalBytes()
                && releasedShadowBytes <= 0L
                && runtimeEvictedBaseCount <= 0) {
            return;
        }
        if (!BO_Diag_cacheBudget()) {
            return;
        }

        DiagnosticLog.info(DiagnosticToolRegistry.Tool.CACHE_BUDGET, "event=trim reason={}, beforeTotalBytes={}, afterTotalBytes={}, beforeShadowBytes={}, afterShadowBytes={}, beforeRuntimeBytes={}, afterRuntimeBytes={}, releasedShadowBytes={}, evictedChunks={}, evictedRuntimeFullBases={}",
                reason == null || reason.isBlank() ? "client_cache_budget_trim" : reason,
                usageBeforeTrim.totalBytes(),
                usageAfterTrim.totalBytes(),
                usageBeforeTrim.shadowBytes(),
                usageAfterTrim.shadowBytes(),
                usageBeforeTrim.runtimeReferenceBytes(),
                usageAfterTrim.runtimeReferenceBytes(),
                releasedShadowBytes,
                evictedChunkCount,
                Math.max(runtimeEvictedBaseCount, 0)
        );
    }

    private record ChunkClientCacheUsage(
            long shadowBytes,
            long runtimeReferenceBytes
    ) {
        private long totalBytes() {
            return Math.max(this.shadowBytes, 0L) + Math.max(this.runtimeReferenceBytes, 0L);
        }
    }

    private static boolean BO_Diag_cacheBudget() {
        return DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.CACHE_BUDGET);
    }
}
