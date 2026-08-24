package com.PinkCats.bandwidthoptimizer.channel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ChannelTransportStreamingRecoveryState {

    private static final int MAX_RETAINED_FRAMES = 64;
    private static final int MAX_RETAINED_BYTES = 8 * 1024 * 1024;

    private final Map<Integer, RetainedOutboundFrame> retainedOutboundFrames = new LinkedHashMap<>();
    private int retainedOutboundBytes;
    private int retainedOutboundEpoch;
    private int retainedOutboundLastSequence;
    private boolean outboundEpochClosed;
    private int inboundEpoch;
    private int nextInboundSequence = 1;
    private boolean inboundRecoveryPending;
    private boolean inboundRecoveryRequested;

    void resetInbound() {
        this.inboundEpoch = 0;
        this.nextInboundSequence = 1;
        this.inboundRecoveryPending = false;
        this.inboundRecoveryRequested = false;
    }

    void resetOutbound() {
        this.retainedOutboundFrames.clear();
        this.retainedOutboundBytes = 0;
        this.retainedOutboundEpoch = 0;
        this.retainedOutboundLastSequence = 0;
        this.outboundEpochClosed = false;
    }

    boolean acceptInboundFrame(int epoch, int sequence) {
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
            this.inboundRecoveryRequested = false;
            return true;
        }
        if (this.inboundEpoch == 0) {
            this.inboundEpoch = epoch;
            this.nextInboundSequence = 1;
        }
        if (this.inboundEpoch != epoch) {
            this.inboundEpoch = epoch;
            this.nextInboundSequence = 1;
            this.inboundRecoveryRequested = false;
            if (sequence != 1) {
                this.inboundRecoveryPending = true;
                throw new StreamingGapException(epoch, 1, sequence, "new-epoch-sequence");
            }
            this.inboundRecoveryPending = false;
            return true;
        }
        if (this.nextInboundSequence != sequence) {
            this.inboundRecoveryPending = true;
            this.inboundRecoveryRequested = false;
            throw new StreamingGapException(this.inboundEpoch, this.nextInboundSequence, sequence, "sequence-gap");
        }
        return false;
    }

    void completeInboundFrame(int epoch, int sequence) {
        if (this.inboundEpoch == epoch && this.nextInboundSequence == sequence) {
            this.nextInboundSequence = sequence == Integer.MAX_VALUE ? 1 : sequence + 1;
        }
    }

    boolean acceptInboundEpochComplete(int epoch, int lastSequence) {
        int expectedNext = lastSequence == Integer.MAX_VALUE ? 1 : lastSequence + 1;
        if (this.inboundEpoch == 0) {
            this.inboundEpoch = epoch;
            this.nextInboundSequence = 1;
            this.inboundRecoveryPending = true;
            return false;
        }
        if (this.inboundEpoch != epoch) {
            throw new IllegalStateException("Streaming epoch completed before all frames were restored");
        }
        if (this.inboundRecoveryPending) {
            if (lastSequence < this.nextInboundSequence) {
                throw new IllegalStateException("Streaming epoch boundary precedes the recovery point");
            }
            return false;
        }
        if (this.nextInboundSequence != expectedNext) {
            this.inboundRecoveryPending = true;
            return false;
        }
        return true;
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

    boolean beginInboundRecovery(int epoch, int expectedSequence) {
        if (!this.inboundRecoveryPending
                || this.inboundEpoch != epoch
                || this.nextInboundSequence != expectedSequence) {
            throw new IllegalStateException("Streaming recovery does not match the pending recovery point");
        }
        if (this.inboundRecoveryRequested) {
            return false;
        }
        this.inboundRecoveryRequested = true;
        return true;
    }

    ChannelTransportStreamingControlCodec.RecoveryRequest inboundRecoveryPoint() {
        if (!this.inboundRecoveryPending || this.inboundEpoch <= 0 || this.nextInboundSequence <= 0) {
            throw new IllegalStateException("No streaming recovery is pending");
        }
        return new ChannelTransportStreamingControlCodec.RecoveryRequest(this.inboundEpoch, this.nextInboundSequence);
    }

    boolean willCloseOutboundEpoch(int epoch, int additionalRetainedBytes) {
        int retainedFrames = this.retainedOutboundEpoch == 0 || this.retainedOutboundEpoch == epoch
                ? this.retainedOutboundFrames.size()
                : 0;
        int retainedBytes = retainedFrames == 0 ? 0 : this.retainedOutboundBytes;
        return retainedFrames + 1 >= retainedFrameLimit()
                || retainedBytes + Math.max(additionalRetainedBytes, 0) >= MAX_RETAINED_BYTES;
    }

    boolean retainOutboundFrame(
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
            return false;
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
        this.retainedOutboundLastSequence = Math.max(this.retainedOutboundLastSequence, sequence);
        if (this.retainedOutboundFrames.size() >= retainedFrameLimit()
                || this.retainedOutboundBytes >= MAX_RETAINED_BYTES) {
            this.outboundEpochClosed = true;
        }
        return this.outboundEpochClosed;
    }

    boolean outboundEpochClosed() {
        return this.outboundEpochClosed;
    }

    private static int retainedFrameLimit() {
        return Boolean.getBoolean("bandwidthoptimizer.test.dropFirstClientboundStreamingFrame")
                ? 1
                : MAX_RETAINED_FRAMES;
    }

    EpochBoundary outboundEpochBoundary() {
        if (!this.outboundEpochClosed || this.retainedOutboundEpoch <= 0 || this.retainedOutboundLastSequence <= 0) {
            return null;
        }
        return new EpochBoundary(this.retainedOutboundEpoch, this.retainedOutboundLastSequence);
    }

    void acceptOutboundEpochOk(int epoch, int lastSequence) {
        if (!this.outboundEpochClosed
                || epoch != this.retainedOutboundEpoch
                || lastSequence != this.retainedOutboundLastSequence) {
            throw new IllegalStateException("Streaming epoch acknowledgement does not match the closed epoch");
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

    record EpochBoundary(int epoch, int lastSequence) {
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
