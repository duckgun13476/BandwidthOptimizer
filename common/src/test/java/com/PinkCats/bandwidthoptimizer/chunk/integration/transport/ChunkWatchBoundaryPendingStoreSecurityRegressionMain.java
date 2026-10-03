package com.PinkCats.bandwidthoptimizer.chunk.integration.transport;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import io.netty.channel.embedded.EmbeddedChannel;

import java.util.ArrayList;
import java.util.List;

public final class ChunkWatchBoundaryPendingStoreSecurityRegressionMain {

    private static final int MEBIBYTE = 1024 * 1024;

    private ChunkWatchBoundaryPendingStoreSecurityRegressionMain() {}

    public static void main(String[] args) {
        verifyBudgetDoesNotDiscardUnansweredReplay();
        verifyEntryLimitPublishesFull();
        verifyReplacementAndTakeAccounting();
        verifyPerChannelAdmissionAndCloseCleanup();
        verifyProcessWideAdmission();
        verifyLateResponseAndRejectedReplay();
        System.out.println("Chunk watch-boundary pending-store security regression passed");
    }

    private static void verifyReplacementAndTakeAccounting() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChunkHotspotFrame frame = frame(1);
        ChunkWatchBoundaryReusePendingStore.rememberProbe(channel, frame, new byte[1024]);
        requireUsage(1, 1024);
        ChunkWatchBoundaryReusePendingStore.rememberProbe(channel, frame, new byte[2048]);
        requireUsage(1, 2048);

