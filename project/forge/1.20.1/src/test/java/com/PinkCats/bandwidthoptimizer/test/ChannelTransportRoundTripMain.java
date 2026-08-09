package com.PinkCats.bandwidthoptimizer.test;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportFragmentCodec;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportFragmentReassembler;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStateManager;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportHooks;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportAdaptiveBypass;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportAlgorithmId;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.ChannelTransportPayloadLimits;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.KineticChannel;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.mes.ChannelTransportOperationTelemetry;
import com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd.KineticStreamingLayer;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

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
        verifyManySmallPacketBatchRoundTrip();
        verifyPostWrapBypassDisabled();
        verifyAdaptiveBypassLearnsAfterSixteenUnprofitableCarriers();
        verifyOversizedBatchRejectedAtEncode();
        verifyOversizedSinglePacketRejectedAtEncode();
        verifyFragmentedTransportReplayPreservesBytesAndOrder();
        reportFragmentedTransportLatency();
        verifyStreamingRejectsMergedCarrierFrames();
        verifyStreamingRecoversAfterIncompleteCarrier();
        verifyFlushStreamingCrossFrameReuse();
        verifyFlushStreamingRequiresCoordinatedReset();
        verifyStreamingBatchFrameRoundTrip();
        verifyProxySwitchBoundaryKeepsInboundTransportEnabled();
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

    // Keep the legal small-packet ceiling covered.
    private static void verifyManySmallPacketBatchRoundTrip() {
        ChannelTransportSession senderSession = new ChannelTransportSession();
        ChannelTransportSession receiverSession = new ChannelTransportSession();
        byte[] firstPacket = smallCustomPayloadPacket(0);
        byte[] secondPacket = smallCustomPayloadPacket(1);
        List<byte[]> packetBytesList = java.util.stream.IntStream.range(0, 40_960)
                .mapToObj(index -> index % 2 == 0 ? firstPacket : secondPacket)
                .toList();

        var wrappedFrame = ChannelTransportPacketCodec.wrapBatchPacketsLight(senderSession, packetBytesList);
        if (wrappedFrame == null || wrappedFrame.frameKind() != ChannelTransportPacketCodec.FrameKind.BATCH) {
            throw new IllegalStateException("Many-small-packet batch wrap did not produce a batch transport frame.");
        }

        var unwrappedFrame = KineticChannel.tryUnpackInboundPacket(receiverSession, wrappedFrame.transportFrameBytes());
        if (unwrappedFrame == null || unwrappedFrame.restoredPacketCount() != packetBytesList.size()) {
            throw new IllegalStateException("Many-small-packet batch did not restore the expected packet count.");
        }
        for (int index = 0; index < packetBytesList.size(); index++) {
            if (!Arrays.equals(packetBytesList.get(index), unwrappedFrame.restoredPacketBytesList().get(index))) {
                throw new IllegalStateException("Many-small-packet batch payload mismatch at index " + index);
            }
        }
    }

    // Post-wrap bypass would desync state.
    private static void verifyPostWrapBypassDisabled() {
        ChannelTransportSession senderSession = new ChannelTransportSession();
        var wrappedFrame = ChannelTransportPacketCodec.wrapPacket(senderSession, utf8Bytes("small-carrier"));
        if (ChannelTransportHooks.shouldBypassUnprofitableCarrier(wrappedFrame)) {
            throw new IllegalStateException("Stateful carrier must not be bypassed after wrap.");
        }
    }

    // Sixteen bad samples enable only the next pre-wrap bypass.
    private static void verifyAdaptiveBypassLearnsAfterSixteenUnprofitableCarriers() {
        byte[] packetBytes = utf8Bytes("tiny");
        Packet<?> packet = new ServerboundCustomPayloadPacket(
                new ResourceLocation("watut", "main"),
                new FriendlyByteBuf(Unpooled.buffer())
        );
        var unprofitableFrame = new ChannelTransportPacketCodec.WrappedTransportFrame(
                ChannelTransportPacketCodec.FrameKind.SINGLE,
                new byte[32],
                packetBytes.length,
                1,
                32,
                ChannelTransportOperationTelemetry.passthrough(ChannelTransportAlgorithmId.TDA_SZA, true, true, 32)
        );
        for (int index = 0; index < 16; index++) {
            ChannelTransportAdaptiveBypass.recordCarrierResult(
                    "PLAY",
                    PacketFlow.SERVERBOUND,
                    packet,
                    packetBytes,
                    unprofitableFrame
            );
        }
        if (!ChannelTransportAdaptiveBypass.shouldBypassBeforeWrap("PLAY", PacketFlow.SERVERBOUND, packet, packetBytes)) {
            throw new IllegalStateException("Adaptive bypass should learn after sixteen unprofitable carriers.");
        }
    }

    // Sender-side batch limits must match receiver-side limits.
    private static void verifyOversizedBatchRejectedAtEncode() {
        ChannelTransportSession senderSession = new ChannelTransportSession();
        byte[] packet = smallCustomPayloadPacket(7);
        List<byte[]> packetBytesList = java.util.stream.IntStream.range(0, 40_961)
                .mapToObj(index -> packet)
                .toList();

        try {
            ChannelTransportPacketCodec.wrapBatchPacketsLight(senderSession, packetBytesList);
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("batch packet count")) {
                return;
            }
            throw new IllegalStateException("Oversized batch was rejected with an unexpected message: " + exception.getMessage(), exception);
        }
        throw new IllegalStateException("Oversized batch should be rejected by the encoder before it reaches the decoder.");
    }

    // Single packet limits must fail on the sender side.
    private static void verifyOversizedSinglePacketRejectedAtEncode() {
        ChannelTransportSession senderSession = new ChannelTransportSession();
        byte[] packetBytes = repeatedLargeBytes(ChannelTransportPayloadLimits.MAX_SINGLE_PACKET_BYTES + 1, 0x51);

        try {
            ChannelTransportPacketCodec.wrapPacket(senderSession, packetBytes);
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("single packet bytes")) {
                return;
            }
            throw new IllegalStateException("Oversized single packet was rejected with an unexpected message: " + exception.getMessage(), exception);
        }
        throw new IllegalStateException("Oversized single packet should be rejected by the encoder before it reaches the decoder.");
    }

    // Reassembly must restore the exact completed BO frame before the stateful decoder runs.
    private static void verifyFragmentedTransportReplayPreservesBytesAndOrder() {
        ChannelTransportSession senderSession = new ChannelTransportSession();
        ChannelTransportSession receiverSession = new ChannelTransportSession();
        byte[] beforePacket = utf8Bytes("before-fragmented-frame");
        byte[] oversizedPacket = randomLargeBytes(3 * 1024 * 1024 + 257);
        byte[] afterPacket = utf8Bytes("after-fragmented-frame");

        var beforeFrame = ChannelTransportPacketCodec.wrapPacket(senderSession, beforePacket);
        var oversizedFrame = ChannelTransportPacketCodec.wrapPacket(senderSession, oversizedPacket);
        var afterFrame = ChannelTransportPacketCodec.wrapPacket(senderSession, afterPacket);
        if (oversizedFrame.transportFrameLength() <= 32_767) {
            throw new IllegalStateException("Fragment regression input did not exceed the carrier limit.");
        }

        List<byte[]> fragments = ChannelTransportFragmentCodec.fragmentTransportFrame(
                oversizedFrame.transportFrameBytes(),
                32_767,
                17
        );
        if (fragments.size() <= 1) {
            throw new IllegalStateException("Oversized transport frame was not fragmented.");
        }

        List<byte[]> replayFrames = new java.util.ArrayList<>();
        replayFrames.add(beforeFrame.transportFrameBytes());
        ChannelTransportFragmentReassembler reassembler = new ChannelTransportFragmentReassembler();
        for (byte[] fragment : fragments) {
            ChannelTransportFragmentReassembler.ReceiveResult result = reassembler.accept(fragment);
            if (result.kind() == ChannelTransportFragmentReassembler.ReceiveResult.Kind.COMPLETE) {
                if (!Arrays.equals(oversizedFrame.transportFrameBytes(), result.transportFrameBytes())) {
                    throw new IllegalStateException("Fragment reassembly changed BO frame bytes.");
                }
                replayFrames.add(result.transportFrameBytes());
            } else if (result.kind() != ChannelTransportFragmentReassembler.ReceiveResult.Kind.INCOMPLETE) {
                throw new IllegalStateException("Fragment payload was not recognized as a fragment.");
            }
        }
        replayFrames.add(afterFrame.transportFrameBytes());

        List<byte[]> restoredPackets = new java.util.ArrayList<>();
        for (byte[] replayFrame : replayFrames) {
            var unwrapped = ChannelTransportPacketCodec.tryUnwrapPacket(receiverSession, replayFrame);
            if (unwrapped == null || unwrapped.restoredPacketCount() != 1) {
                throw new IllegalStateException("Fragment replay did not restore exactly one packet.");
            }
            restoredPackets.add(unwrapped.restoredPacketBytesList().get(0));
        }
        List<byte[]> expectedPackets = List.of(beforePacket, oversizedPacket, afterPacket);
        if (restoredPackets.size() != expectedPackets.size()) {
            throw new IllegalStateException("Fragment replay changed the packet count.");
        }
        for (int index = 0; index < expectedPackets.size(); index++) {
            if (!Arrays.equals(expectedPackets.get(index), restoredPackets.get(index))) {
                throw new IllegalStateException("Fragment replay changed packet bytes or ordering at index " + index);
            }
        }
    }

    // Test-only timing: measures the new outer carrier work separately from the existing BO codec.
    private static void reportFragmentedTransportLatency() {
        byte[] oversizedPacket = randomLargeBytes(3 * 1024 * 1024 + 257);
        int sampleCount = 9;
        long[] encodeNanos = new long[sampleCount];
        long[] fragmentNanos = new long[sampleCount];
        long[] reassemblyNanos = new long[sampleCount];
        long[] decodeNanos = new long[sampleCount];
        int fragmentCount = 0;
        int outerPayloadBytes = 0;

        for (int iteration = -3; iteration < sampleCount; iteration++) {
            ChannelTransportSession senderSession = new ChannelTransportSession();
            ChannelTransportSession receiverSession = new ChannelTransportSession();

            long startedEncode = System.nanoTime();
            var frame = ChannelTransportPacketCodec.wrapPacket(senderSession, oversizedPacket);
            long finishedEncode = System.nanoTime();

            long startedFragment = System.nanoTime();
            List<byte[]> fragments = ChannelTransportFragmentCodec.fragmentTransportFrame(
                    frame.transportFrameBytes(),
                    32_767,
                    iteration + 4
            );
            long finishedFragment = System.nanoTime();

            ChannelTransportFragmentReassembler reassembler = new ChannelTransportFragmentReassembler();
            byte[] reassembledFrame = null;
            long startedReassembly = System.nanoTime();
            for (byte[] fragment : fragments) {
                ChannelTransportFragmentReassembler.ReceiveResult result = reassembler.accept(fragment);
                if (result.kind() == ChannelTransportFragmentReassembler.ReceiveResult.Kind.COMPLETE) {
                    reassembledFrame = result.transportFrameBytes();
                }
            }
            long finishedReassembly = System.nanoTime();
            if (reassembledFrame == null) {
                throw new IllegalStateException("Fragment latency sample did not complete reassembly.");
            }

            long startedDecode = System.nanoTime();
            var decoded = ChannelTransportPacketCodec.tryUnwrapPacket(receiverSession, reassembledFrame);
            long finishedDecode = System.nanoTime();
            if (decoded == null
                    || decoded.restoredPacketCount() != 1
                    || !Arrays.equals(oversizedPacket, decoded.restoredPacketBytesList().get(0))) {
                throw new IllegalStateException("Fragment latency sample changed the decoded packet.");
            }

            if (iteration >= 0) {
                encodeNanos[iteration] = finishedEncode - startedEncode;
                fragmentNanos[iteration] = finishedFragment - startedFragment;
                reassemblyNanos[iteration] = finishedReassembly - startedReassembly;
                decodeNanos[iteration] = finishedDecode - startedDecode;
                fragmentCount = fragments.size();
                outerPayloadBytes = fragments.stream().mapToInt(bytes -> bytes.length).sum();
            }
        }

        System.out.println("fragmented-3MiB latency: fragments=" + fragmentCount
                + ", outerBytes=" + outerPayloadBytes
                + ", encode=" + describeLatency(encodeNanos)
                + ", fragment=" + describeLatency(fragmentNanos)
                + ", reassemble=" + describeLatency(reassemblyNanos)
                + ", decode=" + describeLatency(decodeNanos));
    }

    private static String describeLatency(long[] samples) {
        long[] sorted = Arrays.copyOf(samples, samples.length);
        Arrays.sort(sorted);
        long median = sorted[sorted.length / 2];
        int percentile95Index = (int) Math.ceil(sorted.length * 0.95D) - 1;
        long percentile95 = sorted[Math.max(0, Math.min(percentile95Index, sorted.length - 1))];
        return "median=" + formatMillis(median) + "ms,p95=" + formatMillis(percentile95) + "ms";
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    // A transport body may deliver only one clear-text frame.
    private static void verifyStreamingRejectsMergedCarrierFrames() {
        KineticStreamingLayer senderLayer = new KineticStreamingLayer(4);
        KineticStreamingLayer receiverLayer = new KineticStreamingLayer(4);
        byte[] firstFrame = senderLayer.encode(utf8Bytes("first-frame"));
        byte[] smuggledFrame = senderLayer.encode(utf8Bytes("smuggled-frame"));
        byte[] mergedFrame = concatBytes(firstFrame, smuggledFrame);

        try {
            receiverLayer.decode(mergedFrame);
        } catch (IllegalStateException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("trailing decoded bytes")) {
                return;
            }
            throw new IllegalStateException("Merged carrier frame was rejected with an unexpected message: " + exception.getMessage(), exception);
        }
        byte[] decodedNext = receiverLayer.decode(senderLayer.encode(utf8Bytes("legit-next-frame")));
        if (Arrays.equals(decodedNext, utf8Bytes("smuggled-frame"))) {
            throw new IllegalStateException("Merged carrier frame left a smuggled packet pending for the next decode.");
        }
        throw new IllegalStateException("Merged carrier frame should be rejected before any pending packet can leak.");
    }

    // Failed carrier decode must not poison the next carrier.
    private static void verifyStreamingRecoversAfterIncompleteCarrier() {
        KineticStreamingLayer senderLayer = new KineticStreamingLayer(4);
        KineticStreamingLayer receiverLayer = new KineticStreamingLayer(4);
        byte[] incompleteFrame = Arrays.copyOf(senderLayer.encode(utf8Bytes("incomplete-source-frame")), 8);

        try {
            receiverLayer.decode(incompleteFrame);
        } catch (RuntimeException ignored) {
            byte[] expectedBytes = utf8Bytes("after-incomplete-frame");
            byte[] decodedBytes = receiverLayer.decode(senderLayer.encode(expectedBytes));
            if (!Arrays.equals(expectedBytes, decodedBytes)) {
                throw new IllegalStateException("Streaming decoder returned stale bytes after incomplete carrier.");
            }
            return;
        }
        throw new IllegalStateException("Incomplete carrier should be rejected before the next frame.");
    }

    private static void verifyFlushStreamingCrossFrameReuse() {
        KineticStreamingLayer endSender = new KineticStreamingLayer(4);
        KineticStreamingLayer endReceiver = new KineticStreamingLayer(4);
        KineticStreamingLayer flushSender = new KineticStreamingLayer(
                4,
                KineticStreamingLayer.FrameTermination.FLUSH
        );
        KineticStreamingLayer flushReceiver = new KineticStreamingLayer(
                4,
                KineticStreamingLayer.FrameTermination.FLUSH
        );
        long endBytes = 0L;
        long flushBytes = 0L;
        for (int index = 0; index < 48; index++) {
            byte[] payload = repeatedDynamicPayload(index);
            byte[] endFrame = endSender.encode(payload);
            byte[] flushFrame = flushSender.encode(payload);
            endBytes += endFrame.length;
            flushBytes += flushFrame.length;
            if (!Arrays.equals(payload, endReceiver.decode(endFrame))) {
                throw new IllegalStateException("END streaming payload mismatch at frame " + index);
            }
            if (!Arrays.equals(payload, flushReceiver.decode(flushFrame))) {
                throw new IllegalStateException("FLUSH streaming payload mismatch at frame " + index);
            }
        }
        if (flushBytes >= endBytes) {
            throw new IllegalStateException(
                    "FLUSH streaming did not reuse cross-frame history: flush=" + flushBytes + ", end=" + endBytes
            );
        }
        System.out.printf(
                Locale.ROOT,
                "flush-reuse corpus: endBytes=%d, flushBytes=%d, saved=%.2f%%%n",
                endBytes,
                flushBytes,
                100.0D * (endBytes - flushBytes) / endBytes
        );
    }

    private static void verifyFlushStreamingRequiresCoordinatedReset() {
        KineticStreamingLayer sender = new KineticStreamingLayer(4, KineticStreamingLayer.FrameTermination.FLUSH);
        KineticStreamingLayer receiver = new KineticStreamingLayer(4, KineticStreamingLayer.FrameTermination.FLUSH);
        receiver.decode(sender.encode(repeatedDynamicPayload(0)));
        byte[] staleEpochFrame = sender.encode(repeatedDynamicPayload(1));
        receiver.reset();
        try {
            receiver.decode(staleEpochFrame);
        } catch (RuntimeException expected) {
            sender.reset();
            receiver.reset();
            byte[] synchronizedEpochPayload = repeatedDynamicPayload(2);
            byte[] synchronizedEpochFrame = sender.encode(synchronizedEpochPayload);
            if (!Arrays.equals(synchronizedEpochPayload, receiver.decode(synchronizedEpochFrame))) {
                throw new IllegalStateException("Coordinated FLUSH reset did not restore the next epoch.");
            }
            return;
        }
        throw new IllegalStateException("FLUSH receiver reset accepted a stale-epoch carrier without a synchronized reset.");
    }

    private static void verifyStreamingBatchFrameRoundTrip() {
        ChannelTransportSession sender = new ChannelTransportSession();
        ChannelTransportSession receiver = new ChannelTransportSession();
        sender.setCrossFrameZstdEnabled(true);
        receiver.setCrossFrameZstdEnabled(true);
        try {
            List<byte[]> expectedPackets = List.of(
                    repeatedDynamicPayload(7),
                    repeatedDynamicPayload(8),
                    repeatedDynamicPayload(9)
            );
            var wrapped = ChannelTransportPacketCodec.wrapBatchPackets(sender, expectedPackets);
            if (wrapped == null || wrapped.frameKind() != ChannelTransportPacketCodec.FrameKind.STREAM_BATCH) {
                throw new IllegalStateException("Cross-frame Zstd batch did not select the streaming carrier format.");
            }
            var unwrapped = ChannelTransportPacketCodec.tryUnwrapPacket(receiver, wrapped.transportFrameBytes());
            if (unwrapped == null
                    || unwrapped.frameKind() != ChannelTransportPacketCodec.FrameKind.STREAM_BATCH
                    || unwrapped.streamingEpoch() <= 0
                    || unwrapped.streamingSequence() != 1
                    || unwrapped.restoredPacketBytesList().size() != expectedPackets.size()) {
                throw new IllegalStateException("Cross-frame Zstd batch frame lost its stream metadata.");
            }
            for (int index = 0; index < expectedPackets.size(); index++) {
                if (!Arrays.equals(expectedPackets.get(index), unwrapped.restoredPacketBytesList().get(index))) {
                    throw new IllegalStateException("Cross-frame Zstd batch changed packet bytes at index " + index);
                }
            }
        } finally {
            sender.close();
            receiver.close();
        }
    }

    // Proxy switch guards must not block inbound transport decode.
    private static void verifyProxySwitchBoundaryKeepsInboundTransportEnabled() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            ChannelTransportSession senderSession = new ChannelTransportSession();
            ChannelTransportStateManager.beginProxyServerSwitchBoundary(channel, "round_trip_regression", 15_000L);
            if (!ChannelTransportStateManager.isProxyServerSwitchBoundaryActive(channel)) {
                throw new IllegalStateException("Proxy switch boundary should be active for outbound compatibility guards.");
            }

            byte[] expectedBytes = utf8Bytes("transport-after-proxy-switch-boundary");
            var wrappedFrame = ChannelTransportPacketCodec.wrapPacket(senderSession, expectedBytes);
            ChannelTransportSession receiverSession = ChannelTransportStateManager.getOrCreateSession(channel);
            var unwrappedFrame = ChannelTransportPacketCodec.tryUnwrapPacket(receiverSession, wrappedFrame.transportFrameBytes());
            if (unwrappedFrame == null
                    || unwrappedFrame.restoredPacketCount() != 1
                    || !Arrays.equals(expectedBytes, unwrappedFrame.restoredPacketBytesList().get(0))) {
                throw new IllegalStateException("Proxy switch boundary should not block inbound transport decode.");
            }
        } finally {
            ChannelTransportStateManager.endProxyServerSwitchBoundary(channel, "round_trip_regression_cleanup");
            channel.close();
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

    private static byte[] randomLargeBytes(int length) {
        byte[] bytes = new byte[length];
        new Random(0xB0F00DL).nextBytes(bytes);
        return bytes;
    }

    private static byte[] repeatedLargeBytes(int length, int seed) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) seed);
        return bytes;
    }

    private static byte[] smallCustomPayloadPacket(int variant) {
        byte[] bytes = ("aether:main:setLifeShardCount:" + variant).getBytes(StandardCharsets.UTF_8);
        bytes[0] = 0x2D;
        return bytes;
    }

    private static byte[] repeatedDynamicPayload(int frameIndex) {
        byte[] bytes = new byte[4 * 1024];
        Arrays.fill(bytes, (byte) 0x4D);
        byte[] prefix = "create:main:contraption-update:".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        bytes[512] = (byte) frameIndex;
        bytes[513] = (byte) (frameIndex >>> 8);
        bytes[2048] = (byte) (frameIndex * 31);
        return bytes;
    }

    private static byte[] concatBytes(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
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
