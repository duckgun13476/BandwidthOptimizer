package com.PinkCats.bandwidthoptimizer.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ChannelTransportFragmentSecurityRegressionMain {

    private static final int MAGIC_PACKET_ID = 0x1F_FFFF;
    private static final int FRAGMENT_FRAME_VERSION = 3;

    private ChannelTransportFragmentSecurityRegressionMain() {}

    public static void main(String[] args) {
        verifyFirstFragmentsDoNotPreallocateDeclaredFrames();
        verifyProcessStreamBudgetAndRelease();
        verifyProcessByteBudgetAndRelease();
        verifyExpiryAndInterruptionReleaseState();
        verifyChannelDeadlineReleasesSilentStream();
        verifyValidRoundTripPreservesBytes();
        System.out.println("Channel transport fragment security regression passed");
    }

    private static void verifyFirstFragmentsDoNotPreallocateDeclaredFrames() {
        List<ChannelTransportFragmentReassembler> reassemblers = new ArrayList<>();
        try {
            for (int index = 0; index < 16; index++) {
                ChannelTransportFragmentReassembler reassembler = new ChannelTransportFragmentReassembler();
                reassemblers.add(reassembler);
                ChannelTransportFragmentReassembler.ReceiveResult result = reassembler.accept(fragment(
                        index,
                        0,
                        2,
                        17 * 1024 * 1024,
                        0,
                        new byte[]{1}
                ));
                require(result.kind() == ChannelTransportFragmentReassembler.ReceiveResult.Kind.INCOMPLETE,
                        "First fragment was not retained");
            }
        } finally {
            reassemblers.forEach(ChannelTransportFragmentReassembler::clear);
        }
    }

    private static void verifyProcessStreamBudgetAndRelease() {
        List<ChannelTransportFragmentReassembler> reassemblers = new ArrayList<>();
        try {
            for (int index = 0; index < 256; index++) {
                ChannelTransportFragmentReassembler reassembler = new ChannelTransportFragmentReassembler();
                reassemblers.add(reassembler);
                reassembler.accept(fragment(index, 0, 2, 2, 0, new byte[]{1}));
            }
            ChannelTransportFragmentReassembler rejected = new ChannelTransportFragmentReassembler();
            requireRejected(
                    "process stream budget",
                    () -> rejected.accept(fragment(257, 0, 2, 2, 0, new byte[]{1}))
            );
        } finally {
            reassemblers.forEach(ChannelTransportFragmentReassembler::clear);
        }

        ChannelTransportFragmentReassembler afterRelease = new ChannelTransportFragmentReassembler();
        afterRelease.accept(fragment(300, 0, 2, 2, 0, new byte[]{1}));
        afterRelease.clear();
    }

    private static void verifyExpiryAndInterruptionReleaseState() {
        ChannelTransportFragmentReassembler expired = new ChannelTransportFragmentReassembler();
        expired.accept(fragment(1, 0, 2, 2, 0, new byte[]{1}));
        require(expired.hasPendingFrame(), "Expiry fixture did not retain a frame");
        require(expired.expirePendingFrame(
                        System.nanoTime() + ChannelTransportFragmentReassembler.PENDING_FRAME_TIMEOUT_NANOS
                                + TimeUnit.SECONDS.toNanos(1L)),
                "Expired frame was not released");
        require(!expired.hasPendingFrame(), "Expired frame remained pending");

        ChannelTransportFragmentReassembler interrupted = new ChannelTransportFragmentReassembler();
        interrupted.accept(fragment(2, 0, 2, 2, 0, new byte[]{1}));
        requireRejected("interrupted fragment stream", () -> interrupted.accept(new byte[]{0}));
        require(!interrupted.hasPendingFrame(), "Interrupted frame remained pending");

        ChannelTransportFragmentReassembler afterFailure = new ChannelTransportFragmentReassembler();
        afterFailure.accept(fragment(3, 0, 2, 2, 0, new byte[]{1}));
        afterFailure.clear();
    }

    private static void verifyProcessByteBudgetAndRelease() {
        List<ChannelTransportFragmentReassembler> reassemblers = new ArrayList<>();
        byte[] body = new byte[1024 * 1024];
        try {
            for (int index = 0; index < 64; index++) {
                ChannelTransportFragmentReassembler reassembler = new ChannelTransportFragmentReassembler();
                reassemblers.add(reassembler);
                reassembler.accept(fragment(index, 0, 17, 17 * 1024 * 1024, 0, body));
            }
            ChannelTransportFragmentReassembler rejected = new ChannelTransportFragmentReassembler();
            requireRejected(
                    "process byte budget",
                    () -> rejected.accept(fragment(100, 0, 17, 17 * 1024 * 1024, 0, new byte[]{1}))
            );
        } finally {
            reassemblers.forEach(ChannelTransportFragmentReassembler::clear);
        }

        ChannelTransportFragmentReassembler afterRelease = new ChannelTransportFragmentReassembler();
        afterRelease.accept(fragment(101, 0, 2, 2, 0, new byte[]{1}));
        afterRelease.clear();
    }

    private static void verifyChannelDeadlineReleasesSilentStream() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            ChannelTransportStateManager.acceptInboundFragment(
                    channel,
                    fragment(400, 0, 2, 2, 0, new byte[]{1})
            );
            channel.advanceTimeBy(16L, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            ChannelTransportFragmentReassembler.ReceiveResult result =
                    ChannelTransportStateManager.acceptInboundFragment(channel, new byte[]{0});
            require(result.kind() == ChannelTransportFragmentReassembler.ReceiveResult.Kind.NOT_FRAGMENT,
                    "Silent fragment stream did not expire at its channel deadline");
        } finally {
            ChannelTransportStateManager.clearSession(channel, "fragment-security-regression");
            channel.finishAndReleaseAll();
        }
    }

    private static void verifyValidRoundTripPreservesBytes() {
        byte[] expected = new byte[96 * 1024 + 37];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) (index * 31);
        }
        List<byte[]> fragments = ChannelTransportFragmentCodec.fragmentTransportFrame(expected, 4096, 42);
        ChannelTransportFragmentReassembler reassembler = new ChannelTransportFragmentReassembler();
        byte[] restored = null;
        for (byte[] fragment : fragments) {
            ChannelTransportFragmentReassembler.ReceiveResult result = reassembler.accept(fragment);
            if (result.kind() == ChannelTransportFragmentReassembler.ReceiveResult.Kind.COMPLETE) {
                restored = result.transportFrameBytes();
            }
        }
        require(Arrays.equals(expected, restored), "Valid fragmented frame bytes changed");
        require(!reassembler.hasPendingFrame(), "Completed frame remained pending");
    }

    private static byte[] fragment(
            int streamId,
            int fragmentIndex,
            int fragmentCount,
            int totalFrameBytes,
            int checksum,
            byte[] body
    ) {
        ByteBuf byteBuf = Unpooled.buffer();
        try {
            FriendlyByteBuf buffer = new FriendlyByteBuf(byteBuf);
            buffer.writeVarInt(MAGIC_PACKET_ID);
            buffer.writeVarInt(FRAGMENT_FRAME_VERSION);
            buffer.writeVarInt(streamId);
            buffer.writeVarInt(fragmentIndex);
            buffer.writeVarInt(fragmentCount);
            buffer.writeVarInt(totalFrameBytes);
            buffer.writeInt(checksum);
            buffer.writeBytes(body);
            return ByteBufUtil.getBytes(byteBuf);
        } finally {
            byteBuf.release();
        }
    }

    private static void requireRejected(String label, Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return;
        }
        throw new AssertionError(label + " was accepted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
