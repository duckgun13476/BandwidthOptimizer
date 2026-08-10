package com.PinkCats.bandwidthoptimizer.test;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class CrossFrameZstdRegressionMain {

    private static final int EPOCHS = 12;
    private static final int FRAMES_PER_EPOCH = 64;

    private CrossFrameZstdRegressionMain() {}

    public static void main(String[] args) {
        verifySustainedCrossFrameReuse();
        verifyGapRecoveryAndResume();
        System.out.println("Cross-frame Zstd regression passed.");
    }

    private static void verifySustainedCrossFrameReuse() {
        long independentBytes = 0L;
        long streamingBytes = 0L;
        int restoredFrames = 0;
        try (ChannelTransportSession independentSender = new ChannelTransportSession();
             ChannelTransportSession independentReceiver = new ChannelTransportSession();
             ChannelTransportSession streamingSender = new ChannelTransportSession();
             ChannelTransportSession streamingReceiver = new ChannelTransportSession()) {
            streamingSender.setCrossFrameZstdEnabled(true);
            streamingReceiver.setCrossFrameZstdEnabled(true);

            for (int epochIndex = 0; epochIndex < EPOCHS; epochIndex++) {
                int epoch = streamingSender.outboundStreamingEpoch();
                for (int frameIndex = 0; frameIndex < FRAMES_PER_EPOCH; frameIndex++) {
                    List<byte[]> packets = payload(epochIndex, frameIndex);
                    var independent = ChannelTransportPacketCodec.wrapBatchPackets(independentSender, packets);
                    var streaming = ChannelTransportPacketCodec.wrapBatchPackets(streamingSender, packets);
                    if (independent == null || independent.frameKind() != ChannelTransportPacketCodec.FrameKind.BATCH) {
                        throw new IllegalStateException("Independent batch did not use the END carrier");
                    }
                    if (streaming == null
                            || streaming.frameKind() != ChannelTransportPacketCodec.FrameKind.STREAM_BATCH) {
                        throw new IllegalStateException("Streaming frame metadata changed at epoch " + epoch);
                    }
                    assertRestored(packets, ChannelTransportPacketCodec.tryUnwrapPacket(
                            independentReceiver,
                            independent.transportFrameBytes()
                    ));
                    var restoredStreaming = ChannelTransportPacketCodec.tryUnwrapPacket(
                            streamingReceiver,
                            streaming.transportFrameBytes()
                    );
                    assertRestored(packets, restoredStreaming);
                    if (restoredStreaming.streamingEpoch() != epoch
                            || restoredStreaming.streamingSequence() != frameIndex + 1) {
                        throw new IllegalStateException("Streaming frame metadata changed at epoch " + epoch);
                    }
                    independentBytes += independent.zstdBodyBytes();
                    streamingBytes += streaming.zstdBodyBytes();
                    restoredFrames++;
                }

                ChannelTransportSession.StreamingEpochBoundary boundary =
                        streamingSender.outboundStreamingEpochBoundary();
                if (boundary == null
                        || boundary.epoch() != epoch
                        || boundary.lastSequence() != FRAMES_PER_EPOCH
                        || !streamingReceiver.acceptInboundStreamingEpochComplete(
                                boundary.epoch(),
                                boundary.lastSequence())) {
                    throw new IllegalStateException("Streaming epoch did not close cleanly: " + epoch);
                }
                streamingSender.acceptOutboundStreamingEpochOk(boundary.epoch(), boundary.lastSequence());
                streamingSender.restartOutboundStreamingEpoch();
                streamingReceiver.resetInboundStreamingEpoch();
                if (streamingSender.outboundStreamingEpoch() == epoch) {
                    throw new IllegalStateException("Streaming epoch did not advance after acknowledgement");
                }
            }
        }

        if (streamingBytes >= independentBytes) {
            throw new IllegalStateException(
                    "Cross-frame history did not reduce bytes: streaming=" + streamingBytes
                            + ", independent=" + independentBytes
            );
        }
        System.out.printf(
                Locale.ROOT,
                "sustained-reuse: frames=%d, independentBytes=%d, streamingBytes=%d, saved=%.2f%%%n",
                restoredFrames,
                independentBytes,
                streamingBytes,
                100.0D * (independentBytes - streamingBytes) / independentBytes
        );
    }

    private static void verifyGapRecoveryAndResume() {
        try (ChannelTransportSession sender = new ChannelTransportSession();
             ChannelTransportSession receiver = new ChannelTransportSession()) {
            sender.setCrossFrameZstdEnabled(true);
            receiver.setCrossFrameZstdEnabled(true);
            List<ChannelTransportPacketCodec.WrappedTransportFrame> frames = List.of(
                    wrapStreaming(sender, "frame-one"),
                    wrapStreaming(sender, "frame-two"),
                    wrapStreaming(sender, "frame-three"),
                    wrapStreaming(sender, "frame-four")
            );
            int epoch = sender.outboundStreamingEpoch();
            assertRestored(List.of(bytes("frame-one")), ChannelTransportPacketCodec.tryUnwrapPacket(
                    receiver,
                    frames.get(0).transportFrameBytes()
            ));

            try {
                ChannelTransportPacketCodec.tryUnwrapPacket(receiver, frames.get(2).transportFrameBytes());
                throw new IllegalStateException("A missing streaming frame was not detected");
            } catch (ChannelTransportPacketCodec.StreamingRecoveryException expected) {
                if (expected.recoveryRequest().epoch() != epoch
                        || expected.recoveryRequest().expectedSequence() != 2) {
                    throw expected;
                }
            }

            receiver.resetInboundStreamingForRecovery();
            List<ChannelTransportSession.StreamingFallbackBatch> fallback =
                    sender.fallbackOutboundStreamingBatches(epoch, 2);
            if (fallback.size() != 3) {
                throw new IllegalStateException("Recovery did not retain the missing suffix");
            }
            String[] expectedValues = {"frame-two", "frame-three", "frame-four"};
            for (int index = 0; index < fallback.size(); index++) {
                var recoveryFrame = ChannelTransportPacketCodec.wrapStreamingFallbackBatch(sender, fallback.get(index));
                if (recoveryFrame == null
                        || recoveryFrame.frameKind() != ChannelTransportPacketCodec.FrameKind.RECOVERY_BATCH) {
                    throw new IllegalStateException("Recovery suffix was not independently framed");
                }
                assertRestored(List.of(bytes(expectedValues[index])), ChannelTransportPacketCodec.tryUnwrapPacket(
                        receiver,
                        recoveryFrame.transportFrameBytes()
                ));
            }

            sender.restartOutboundStreamingEpoch();
            receiver.resetInboundStreamingEpoch();
            var resumed = wrapStreaming(sender, "resumed-frame");
            var restoredResumed = ChannelTransportPacketCodec.tryUnwrapPacket(
                    receiver,
                    resumed.transportFrameBytes()
            );
            assertRestored(List.of(bytes("resumed-frame")), restoredResumed);
            if (restoredResumed.streamingEpoch() == epoch || restoredResumed.streamingSequence() != 1) {
                throw new IllegalStateException("Recovery did not resume in a fresh streaming epoch");
            }
        }
        System.out.println("gap-recovery: missing suffix restored and fresh epoch resumed");
    }

    private static ChannelTransportPacketCodec.WrappedTransportFrame wrapStreaming(
            ChannelTransportSession sender,
            String value
    ) {
        var frame = ChannelTransportPacketCodec.wrapBatchPackets(sender, List.of(bytes(value)));
        if (frame == null || frame.frameKind() != ChannelTransportPacketCodec.FrameKind.STREAM_BATCH) {
            throw new IllegalStateException("Expected a streaming batch frame");
        }
        return frame;
    }

    private static void assertRestored(
            List<byte[]> expected,
            ChannelTransportPacketCodec.UnwrappedTransportFrame restored
    ) {
        if (restored == null || restored.restoredPacketBytesList().size() != expected.size()) {
            throw new IllegalStateException("Transport frame restored the wrong packet count");
        }
        for (int index = 0; index < expected.size(); index++) {
            if (!Arrays.equals(expected.get(index), restored.restoredPacketBytesList().get(index))) {
                throw new IllegalStateException("Transport frame changed packet bytes at index " + index);
            }
        }
    }

    private static List<byte[]> payload(int epochIndex, int frameIndex) {
        byte[] first = new byte[4096];
        byte[] second = new byte[1536];
        Arrays.fill(first, (byte) 0x5A);
        Arrays.fill(second, (byte) 0x31);
        first[7] = (byte) epochIndex;
        first[31] = (byte) frameIndex;
        first[1024] = (byte) (frameIndex * 17);
        second[9] = (byte) epochIndex;
        second[511] = (byte) (frameIndex * 29);
        return List.of(first, second);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
