package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.Envelope.ChunkTransportEnvelope;
import com.PinkCats.bandwidthoptimizer.chunk.integration.transport.Envelope.ChunkTransportEnvelopeCodec;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameCodec;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RunAllChunkBranchCoverageMain {

    private static final Path DEFAULT_SERVER_LOG = Path.of("run", "runall-server-task.log");
    private static final Path DEFAULT_SERVER_SEND_JSONL = Path.of("run", "server", "send.jsonl");
    private static final List<String> REQUIRED_REASON_MARKERS = List.of(
            "reuse_inflight_full_snapshot_before_ack",
            "reuse_acknowledged_full_snapshot",
            "light_patch_after_published_full_before_ack",
            "section_patch_after_published_full_before_ack",
            "block_entity_patch_after_published_full_before_ack"
    );
    private static final List<String> OPTIONAL_REASON_MARKERS = List.of(
            "block_patch_after_published_full_before_ack"
    );

    private RunAllChunkBranchCoverageMain() {}


    public static void main(String[] args) throws IOException {
        Map<String, Integer> reasonCounts = countReasonMarkers(DEFAULT_SERVER_LOG);
        Map<String, PacketStats> packetStats = readPacketStats(DEFAULT_SERVER_SEND_JSONL);
        int blockPatchTransportLowerBound = estimatePatchTransportLowerBound(
                ChunkHotspotKind.BLOCK_UPDATE,
                ClientboundBlockUpdatePacket.class.getName()
        );
        PacketStats blockUpdateStats = packetStats.getOrDefault(ClientboundBlockUpdatePacket.class.getName(), PacketStats.empty());
        boolean blockPatchReachable = blockUpdateStats.count() > 0
                && blockUpdateStats.maxByteLength() >= blockPatchTransportLowerBound;
        List<String> failures = collectRequiredFailures(reasonCounts, packetStats);

        System.out.println("=== ChunkBranchCoverage ===");
        for (String marker : REQUIRED_REASON_MARKERS) {
            System.out.println(marker + "=" + reasonCounts.getOrDefault(marker, 0));
        }
        for (String marker : OPTIONAL_REASON_MARKERS) {
            System.out.println(marker + "=" + reasonCounts.getOrDefault(marker, 0));
        }
        printPacketStats(packetStats, ClientboundLightUpdatePacket.class.getName(), "light_update");
        printPacketStats(packetStats, ClientboundSectionBlocksUpdatePacket.class.getName(), "section_blocks_update");
        printPacketStats(packetStats, ClientboundBlockEntityDataPacket.class.getName(), "block_entity_update");
        printPacketStats(packetStats, ClientboundBlockUpdatePacket.class.getName(), "block_update");
        System.out.println("block_update_patch_transport_lower_bound=" + blockPatchTransportLowerBound);
        System.out.println("block_update_patch_reachable_under_current_envelope=" + blockPatchReachable);

        if (!blockPatchReachable) {
            System.out.println(
                    "block_update_patch_status=skipped_as_structurally_unreachable"
                            + " (maxObservedBlockUpdateBytes=" + blockUpdateStats.maxByteLength()
                            + ", minPatchTransportBytes=" + blockPatchTransportLowerBound + ")"
            );
        }

        if (!failures.isEmpty()) {
            System.out.println("Result: Fail");
            for (String failure : failures) {
                System.out.println(failure);
            }
            System.exit(1);
        }

        System.out.println("Result: Pass");
    }


    private static Map<String, Integer> countReasonMarkers(Path logFile) throws IOException {
        String logText = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (String marker : REQUIRED_REASON_MARKERS) {
            counts.put(marker, countOccurrences(logText, marker));
        }
        for (String marker : OPTIONAL_REASON_MARKERS) {
            counts.put(marker, countOccurrences(logText, marker));
        }
        return counts;
    }


    private static Map<String, PacketStats> readPacketStats(Path jsonlFile) throws IOException {
        LinkedHashMap<String, PacketStats> statsByPacketClass = new LinkedHashMap<>();
        statsByPacketClass.put(ClientboundLightUpdatePacket.class.getName(), PacketStats.empty());
        statsByPacketClass.put(ClientboundSectionBlocksUpdatePacket.class.getName(), PacketStats.empty());
        statsByPacketClass.put(ClientboundBlockEntityDataPacket.class.getName(), PacketStats.empty());
        statsByPacketClass.put(ClientboundBlockUpdatePacket.class.getName(), PacketStats.empty());

        if (!Files.exists(jsonlFile)) {
            return statsByPacketClass;
        }

        for (String line : Files.readAllLines(jsonlFile, StandardCharsets.UTF_8)) {
            if (line == null || line.isBlank()) {
                continue;
            }

            JsonObject jsonObject = JsonParser.parseString(line).getAsJsonObject();
            String packetClassName = jsonObject.get("packet_class").getAsString();
            if (!statsByPacketClass.containsKey(packetClassName)) {
                continue;
            }

            int byteLength = jsonObject.get("byte_length").getAsInt();
            statsByPacketClass.put(packetClassName, statsByPacketClass.get(packetClassName).record(byteLength));
        }
        return statsByPacketClass;
    }


    private static List<String> collectRequiredFailures(
            Map<String, Integer> reasonCounts,
            Map<String, PacketStats> packetStats
    ) {
        List<String> failures = new ArrayList<>();
        for (String marker : REQUIRED_REASON_MARKERS) {
            if (reasonCounts.getOrDefault(marker, 0) <= 0) {
                failures.add("missing_reason_hit=" + marker);
            }
        }

        requirePacket(packetStats, ClientboundLightUpdatePacket.class.getName(), "missing_packet_class=light_update", failures);
        requirePacket(packetStats, ClientboundSectionBlocksUpdatePacket.class.getName(), "missing_packet_class=section_blocks_update", failures);
        requirePacket(packetStats, ClientboundBlockEntityDataPacket.class.getName(), "missing_packet_class=block_entity_update", failures);
        return failures;
    }


    private static void requirePacket(
            Map<String, PacketStats> packetStats,
            String packetClassName,
            String failureText,
            List<String> failures
    ) {
        PacketStats packetStatsSnapshot = packetStats.getOrDefault(packetClassName, PacketStats.empty());
        if (packetStatsSnapshot.count() <= 0) {
            failures.add(failureText);
        }
    }


    private static void printPacketStats(
            Map<String, PacketStats> packetStats,
            String packetClassName,
            String label
    ) {
        PacketStats stats = packetStats.getOrDefault(packetClassName, PacketStats.empty());
        System.out.println(
                label
                        + "_count=" + stats.count()
                        + ", minByteLength=" + stats.minByteLength()
                        + ", maxByteLength=" + stats.maxByteLength()
        );
    }

    private static int estimatePatchTransportLowerBound(
            ChunkHotspotKind hotspotKind,
            String packetClassName
    ) {
        ChunkHotspotFrame frame = new ChunkHotspotFrame(
                ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                ChunkHotspotFrameOp.PUBLISH_PATCH,
                1L,
                1L,
                "PLAY",
                packetClassName,
                hotspotKind,
                hotspotKind.laneKind(),
                ChunkPacketCoordinate.ofChunk(0, 0),
                0,
                1L,
                1L,
                "",
                "",
                0L,
                ""
        );
        return ChunkTransportEnvelopeCodec.encodeEnvelope(new ChunkTransportEnvelope(frame, new byte[0])).length;
    }


    private static int countOccurrences(String text, String marker) {
        if (text == null || text.isBlank() || marker == null || marker.isBlank()) {
            return 0;
        }

        int count = 0;
        int searchIndex = 0;
        while (searchIndex >= 0) {
            searchIndex = text.indexOf(marker, searchIndex);
            if (searchIndex < 0) {
                break;
            }
            count++;
            searchIndex += marker.length();
        }
        return count;
    }

    private record PacketStats(int count, int minByteLength, int maxByteLength) {

        private static PacketStats empty() {
            return new PacketStats(0, 0, 0);
        }


        private PacketStats record(int byteLength) {
            int normalizedByteLength = Math.max(byteLength, 0);
            if (this.count <= 0) {
                return new PacketStats(1, normalizedByteLength, normalizedByteLength);
            }
            return new PacketStats(
                    this.count + 1,
                    Math.min(this.minByteLength, normalizedByteLength),
                    Math.max(this.maxByteLength, normalizedByteLength)
            );
        }
    }
}
