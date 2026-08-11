package com.PinkCats.bandwidthoptimizer.report.unified;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportBypassRankCore;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelTransportTelemetry;
import com.PinkCats.bandwidthoptimizer.chunk.integration.ChunkRuntimeReferenceStore;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkLocalCacheReuseStats;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.shadow.ChunkShadowSnapshotManager;
import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotReport;
import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotStats;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class UnifiedBandwidthReportCollector {
    private static final int SCHEMA_VERSION = 1;
    private static final int BYPASS_LIMIT = 100;

    private UnifiedBandwidthReportCollector() {
    }

    public static UnifiedBandwidthReport collect(String physicalSide) {
        List<UnifiedBandwidthReport.Section> sections = new ArrayList<>();
        sections.add(transportSection());
        sections.add(serverSection());
        sections.add(chunkSection());
        sections.add(diagnosticsSection());
        return new UnifiedBandwidthReport(
                SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                System.currentTimeMillis(),
                Bandwidthoptimizer.networkProtocolVersion(),
                safeText(physicalSide, "unknown"),
                "anonymous-aggregate",
                List.copyOf(sections),
                ChannelTransportBypassRankCore.snapshot(BYPASS_LIMIT)
        );
    }

    private static UnifiedBandwidthReport.Section transportSection() {
        ChannelTransportTelemetry.Snapshot snapshot = ChannelTransportTelemetry.snapshot();
        List<UnifiedBandwidthReport.Metric> metrics = new ArrayList<>();
        appendDirection(metrics, "outbound", snapshot.outbound());
        appendDirection(metrics, "inbound", snapshot.inbound());
        return section(
                "transport",
                "Transport pipeline",
                "Logical packet, mapping, Zstd body, BO frame, and bypass counters are separate accounting stages.",
                metrics
        );
    }

    private static void appendDirection(
            List<UnifiedBandwidthReport.Metric> metrics,
            String direction,
            ChannelTransportTelemetry.DirectionSnapshot snapshot
    ) {
        add(metrics, direction + ".frames", "Frames", snapshot.frameCount(), "frames", "bo_frame", direction,
                "BO carrier frame count; not socket packet count.");
        add(metrics, direction + ".packets", "Logical packets", snapshot.packetCount(), "packets", "logical_packet", direction,
                "Packets represented before BO carrier framing.");
        add(metrics, direction + ".baseline_bytes", "Logical bytes", snapshot.baselineBytes(), "bytes", "logical_packet", direction,
                "Logical encoded packet bytes before BO transport.");
        add(metrics, direction + ".vanilla_estimate_bytes", "Vanilla estimate", snapshot.vanillaCompressedEstimateBytes(), "bytes", "minecraft_compression_estimate", direction,
                "Estimated Minecraft native compression result, not measured socket traffic.");
        add(metrics, direction + ".mapping_bytes", "Mapping bytes", snapshot.mappingStageBytes(), "bytes", "mapping", direction,
                "Packet/template mapping stage output.");
        add(metrics, direction + ".zstd_body_bytes", "Zstd body bytes", snapshot.transportBodyBytes(), "bytes", "zstd_body", direction,
                "Compressed transport body before BO frame overhead.");
        add(metrics, direction + ".bo_frame_bytes", "BO frame bytes", snapshot.transportFrameBytes(), "bytes", "bo_frame", direction,
                "Final BO carrier bytes before Minecraft native compression and socket framing.");
        add(metrics, direction + ".bypass_packets", "Bypass packets", snapshot.bypassPacketCount(), "packets", "logical_packet", direction,
                "Packets intentionally kept outside the BO carrier.");
        add(metrics, direction + ".bypass_bytes", "Bypass bytes", snapshot.bypassPacketBytes(), "bytes", "logical_packet", direction,
                "Logical encoded bytes bypassing BO transport; do not add to BO frame bytes as socket wire traffic.");
        add(metrics, direction + ".literal_entries", "Literal entries", snapshot.literalEntryCount(), "entries", "mapping", direction,
                "Mapping literals emitted.");
        add(metrics, direction + ".exact_refs", "Exact references", snapshot.exactReferenceCount(), "entries", "mapping", direction,
                "Exact mapping references emitted.");
        add(metrics, direction + ".template_refs", "Template references", snapshot.templateReferenceCount(), "entries", "mapping", direction,
                "Template mapping references emitted.");
    }

    private static UnifiedBandwidthReport.Section serverSection() {
        ServerBandwidthStatsRegistry.TotalsSnapshot snapshot = ServerBandwidthStatsRegistry.snapshotSessionTotals();
        List<UnifiedBandwidthReport.Metric> metrics = new ArrayList<>();
        add(metrics, "active_channels", "Active channels", snapshot.activeChannels(), "channels", "connection", "server_session", "Currently tracked channels.");
        add(metrics, "bound_players", "Bound players", snapshot.boundPlayers(), "players", "connection", "server_session", "Currently bound player channels; identities are not exported.");
        add(metrics, "outbound_raw_bytes", "Outbound raw", snapshot.outboundRawEncodedBytes(), "bytes", "logical_packet", "server_session", "Server logical encoded bytes.");
        add(metrics, "outbound_vanilla_estimate_bytes", "Outbound vanilla estimate", snapshot.outboundVanillaCompressedEstimateBytes(), "bytes", "minecraft_compression_estimate", "server_session", "Estimated native compression baseline.");
        add(metrics, "outbound_wire_bytes", "Outbound measured wire", snapshot.outboundWireBytes(), "bytes", "socket_wire", "server_session", "Measured outbound bytes at the server wire accounting point.");
        add(metrics, "inbound_wire_bytes", "Inbound measured wire", snapshot.inboundWireBytes(), "bytes", "socket_wire", "server_session", "Measured inbound bytes at the server wire accounting point.");
        add(metrics, "outbound_saved_bytes", "Outbound saved", snapshot.outboundSavedBytes(), "bytes", "derived", "server_session", "Derived against the vanilla estimate and includes idle-gate suppression.");
        add(metrics, "offline_reuse_saved_bytes", "Offline cache saved", snapshot.serverOfflineReuseConfirmedSavedBytes(), "bytes", "chunk_reuse", "server_session", "Confirmed persistent-cache reuse savings.");
        add(metrics, "temporary_reuse_saved_bytes", "Temporary cache saved", snapshot.serverTemporaryReuseSavedBytes(), "bytes", "chunk_reuse", "server_session", "Runtime chunk reuse savings excluding confirmed offline reuse.");
        add(metrics, "create_gate_observed_bytes", "Create gate observed", snapshot.serverCreateGateObservedBytes(), "bytes", "gate_input", "server_session", "Create block-entity bytes observed by the gate.");
        add(metrics, "create_gate_saved_bytes", "Create gate saved", snapshot.serverCreateGateSavedBytes(), "bytes", "gate_suppressed", "server_session", "Create update bytes superseded or suppressed by the gate.");
        add(metrics, "idle_gate_saved_bytes", "Idle gate saved", snapshot.serverIdleGateSavedBytes(), "bytes", "gate_suppressed", "server_session", "Clientbound bytes intentionally not sent during idle gating.");
        return section("server", "Server session", "Aggregate server counters without player names, UUIDs, addresses, or channel identifiers.", metrics);
    }

    private static UnifiedBandwidthReport.Section chunkSection() {
        ChunkHotspotReport hotspot = ChunkHotspotStats.snapshotReport();
        ChunkHotspotReport.DirectionTotals outbound = hotspot == null || hotspot.outboundTotals() == null
                ? ChunkHotspotReport.DirectionTotals.empty()
                : hotspot.outboundTotals();
        ChunkHotspotReport.DirectionTotals inbound = hotspot == null || hotspot.inboundTotals() == null
                ? ChunkHotspotReport.DirectionTotals.empty()
                : hotspot.inboundTotals();
        ChunkShadowSnapshotManager.Snapshot shadow = ChunkShadowSnapshotManager.snapshot();
        ChunkRuntimeReferenceStore.Snapshot runtime = ChunkRuntimeReferenceStore.snapshot();
        ChunkLocalCacheReuseStats.Snapshot reuse = ChunkLocalCacheReuseStats.snapshot();
        List<UnifiedBandwidthReport.Metric> metrics = new ArrayList<>();
        add(metrics, "hotspot.outbound.logical_bytes", "Hotspot outbound logical", outbound.totalLogicalPacketBytes(), "bytes", "logical_packet", "session", "Chunk hotspot logical bytes.");
        add(metrics, "hotspot.outbound.frame_bytes", "Hotspot outbound frames", outbound.totalWireFrameBytes(), "bytes", "chunk_frame", "session", "Chunk protocol frames, not socket wire bytes.");
        add(metrics, "hotspot.inbound.logical_bytes", "Hotspot inbound logical", inbound.totalLogicalPacketBytes(), "bytes", "logical_packet", "session", "Restored chunk logical bytes.");
        add(metrics, "shadow.total_bytes", "Shadow snapshot bytes", shadow.totalEncodedBytes(), "bytes", "memory", "current", "Encoded snapshot payload retained in memory.");
        add(metrics, "shadow.retained_original_bytes", "Shadow original bytes", shadow.retainedOriginalBytes(), "bytes", "memory", "current", "Original packet bytes retained for recovery.");
        add(metrics, "runtime.total_bytes", "Runtime reference bytes", runtime.totalBytes(), "bytes", "memory", "current", "Runtime reference cache memory.");
        add(metrics, "reuse.temporary_saved_bytes", "Client temporary reuse saved", reuse.temporaryReuseSavedBytes(), "bytes", "chunk_reuse", "session", "Client-confirmed temporary cache reuse savings.");
        add(metrics, "reuse.offline_saved_bytes", "Client offline reuse saved", reuse.offlineReuseSavedBytes(), "bytes", "chunk_reuse", "session", "Client-confirmed persistent cache reuse savings.");
        return section("chunk", "Chunk transport and cache", "Chunk protocol traffic, reuse, and current retained-memory counters.", metrics);
    }

    private static UnifiedBandwidthReport.Section diagnosticsSection() {
        List<UnifiedBandwidthReport.Metric> metrics = new ArrayList<>();
        for (DiagnosticToolRegistry.Tool tool : DiagnosticToolRegistry.Tool.values()) {
            add(metrics, tool.id(), tool.id(), DiagnosticToolRegistry.isEnabled(tool) ? 1L : 0L, "boolean", "diagnostic", "current",
                    "Runtime diagnostic switch; cost=" + tool.cost().name().toLowerCase(java.util.Locale.ROOT) + ".");
        }
        return section("diagnostics", "Diagnostics", "Runtime tool state only; diagnostic log contents are not embedded.", metrics);
    }

    private static UnifiedBandwidthReport.Section section(
            String id,
            String title,
            String description,
            List<UnifiedBandwidthReport.Metric> metrics
    ) {
        return new UnifiedBandwidthReport.Section(id, title, description, List.copyOf(metrics));
    }

    private static void add(
            List<UnifiedBandwidthReport.Metric> metrics,
            String id,
            String label,
            long value,
            String unit,
            String stage,
            String scope,
            String semantics
    ) {
        metrics.add(new UnifiedBandwidthReport.Metric(id, label, value, unit, stage, scope, semantics));
    }

    private static String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
