package com.PinkCats.bandwidthoptimizer.channel;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Reassembles one contiguous fragmented BO frame before the regular codec sees it. */
public final class ChannelTransportFragmentReassembler {

    private PendingFrame pendingFrame;

    public ReceiveResult accept(byte[] payloadBytes) {
        ChannelTransportFragmentCodec.DecodedFragment fragment = ChannelTransportFragmentCodec.tryDecodeFragment(payloadBytes);
        if (fragment == null) {
            if (this.pendingFrame != null) {
                this.pendingFrame = null;
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
                pending = new PendingFrame(fragment);
                this.pendingFrame = pending;
            }
            pending.append(fragment);
            if (!pending.isComplete()) {
                return ReceiveResult.incomplete();
            }

            byte[] transportFrameBytes = pending.complete();
            this.pendingFrame = null;
            return ReceiveResult.complete(transportFrameBytes);
        } catch (RuntimeException exception) {
            this.pendingFrame = null;
            throw exception;
        }
    }

    public void clear() {
        this.pendingFrame = null;
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
        private final ByteArrayOutputStream output;
        private int nextFragmentIndex;

        private PendingFrame(ChannelTransportFragmentCodec.DecodedFragment fragment) {
            this.streamId = fragment.streamId();
            this.fragmentCount = fragment.fragmentCount();
            this.totalFrameBytes = fragment.totalFrameBytes();
            this.checksum = fragment.checksum();
            this.output = new ByteArrayOutputStream(this.totalFrameBytes);
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
            if (bytes.length == 0 || this.output.size() + bytes.length > this.totalFrameBytes) {
                throw new IllegalStateException("fragment stream length out of range");
            }
            this.output.write(bytes, 0, bytes.length);
            this.nextFragmentIndex++;
        }

        private boolean isComplete() {
            return this.nextFragmentIndex == this.fragmentCount;
        }

        private byte[] complete() {
            if (this.output.size() != this.totalFrameBytes) {
                throw new IllegalStateException("fragment stream completed with the wrong length");
            }
            byte[] transportFrameBytes = this.output.toByteArray();
            if (ChannelTransportFragmentCodec.checksum(transportFrameBytes) != this.checksum) {
                throw new IllegalStateException("fragment stream checksum mismatch");
            }
            return transportFrameBytes;
        }
    }
}
