package com.PinkCats.bandwidthoptimizer.chunk.verify;

import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;

import java.util.EnumMap;
import java.util.Map;

public record ChunkHotspotReport(
        int reportVersion,
        String physicalSide,
        long generatedAtMillis,
        long firstRecordedAtMillis,
        long lastRecordedAtMillis,
        DirectionTotals outboundTotals,
        DirectionTotals inboundTotals
) {
    public String toPropertiesText() {
        StringBuilder builder = new StringBuilder(2048);
        builder.append("report_version=").append(this.reportVersion).append('\n');
        builder.append("physical_side=").append(nullToEmpty(this.physicalSide)).append('\n');
        builder.append("generated_at_ms=").append(this.generatedAtMillis).append('\n');
        builder.append("first_recorded_at_ms=").append(this.firstRecordedAtMillis).append('\n');
        builder.append("last_recorded_at_ms=").append(this.lastRecordedAtMillis).append('\n');
        appendDirection(builder, "outbound", this.outboundTotals);
        appendDirection(builder, "inbound", this.inboundTotals);
        return builder.toString();
    }

    private static void appendDirection(StringBuilder builder, String prefix, DirectionTotals directionTotals) {
        DirectionTotals safeTotals = directionTotals == null ? DirectionTotals.empty() : directionTotals;
        builder.append(prefix).append("_total_frames=").append(safeTotals.totalFrames()).append('\n');
        builder.append(prefix).append("_total_logical_packet_bytes=").append(safeTotals.totalLogicalPacketBytes()).append('\n');
        builder.append(prefix).append("_total_wire_frame_bytes=").append(safeTotals.totalWireFrameBytes()).append('\n');
        builder.append(prefix).append("_total_saved_vs_logical_bytes=").append(safeTotals.savedVsLogicalBytes()).append('\n');

        for (ChunkHotspotFrameOp operation : ChunkHotspotFrameOp.values()) {
            OperationTotals operationTotals = safeTotals.operationTotals().getOrDefault(operation, OperationTotals.empty());
            String operationPrefix = prefix + "_" + operation.logName();
            builder.append(operationPrefix).append("_frames=").append(operationTotals.frameCount()).append('\n');
            builder.append(operationPrefix).append("_logical_packet_bytes=").append(operationTotals.logicalPacketBytes()).append('\n');
            builder.append(operationPrefix).append("_wire_frame_bytes=").append(operationTotals.wireFrameBytes()).append('\n');
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record DirectionTotals(
            long totalFrames,
            long totalLogicalPacketBytes,
            long totalWireFrameBytes,
            Map<ChunkHotspotFrameOp, OperationTotals> operationTotals
    ) {

        public long savedVsLogicalBytes() {
            return this.totalLogicalPacketBytes - this.totalWireFrameBytes;
        }

        public static DirectionTotals empty() {
            EnumMap<ChunkHotspotFrameOp, OperationTotals> operationTotals = new EnumMap<>(ChunkHotspotFrameOp.class);
            for (ChunkHotspotFrameOp operation : ChunkHotspotFrameOp.values()) {
                operationTotals.put(operation, OperationTotals.empty());
            }
            return new DirectionTotals(0L, 0L, 0L, Map.copyOf(operationTotals));
        }
    }

    public record OperationTotals(
            long frameCount,
            long logicalPacketBytes,
            long wireFrameBytes
    ) {

        public static OperationTotals empty() {
            return new OperationTotals(0L, 0L, 0L);
        }
    }
}