        var replay = ChunkWatchBoundaryReusePendingStore.takePendingFull(channel, frame);
        require(replay != null && replay.copyOriginalPacketBytes().length == 2048, "Replacement replay was not retained");
        requireUsage(0, 0);
        channel.finishAndReleaseAll();
    }

    private static void verifyPerChannelAdmissionAndCloseCleanup() {
        EmbeddedChannel channel = new EmbeddedChannel();
        byte[] oneMebibyte = new byte[MEBIBYTE];
        for (int index = 0; index < 12; index++) {
            ChunkHotspotFrame admitted = ChunkTransportDispatcher.reserveRecoverableFrame(channel, frame(index), oneMebibyte);
            require(admitted.operation() == (index < 8 ? ChunkHotspotFrameOp.PUBLISH_REF : ChunkHotspotFrameOp.PUBLISH_FULL),
                    "Channel admission must publish FULL when recovery storage is unavailable");
        }
        requireUsage(8, 8L * MEBIBYTE);
        channel.finishAndReleaseAll();
        requireUsage(0, 0);
    }

    private static void verifyProcessWideAdmission() {
        byte[] oneMebibyte = new byte[MEBIBYTE];
        List<EmbeddedChannel> channels = new ArrayList<>();
        for (int index = 0; index < 65; index++) {
            EmbeddedChannel channel = new EmbeddedChannel();
            channels.add(channel);
            ChunkHotspotFrame admitted = ChunkTransportDispatcher.reserveRecoverableFrame(channel, frame(index), oneMebibyte);
            require(admitted.operation() == (index < 64 ? ChunkHotspotFrameOp.PUBLISH_REF : ChunkHotspotFrameOp.PUBLISH_FULL),
                    "Global admission must publish FULL when recovery storage is unavailable");
        }
        requireUsage(64, 64L * MEBIBYTE);
        for (EmbeddedChannel channel : channels) {
            channel.finishAndReleaseAll();
        }
        requireUsage(0, 0);
    }

    private static void verifyLateResponseAndRejectedReplay() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChunkHotspotFrame first = frame(9001);
        ChunkWatchBoundaryReusePendingStore.rememberProbe(channel, first, new byte[0]);
        ChunkWatchBoundaryReusePendingStore.rememberProbe(channel, first, new byte[1024]);
        ChunkHotspotFrame wrongEpoch = new ChunkHotspotFrame(first.protocolVersion(), first.operation(), 2L,
                first.observedPacketCount(), first.protocolName(), first.packetClassName(), first.hotspotKind(), first.laneKind(),
                first.coordinate(), first.originalEncodedBytes(), first.fullSnapshotVersion(), first.laneVersion(),
                first.baseSnapshotHash(), first.payloadHash(), first.deltaBytesSinceFullSnapshot(), first.reason());
        require(ChunkWatchBoundaryReusePendingStore.takePendingFull(channel, wrongEpoch) == null, "Wrong-epoch replay matched");
        requireUsage(1, 1024);
        ChunkWatchBoundaryReusePendingStore.takePendingFull(channel, frame(9002));
        ChunkWatchBoundaryReusePendingStore.clearPendingFull(channel, first);
        ChunkWatchBoundaryReusePendingStore.rememberProbe(channel, first, new byte[MEBIBYTE]);
        for (int i = 9002; i <= 9010; i++) ChunkWatchBoundaryReusePendingStore.rememberProbe(channel, frame(i), new byte[MEBIBYTE]);
        channel.advanceTimeBy(30, java.util.concurrent.TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        require(ChunkWatchBoundaryReusePendingStore.takePendingFull(channel, first) != null, "Late NACK lost recovery bytes after former TTL");
        require(ChunkWatchBoundaryReusePendingStore.rememberProbe(channel, frame(9011), new byte[32]), "Released budget was not reusable");
        channel.finishAndReleaseAll();
        require(ChunkWatchBoundaryReusePendingStore.takePendingFull(channel, frame(9011)) == null, "Closed-channel replay retained");
        requireUsage(0, 0);
        EmbeddedChannel rejected = new EmbeddedChannel();
        require(!ChunkTransportControlFrameSender.sendReplayFullFrame(rejected, first, new byte[32], "test"), "Unconfigured replay unexpectedly accepted");
        rejected.finishAndReleaseAll();
        System.out.println("Replay diagnostic regression passed: bounded admission, keyed lookup, response cleanup, late NACK pinning, close, send rejection");
    }

    private static void verifyBudgetDoesNotDiscardUnansweredReplay() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            for (int index = 0; index < 9; index++) {
                ChunkHotspotFrame admitted = ChunkTransportDispatcher.reserveRecoverableFrame(channel, frame(index), new byte[MEBIBYTE]);
                require(admitted.operation() == (index < 8 ? ChunkHotspotFrameOp.PUBLISH_REF : ChunkHotspotFrameOp.PUBLISH_FULL),
                        "Budget refusal must publish FULL before advertising a dependency");
            }
            var oldest = ChunkWatchBoundaryReusePendingStore.takePendingFull(channel, frame(0));
            require(oldest != null, "Incident reproduced: channel budget discarded unanswered FULL replay before NACK");
            require(oldest.copyOriginalPacketBytes().length == MEBIBYTE, "Oldest replay payload changed");
        } finally { channel.finishAndReleaseAll(); }
    }


    private static void verifyEntryLimitPublishesFull() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            byte[] bytes = new byte[32];
            for (int index = 0; index <= 512; index++) {
                ChunkHotspotFrame admitted = ChunkTransportDispatcher.reserveRecoverableFrame(channel, frame(index), bytes);
                require(admitted.operation() == (index < 512 ? ChunkHotspotFrameOp.PUBLISH_REF : ChunkHotspotFrameOp.PUBLISH_FULL),
                        "Entry limit must refuse the new dependency, preserving existing recovery payloads");
                if (index == 512) {
                    var envelope = new com.PinkCats.bandwidthoptimizer.chunk.integration.transport.envelope.ChunkTransportEnvelope(admitted, bytes);
                    var decoded = com.PinkCats.bandwidthoptimizer.chunk.integration.transport.envelope.ChunkTransportEnvelopeCodec.decodeEnvelope(
                            com.PinkCats.bandwidthoptimizer.chunk.integration.transport.envelope.ChunkTransportEnvelopeCodec.encodeEnvelope(envelope));
                    require(decoded.frame().operation() == ChunkHotspotFrameOp.PUBLISH_FULL
                            && java.util.Arrays.equals(bytes, decoded.copyOriginalPacketBytes()), "FULL fallback did not preserve original bytes on wire");
                }
            }
            requireUsage(512, 512L * bytes.length);
            ChunkHotspotFrame ref = frame(513);
            ChunkHotspotFrame patch = new ChunkHotspotFrame(ref.protocolVersion(), ChunkHotspotFrameOp.PUBLISH_PATCH,
                    ref.epoch(), ref.observedPacketCount(), ref.protocolName(), ref.packetClassName(), ref.hotspotKind(),
                    ref.laneKind(), ref.coordinate(), ref.originalEncodedBytes(), ref.fullSnapshotVersion(),
                    ref.laneVersion(), ref.baseSnapshotHash(), ref.payloadHash(), 17L, "patch-budget-regression");
            ChunkHotspotFrame admittedPatch = ChunkTransportDispatcher.reserveRecoverableFrame(channel, patch, bytes);
            require(admittedPatch.operation() == ChunkHotspotFrameOp.PUBLISH_FULL
                    && admittedPatch.baseSnapshotHash().equals(patch.payloadHash())
                    && admittedPatch.deltaBytesSinceFullSnapshot() == 0L, "Rejected PATCH retained its unavailable base dependency");
            require(ChunkWatchBoundaryReusePendingStore.takePendingFull(channel, frame(0)) != null, "Entry pressure discarded oldest unanswered replay");
        } finally { channel.finishAndReleaseAll(); }
        requireUsage(0, 0);
    }

    private static ChunkHotspotFrame frame(int index) {
        return new ChunkHotspotFrame(
                1,
                ChunkHotspotFrameOp.PUBLISH_REF,
                1L,
                index,
                "PLAY",
                "security",
                ChunkHotspotKind.FULL_CHUNK,
                ChunkLaneKind.FULL,
                new ChunkPacketCoordinate(true, index, 0),
                MEBIBYTE,
                index + 1L,
                0L,
                "base-" + index,
                "payload-" + index,
                0L,
                "security-regression"
        );
    }

    private static void requireUsage(int entries, long bytes) {
        var usage = ChunkWatchBoundaryReusePendingStore.snapshotUsage();
        require(usage.entries() == entries, "Unexpected retained entry count: " + usage.entries());
        require(usage.bytes() == bytes, "Unexpected retained byte count: " + usage.bytes());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
