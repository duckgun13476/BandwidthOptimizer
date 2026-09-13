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
        verifyReplacementAndTakeAccounting();
        verifyPerChannelAdmissionAndCloseCleanup();
        verifyProcessWideAdmission();
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
            ChunkWatchBoundaryReusePendingStore.rememberProbe(channel, frame(index), oneMebibyte);
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
            ChunkWatchBoundaryReusePendingStore.rememberProbe(channel, frame(index), oneMebibyte);
        }
        requireUsage(64, 64L * MEBIBYTE);
        for (EmbeddedChannel channel : channels) {
            channel.finishAndReleaseAll();
        }
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
