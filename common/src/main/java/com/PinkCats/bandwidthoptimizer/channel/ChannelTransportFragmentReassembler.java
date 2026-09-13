package com.PinkCats.bandwidthoptimizer.channel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Reassembles one contiguous fragmented BO frame before the regular codec sees it. */
public final class ChannelTransportFragmentReassembler {

    static final long PENDING_FRAME_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15L);
    private static final ReassemblyBudget PROCESS_BUDGET = new ReassemblyBudget(
            64L * 1024L * 1024L,
            65_536,
            256
    );

    private PendingFrame pendingFrame;

    public synchronized ReceiveResult accept(byte[] payloadBytes) {
        ChannelTransportFragmentCodec.DecodedFragment fragment = ChannelTransportFragmentCodec.tryDecodeFragment(payloadBytes);
        if (fragment == null) {
            if (this.pendingFrame != null) {
                discardPendingFrame();
                throw new IllegalStateException("fragment stream was interrupted by a complete transport frame");
            }
            return ReceiveResult.notFragment();
        }

        try {
            PendingFrame pending = this.pendingFrame;
            if (pending == null) {
                if (fragment.fragmentIndex() != 0) {
                    throw new IllegalStateException("fragment stream did not start at index zero");
                }
                pending = new PendingFrame(fragment, System.nanoTime());
                this.pendingFrame = pending;
            }
            pending.append(fragment);
            if (!pending.isComplete()) {
                return ReceiveResult.incomplete();
            }

            byte[] transportFrameBytes = pending.complete();
            discardPendingFrame();
            return ReceiveResult.complete(transportFrameBytes);
        } catch (RuntimeException exception) {
            discardPendingFrame();
            throw exception;
        }
    }

    public synchronized void clear() {
        discardPendingFrame();
    }

    synchronized boolean hasPendingFrame() {
        return this.pendingFrame != null;
    }

    synchronized long pendingFrameRemainingNanos(long nowNanos) {
        return this.pendingFrame == null
                ? 0L
                : Math.max(PENDING_FRAME_TIMEOUT_NANOS - (nowNanos - this.pendingFrame.startedAtNanos), 0L);
    }

    synchronized long pendingFrameDeadlineNanos() {
        return this.pendingFrame == null
                ? 0L
                : this.pendingFrame.startedAtNanos + PENDING_FRAME_TIMEOUT_NANOS;
    }

    synchronized boolean expirePendingFrame(long nowNanos) {
        if (this.pendingFrame == null
                || nowNanos - this.pendingFrame.startedAtNanos < PENDING_FRAME_TIMEOUT_NANOS) {
            return false;
        }
        discardPendingFrame();
        return true;
    }

    synchronized void expirePendingFrameAtDeadline(long expectedDeadlineNanos) {
        if (this.pendingFrame != null
                && this.pendingFrame.startedAtNanos + PENDING_FRAME_TIMEOUT_NANOS == expectedDeadlineNanos) {
            discardPendingFrame();
        }
    }

    private void discardPendingFrame() {
        PendingFrame discarded = this.pendingFrame;
        this.pendingFrame = null;
        if (discarded != null) {
            discarded.releaseBudget();
        }
    }

    public record ReceiveResult(Kind kind, byte[] transportFrameBytes) {
        public static ReceiveResult notFragment() {
            return new ReceiveResult(Kind.NOT_FRAGMENT, null);
        }

        public static ReceiveResult incomplete() {
            return new ReceiveResult(Kind.INCOMPLETE, null);
        }

        public static ReceiveResult complete(byte[] transportFrameBytes) {
            return new ReceiveResult(Kind.COMPLETE, transportFrameBytes == null ? null : Arrays.copyOf(transportFrameBytes, transportFrameBytes.length));
        }

        public enum Kind {
            NOT_FRAGMENT,
            INCOMPLETE,
            COMPLETE
        }
    }

    private static final class PendingFrame {
        private final int streamId;
        private final int fragmentCount;
        private final int totalFrameBytes;
        private final int checksum;
        private final long startedAtNanos;
        private final List<byte[]> fragments = new ArrayList<>();
        private int retainedBytes;
        private int nextFragmentIndex;
        private boolean budgetReleased;

        private PendingFrame(ChannelTransportFragmentCodec.DecodedFragment fragment, long startedAtNanos) {
            PROCESS_BUDGET.reserveStream();
            this.streamId = fragment.streamId();
            this.fragmentCount = fragment.fragmentCount();
            this.totalFrameBytes = fragment.totalFrameBytes();
            this.checksum = fragment.checksum();
            this.startedAtNanos = startedAtNanos;
        }

        private void append(ChannelTransportFragmentCodec.DecodedFragment fragment) {
            if (fragment.streamId() != this.streamId
                    || fragment.fragmentCount() != this.fragmentCount
                    || fragment.totalFrameBytes() != this.totalFrameBytes
                    || fragment.checksum() != this.checksum
                    || fragment.fragmentIndex() != this.nextFragmentIndex) {
                throw new IllegalStateException("fragment stream header or ordering mismatch");
            }
            byte[] bytes = fragment.fragmentBytes();
            if (bytes.length == 0 || (long) this.retainedBytes + bytes.length > this.totalFrameBytes) {
                throw new IllegalStateException("fragment stream length out of range");
            }
            PROCESS_BUDGET.reserveFragment(bytes.length);
            this.fragments.add(bytes);
            this.retainedBytes += bytes.length;
            this.nextFragmentIndex++;
        }

        private boolean isComplete() {
            return this.nextFragmentIndex == this.fragmentCount;
        }

        private byte[] complete() {
            if (this.retainedBytes != this.totalFrameBytes) {
                throw new IllegalStateException("fragment stream completed with the wrong length");
            }
            byte[] transportFrameBytes = new byte[this.totalFrameBytes];
            int offset = 0;
            for (byte[] fragment : this.fragments) {
                System.arraycopy(fragment, 0, transportFrameBytes, offset, fragment.length);
                offset += fragment.length;
            }
            if (ChannelTransportFragmentCodec.checksum(transportFrameBytes) != this.checksum) {
                throw new IllegalStateException("fragment stream checksum mismatch");
            }
            return transportFrameBytes;
        }

        private void releaseBudget() {
            if (this.budgetReleased) {
                return;
            }
            this.budgetReleased = true;
            PROCESS_BUDGET.release(this.retainedBytes, this.fragments.size());
            this.fragments.clear();
            this.retainedBytes = 0;
        }
    }

    private static final class ReassemblyBudget {

        private final long maxBytes;
        private final int maxFragments;
        private final int maxStreams;
        private long retainedBytes;
        private int retainedFragments;
        private int activeStreams;

        private ReassemblyBudget(long maxBytes, int maxFragments, int maxStreams) {
            this.maxBytes = maxBytes;
            this.maxFragments = maxFragments;
            this.maxStreams = maxStreams;
        }

        private synchronized void reserveStream() {
            if (this.activeStreams >= this.maxStreams) {
                throw new IllegalStateException("fragment reassembly stream budget exceeded");
            }
            this.activeStreams++;
        }

        private synchronized void reserveFragment(int bytes) {
            if (bytes <= 0
                    || this.retainedBytes + bytes > this.maxBytes
                    || this.retainedFragments >= this.maxFragments) {
                throw new IllegalStateException("fragment reassembly process budget exceeded");
            }
            this.retainedBytes += bytes;
            this.retainedFragments++;
        }

        private synchronized void release(int bytes, int fragments) {
            this.retainedBytes = Math.max(this.retainedBytes - Math.max(bytes, 0), 0L);
            this.retainedFragments = Math.max(this.retainedFragments - Math.max(fragments, 0), 0);
            this.activeStreams = Math.max(this.activeStreams - 1, 0);
        }
    }
}
