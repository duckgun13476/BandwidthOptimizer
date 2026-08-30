package com.PinkCats.bandwidthoptimizer.channel.algorithm.zstd;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;

import java.util.Arrays;

public final class KineticStreamingDirectBufferReuseRegressionMain {

    private static final int PAYLOAD_BYTES = 192 * 1024;
    private static final int ITERATIONS = 96;

    private KineticStreamingDirectBufferReuseRegressionMain() {
    }

    public static void main(String[] args) {
        byte[] payload = payload();
        verifyLargeFramesReuseOneWorkspace(payload);
        verifyIndependentRecoveryResetsSafely(payload);
        System.out.println("direct-buffer-reuse: passed");
    }

    private static void verifyLargeFramesReuseOneWorkspace(byte[] payload) {
        try (KineticStreamingLayer layer = new KineticStreamingLayer(4)) {
            require(layer.directWorkspaceBufferCountForTesting() == 0,
                    "direct workspaces must be lazy");
            for (int index = 0; index < ITERATIONS; index++) {
                byte[] encoded = layer.encode(payload);
                require(Arrays.equals(payload, layer.decode(encoded)),
                        "large direct workspace frame did not round-trip at iteration " + index);
            }
            require(layer.directWorkspaceBufferCountForTesting() == 2,
                    "large frames allocated more than the fixed source and target workspaces");
        }
    }

    private static void verifyIndependentRecoveryResetsSafely(byte[] payload) {
        try (ChannelTransportSession sender = new ChannelTransportSession();
             ChannelTransportSession receiver = new ChannelTransportSession()) {
            for (int index = 0; index < ITERATIONS; index++) {
                ChannelTransportSession.PacketResult encoded = sender.encodeStreamingFallbackBatch(payload);
                ChannelTransportSession.PacketResult restored = receiver.decodeStreamingFallbackBatch(encoded.bytes());
                require(Arrays.equals(payload, restored.bytes()),
                        "independent recovery changed after session reuse at iteration " + index);
            }
        }
    }

    private static byte[] payload() {
        byte[] bytes = new byte[PAYLOAD_BYTES];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) ((index * 31 + (index >>> 9)) & 0xff);
        }
        return bytes;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
