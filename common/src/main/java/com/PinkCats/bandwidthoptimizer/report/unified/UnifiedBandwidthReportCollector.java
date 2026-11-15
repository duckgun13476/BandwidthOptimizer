package com.PinkCats.bandwidthoptimizer.report.unified;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.channel.bypass.ChannelTransportBypassRankCore;
import com.PinkCats.bandwidthoptimizer.channel.capture.ChannelTransportTelemetry;
import com.PinkCats.bandwidthoptimizer.chunk.integration.ChunkRuntimeReferenceStore;
import com.PinkCats.bandwidthoptimizer.chunk.persistent.ChunkLocalCacheReuseStats;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import com.PinkCats.bandwidthoptimizer.chunk.snapshot.shadow.ChunkShadowSnapshotManager;
import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotReport;
import com.PinkCats.bandwidthoptimizer.chunk.verify.ChunkHotspotStats;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import com.PinkCats.bandwidthoptimizer.gate.integration.minecraft.ActiveEntityViewGate;
import com.PinkCats.bandwidthoptimizer.recipe.RecipeSyncTrafficStats;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportSourceRankCore;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
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
        sections.add(entityInterpolationSection());
        sections.add(recipeSection());
        sections.add(watutSection());
        sections.add(tabListSection());
        sections.add(sifterSection());
        sections.add(currencySection());
        sections.add(chunkSection());
        sections.add(jvmSection());
        sections.add(diagnosticsSection());
        return new UnifiedBandwidthReport(
                SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                System.currentTimeMillis(),
                Bandwidthoptimizer.networkProtocolVersion(),
                safeText(physicalSide, "unknown"),
                "anonymous-aggregate",
                List.copyOf(sections),
                ChannelTransportBypassRankCore.snapshot(BYPASS_LIMIT),
                ChannelTransportSourceRankCore.snapshotForReport(100)
        );
    }

    private static UnifiedBandwidthReport.Section transportSection() {
        ChannelTransportTelemetry.Snapshot snapshot = ChannelTransportTelemetry.snapshot();
        List<UnifiedBandwidthReport.Metric> metrics = new ArrayList<>();
        add(metrics, "counters_started_at_millis", "Counters started at", ChannelTransportTelemetry.countersStartedAtMillis(), "timestamp", "observation", "process", "Counter initialization or latest reset, in epoch milliseconds.");
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
        add(metrics, "counters_started_at_millis", "Counters started at", ServerBandwidthStatsRegistry.countersStartedAtMillis(), "timestamp", "observation", "server_session", "Counter initialization or latest session reset, in epoch milliseconds.");
        add(metrics, "active_channels", "Active channels", snapshot.activeChannels(), "channels", "connection", "server_session", "Currently tracked channels.");
        add(metrics, "bound_players", "Bound players", snapshot.boundPlayers(), "players", "connection", "server_session", "Currently bound player channels; identities are not exported.");
        add(metrics, "outbound_raw_bytes", "Outbound raw", snapshot.outboundRawEncodedBytes(), "bytes", "logical_packet", "server_session", "Server logical encoded bytes.");
        add(metrics, "outbound_vanilla_estimate_bytes", "Outbound vanilla estimate", snapshot.outboundVanillaCompressedEstimateBytes(), "bytes", "minecraft_compression_estimate", "server_session", "Estimated native compression baseline.");
        add(metrics, "outbound_wire_bytes", "Outbound measured wire", snapshot.outboundWireBytes(), "bytes", "socket_wire", "server_session", "Measured outbound bytes at the server wire accounting point.");
        add(metrics, "inbound_wire_bytes", "Inbound measured wire", snapshot.inboundWireBytes(), "bytes", "socket_wire", "server_session", "Measured inbound bytes at the server wire accounting point.");
        add(metrics, "outbound_saved_bytes", "Outbound saved", snapshot.outboundSavedBytes(), "bytes", "derived", "server_session", "Derived against the vanilla estimate and includes idle-gate and source-gate suppression.");
        add(metrics, "offline_reuse_saved_bytes", "Offline cache saved", snapshot.serverOfflineReuseConfirmedSavedBytes(), "bytes", "chunk_reuse", "server_session", "Confirmed persistent-cache reuse savings.");
        add(metrics, "temporary_reuse_saved_bytes", "Temporary cache saved", snapshot.serverTemporaryReuseSavedBytes(), "bytes", "chunk_reuse", "server_session", "Runtime chunk reuse savings excluding confirmed offline reuse.");
        add(metrics, "create_gate_observed_bytes", "Create gate observed", snapshot.serverCreateGateObservedBytes(), "bytes", "gate_input", "server_session", "Create block-entity bytes observed by the gate.");
        add(metrics, "create_gate_saved_bytes", "Create gate saved", snapshot.serverCreateGateSavedBytes(), "bytes", "gate_suppressed", "server_session", "Create update bytes superseded or suppressed by the gate.");
        add(metrics, "idle_gate_saved_bytes", "Idle gate saved", snapshot.serverIdleGateSavedBytes(), "bytes", "gate_suppressed", "server_session", "Clientbound bytes intentionally not sent during idle gating.");
        add(metrics, "source_gate_estimated_saved_bytes", "Source gate estimated saved", snapshot.serverSourceGateEstimatedSavedBytes(), "bytes", "estimated_gate_suppressed", "server_session", "Conservative estimate derived from previously observed WATUT compressed frames that BO prevented at capture time.");
        add(metrics, "source_gate_estimated_outbound_saved_bytes", "Source gate estimated outbound saved", snapshot.serverSourceGateEstimatedOutboundSavedBytes(), "bytes", "estimated_gate_suppressed", "server_session", "Estimated clientbound WATUT fanout prevented at capture time; this is the source-gate amount included in outbound savings.");
        add(metrics, "source_gate_suppressed_frames", "Source gate suppressed frames", snapshot.serverSourceGateSuppressedFrames(), "frames", "gate_suppressed", "server_session", "WATUT GUI frames prevented before payload construction.");
        return section("server", "Server session", "Aggregate server counters without player names, UUIDs, addresses, or channel identifiers.", metrics);
    }

    private static UnifiedBandwidthReport.Section entityInterpolationSection() {
        ActiveEntityViewGate.Snapshot snapshot = ActiveEntityViewGate.snapshot();
        List<UnifiedBandwidthReport.Metric> metrics = new ArrayList<>();
        add(metrics, "enabled", "Entity movement pairing enabled", ActiveEntityViewGate.enabled() ? 1L : 0L, "boolean", "gate_input", "current", "Whether active-play relative movement pairing is enabled for this JVM.");
        add(metrics, "observed_packets", "Observed movement packets", snapshot.observedPackets(), "packets", "gate_input", "server_session", "Eligible active-play relative entity movement packets observed before pairing.");
        add(metrics, "observed_payload_bytes", "Observed movement payload", snapshot.observedPayloadBytes(), "bytes", "gate_input", "server_session", "Encoded relative movement packet bodies before pairing, excluding packet ID, compression, and socket framing.");
        add(metrics, "captured_packets", "Temporarily held movement packets", snapshot.capturedPackets(), "packets", "gate_input", "server_session", "Packets held pending the next active-play movement update; includes later unpaired and superseded releases.");
        add(metrics, "coalesced_packets", "Coalesced movement packets", snapshot.coalescedPackets(), "packets", "gate_suppressed", "server_session", "Pairs emitted as one exact cumulative movement packet; each pair removes one logical packet.");
        add(metrics, "saved_payload_bytes", "Coalesced payload reduction", snapshot.coalescedSavedPayloadBytes(), "bytes", "gate_suppressed", "server_session", "Sum of original movement packet body bytes minus replacement body bytes for completed pairs; not measured wire savings.");
        add(metrics, "timeout_releases", "Unpaired releases", snapshot.timeoutReleases(), "packets", "gate_input", "server_session", "Held movement packets released unchanged after the next server tick without a partner.");
        add(metrics, "overflow_releases", "Delta overflow releases", snapshot.overflowReleases(), "packets", "gate_input", "server_session", "Unrepresentable combined deltas released as the two original packets in order.");
        add(metrics, "superseded_packets", "Superseded held packets", snapshot.supersededPackets(), "packets", "gate_suppressed", "server_session", "Held movement packets invalidated by a later absolute teleport or entity removal; excluded from paired payload reduction.");
        add(metrics, "pending_entities", "Pending movement packets", snapshot.pendingEntities(), "packets", "gate_input", "current", "Movement packets currently held for at most one server tick across all players.");
        return section("entity_interpolation", "Entity movement interpolation", "Server-run relative movement pairing; vanilla clients interpolate delivered endpoints. Payload reduction is before compression and is not measured socket savings.", metrics);
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
        OperationSum outboundChunk = sumOperations(outbound,
                ChunkHotspotFrameOp.PUBLISH_FULL,
                ChunkHotspotFrameOp.PUBLISH_REF,
                ChunkHotspotFrameOp.PUBLISH_PATCH);
        OperationSum inboundChunk = sumOperations(inbound,
                ChunkHotspotFrameOp.PUBLISH_FULL,
                ChunkHotspotFrameOp.PUBLISH_REF,
                ChunkHotspotFrameOp.PUBLISH_PATCH);
        List<UnifiedBandwidthReport.Metric> metrics = new ArrayList<>();
        add(metrics, "hotspot.outbound.logical_bytes", "Hotspot outbound logical", outboundChunk.logicalBytes(), "bytes", "logical_packet", "session", "Logical bytes for chunk full, reference, and patch operations only.");
        add(metrics, "hotspot.outbound.frame_bytes", "Hotspot outbound frames", outboundChunk.frameBytes(), "bytes", "chunk_frame", "session", "Chunk data frames only, excluding recipe and control frames; not socket wire bytes.");
        add(metrics, "hotspot.inbound.logical_bytes", "Hotspot inbound logical", inboundChunk.logicalBytes(), "bytes", "logical_packet", "session", "Restored logical bytes for chunk full, reference, and patch operations only.");
        add(metrics, "shadow.total_bytes", "Logical snapshot coverage", shadow.totalEncodedBytes(), "bytes", "reuse_model", "current", "Sum of encoded packet lengths in retained snapshot metadata; not JVM heap retention.");
        add(metrics, "shadow.retained_original_bytes", "Shadow original bytes", shadow.retainedOriginalBytes(), "bytes", "memory", "current", "Original packet bytes retained for recovery.");
        add(metrics, "runtime.total_bytes", "Runtime reference bytes", runtime.totalBytes(), "bytes", "memory", "current", "Runtime reference cache memory.");
        add(metrics, "reuse.temporary_saved_bytes", "Client temporary reuse saved", reuse.temporaryReuseSavedBytes(), "bytes", "chunk_reuse", "session", "Client-confirmed temporary cache reuse savings.");
        add(metrics, "reuse.offline_saved_bytes", "Client offline reuse saved", reuse.offlineReuseSavedBytes(), "bytes", "chunk_reuse", "session", "Client-confirmed persistent cache reuse savings.");
        return section("chunk", "Chunk transport and cache", "Chunk protocol traffic, reuse, and current retained-memory counters.", metrics);
    }

    private static OperationSum sumOperations(
            ChunkHotspotReport.DirectionTotals totals,
            ChunkHotspotFrameOp... operations
    ) {
        long logicalBytes = 0L;
        long frameBytes = 0L;
        for (ChunkHotspotFrameOp operation : operations) {
            ChunkHotspotReport.OperationTotals operationTotals = totals.operationTotals()
                    .getOrDefault(operation, ChunkHotspotReport.OperationTotals.empty());
            logicalBytes += operationTotals.logicalPacketBytes();
            frameBytes += operationTotals.wireFrameBytes();
        }
        return new OperationSum(logicalBytes, frameBytes);
    }

    private record OperationSum(long logicalBytes, long frameBytes) {
    }

    private static UnifiedBandwidthReport.Section recipeSection() {
        RecipeSyncTrafficStats.Snapshot snapshot = RecipeSyncTrafficStats.snapshot();
        List<UnifiedBandwidthReport.Metric> metrics = new ArrayList<>();
        add(metrics, "frames", "Recipe sync frames", snapshot.totalFrames(), "frames", "recipe_sync", "session", "Completed recipe synchronization frames.");
        add(metrics, "full_frames", "Full frames", snapshot.fullFrames(), "frames", "recipe_sync", "session", "Recipe trees sent without a reusable base.");
        add(metrics, "identity_frames", "Identity frames", snapshot.identityFrames(), "frames", "recipe_sync", "session", "Semantically identical recipe trees restored from a persistent base.");
        add(metrics, "structural_delta_frames", "Structural delta frames", snapshot.structuralDeltaFrames(), "frames", "recipe_sync", "session", "Changed recipe trees encoded by record-aware structural delta.");
        add(metrics, "byte_delta_frames", "Byte delta frames", snapshot.byteDeltaFrames(), "frames", "recipe_sync", "session", "Changed recipe trees encoded by the exact byte-delta fallback.");
        add(metrics, "logical_bytes", "Recipe logical bytes", snapshot.logicalBytes(), "bytes", "logical_packet", "session", "Original recipe packet bytes before BO recipe transport.");
        add(metrics, "payload_bytes", "Recipe payload bytes", snapshot.payloadBytes(), "bytes", "recipe_payload", "session", "Recipe full, reference, or delta payload before BO envelope overhead.");
        add(metrics, "frame_bytes", "Recipe frame bytes", snapshot.frameBytes(), "bytes", "bo_frame", "session", "Final BO recipe envelope bytes before Minecraft native compression and socket framing.");
        add(metrics, "saved_bytes", "Recipe saved bytes", snapshot.savedBytes(), "bytes", "derived", "session", "Logical recipe bytes minus BO recipe envelope bytes.");
        return section("recipe", "Recipe synchronization", "Persistent recipe reuse and exact structural-delta traffic.", metrics);
    }

    private static UnifiedBandwidthReport.Section diagnosticsSection() {
        List<UnifiedBandwidthReport.Metric> metrics = new ArrayList<>();
        for (DiagnosticToolRegistry.Tool tool : DiagnosticToolRegistry.Tool.values()) {
            add(metrics, tool.id(), tool.id(), DiagnosticToolRegistry.isEnabled(tool) ? 1L : 0L, "boolean", "diagnostic", "current",
                    "Runtime diagnostic switch; cost=" + tool.cost().name().toLowerCase(java.util.Locale.ROOT) + ".");
        }
        return section("diagnostics", "Diagnostics", "Runtime tool state only; diagnostic log contents are not embedded.", metrics);
    }

    private static UnifiedBandwidthReport.Section tabListSection() {
        var stats = com.PinkCats.bandwidthoptimizer.channel.packet.ClientboundTabListDeduplicator.snapshot();
        List<UnifiedBandwidthReport.Metric> metrics = new ArrayList<>();
        add(metrics, "counters_started_at_millis", "Counters started at", stats.startedAtMillis(), "timestamp", "observation", "process", "Counter initialization, in epoch milliseconds.");
        add(metrics, "saved_raw_bytes", "Duplicate header/footer bytes suppressed", stats.savedRawBytes(), "bytes", "native_encoded_packet", "process", "Exact native encoded bytes withheld before BO compression; not socket savings.");
        add(metrics, "suppressed_packets", "Duplicate header/footer packets", stats.suppressedPackets(), "packets", "native_encoded_packet", "process", "Identical committed state only; changing animations and five-second refresh pass through.");
        add(metrics, "accepted_raw_bytes", "Header/footer bytes accepted", stats.acceptedRawBytes(), "bytes", "native_encoded_packet", "process", "Accepted by transport or ordered epoch queue; not a socket delivery acknowledgement. Includes oversized baseline bypasses.");
        add(metrics, "accepted_packets", "Header/footer packets accepted", stats.acceptedPackets(), "packets", "native_encoded_packet", "process", "Same enabled observation path as suppressed packets; disabled observations are excluded.");
        return section("tab_list", "TAB header/footer reuse", "Process-lifetime enabled-path counters. Native encoded bytes before compression, not measured wire savings.", metrics);
    }

    private static UnifiedBandwidthReport.Section sifterSection() {
        var stats = com.PinkCats.bandwidthoptimizer.integration.createsifter.SifterSyncTransaction.snapshot();
        List<UnifiedBandwidthReport.Metric> metrics = new ArrayList<>();
        add(metrics, "counters_started_at_millis", "Counters started at", stats.startedAtMillis(), "timestamp", "observation", "process", "Counter initialization in epoch milliseconds.");
        add(metrics, "transactions", "Processing transactions", stats.transactions(), "transactions", "source_sync", "process", "Supported server-side CreateSifter processing calls.");
        add(metrics, "deferred_calls", "Deferred sync calls", stats.deferredCalls(), "calls", "source_sync", "process", "Calls combined inside one synchronous processing transaction.");
        add(metrics, "flushed_calls", "Final sync calls", stats.flushedCalls(), "calls", "source_sync", "process", "Successful immediate final-state flushes, not socket acknowledgements.");
        add(metrics, "saved_calls", "Sync calls avoided", stats.savedCalls(), "calls", "source_sync", "process", "Deferred calls minus successful final flushes; not bytes or measured wire savings.");
        return section("create_sifter", "CreateSifter synchronization", "Synchronous processing-call reduction. Start, completion and external updates remain independent.", metrics);
    }

    private static UnifiedBandwidthReport.Section currencySection() {
        var stats = com.PinkCats.bandwidthoptimizer.integration.lightmanscurrency.CurrencyBankSyncTransaction.snapshot();
        List<UnifiedBandwidthReport.Metric> metrics = new ArrayList<>();
        add(metrics, "counters_started_at_millis", "Counters started at", stats.startedAtMillis(), "timestamp", "observation", "process", "Counter initialization in epoch milliseconds.");
        add(metrics, "requested_calls", "Requested bank sync calls", stats.requestedCalls(), "calls", "source_sync", "process", "Full broadcast account snapshots requested inside supported player item-trader transactions.");
        add(metrics, "flushed_calls", "Bank snapshot calls flushed", stats.flushedCalls(), "calls", "source_sync", "process", "Successful native calls flushed before another packet, trade event, or return; not socket acknowledgements.");
        add(metrics, "saved_calls", "Bank sync calls avoided", stats.savedCalls(), "calls", "source_sync", "process", "Adjacent full snapshots for the same account/cache combined; targeted, inventory, notification and Stats sends remain independent. Not measured byte/socket savings.");
        return section("lightmanscurrency", "LightmansCurrency bank synchronization", "Process-lifetime source-call counters. Full bank snapshots retain the final balance and complete log.", metrics);
    }

    private static UnifiedBandwidthReport.Section watutSection() {
        var stats = com.PinkCats.bandwidthoptimizer.integration.watut.WatutTrafficStats.snapshot();
        var clients = com.PinkCats.bandwidthoptimizer.integration.watut.ServerWatutClientStats.snapshot();
        List<UnifiedBandwidthReport.Metric> metrics = new ArrayList<>();
        add(metrics, "counters_started_at_millis", "Counters started at", stats.startedAtMillis(), "timestamp", "observation", "process", "Counter initialization or latest reset, in epoch milliseconds.");
        add(metrics, "client_reports_started_at_millis", "Client reporting window started at", clients.startedAtMillis(), "timestamp", "observation", "server_session", "Server aggregation initialization or latest reset; cumulative baselines retained across resets.");
        add(metrics, "client_sent_payload_bytes", "Images sent by this client", stats.clientSentPayloadBytes(), "bytes", "watut_image_payload", "client_connection", "Compressed image payload handed to WATUT by this reporting client; not socket bytes. Resets on disconnect.");
        add(metrics, "reported_client_saved_payload_bytes", "Client-reported exact image savings", clients.savedPayloadBytes(), "bytes", "watut_image_payload", "server_session", "Sum of independent client exact dedup reports, excluding cadence estimates and server fanout; client claims, not verified wire savings.");
        add(metrics, "reported_client_suppressed_frames", "Client-reported duplicate frames", clients.suppressedFrames(), "frames", "watut_image_payload", "server_session", "Cumulative exact duplicate captures reported by clients, once per sequence and connection.");
        add(metrics, "reported_client_sent_payload_bytes", "Client-reported sent images", clients.sentPayloadBytes(), "bytes", "watut_image_payload", "server_session", "Sent compressed image payload from reporting clients only; add reported saved payload for a matching exact-dedup denominator.");
        add(metrics, "reporting_client_connections", "Currently reporting clients", clients.reportingConnections(), "connections", "observation", "server_session", "Connected clients with the exact statistics extension; zero-valued reports still count. Does not establish all players have WATUT enabled.");
        add(metrics, "server_sent_payload_bytes", "Images delivered by server reuse adapter", stats.serverSentPayloadBytes(), "bytes", "watut_image_payload", "server_session", "Complete compressed image payload handed to native send per recipient, including login replay. Does not include native fallback or socket framing.");
        add(metrics, "native_image_packets", "Native fallback image fragments", stats.nativeImagePackets(), "packets", "watut_semantic_packet", "server_session", "Image fragments passed through native receive instead of the complete-frame reuse adapter; nonzero indicates incomplete sent-image coverage.");
        add(metrics, "received_image_payload_bytes", "Received image payload", stats.receivedImagePayloadBytes(), "bytes", "watut_image_payload", "process", "Compressed screenshot byte-array bytes received by this server, before fanout; excludes NBT, BO and socket framing.");
        add(metrics, "received_image_packets", "Received image fragments", stats.receivedImagePackets(), "packets", "watut_semantic_packet", "process", "NBT packets containing screenshot data; not complete frame count.");
        add(metrics, "received_state_packets", "Received status packets", stats.receivedStatePackets(), "packets", "watut_semantic_packet", "process", "Non-image GUI, chat or idle packets; image classification takes precedence.");
        add(metrics, "received_mouse_packets", "Received mouse packets", stats.receivedMousePackets(), "packets", "watut_semantic_packet", "process", "Non-image, non-status mouse packets; click transitions remain unchanged.");
        add(metrics, "received_other_packets", "Received other packets", stats.receivedOtherPackets(), "packets", "watut_semantic_packet", "process", "Other WATUT receiveAny packets, including typing amplifier; categories are disjoint.");
        add(metrics, "client_saved_payload_bytes", "Client image payload saved", stats.clientSavedPayloadBytes(), "bytes", "watut_image_payload", "process", "Identical fresh captures suppressed on this client only. Does not estimate server fanout or wire savings.");
        add(metrics, "client_suppressed_frames", "Client duplicate frames suppressed", stats.clientSuppressedFrames(), "frames", "watut_image_payload", "process", "Explicit cached-frame replay, screen/viewer changes and periodic refresh are not suppressed.");
        add(metrics, "server_saved_payload_bytes", "Server image payload saved", stats.serverSavedPayloadBytes(), "bytes", "watut_image_payload", "process", "Exact compressed screenshot bytes withheld from recipients already sent the complete identical frame; excludes NBT and socket overhead.");
        add(metrics, "server_suppressed_recipient_frames", "Server duplicate recipient frames suppressed", stats.serverSuppressedRecipientFrames(), "frames", "watut_image_payload", "process", "One frame for each recipient skipped; source self delivery is preserved.");
        add(metrics, "login_replay_payload_bytes", "Nearby login image replay", stats.loginReplayPayloadBytes(), "bytes", "watut_image_payload", "process", "Complete bounded cached images sent only to nearby same-level entrants, not global state snapshots.");
        add(metrics, "login_replay_recipient_frames", "Nearby login frames", stats.loginReplayRecipientFrames(), "frames", "watut_image_payload", "process", "Complete images replayed through the optional WATUT server adapter.");
        return section("watut", "WATUT image reuse", "Process counters; byte values are compressed image payload bytes, not total channel or socket traffic. Client counters belong to the reporting client, not all server players.", metrics);
    }

    private static UnifiedBandwidthReport.Section jvmSection() {
        Runtime runtime = Runtime.getRuntime();
        long committed = runtime.totalMemory();
        long used = Math.max(0L, committed - runtime.freeMemory());
        long gcCount = 0L;
        long gcMillis = 0L;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(collector.getCollectionCount(), 0L);
            gcMillis += Math.max(collector.getCollectionTime(), 0L);
        }
        List<UnifiedBandwidthReport.Metric> metrics = new ArrayList<>();
        add(metrics, "heap_used_bytes", "Heap used", used, "bytes", "jvm_heap", "current", "JVM heap used at report generation; includes all mods, not BO alone.");
        add(metrics, "heap_committed_bytes", "Heap committed", committed, "bytes", "jvm_heap", "current", "Memory committed by the JVM heap at report generation.");
        add(metrics, "heap_max_bytes", "Heap maximum", Math.max(runtime.maxMemory(), 0L), "bytes", "jvm_heap", "current", "Configured JVM heap maximum, if available.");
        add(metrics, "uptime_millis", "Process uptime", Math.max(ManagementFactory.getRuntimeMXBean().getUptime(), 0L), "milliseconds", "jvm", "current", "JVM uptime at report generation.");
        add(metrics, "gc_count", "GC collections", gcCount, "events", "jvm_gc", "process", "Cumulative collection count since JVM start; not a current pause rate.");
        add(metrics, "gc_millis", "GC time", gcMillis, "milliseconds", "jvm_gc", "process", "Cumulative collection time since JVM start; not a current pause duration.");
        return section("jvm", "JVM health", "Command-time heap and cumulative process counters; no heap dump or player data.", metrics);
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
