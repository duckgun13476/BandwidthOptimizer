package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.diag.OutboundFloodAlarm;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.PacketFlow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

/** Operational attribution plus bounded final-wire queue admission for clientbound connections. */
public final class ChannelOutboundFloodWarning {
    private static final AttributeKey<OutboundFloodAlarm> KEY = AttributeKey.valueOf("bo:outbound_flood_alarm");
    private ChannelOutboundFloodWarning() {}

    public static void record(ChannelHandlerContext context, PacketFlow flow, String source, int bytes) {
        if (context == null || flow != PacketFlow.CLIENTBOUND || bytes <= 0) return;
        Channel channel = context.channel();
        if (!channel.isOpen()) return;
        OutboundFloodAlarm alarm = channel.attr(KEY).get();
        if (alarm == null) {
            OutboundFloodAlarm created = new OutboundFloodAlarm();
            OutboundFloodAlarm raced = channel.attr(KEY).setIfAbsent(created);
            alarm = raced == null ? created : raced;
            if (raced == null) {
                try {
                    java.util.function.Consumer<String> report = detail ->
                            Bandwidthoptimizer.LOGGER.warn("[BO] Outbound flood warning: connection={} remote={} {}",
                                    channel.id().asLongText(), channel.remoteAddress(), detail);
                    var guard = new OutboundQueueCapacityGuard(created, report);
                    channel.pipeline().addFirst("bo_outbound_capacity", guard);
                    var task = channel.eventLoop().scheduleWithFixedDelay(() -> {
                    var guardContext = channel.pipeline().context(guard);
                    if (guardContext != null) guard.checkExisting(guardContext);
                    if (!channel.isOpen()) return;
                    var buffer = channel.unsafe().outboundBuffer();
                    created.sample(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()), channel.isWritable(),
                            buffer == null ? 0 : buffer.totalPendingWriteBytes(), report);
                    }, 1, 1, TimeUnit.SECONDS);
                    channel.closeFuture().addListener(ignored -> { task.cancel(false); channel.attr(KEY).set(null); });
                } catch (RejectedExecutionException closing) {
                    channel.attr(KEY).compareAndSet(created, null);
                    return;
                }
            }
        }
        alarm.submitted(source, bytes, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()));
        var guardContext = channel.pipeline().context("bo_outbound_capacity");
        if (guardContext != null) {
            ((OutboundQueueCapacityGuard) guardContext.handler()).checkExisting(guardContext);
            if (!channel.isOpen()) throw new io.netty.handler.codec.EncoderException("BO outbound capacity exceeded");
        }
    }
}
