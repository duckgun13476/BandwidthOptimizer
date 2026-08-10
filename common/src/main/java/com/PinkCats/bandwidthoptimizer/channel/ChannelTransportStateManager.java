package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.PacketFlow;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChannelTransportStateManager {

    private static final AttributeKey<ChannelTransportSession> TRANSPORT_SESSION_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:channel_transport_session");

    private static final AttributeKey<Boolean> SESSION_CLOSE_CLEANUP_ATTACHED_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:channel_transport_session_close_cleanup_attached");

    private static final AttributeKey<ProxyServerSwitchBoundaryState> PROXY_SERVER_SWITCH_BOUNDARY_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:proxy_server_switch_boundary");

    private static final AttributeKey<AtomicInteger> OUTBOUND_FRAGMENT_STREAM_ID_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:channel_transport_fragment_stream_id");

    private static final AttributeKey<ChannelTransportFragmentReassembler> INBOUND_FRAGMENT_REASSEMBLER_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:channel_transport_fragment_reassembler");

    private static final AttributeKey<Boolean> TEST_STREAMING_DROP_CONSUMED_KEY =
            AttributeKey.valueOf("bandwidthoptimizer:test_streaming_drop_consumed");

    private static final boolean TEST_STREAMING_ENABLED =
            Boolean.getBoolean("bandwidthoptimizer.test.streamingZstd");

    private static final boolean STREAMING_ENABLED =
            TEST_STREAMING_ENABLED
                    || Boolean.parseBoolean(System.getProperty("bandwidthoptimizer.streamingZstd", "true"));

    private static final boolean TEST_DROP_FIRST_CLIENTBOUND_STREAMING_FRAME =
            Boolean.getBoolean("bandwidthoptimizer.test.dropFirstClientboundStreamingFrame");

    private ChannelTransportStateManager() {}

    public static ChannelTransportSession getOrCreateSession(Channel channel) {
        if (channel == null) {
            throw new IllegalArgumentException("channel");
        }

        ensureSessionCloseCleanup(channel);
        ChannelTransportSession existingSession = channel.attr(TRANSPORT_SESSION_KEY).get();

        if (existingSession != null)
            return existingSession;

        ChannelTransportSession newSession = new ChannelTransportSession();
        if (STREAMING_ENABLED) {
            newSession.setCrossFrameZstdEnabled(true);
        }
        ChannelTransportSession racedSession = channel.attr(TRANSPORT_SESSION_KEY).setIfAbsent(newSession);
        if (racedSession != null) {
            closeSession(newSession, "create-race");
            return racedSession;
        }
        return newSession;
    }

    public static boolean consumeTestClientboundStreamingDrop(
            Channel channel,
            PacketFlow packetFlow,
            byte[] transportFrameBytes
    ) {
        if (!STREAMING_ENABLED
                || !TEST_DROP_FIRST_CLIENTBOUND_STREAMING_FRAME
                || channel == null
                || packetFlow != PacketFlow.CLIENTBOUND
                || !ChannelTransportPacketCodec.isStreamingFrame(transportFrameBytes)) {
            return false;
        }
        Boolean consumed = channel.attr(TEST_STREAMING_DROP_CONSUMED_KEY).get();
        if (Boolean.TRUE.equals(consumed)) {
            return false;
        }
        boolean consumedNow = channel.attr(TEST_STREAMING_DROP_CONSUMED_KEY).setIfAbsent(Boolean.TRUE) == null;
        if (consumedNow) {
            Bandwidthoptimizer.LOGGER.info(
                    "[StreamingRecoveryTest] Dropped complete clientbound v4 frame, bytes={}",
                    transportFrameBytes.length
            );
        }
        return consumedNow;
    }

    public static boolean isTestStreamingEnabled() {
        return TEST_STREAMING_ENABLED;
    }


    public static void clearSession(Channel channel, String reason) {
        if (channel == null) {
            return;
        }
        ChannelTransportSession existingSession = channel.attr(TRANSPORT_SESSION_KEY).getAndSet(null);
        if (existingSession != null) {
            closeSession(existingSession, reason);
        }
        ChannelTransportFragmentReassembler reassembler = channel.attr(INBOUND_FRAGMENT_REASSEMBLER_KEY).getAndSet(null);
        if (reassembler != null) {
            reassembler.clear();
        }
        channel.attr(OUTBOUND_FRAGMENT_STREAM_ID_KEY).set(null);
        ChannelTransportStreamingEpochGate.clear(channel);
    }

    public static int nextOutboundFragmentStreamId(Channel channel) {
        if (channel == null) {
            throw new IllegalArgumentException("channel");
        }
        AtomicInteger counter = channel.attr(OUTBOUND_FRAGMENT_STREAM_ID_KEY).get();
        if (counter == null) {
            AtomicInteger created = new AtomicInteger();
            AtomicInteger raced = channel.attr(OUTBOUND_FRAGMENT_STREAM_ID_KEY).setIfAbsent(created);
            counter = raced == null ? created : raced;
        }
        return counter.updateAndGet(previous -> previous == Integer.MAX_VALUE ? 1 : previous + 1);
    }

    public static ChannelTransportFragmentReassembler.ReceiveResult acceptInboundFragment(Channel channel, byte[] payloadBytes) {
        if (channel == null) {
            throw new IllegalArgumentException("channel");
        }
        ChannelTransportFragmentReassembler reassembler = channel.attr(INBOUND_FRAGMENT_REASSEMBLER_KEY).get();
        if (reassembler == null) {
            ChannelTransportFragmentReassembler created = new ChannelTransportFragmentReassembler();
            ChannelTransportFragmentReassembler raced = channel.attr(INBOUND_FRAGMENT_REASSEMBLER_KEY).setIfAbsent(created);
            reassembler = raced == null ? created : raced;
        }
        return reassembler.accept(payloadBytes);
    }

    private static void closeSession(ChannelTransportSession session, String reason) {
        try {
            session.close();
        } catch (RuntimeException exception) {
            Bandwidthoptimizer.LOGGER.warn(
                    "[Transport][SessionClose] Failed to close transport session, reason={}",
                    reason,
                    exception
            );
        }
    }

    // Proxy switches only guard outbound stale packets; inbound transport stays enabled.
    public static void beginProxyServerSwitchBoundary(Channel channel, String reason, long durationMillis) {
        if (channel == null || durationMillis <= 0L) {
            return;
        }
        long untilNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMillis);
        channel.attr(PROXY_SERVER_SWITCH_BOUNDARY_KEY).set(new ProxyServerSwitchBoundaryState(reason == null ? "" : reason, untilNanos));
        Bandwidthoptimizer.LOGGER.info(
                "[Transport][ProxySwitchBoundary] channel={}, reason={}, durationMs={}",
                ChannelIdentity.shortText(channel),
                reason,
                durationMillis
        );
    }

    public static boolean isProxyServerSwitchBoundaryActive(Channel channel) {
        ProxyServerSwitchBoundaryState state = proxyServerSwitchBoundaryState(channel);
        return state != null;
    }

    public static String proxyServerSwitchBoundaryReason(Channel channel) {
        ProxyServerSwitchBoundaryState state = proxyServerSwitchBoundaryState(channel);
        return state == null ? "" : state.reason();
    }

    // End the outbound guard once the new backend login packet arrives.
    public static void endProxyServerSwitchBoundary(Channel channel, String reason) {
        if (channel == null) {
            return;
        }
        ProxyServerSwitchBoundaryState state = channel.attr(PROXY_SERVER_SWITCH_BOUNDARY_KEY).getAndSet(null);
        if (state != null) {
            Bandwidthoptimizer.LOGGER.info(
                    "[Transport][ProxySwitchBoundary][End] channel={}, reason={}, previousReason={}",
                    ChannelIdentity.shortText(channel),
                    reason,
                    state.reason()
            );
        }
    }

    private static ProxyServerSwitchBoundaryState proxyServerSwitchBoundaryState(Channel channel) {
        if (channel == null) {
            return null;
        }
        ProxyServerSwitchBoundaryState state = channel.attr(PROXY_SERVER_SWITCH_BOUNDARY_KEY).get();
        if (state == null) {
            return null;
        }
        if (System.nanoTime() <= state.untilNanos()) {
            return state;
        }
        channel.attr(PROXY_SERVER_SWITCH_BOUNDARY_KEY).set(null);
        return null;
    }

    private static void ensureSessionCloseCleanup(Channel channel) {
        Boolean alreadyAttached = channel.attr(SESSION_CLOSE_CLEANUP_ATTACHED_KEY).get();
        if (Boolean.TRUE.equals(alreadyAttached)) {
            return;
        }

        Boolean raced = channel.attr(SESSION_CLOSE_CLEANUP_ATTACHED_KEY).setIfAbsent(Boolean.TRUE);
        if (Boolean.TRUE.equals(raced)) {
            return;
        }

        channel.closeFuture().addListener(future -> clearSession(channel, "channel-close"));
    }

    private record ProxyServerSwitchBoundaryState(String reason, long untilNanos) { }
}
