package com.PinkCats.bandwidthoptimizer.channel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ChannelTransportStreamingRecoveryState {

    private static final int MAX_RETAINED_FRAMES = 128;
    private static final int MAX_RETAINED_BYTES = 16 * 1024 * 1024;

    private final Map<Integer, byte[]> retainedOutboundFrames = new LinkedHashMap<>();
    private int retainedOutboundBytes;
    private int retainedOutboundEpoch;
    private int inboundEpoch;
    private int nextInboundSequence = 1;
    private boolean inboundRecoveryPending;

    void resetInbound() {
        this.inboundEpoch = 0;
        this.nextInboundSequence = 1;
        this.inboundRecoveryPending = false;
    }

    void resetOutbound() {
        this.retainedOutboundFrames.clear();
        this.retainedOutboundBytes = 0;
        this.retainedOutboundEpoch = 0;
    }

    void acceptInboundFrame(int epoch, int sequence) {
        if (epoch <= 0 || sequence <= 0) {
            throw new IllegalStateException("Invalid streaming epoch or sequence");
        }
        if (this.inboundRecoveryPending) {
            throw new StreamingGapException(this.inboundEpoch, this.nextInboundSequence, sequence, "recovery-pending");
        }
        if (this.inboundEpoch == 0) {
            this.inboundEpoch = epoch;
            this.nextInboundSequence = 1;
        }
        if (this.inboundEpoch != epoch) {
            throw new StreamingGapException(this.inboundEpoch, this.nextInboundSequence, sequence, "epoch-mismatch");
        }
        if (this.nextInboundSequence != sequence) {
            this.inboundRecoveryPending = true;
            throw new StreamingGapException(this.inboundEpoch, this.nextInboundSequence, sequence, "sequence-gap");
        }
    }

    void completeInboundFrame(int epoch, int sequence) {
        if (this.inboundEpoch == epoch && this.nextInboundSequence == sequence) {
            this.nextInboundSequence = sequence == Integer.MAX_VALUE ? 1 : sequence + 1;
        }
    }

    void failInboundFrame(int epoch, int sequence) {
        if (this.inboundEpoch == epoch && this.nextInboundSequence == sequence) {
            this.inboundRecoveryPending = true;
        }
    }

    void prepareInboundReplay(int epoch, int expectedSequence) {
        if (!this.inboundRecoveryPending
                || this.inboundEpoch != epoch
                || this.nextInboundSequence != expectedSequence) {
            throw new IllegalStateException("Streaming replay does not match the pending recovery point");
        }
        this.inboundRecoveryPending = false;
    }

    void retainOutboundFrame(int epoch, int sequence, byte[] transportFrameBytes) {
        if (epoch <= 0 || sequence <= 0 || transportFrameBytes == null) {
            return;
        }
        if (this.retainedOutboundEpoch != epoch) {
            resetOutbound();
            this.retainedOutboundEpoch = epoch;
        }
        byte[] copiedBytes = Arrays.copyOf(transportFrameBytes, transportFrameBytes.length);
        byte[] previous = this.retainedOutboundFrames.put(sequence, copiedBytes);
        if (previous != null) {
            this.retainedOutboundBytes -= previous.length;
        }
        this.retainedOutboundBytes += copiedBytes.length;
        while (!this.retainedOutboundFrames.isEmpty()
                && (this.retainedOutboundFrames.size() > MAX_RETAINED_FRAMES
                || this.retainedOutboundBytes > MAX_RETAINED_BYTES)) {
            Map.Entry<Integer, byte[]> oldest = this.retainedOutboundFrames.entrySet().iterator().next();
            this.retainedOutboundFrames.remove(oldest.getKey());
            this.retainedOutboundBytes -= oldest.getValue().length;
        }
    }

    List<byte[]> replayOutboundFrames(int epoch, int expectedSequence) {
        if (epoch <= 0
                || expectedSequence <= 0
                || epoch != this.retainedOutboundEpoch
                || this.retainedOutboundFrames.isEmpty()) {
            return List.of();
        }
        List<byte[]> replayFrames = new ArrayList<>();
        for (Map.Entry<Integer, byte[]> entry : this.retainedOutboundFrames.entrySet()) {
            if (entry.getKey() >= expectedSequence) {
                replayFrames.add(Arrays.copyOf(entry.getValue(), entry.getValue().length));
            }
        }
        if (replayFrames.isEmpty() || !this.retainedOutboundFrames.containsKey(expectedSequence)) {
            return List.of();
        }
        return List.copyOf(replayFrames);
    }

    static final class StreamingGapException extends IllegalStateException {

        private final int epoch;
        private final int expectedSequence;
        private final int actualSequence;
        private final String reason;

        private StreamingGapException(int epoch, int expectedSequence, int actualSequence, String reason) {
            this.epoch = epoch;
            this.expectedSequence = expectedSequence;
            this.actualSequence = actualSequence;
            this.reason = reason;
        }

        int epoch() {
            return this.epoch;
        }

        int expectedSequence() {
            return this.expectedSequence;
        }

        @Override
        public String getMessage() {
            return "Streaming Zstd gap: epoch=" + this.epoch
                    + ", expected=" + this.expectedSequence
                    + ", actual=" + this.actualSequence
                    + ", reason=" + this.reason;
        }
    }
}
