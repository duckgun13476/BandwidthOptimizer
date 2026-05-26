package com.PinkCats.bandwidthoptimizer.test;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportAlgorithmId;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.KineticChannel;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class ChannelTransportRoundTripMain {

    private ChannelTransportRoundTripMain() {}

    public static void main(String[] args) {
        ChannelTransportSession senderSession = new ChannelTransportSession();
        ChannelTransportSession receiverSession = new ChannelTransportSession();
        ChannelTransportAlgorithmId algorithmId = senderSession.algorithmId();
        List<TestCase> testCases = List.of(
                new TestCase("compressible-large", repeatedBytes(), true),
                new TestCase("mixed-small", utf8Bytes("channel-transport"), false),
                new TestCase("template-seed-a", templateLikeBytes(0x11, 0x22), false),
                new TestCase("template-seed-b", templateLikeBytes(0x33, 0x44), false),
                new TestCase("template-seed-c", templateLikeBytes(0x55, 0x66), false),
                new TestCase("patterned", patternedBytes(), false),
                new TestCase("empty", new byte[0], false)
        );

        System.out.println("=== Channel Transport Round Trip Verification ===");
        System.out.println("algorithmId=" + algorithmId);

        for (TestCase testCase : testCases) {
            verifyRoundTrip(senderSession, receiverSession, algorithmId, testCase);
        }

        verifyDefaultBatchUsesStatelessMapping();
        verifyLightBatchRoundTrip();
        verifyLargeLightBatchRoundTrip();
        verifyNonTransportPassThrough(receiverSession);
        System.out.println("All channel transport round trips passed.");
    }

    private static void verifyRoundTrip(
            ChannelTransportSession senderSession,
            ChannelTransportSession receiverSession,
            ChannelTransportAlgorithmId algorithmId,
            TestCase testCase
    ) {
        var wrappedFrame = KineticChannel.processOutboundPacket(senderSession, testCase.packetBytes());
        if (wrappedFrame == null) {
            throw new IllegalStateException("Wrap unexpectedly returned null for " + testCase.name());
        }

        var unwrappedFrame = KineticChannel.tryUnpackInboundPacket(receiverSession, wrappedFrame.transportFrameBytes());
        if (unwrappedFrame == null) {
            throw new IllegalStateException("Unwrap unexpectedly returned null for " + testCase.name());
        }

        if (unwrappedFrame.restoredPacketCount() != 1) {
            throw new IllegalStateException("Round trip unexpectedly restored " + unwrappedFrame.restoredPacketCount() + " packets for " + testCase.name());
        }

        if (!Arrays.equals(testCase.packetBytes(), unwrappedFrame.restoredPacketBytesList().get(0))) {
            throw new IllegalStateException("Round trip payload mismatch for " + testCase.name());
        }

        if (testCase.expectShrink() && algorithmId.usesZstd()
                && wrappedFrame.zstdBodyBytes() >= testCase.packetBytes().length) {
            throw new IllegalStateException(
                    "Expected transport body shrink for " + testCase.name()
                            + ", raw=" + testCase.packetBytes().length
                            + ", transportBody=" + wrappedFrame.zstdBodyBytes()
            );
        }

        System.out.printf(
                Locale.ROOT,
                "%s -> rawPacketBytes=%d, transportBodyBytes=%d, transportFrameBytes=%d, bodyRatio=%s, frameRatio=%s%n",
                testCase.name(),
                testCase.packetBytes().length,
                wrappedFrame.zstdBodyBytes(),
                wrappedFrame.transportFrameLength(),
                ratioText(wrappedFrame.zstdBodyBytes(), testCase.packetBytes().length),
                ratioText(wrappedFrame.transportFrameLength(), testCase.packetBytes().length)
        );
    }


    private static void verifyNonTransportPassThrough(ChannelTransportSession receiverSession) {
        byte[] vanillaPacketBytes = utf8Bytes("not-a-transport-frame");
        var result = KineticChannel.tryUnpackInboundPacket(receiverSession, vanillaPacketBytes);
        if (result != null) {
            throw new IllegalStateException("Non-transport bytes should not be unwrapped");
        }
    }

    // Default batch frames must not depend on receiver-side template history.
    private static void verifyDefaultBatchUsesStatelessMapping() {
        ChannelTransportSession senderSession = new ChannelTransportSession();
        ChannelTransportSession receiverSession = new ChannelTransportSession();
        for (int round = 0; round < 4; round++) {
            List<byte[]> packetBytesList = List.of(
                    templateLikeBytes(0x30 + round, 0x40 + round),
                    templateLikeBytes(0x50 + round, 0x60 + round),
                    patternedBytes()
            );

            var wrappedFrame = ChannelTransportPacketCodec.wrapBatchPackets(senderSession, packetBytesList);
            if (wrappedFrame == null || wrappedFrame.frameKind() != ChannelTransportPacketCodec.FrameKind.BATCH) {
                throw new IllegalStateException("Default batch wrap did not produce a batch transport frame.");
            }
            if (wrappedFrame.telemetry() == null
                    || wrappedFrame.telemetry().literalEntryCount() != 1
                    || wrappedFrame.telemetry().exactReferenceCount() != 0
                    || wrappedFrame.telemetry().templateReferenceCount() != 0
                    || wrappedFrame.telemetry().exactAdditionCount() != 0
                    || wrappedFrame.telemetry().templateAdditionCount() != 0) {
                throw new IllegalStateException("Default batch should use one literal mapping entry without dictionary state.");
            }

            var unwrappedFrame = KineticChannel.tryUnpackInboundPacket(receiverSession, wrappedFrame.transportFrameBytes());
            if (unwrappedFrame == null || unwrappedFrame.restoredPacketCount() != packetBytesList.size()) {
                throw new IllegalStateException("Default stateless batch did not restore the expected packet count.");
            }
            for (int index = 0; index < packetBytesList.size(); index++) {
                if (!Arrays.equals(packetBytesList.get(index), unwrappedFrame.restoredPacketBytesList().get(index))) {
                    throw new IllegalStateException("Default stateless batch payload mismatch at index " + index);
                }
            }
        }
    }

    private static void verifyLightBatchRoundTrip() {
        ChannelTransportSession senderSession = new ChannelTransportSession();
        ChannelTransportSession receiverSession = new ChannelTransportSession();
        List<byte[]> packetBytesList = List.of(
                utf8Bytes("light-batch-a"),
                templateLikeBytes(0x21, 0x32),
                patternedBytes()
        );

        var wrappedFrame = ChannelTransportPacketCodec.wrapBatchPacketsLight(senderSession, packetBytesList);
        if (wrappedFrame == null || wrappedFrame.frameKind() != ChannelTransportPacketCodec.FrameKind.BATCH) {
            throw new IllegalStateException("Light batch wrap did not produce a batch transport frame.");
        }
        if (wrappedFrame.telemetry() == null
                || wrappedFrame.telemetry().literalEntryCount() != 1
                || wrappedFrame.telemetry().exactAdditionCount() != 0
                || wrappedFrame.telemetry().templateAdditionCount() != 0) {
            throw new IllegalStateException("Light batch should use one literal mapping entry without dictionary additions.");
        }

        var unwrappedFrame = KineticChannel.tryUnpackInboundPacket(receiverSession, wrappedFrame.transportFrameBytes());
        if (unwrappedFrame == null || unwrappedFrame.restoredPacketCount() != packetBytesList.size()) {
            throw new IllegalStateException("Light batch did not restore the expected packet count.");
        }
        for (int index = 0; index < packetBytesList.size(); index++) {
            if (!Arrays.equals(packetBytesList.get(index), unwrappedFrame.restoredPacketBytesList().get(index))) {
                throw new IllegalStateException("Light batch payload mismatch at index " + index);
            }
        }
    }

    private static void verifyLargeLightBatchRoundTrip() {
        ChannelTransportSession senderSession = new ChannelTransportSession();
        ChannelTransportSession receiverSession = new ChannelTransportSession();
        List<byte[]> packetBytesList = List.of(
                repeatedLargeBytes(4 * 1024 * 1024 + 256 * 1024, 0x41),
                repeatedLargeBytes(4 * 1024 * 1024 + 256 * 1024, 0x42)
        );

        var wrappedFrame = ChannelTransportPacketCodec.wrapBatchPacketsLight(senderSession, packetBytesList);
        if (wrappedFrame == null || wrappedFrame.frameKind() != ChannelTransportPacketCodec.FrameKind.BATCH) {
            throw new IllegalStateException("Large light batch wrap did not produce a batch transport frame.");
        }

        var unwrappedFrame = KineticChannel.tryUnpackInboundPacket(receiverSession, wrappedFrame.transportFrameBytes());
        if (unwrappedFrame == null || unwrappedFrame.restoredPacketCount() != packetBytesList.size()) {
            throw new IllegalStateException("Large light batch did not restore the expected packet count.");
        }
        for (int index = 0; index < packetBytesList.size(); index++) {
            if (!Arrays.equals(packetBytesList.get(index), unwrappedFrame.restoredPacketBytesList().get(index))) {
                throw new IllegalStateException("Large light batch payload mismatch at index " + index);
            }
        }
    }


    private static String ratioText(long currentBytes, long baselineBytes) {
        if (baselineBytes <= 0L) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.3fx", (double) currentBytes / (double) baselineBytes);
    }


    private static byte[] utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }


    private static byte[] repeatedBytes() {
        byte[] bytes = new byte[4096];
        Arrays.fill(bytes, (byte) 65);
        return bytes;
    }

    private static byte[] patternedBytes() {
        byte[] bytes = new byte[1024];
        for (int index = 0; index < 1024; index++) {
            bytes[index] = (byte) ((index * 31 + 17) & 0xFF);
        }
        return bytes;
    }

    private static byte[] repeatedLargeBytes(int length, int seed) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) seed);
        return bytes;
    }


    private static byte[] templateLikeBytes(int leftMarker, int rightMarker) {
        byte[] bytes = new byte[192];
        Arrays.fill(bytes, (byte) 0x5A);
        for (int index = 0; index < bytes.length; index += 16) {
            bytes[index] = (byte) (index & 0xFF);
            bytes[index + 1] = (byte) ((index * 3) & 0xFF);
        }
        bytes[20] = (byte) leftMarker;
        bytes[21] = (byte) (leftMarker ^ 0x5C);
        bytes[22] = (byte) (leftMarker + 7);
        bytes[96] = (byte) rightMarker;
        bytes[97] = (byte) (rightMarker ^ 0x33);
        bytes[98] = (byte) (rightMarker + 11);
        return bytes;
    }

    private record TestCase(String name, byte[] packetBytes, boolean expectShrink) { }
}
