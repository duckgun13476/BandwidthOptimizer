package com.PinkCats.bandwidthoptimizer.chunk.classify;

import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportPacketCodec;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportSession;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStateManager;
import com.PinkCats.bandwidthoptimizer.channel.ChannelTransportStreamingEpochGate;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChunkLoginTransportLifecycleRegression {
    private ChunkLoginTransportLifecycleRegression() {}

    public static void verify() {
        verifyLoginBoundary(false);
        verifyLoginBoundary(true);
    }

    private static void verifyLoginBoundary(boolean proxySwitch) {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        ChannelTransportSession peer = new ChannelTransportSession();
        peer.setCrossFrameZstdEnabled(true);
        try {
            if (proxySwitch) {
                // The explicit switch entry resets the OLD backend before new-peer traffic.
                ChannelTransportStateManager.getOrCreateSession(channel);
                ChannelTransportStateManager.beginProxyServerSwitchBoundary(channel, "login-regression", 15_000L);
                ChannelTransportStateManager.clearSession(channel, "login-regression-old-backend");
            }
            ChannelTransportSession session = ChannelTransportStateManager.getOrCreateSession(channel);
            assertRestored(peer, session, "polymer-early-play-keepalive".getBytes(StandardCharsets.UTF_8));
            assertRestored(session, peer, "client-early-play-reply".getBytes(StandardCharsets.UTF_8));

            AtomicInteger delivered = new AtomicInteger();
            ChannelTransportStreamingEpochGate.closeForEpoch(channel, 17, 3);
            if (!ChannelTransportStreamingEpochGate.deferTaskIfClosed(channel, 1, delivered::incrementAndGet)) {
                throw new IllegalStateException("Login fixture did not queue an epoch-gated operation");
            }
            ChunkInboundObservationService.resetWorldStateForLogin(channel.pipeline().firstContext(), "PLAY");
            if (ChannelTransportStateManager.getOrCreateSession(channel) != session) {
                throw new IllegalStateException("GameJoin replaced the live early-PLAY transport session");
            }
            if (ChannelTransportStateManager.isProxyServerSwitchBoundaryActive(channel)) {
                throw new IllegalStateException("GameJoin did not end the outbound proxy switch guard");
            }
            if (!ChannelTransportStreamingEpochGate.matchesClosedEpoch(channel, 17, 3)) {
                throw new IllegalStateException("GameJoin discarded the live transport epoch gate");
            }
            assertRestored(peer, session, "post-join-server-packet".getBytes(StandardCharsets.UTF_8));
            assertRestored(session, peer, "post-join-client-packet".getBytes(StandardCharsets.UTF_8));
            ChannelTransportStreamingEpochGate.release(channel);
            if (delivered.get() != 1) {
                throw new IllegalStateException("GameJoin lost or duplicated deferred transport work");
            }
        } finally {
            peer.close();
            channel.finishAndReleaseAll();
        }
    }

    private static void assertRestored(ChannelTransportSession sender, ChannelTransportSession receiver, byte[] bytes) {
        var wrapped = ChannelTransportPacketCodec.wrapPacket(sender, bytes);
        var restored = ChannelTransportPacketCodec.tryUnwrapPacket(receiver, wrapped.transportFrameBytes());
        if (restored == null || restored.restoredPacketCount() != 1
                || !Arrays.equals(bytes, restored.restoredPacketBytesList().get(0))) {
            throw new IllegalStateException("Login boundary changed transport bytes");
        }
    }
}
