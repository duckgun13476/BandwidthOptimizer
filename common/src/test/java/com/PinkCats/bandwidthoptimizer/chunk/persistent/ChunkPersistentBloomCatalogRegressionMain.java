package com.PinkCats.bandwidthoptimizer.chunk.persistent;

import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameCodec;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;

import java.util.ArrayList;

public final class ChunkPersistentBloomCatalogRegressionMain {

    private ChunkPersistentBloomCatalogRegressionMain() {}

    public static void main(String[] args) {
        ArrayList<ChunkPacketCoordinate> stored = new ArrayList<>();
        for (int index = 0; index < 34_000; index++) {
            stored.add(ChunkPacketCoordinate.ofChunk(index - 17_000, index * 31 - 500_000));
        }

        ChunkPersistentBloomCatalog original = ChunkPersistentBloomCatalog.build(stored);
        ChunkPersistentBloomCatalog decoded = ChunkPersistentBloomCatalog.decode(original.encode());
        require(decoded.entryCount() == stored.size(), "Bloom entry count changed during round trip");
        for (ChunkPacketCoordinate coordinate : stored) {
            require(decoded.mightContain(coordinate), "Bloom catalog produced a false negative");
        }

        int falsePositives = 0;
        int absentSamples = 20_000;
        for (int index = 0; index < absentSamples; index++) {
            ChunkPacketCoordinate absent = ChunkPacketCoordinate.ofChunk(200_000 + index, -300_000 - index * 17);
            if (decoded.mightContain(absent)) {
                falsePositives++;
            }
        }
        require(falsePositives < absentSamples / 20, "Bloom false-positive rate exceeded 5%: " + falsePositives);

        byte[] malformed = original.encode();
        malformed[0] ^= 0x01;
        boolean rejected = false;
        try {
            ChunkPersistentBloomCatalog.decode(malformed);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "Malformed Bloom payload was accepted");
        verifyControlOperationsRoundTrip();
        System.out.println("Persistent cache Bloom catalog regression passed; falsePositives=" + falsePositives);
    }

    private static void verifyControlOperationsRoundTrip() {
        for (ChunkHotspotFrameOp operation : new ChunkHotspotFrameOp[]{
                ChunkHotspotFrameOp.CLIENT_CACHE_BLOOM,
                ChunkHotspotFrameOp.CACHE_PREPARE,
                ChunkHotspotFrameOp.CACHE_READY,
                ChunkHotspotFrameOp.CACHE_MISS
        }) {
            ChunkHotspotFrame frame = new ChunkHotspotFrame(
                    ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                    operation,
                    17L,
                    29L,
                    "PLAY",
                    "test.Packet",
                    ChunkHotspotKind.FULL_CHUNK,
                    ChunkLaneKind.FULL,
                    ChunkPacketCoordinate.ofChunk(3, -7),
                    4096,
                    2L,
                    0L,
                    "scope-hash",
                    "payload-hash",
                    0L,
                    "regression"
            );
            ChunkHotspotFrame decodedFrame = ChunkHotspotFrameCodec.decodeFrame(ChunkHotspotFrameCodec.encodeFrame(frame));
            require(decodedFrame.operation() == operation, "Control operation changed during frame round trip: " + operation);
            require(decodedFrame.observedPacketCount() == 29L, "Control token changed during frame round trip");
            require(decodedFrame.coordinate().equals(frame.coordinate()), "Control coordinate changed during frame round trip");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
