package com.PinkCats.bandwidthoptimizer.compat.sable;

import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.chunk.PeerState.ChunkPeerStateManager;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketDescriptor;
import com.PinkCats.bandwidthoptimizer.compat.minecraft.CustomPayloadPacketCompat;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.Packet;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SableChunkSyncCompat {

    private static final AttributeKey<SableSyncState> STATE_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:sable_chunk_sync_state");
    private static final String SABLE_CHANNEL_PREFIX = "sable:";
    private static final String START_TRACKING_SUB_LEVEL = "sable:start_tracking_sub_level";
    private static final String FINALIZE_SUB_LEVEL = "sable:finalize_sub_level";
    private static final String STOP_TRACKING_SUB_LEVEL = "sable:stop_tracking_sub_level";
    private static final String CHANGE_BOUNDS_SUB_LEVEL = "sable:change_bounds_sublevel";
    private static final String RECENTLY_SPLIT_SUB_LEVEL = "sable:recently_split_sub_level";
    private static final String STOP_MOVING_SUB_LEVEL = "sable:stop_moving_sub_level";
    private static final int MAX_TRACKED_PLOTS = 256;
    private static final int MAX_CHUNKS_PER_PLOT = 2048;

    private SableChunkSyncCompat() {}

    // Track Sable sub-level sync boundaries so chunk transport can fall back safely.
    public static PayloadDecision observeOutboundPayload(ChannelHandlerContext context, Packet<?> packet) {
        String payloadChannel = normalizePayloadChannel(CustomPayloadPacketCompat.payloadChannel(packet));
        if (context == null || payloadChannel.isBlank() || !payloadChannel.startsWith(SABLE_CHANNEL_PREFIX)) {
            return PayloadDecision.allow();
        }

        SableSyncState state = getOrCreateState(context);
        Long plotCoordinate = readPlotCoordinate(packet);
        if (START_TRACKING_SUB_LEVEL.equals(payloadChannel)) {
            state.beginInitialSync(plotCoordinate);
            logPayloadBoundary(context, "start", payloadChannel, plotCoordinate, 0);
            return PayloadDecision.forceDirect("sable_start_tracking_sub_level");
        }
        if (FINALIZE_SUB_LEVEL.equals(payloadChannel)) {
            int trackedChunks = state.finishInitialSync(plotCoordinate);
            logPayloadBoundary(context, "finalize", payloadChannel, plotCoordinate, trackedChunks);
            return PayloadDecision.forceDirect("sable_finalize_sub_level");
        }
        if (STOP_TRACKING_SUB_LEVEL.equals(payloadChannel)) {
            int invalidatedChunks = invalidateTrackedPlot(context, state, plotCoordinate);
            logPayloadBoundary(context, "stop", payloadChannel, plotCoordinate, invalidatedChunks);
            return PayloadDecision.forceDirect("sable_stop_tracking_sub_level");
        }
        if (CHANGE_BOUNDS_SUB_LEVEL.equals(payloadChannel)) {
            int invalidatedChunks = invalidateTrackedPlot(context, state, plotCoordinate);
            logPayloadBoundary(context, "change_bounds", payloadChannel, plotCoordinate, invalidatedChunks);
            return PayloadDecision.forceDirect("sable_change_bounds_sublevel");
        }
        if (RECENTLY_SPLIT_SUB_LEVEL.equals(payloadChannel)) {
            state.clearInitialSync();
            logPayloadBoundary(context, "recently_split", payloadChannel, plotCoordinate, 0);
            return PayloadDecision.forceDirect("sable_recently_split_sub_level");
        }
        if (STOP_MOVING_SUB_LEVEL.equals(payloadChannel)) {
            logPayloadBoundary(context, "stop_moving", payloadChannel, plotCoordinate, 0);
            return PayloadDecision.forceDirect("sable_stop_moving_sub_level");
        }
        return state.isInitialSyncActive()
                ? PayloadDecision.forceDirect("sable_initial_sync_payload_boundary")
                : PayloadDecision.allow();
    }

    public static boolean shouldForceFullChunkTransport(ChannelHandlerContext context, ChunkPacketDescriptor descriptor) {
        if (context == null || descriptor == null || !descriptor.hasChunkCoordinate()) {
            return false;
        }

        SableSyncState state = context.channel().attr(STATE_KEY).get();
        if (state == null || !state.isInitialSyncActive()) {
            return false;
        }
        state.rememberInitialSyncChunk(descriptor.coordinate());
        if (BO_Diag_compatDynamicGates()) {
            DiagnosticLog.info(DiagnosticToolRegistry.Tool.COMPAT_DYNAMIC_GATES, "event=sable_chunk_force_full channel={}, plot={}, chunk={}",
                    com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.shortText(context.channel()),
                    state.activePlotText(),
                    descriptor.coordinate().logText()
            );
        }
        return true;
    }

    public static boolean shouldBypassTransparentTransport(ChannelHandlerContext context, String protocolName, Packet<?> packet) {
        if (context == null || packet == null) {
            return false;
        }
        String payloadChannel = normalizePayloadChannel(CustomPayloadPacketCompat.payloadChannel(packet));
        return isLifecyclePayload(payloadChannel);
    }

    private static int invalidateTrackedPlot(
            ChannelHandlerContext context,
            SableSyncState state,
            Long plotCoordinate
    ) {
        if (context == null || state == null) {
            return 0;
        }

        Set<ChunkPacketCoordinate> trackedChunks = plotCoordinate == null
                ? state.removeUnknownTrackedChunks()
                : state.removeTrackedPlot(plotCoordinate);
        int invalidatedChunks = 0;
        for (ChunkPacketCoordinate coordinate : trackedChunks) {
            if (coordinate == null || !coordinate.present()) {
                continue;
            }
            ChunkPeerStateManager.invalidateChannelChunkAcrossScopes(context, coordinate, "sable_stop_tracking_sub_level");
            invalidatedChunks++;
        }
        return invalidatedChunks;
    }

    private static SableSyncState getOrCreateState(ChannelHandlerContext context) {
        SableSyncState existingState = context.channel().attr(STATE_KEY).get();
        if (existingState != null) {
            return existingState;
        }

        SableSyncState newState = new SableSyncState();
        SableSyncState racedState = context.channel().attr(STATE_KEY).setIfAbsent(newState);
        return racedState == null ? newState : racedState;
    }

    private static Long readPlotCoordinate(Packet<?> packet) {
        Object payload = CustomPayloadPacketCompat.payloadObject(packet);
        if (payload == null) {
            return null;
        }
        try {
            Method method = payload.getClass().getMethod("plotCoordinate");
            Object value = method.invoke(payload);
            return value instanceof Number number ? number.longValue() : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static ChunkPacketCoordinate coordinateFromPlot(long plotCoordinate) {
        return ChunkPacketCoordinate.ofChunk((int) plotCoordinate, (int) (plotCoordinate >> 32));
    }

    private static String normalizePayloadChannel(String payloadChannel) {
        return payloadChannel == null ? "" : payloadChannel.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isLifecyclePayload(String payloadChannel) {
        return START_TRACKING_SUB_LEVEL.equals(payloadChannel)
                || FINALIZE_SUB_LEVEL.equals(payloadChannel)
                || STOP_TRACKING_SUB_LEVEL.equals(payloadChannel)
                || CHANGE_BOUNDS_SUB_LEVEL.equals(payloadChannel)
                || RECENTLY_SPLIT_SUB_LEVEL.equals(payloadChannel)
                || STOP_MOVING_SUB_LEVEL.equals(payloadChannel);
    }

    private static void logPayloadBoundary(
            ChannelHandlerContext context,
            String action,
            String payloadChannel,
            Long plotCoordinate,
            int chunkCount
    ) {
        if (!BO_Diag_compatDynamicGates()) {
            return;
        }
        DiagnosticLog.info(DiagnosticToolRegistry.Tool.COMPAT_DYNAMIC_GATES, "event=sable_payload_boundary action={}, channel={}, nettyChannel={}, plot={}, chunks={}",
                action,
                payloadChannel,
                com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity.shortText(context.channel()),
                plotCoordinate == null ? "<unknown>" : coordinateFromPlot(plotCoordinate).logText(),
                chunkCount
        );
    }

    public record PayloadDecision(boolean forceDirectTransport, String reason) {
        public PayloadDecision {
            reason = reason == null ? "" : reason;
        }

        private static PayloadDecision allow() {
            return new PayloadDecision(false, "");
        }

        private static PayloadDecision forceDirect(String reason) {
            return new PayloadDecision(true, reason);
        }
    }

    private static boolean BO_Diag_compatDynamicGates() {
        return DiagnosticToolRegistry.isEnabled(DiagnosticToolRegistry.Tool.COMPAT_DYNAMIC_GATES);
    }

    private static final class SableSyncState {
        private boolean initialSyncActive;
        private Long activeInitialSyncPlot;
        private final LinkedHashMap<Long, LinkedHashSet<ChunkPacketCoordinate>> chunksByPlot = new LinkedHashMap<>();
        private final LinkedHashSet<ChunkPacketCoordinate> unknownInitialSyncChunks = new LinkedHashSet<>();

        private synchronized void beginInitialSync(Long plotCoordinate) {
            this.initialSyncActive = true;
            this.activeInitialSyncPlot = plotCoordinate;
            if (plotCoordinate != null) {
                this.chunksByPlot.put(plotCoordinate, new LinkedHashSet<>());
                trimTrackedPlots();
            }
        }

        private synchronized int finishInitialSync(Long plotCoordinate) {
            Long resolvedPlot = plotCoordinate == null ? this.activeInitialSyncPlot : plotCoordinate;
            int trackedChunks = resolvedPlot == null ? this.unknownInitialSyncChunks.size() : trackedChunkCount(resolvedPlot);
            this.initialSyncActive = false;
            this.activeInitialSyncPlot = null;
            return trackedChunks;
        }

        private synchronized boolean isInitialSyncActive() {
            return this.initialSyncActive;
        }

        private synchronized void clearInitialSync() {
            this.initialSyncActive = false;
            this.activeInitialSyncPlot = null;
        }

        private synchronized String activePlotText() {
            return this.activeInitialSyncPlot == null
                    ? "<unknown>"
                    : coordinateFromPlot(this.activeInitialSyncPlot).logText();
        }

        private synchronized void rememberInitialSyncChunk(ChunkPacketCoordinate coordinate) {
            if (!this.initialSyncActive || coordinate == null || !coordinate.present()) {
                return;
            }
            if (this.activeInitialSyncPlot == null) {
                this.unknownInitialSyncChunks.add(coordinate);
                while (this.unknownInitialSyncChunks.size() > MAX_CHUNKS_PER_PLOT) {
                    Iterator<ChunkPacketCoordinate> iterator = this.unknownInitialSyncChunks.iterator();
                    if (!iterator.hasNext()) {
                        break;
                    }
                    iterator.next();
                    iterator.remove();
                }
                return;
            }
            LinkedHashSet<ChunkPacketCoordinate> chunks = this.chunksByPlot.computeIfAbsent(
                    this.activeInitialSyncPlot,
                    ignored -> new LinkedHashSet<>()
            );
            chunks.add(coordinate);
            while (chunks.size() > MAX_CHUNKS_PER_PLOT) {
                Iterator<ChunkPacketCoordinate> iterator = chunks.iterator();
                if (!iterator.hasNext()) {
                    break;
                }
                iterator.next();
                iterator.remove();
            }
            trimTrackedPlots();
        }

        private synchronized Set<ChunkPacketCoordinate> removeTrackedPlot(long plotCoordinate) {
            LinkedHashSet<ChunkPacketCoordinate> chunks = this.chunksByPlot.remove(plotCoordinate);
            if (this.activeInitialSyncPlot != null && this.activeInitialSyncPlot.equals(plotCoordinate)) {
                clearInitialSync();
            }
            return chunks == null ? Set.of() : Set.copyOf(chunks);
        }

        private synchronized Set<ChunkPacketCoordinate> removeUnknownTrackedChunks() {
            if (this.unknownInitialSyncChunks.isEmpty()) {
                clearInitialSync();
                return Set.of();
            }
            Set<ChunkPacketCoordinate> chunks = Set.copyOf(this.unknownInitialSyncChunks);
            this.unknownInitialSyncChunks.clear();
            clearInitialSync();
            return chunks;
        }

        private synchronized int trackedChunkCount(Long plotCoordinate) {
            if (plotCoordinate == null) {
                return 0;
            }
            LinkedHashSet<ChunkPacketCoordinate> chunks = this.chunksByPlot.get(plotCoordinate);
            return chunks == null ? 0 : chunks.size();
        }

        private synchronized void trimTrackedPlots() {
            while (this.chunksByPlot.size() > MAX_TRACKED_PLOTS) {
                Iterator<Map.Entry<Long, LinkedHashSet<ChunkPacketCoordinate>>> iterator = this.chunksByPlot.entrySet().iterator();
                if (!iterator.hasNext()) {
                    return;
                }
                iterator.next();
                iterator.remove();
            }
        }
    }
}
