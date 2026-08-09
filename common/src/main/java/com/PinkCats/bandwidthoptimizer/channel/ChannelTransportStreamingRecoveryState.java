package com.PinkCats.bandwidthoptimizer.channel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ChannelTransportStreamingRecoveryState {

    private static final int MAX_RETAINED_FRAMES = 128;
    private static final int MAX_RETAINED_BYTES = 16 * 1024 * 1024;

    private final Map<Integer, RetainedOutboundFrame> retainedOutboundFrames = new LinkedHashMap<>();
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
            if (epoch == this.inboundEpoch) {
                throw new StreamingGapException(this.inboundEpoch, this.nextInboundSequence, sequence, "recovery-pending");
            }
            if (sequence != 1) {
                throw new StreamingGapException(this.inboundEpoch, this.nextInboundSequence, sequence, "recovery-new-epoch-sequence");
            }
            this.inboundEpoch = epoch;
            this.nextInboundSequence = 1;
            this.inboundRecoveryPending = false;
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

    void beginInboundRecovery(int epoch, int expectedSequence) {
        if (!this.inboundRecoveryPending
                || this.inboundEpoch != epoch
                || this.nextInboundSequence != expectedSequence) {
            throw new IllegalStateException("Streaming recovery does not match the pending recovery point");
        }
    }

    ChannelTransportStreamingControlCodec.RecoveryRequest inboundRecoveryPoint() {
        if (!this.inboundRecoveryPending || this.inboundEpoch <= 0 || this.nextInboundSequence <= 0) {
            throw new IllegalStateException("No streaming recovery is pending");
        }
        return new ChannelTransportStreamingControlCodec.RecoveryRequest(this.inboundEpoch, this.nextInboundSequence);
    }

    void retainOutboundFrame(
            int epoch,
            int sequence,
            byte[] transportFrameBytes,
            byte[] fallbackBatchPayloadBytes,
            int originalPacketBytes,
            int originalPacketCount
    ) {
        if (epoch <= 0
                || sequence <= 0
                || transportFrameBytes == null
                || fallbackBatchPayloadBytes == null) {
            return;
        }
        if (this.retainedOutboundEpoch != epoch) {
            resetOutbound();
            this.retainedOutboundEpoch = epoch;
        }
        RetainedOutboundFrame copiedFrame = new RetainedOutboundFrame(
                Arrays.copyOf(transportFrameBytes, transportFrameBytes.length),
                Arrays.copyOf(fallbackBatchPayloadBytes, fallbackBatchPayloadBytes.length),
                Math.max(originalPacketBytes, 0),
                Math.max(originalPacketCount, 0)
        );
        RetainedOutboundFrame previous = this.retainedOutboundFrames.put(sequence, copiedFrame);
        if (previous != null) {
            this.retainedOutboundBytes -= previous.retainedBytes();
        }
        this.retainedOutboundBytes += copiedFrame.retainedBytes();
        while (!this.retainedOutboundFrames.isEmpty()
                && (this.retainedOutboundFrames.size() > MAX_RETAINED_FRAMES
                || this.retainedOutboundBytes > MAX_RETAINED_BYTES)) {
            Map.Entry<Integer, RetainedOutboundFrame> oldest = this.retainedOutboundFrames.entrySet().iterator().next();
            this.retainedOutboundFrames.remove(oldest.getKey());
            this.retainedOutboundBytes -= oldest.getValue().retainedBytes();
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
        for (Map.Entry<Integer, RetainedOutboundFrame> entry : this.retainedOutboundFrames.entrySet()) {
            if (entry.getKey() >= expectedSequence) {
                replayFrames.add(Arrays.copyOf(entry.getValue().transportFrameBytes(), entry.getValue().transportFrameBytes().length));
            }
        }
        if (replayFrames.isEmpty() || !this.retainedOutboundFrames.containsKey(expectedSequence)) {
            return List.of();
        }
        return List.copyOf(replayFrames);
    }

    List<RetainedFallbackBatch> fallbackOutboundBatches(int epoch, int expectedSequence) {
        if (epoch <= 0
                || expectedSequence <= 0
                || epoch != this.retainedOutboundEpoch
                || !this.retainedOutboundFrames.containsKey(expectedSequence)) {
            return List.of();
        }
        List<RetainedFallbackBatch> batches = new ArrayList<>();
        for (Map.Entry<Integer, RetainedOutboundFrame> entry : this.retainedOutboundFrames.entrySet()) {
            if (entry.getKey() >= expectedSequence) {
                RetainedOutboundFrame frame = entry.getValue();
                batches.add(new RetainedFallbackBatch(
                        entry.getKey(),
                        Arrays.copyOf(frame.fallbackBatchPayloadBytes(), frame.fallbackBatchPayloadBytes().length),
                        frame.originalPacketBytes(),
                        frame.originalPacketCount()
                ));
            }
        }
        return List.copyOf(batches);
    }

    record RetainedFallbackBatch(
            int sequence,
            byte[] batchPayloadBytes,
            int originalPacketBytes,
            int originalPacketCount
    ) {
    }

    private record RetainedOutboundFrame(
            byte[] transportFrameBytes,
            byte[] fallbackBatchPayloadBytes,
            int originalPacketBytes,
            int originalPacketCount
    ) {
        private int retainedBytes() {
            return this.transportFrameBytes.length + this.fallbackBatchPayloadBytes.length;
        }
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
