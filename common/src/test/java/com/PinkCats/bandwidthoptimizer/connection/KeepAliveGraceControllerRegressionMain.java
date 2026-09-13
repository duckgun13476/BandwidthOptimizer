package com.PinkCats.bandwidthoptimizer.connection;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStreamingControlCodec;
import io.netty.channel.embedded.EmbeddedChannel;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class KeepAliveGraceControllerRegressionMain {

    private KeepAliveGraceControllerRegressionMain() {
    }

    public static void main(String[] args) {
        verifyControlCodec();
        verifyPhaseBoundary();
        verifyDeadPeerExpiry();
        verifyLiveProbeGraceAndAckRelease();
        verifyAbsoluteGraceLimit();
        System.out.println("KeepAlive grace controller regression passed");
    }

    private static void verifyControlCodec() {
        ChannelTransportStreamingControlCodec.ControlMessage ping =
                ChannelTransportStreamingControlCodec.tryDecodeControlMessage(
                        ChannelTransportStreamingControlCodec.encodeKeepAliveProbePing(41L)
                );
        ChannelTransportStreamingControlCodec.ControlMessage pong =
                ChannelTransportStreamingControlCodec.tryDecodeControlMessage(
                        ChannelTransportStreamingControlCodec.encodeKeepAliveProbePong(42L)
                );
        check(ping instanceof ChannelTransportStreamingControlCodec.KeepAliveProbePing value
                        && value.nonce() == 41L,
                "probe ping did not round-trip");
        check(pong instanceof ChannelTransportStreamingControlCodec.KeepAliveProbePong value
                        && value.nonce() == 42L,
                "probe pong did not round-trip");
    }

    private static void verifyPhaseBoundary() {
        EmbeddedChannel channel = new EmbeddedChannel();
        boolean deferred = KeepAliveGraceController.shouldDeferTimeout(
                channel, false, nonce -> true, seconds(1L), false
        );
        check(!deferred && !KeepAliveGraceController.snapshot(channel, seconds(1L)).active(),
                "configuration timeout entered PLAY grace");
        channel.finishAndReleaseAll();
    }

    private static void verifyDeadPeerExpiry() {
        EmbeddedChannel channel = new EmbeddedChannel();
        check(KeepAliveGraceController.shouldDeferTimeout(channel, true, nonce -> true, seconds(1L), false),
                "initial grace was not granted");
        check(!KeepAliveGraceController.shouldDeferTimeout(channel, true, nonce -> true, seconds(17L), false),
                "dead peer survived initial grace without a pong");
        check(!KeepAliveGraceController.shouldDeferTimeout(channel, true, nonce -> true, seconds(18L), false),
                "expired challenge restarted its grace window");
        channel.finishAndReleaseAll();
    }

    private static void verifyLiveProbeGraceAndAckRelease() {
        EmbeddedChannel channel = new EmbeddedChannel();
        AtomicLong sent = new AtomicLong();
        check(KeepAliveGraceController.shouldDeferTimeout(channel, true, nonce -> {
            sent.set(nonce);
            return true;
        }, seconds(1L), false), "live peer did not enter grace");
        long nonce = KeepAliveGraceController.issueProbeForTest(channel, seconds(14L));
        check(nonce > 0L && sent.get() == nonce, "probe was not issued");
        KeepAliveGraceController.observeProbePong(channel, nonce, seconds(14L) + 100L);
        check(KeepAliveGraceController.shouldDeferTimeout(channel, true, ignored -> true, seconds(17L), false),
                "fresh pong did not extend grace");
        KeepAliveGraceController.observeVanillaKeepAliveAck(
                channel,
                "net.minecraft.network.protocol.common.ServerboundKeepAlivePacket",
                false
        );
        check(!KeepAliveGraceController.snapshot(channel, seconds(17L)).active(),
                "vanilla ACK did not release grace");
        channel.finishAndReleaseAll();
    }

    private static void verifyAbsoluteGraceLimit() {
        EmbeddedChannel channel = new EmbeddedChannel();
        AtomicLong sent = new AtomicLong();
        check(KeepAliveGraceController.shouldDeferTimeout(channel, true, nonce -> {
            sent.set(nonce);
            return true;
        }, seconds(1L), false), "absolute-limit peer did not enter grace");
        long nonce = KeepAliveGraceController.issueProbeForTest(channel, seconds(70L));
        check(nonce > 0L && sent.get() == nonce, "late probe was not issued");
        KeepAliveGraceController.observeProbePong(channel, nonce, seconds(70L) + 100L);
        check(!KeepAliveGraceController.shouldDeferTimeout(
                        channel, true, ignored -> true, seconds(76L), false),
                "fresh pongs bypassed the absolute grace limit");
        channel.finishAndReleaseAll();
    }

    private static long seconds(long seconds) {
        return TimeUnit.SECONDS.toNanos(seconds);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
