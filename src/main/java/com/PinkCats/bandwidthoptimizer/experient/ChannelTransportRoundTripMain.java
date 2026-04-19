package com.PinkCats.bandwidthoptimizer.experient;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.KineticChannel;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;


public final class ChannelTransportRoundTripMain {


    private ChannelTransportRoundTripMain() {}

    // 这个函数跑一组固定样本，验证 wrap/unwrap 后包体完全相等，并打印压缩效果。
    public static void main(String[] args) {
        ChannelTransportSession senderSession = new ChannelTransportSession();
        ChannelTransportSession receiverSession = new ChannelTransportSession();
        List<TestCase> testCases = List.of(
                new TestCase("compressible-large", repeatedBytes(), true),
                new TestCase("mixed-small", utf8Bytes("channel-transport"), false),
                new TestCase("patterned", patternedBytes(), false),
                new TestCase("empty", new byte[0], false)
        );

        System.out.println("=== Channel Transport Round Trip Verification ===");
        System.out.println("algorithmId=" + senderSession.algorithmId());

        for (TestCase testCase : testCases) {
            verifyRoundTrip(senderSession, receiverSession, testCase);
        }

        verifyNonTransportPassThrough(receiverSession);
        System.out.println("All channel transport round trips passed.");
    }


    private static void verifyRoundTrip(ChannelTransportSession senderSession, ChannelTransportSession receiverSession, TestCase testCase) {
        var wrappedFrame = KineticChannel.processOutboundPacket(senderSession, testCase.packetBytes());
        if (wrappedFrame == null) {
            throw new IllegalStateException("Wrap unexpectedly returned null for " + testCase.name());
        }

        var unwrappedFrame = KineticChannel.tryUnpackInboundPacket(receiverSession, wrappedFrame.transportFrameBytes());
        if (unwrappedFrame == null) {
            throw new IllegalStateException("Unwrap unexpectedly returned null for " + testCase.name());
        }

        if (!Arrays.equals(testCase.packetBytes(), unwrappedFrame.restoredPacketBytes())) {
            throw new IllegalStateException("Round trip payload mismatch for " + testCase.name());
        }

        if (testCase.expectShrink() && wrappedFrame.zstdBodyBytes() >= testCase.packetBytes().length) {
            throw new IllegalStateException(
                    "Expected zstd body shrink for " + testCase.name()
                            + ", raw=" + testCase.packetBytes().length
                            + ", zstdBody=" + wrappedFrame.zstdBodyBytes()
            );
        }

        System.out.printf(
                Locale.ROOT,
                "%s -> rawPacketBytes=%d, zstdBodyBytes=%d, transportFrameBytes=%d, bodyRatio=%s, frameRatio=%s%n",
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

    private record TestCase(String name, byte[] packetBytes, boolean expectShrink) { }
}
