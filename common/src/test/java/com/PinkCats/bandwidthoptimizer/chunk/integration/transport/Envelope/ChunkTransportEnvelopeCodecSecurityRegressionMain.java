package com.PinkCats.bandwidthoptimizer.chunk.integration.transport.Envelope;

import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkHotspotKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.ChunkLaneKind;
import com.PinkCats.bandwidthoptimizer.chunk.classify.packet.ChunkPacketCoordinate;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrame;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameCodec;
import com.PinkCats.bandwidthoptimizer.chunk.protocol.hotspot.ChunkHotspotFrameOp;
import io.netty.channel.embedded.EmbeddedChannel;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public final class ChunkTransportEnvelopeCodecSecurityRegressionMain {

    private static final byte[] MAGIC = "BOCHKENV".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private ChunkTransportEnvelopeCodecSecurityRegressionMain() {}

    public static void main(String[] args) {
        verifyValidEnvelopeRoundTrip();
        requireRejected("max frame length", maliciousEnvelope(Integer.MAX_VALUE, new byte[0]));
        requireRejected("truncated frame", maliciousEnvelope(4096, new byte[0]));

        byte[] validFrame = ChunkHotspotFrameCodec.encodeFrame(testFrame());
        requireRejected("max payload length", maliciousEnvelope(validFrame.length, validFrame, Integer.MAX_VALUE));
        requireRejected("truncated payload", maliciousEnvelope(validFrame.length, validFrame, 4096));
        verifyRejectionReportLimits();
        System.out.println("Chunk transport envelope security regression passed");
    }

    private static void verifyValidEnvelopeRoundTrip() {
        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        ChunkTransportEnvelope decoded = ChunkTransportEnvelopeCodec.decodeEnvelope(
                ChunkTransportEnvelopeCodec.encodeEnvelope(new ChunkTransportEnvelope(testFrame(), payload))
        );
        require(decoded.frame().operation() == ChunkHotspotFrameOp.PUBLISH_FULL, "Envelope frame operation changed");
        require(Arrays.equals(decoded.copyOriginalPacketBytes(), payload), "Envelope payload changed");
    }

    private static void requireRejected(String label, byte[] encodedEnvelope) {
        try {
            ChunkTransportEnvelopeCodec.decodeEnvelope(encodedEnvelope);
        } catch (IllegalArgumentException expected) {
            return;
        } catch (OutOfMemoryError error) {
            throw new AssertionError(label + " attempted an attacker-controlled allocation", error);
        }
        throw new AssertionError(label + " was accepted");
    }

    private static void verifyRejectionReportLimits() {
        ChunkTransportEnvelopeRejectionReporter.resetForTest();
        EmbeddedChannel connection = new EmbeddedChannel();
        require(
                ChunkTransportEnvelopeRejectionReporter.shouldReportForTest(connection, 1_000L),
                "First malformed envelope on a connection was not reportable"
        );
        require(
                !ChunkTransportEnvelopeRejectionReporter.shouldReportForTest(connection, 1_001L),
                "A connection could emit more than one malformed-envelope report"
        );
        connection.close();

        for (int index = 1; index < 10; index++) {
            EmbeddedChannel channel = new EmbeddedChannel();
            require(
                    ChunkTransportEnvelopeRejectionReporter.shouldReportForTest(channel, 1_000L + index),
                    "Global limiter rejected an allowed report at index " + index
            );
            channel.close();
        }
        EmbeddedChannel limited = new EmbeddedChannel();
        require(
                !ChunkTransportEnvelopeRejectionReporter.shouldReportForTest(limited, 1_020L),
                "Global malformed-envelope report limiter did not engage"
        );
        limited.close();

        EmbeddedChannel nextWindow = new EmbeddedChannel();
        require(
                ChunkTransportEnvelopeRejectionReporter.shouldReportForTest(nextWindow, 61_001L),
                "Malformed-envelope reporting did not recover in the next window"
        );
        nextWindow.close();
    }

    private static byte[] maliciousEnvelope(int frameLength, byte[] frameBytes) {
        return maliciousEnvelope(frameLength, frameBytes, 0);
    }

    private static byte[] maliciousEnvelope(int frameLength, byte[] frameBytes, int payloadLength) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(MAGIC);
        writeVarInt(output, frameLength);
        output.writeBytes(frameBytes);
        writeVarInt(output, payloadLength);
        return output.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        int remaining = value;
        while ((remaining & -128) != 0) {
            output.write(remaining & 127 | 128);
            remaining >>>= 7;
        }
        output.write(remaining);
    }

    private static ChunkHotspotFrame testFrame() {
        return new ChunkHotspotFrame(
                ChunkHotspotFrameCodec.PROTOCOL_VERSION,
                ChunkHotspotFrameOp.PUBLISH_FULL,
                1L,
                2L,
                "PLAY",
                "test.Packet",
                ChunkHotspotKind.FULL_CHUNK,
                ChunkLaneKind.FULL,
                ChunkPacketCoordinate.ofChunk(3, -7),
                5,
                4L,
                0L,
                "base",
                "payload",
                0L,
                "security-regression"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
