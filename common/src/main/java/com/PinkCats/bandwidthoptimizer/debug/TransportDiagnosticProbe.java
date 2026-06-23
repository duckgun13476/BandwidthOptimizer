package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class TransportDiagnosticProbe {
    private static final long ENCODE_LOG_THRESHOLD_NANOS = TimeUnit.MILLISECONDS.toNanos(25L);
    private static final long HOOK_LOG_THRESHOLD_NANOS = TimeUnit.MILLISECONDS.toNanos(10L);
    private static final long LOG_MIN_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);
    private static final ConcurrentHashMap<String, Long> LAST_LOG_NANOS = new ConcurrentHashMap<>();

    private TransportDiagnosticProbe() {}

    public static void observeOutboundEncodeCost(
            ChannelHandlerContext context,
            PacketFlow flow,
            Packet<?> packet,
            int encodedBytes,
            long vanillaEncodeNanos,
            long boHookNanos
    ) {
        if (!DiagnosticRuntimeSwitch.isEnabled(DiagnosticRuntimeSwitch.Topic.TRANSPORT)
                || context == null
                || packet == null
                || (vanillaEncodeNanos < ENCODE_LOG_THRESHOLD_NANOS && boHookNanos < HOOK_LOG_THRESHOLD_NANOS)) {
            return;
        }
        Channel channel = context.channel();
        long now = System.nanoTime();
        String channelId = ChannelIdentity.longText(channel);
        Long previous = LAST_LOG_NANOS.put(channelId, now);
        if (previous != null && now - previous < LOG_MIN_INTERVAL_NANOS) {
            return;
        }
        Bandwidthoptimizer.LOGGER.warn(
                "[BODiag][Transport][EncodeCost] channel={}, flow={}, packetClass={}, bytes={}, vanillaMs={}, boHookMs={}, pendingTasks={}, writable={}",
                channelId,
                flow,
                packet.getClass().getName(),
                Math.max(encodedBytes, 0),
                millis(vanillaEncodeNanos),
                millis(boHookNanos),
                pendingTasks(channel),
                channel == null || channel.isWritable()
        );
    }

    private static double millis(long nanos) {
        return Math.round((Math.max(nanos, 0L) / 1_000_000.0D) * 100.0D) / 100.0D;
    }

    private static int pendingTasks(Channel channel) {
        if (channel == null || !(channel.eventLoop() instanceof SingleThreadEventExecutor executor)) {
            return -1;
        }
        return executor.pendingTasks();
    }
}
