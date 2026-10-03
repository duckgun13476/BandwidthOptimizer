package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.diag.OutboundFloodAlarm;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.PacketFlow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

/** Always-on operational pressure warning; never changes delivery. */
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
                    var task = channel.eventLoop().scheduleWithFixedDelay(() -> {
                    var buffer = channel.unsafe().outboundBuffer();
                    created.sample(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()), channel.isWritable(),
                            buffer == null ? 0 : buffer.totalPendingWriteBytes(), detail ->
                            Bandwidthoptimizer.LOGGER.warn("[BO] Outbound flood warning: connection={} remote={} {}",
                                    channel.id().asLongText(), channel.remoteAddress(), detail));
                    }, 1, 1, TimeUnit.SECONDS);
                    channel.closeFuture().addListener(ignored -> { task.cancel(false); channel.attr(KEY).set(null); });
                } catch (RejectedExecutionException closing) {
                    channel.attr(KEY).compareAndSet(created, null);
                    return;
                }
            }
        }
        alarm.submitted(source, bytes, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()));
    }
}
