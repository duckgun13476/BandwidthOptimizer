package com.PinkCats.bandwidthoptimizer.chunk.verify;

import com.PinkCats.bandwidthoptimizer.integration.minecraft.LoaderEnvironmentCompat;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;

import java.util.EnumMap;
import java.util.Map;

public final class ChunkHotspotStats {

    private static final Object LOCK = new Object();
    private static final EnumMap<ChunkHotspotFrameOp, MutableOperationTotals> OUTBOUND_OPERATION_TOTALS = createOperationTotalsMap();
    private static final EnumMap<ChunkHotspotFrameOp, MutableOperationTotals> INBOUND_OPERATION_TOTALS = createOperationTotalsMap();

    private static long firstRecordedAtMillis;
    private static long lastRecordedAtMillis;
    private static long outboundTotalFrames;
    private static long outboundTotalLogicalPacketBytes;
    private static long outboundTotalWireFrameBytes;
    private static long inboundTotalFrames;
    private static long inboundTotalLogicalPacketBytes;
    private static long inboundTotalWireFrameBytes;

    private ChunkHotspotStats() {}

    public static void recordOutboundFrame(ChunkHotspotFrame frame, int logicalPacketBytes, int wireFrameBytes) {
        recordFrame(frame, logicalPacketBytes, wireFrameBytes, true);
    }

    public static void recordInboundFrame(ChunkHotspotFrame frame, int logicalPacketBytes, int wireFrameBytes) {
        recordFrame(frame, logicalPacketBytes, wireFrameBytes, false);
    }

    public static void reset() {
        synchronized (LOCK) {
            firstRecordedAtMillis = 0L;
            lastRecordedAtMillis = 0L;
            outboundTotalFrames = 0L;
            outboundTotalLogicalPacketBytes = 0L;
            outboundTotalWireFrameBytes = 0L;
            inboundTotalFrames = 0L;
            inboundTotalLogicalPacketBytes = 0L;
            inboundTotalWireFrameBytes = 0L;
            resetOperationTotals(OUTBOUND_OPERATION_TOTALS);
            resetOperationTotals(INBOUND_OPERATION_TOTALS);
        }
    }

    public static ChunkHotspotReport snapshotReport() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            return new ChunkHotspotReport(
                    1,
                    LoaderEnvironmentCompat.physicalSideName(),
                    now,
                    firstRecordedAtMillis,
                    lastRecordedAtMillis,
                    snapshotDirectionTotals(
                            outboundTotalFrames,
                            outboundTotalLogicalPacketBytes,
                            outboundTotalWireFrameBytes,
                            OUTBOUND_OPERATION_TOTALS
                    ),
                    snapshotDirectionTotals(
                            inboundTotalFrames,
                            inboundTotalLogicalPacketBytes,
                            inboundTotalWireFrameBytes,
                            INBOUND_OPERATION_TOTALS
                    )
            );
        }
    }

    private static void recordFrame(
            ChunkHotspotFrame frame,
            int logicalPacketBytes,
            int wireFrameBytes,
            boolean outbound
    ) {
        if (frame == null || frame.operation() == null) {
            return;
        }

        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (firstRecordedAtMillis <= 0L) {
                firstRecordedAtMillis = now;
            }
            lastRecordedAtMillis = now;

            long safeLogicalPacketBytes = Math.max(logicalPacketBytes, 0);
            long safeWireFrameBytes = Math.max(wireFrameBytes, 0);
            MutableOperationTotals operationTotals = (outbound ? OUTBOUND_OPERATION_TOTALS : INBOUND_OPERATION_TOTALS)
                    .computeIfAbsent(frame.operation(), ignored -> new MutableOperationTotals());
            operationTotals.record(safeLogicalPacketBytes, safeWireFrameBytes);

            if (outbound) {
                outboundTotalFrames++;
                outboundTotalLogicalPacketBytes += safeLogicalPacketBytes;
                outboundTotalWireFrameBytes += safeWireFrameBytes;
            } else {
                inboundTotalFrames++;
                inboundTotalLogicalPacketBytes += safeLogicalPacketBytes;
                inboundTotalWireFrameBytes += safeWireFrameBytes;
            }
        }
    }

    private static ChunkHotspotReport.DirectionTotals snapshotDirectionTotals(
            long totalFrames,
            long totalLogicalPacketBytes,
            long totalWireFrameBytes,
            EnumMap<ChunkHotspotFrameOp, MutableOperationTotals> operationTotals
    ) {
        EnumMap<ChunkHotspotFrameOp, ChunkHotspotReport.OperationTotals> immutableTotals =
                new EnumMap<>(ChunkHotspotFrameOp.class);
        for (ChunkHotspotFrameOp operation : ChunkHotspotFrameOp.values()) {
            MutableOperationTotals mutableTotals = operationTotals.get(operation);
            immutableTotals.put(
                    operation,
                    mutableTotals == null ? ChunkHotspotReport.OperationTotals.empty() : mutableTotals.snapshot()
            );
        }
        return new ChunkHotspotReport.DirectionTotals(
                totalFrames,
                totalLogicalPacketBytes,
                totalWireFrameBytes,
                Map.copyOf(immutableTotals)
        );
    }

    private static EnumMap<ChunkHotspotFrameOp, MutableOperationTotals> createOperationTotalsMap() {
        EnumMap<ChunkHotspotFrameOp, MutableOperationTotals> operationTotals =
                new EnumMap<>(ChunkHotspotFrameOp.class);
        for (ChunkHotspotFrameOp operation : ChunkHotspotFrameOp.values()) {
            operationTotals.put(operation, new MutableOperationTotals());
        }
        return operationTotals;
    }

    private static void resetOperationTotals(EnumMap<ChunkHotspotFrameOp, MutableOperationTotals> operationTotals) {
        for (ChunkHotspotFrameOp operation : ChunkHotspotFrameOp.values()) {
            operationTotals.computeIfAbsent(operation, ignored -> new MutableOperationTotals()).reset();
        }
    }

    private static final class MutableOperationTotals {

        private long frameCount;
        private long logicalPacketBytes;
        private long wireFrameBytes;

        private void record(long logicalPacketBytes, long wireFrameBytes) {
            this.frameCount++;
            this.logicalPacketBytes += logicalPacketBytes;
            this.wireFrameBytes += wireFrameBytes;
        }

        private ChunkHotspotReport.OperationTotals snapshot() {
            return new ChunkHotspotReport.OperationTotals(
                    this.frameCount,
                    this.logicalPacketBytes,
                    this.wireFrameBytes
            );
        }

        private void reset() {
            this.frameCount = 0L;
            this.logicalPacketBytes = 0L;
            this.wireFrameBytes = 0L;
        }
    }
}
