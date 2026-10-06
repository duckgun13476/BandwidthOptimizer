package com.PinkCats.bandwidthoptimizer.channel;

import com.PinkCats.bandwidthoptimizer.diag.OutboundFloodAlarm;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Final encoded-byte admission; closes the connection rather than dropping protocol state. */
public final class OutboundQueueCapacityGuard extends ChannelOutboundHandlerAdapter {
    static final long CONNECTION_LIMIT = 128L * 1024 * 1024;
    static final long GLOBAL_LIMIT = 512L * 1024 * 1024;
    private static final AtomicLong GLOBAL = new AtomicLong();
    private final long connectionLimit, globalLimit;
    private final AtomicLong global;
    private final OutboundFloodAlarm sources;
    private final Consumer<String> report;
    private long outstanding;
    private boolean terminated;

    public OutboundQueueCapacityGuard(OutboundFloodAlarm sources, Consumer<String> report) {
        this(sources, report, CONNECTION_LIMIT, GLOBAL_LIMIT, GLOBAL);
    }

    OutboundQueueCapacityGuard(OutboundFloodAlarm sources, Consumer<String> report,
                              long connectionLimit, long globalLimit, AtomicLong global) {
        this.sources = sources;
        this.report = report;
        this.connectionLimit = connectionLimit;
        this.globalLimit = globalLimit;
        this.global = global;
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
        if (!(message instanceof ByteBuf bytes)) {
            context.write(message, promise);
            return;
        }
        if (promise.isCancelled()) {
            ReferenceCountUtil.release(message);
            return;
        }
        long charge = (long) bytes.readableBytes() + 96;
        var buffer = context.channel().unsafe().outboundBuffer();
        long pending = buffer == null ? 0 : buffer.totalPendingWriteBytes();
        String reason = terminated ? "already_terminated" :
                charge > connectionLimit - Math.max(pending, outstanding) ? "connection_capacity" : null;
        boolean reserved = false;
        if (reason == null) {
            long current;
            do {
                current = global.get();
                if (charge > globalLimit - current) { reason = "global_capacity"; break; }
            } while (!global.compareAndSet(current, current + charge));
            reserved = reason == null;
        }
        if (!reserved) {
            ReferenceCountUtil.release(message);
            promise.tryFailure(new IOException("BO outbound queue capacity exceeded: " + reason));
            terminate(context, reason, pending, charge);
            return;
        }
        outstanding += charge;
        // A caller cancelling its public promise must not refund bytes still held by
        // the socket queue. Own an uncancellable completion until actual write/close.
        ChannelPromise completion = context.newPromise();
        completion.setUncancellable();
        completion.addListener(result -> {
            outstanding -= charge;
            global.addAndGet(-charge);
            if (result.isSuccess()) promise.trySuccess();
            else promise.tryFailure(result.cause());
        });
        try {
            context.write(message, completion);
        } catch (RuntimeException | Error failure) {
            ReferenceCountUtil.safeRelease(message);
            completion.tryFailure(failure);
            throw failure;
        }
    }

    public void checkExisting(ChannelHandlerContext context) {
        var buffer = context.channel().unsafe().outboundBuffer();
        long pending = buffer == null ? 0 : buffer.totalPendingWriteBytes();
        if (pending > connectionLimit) terminate(context, "existing_connection_capacity", pending, 0);
    }

    private void terminate(ChannelHandlerContext context, String reason, long pending, long rejected) {
        if (terminated) return;
        terminated = true;
        try {
            report.accept("event=outbound_capacity_exceeded action=close_connection reason=" + reason
                    + " connectionLimitBytes=" + connectionLimit + " globalLimitBytes=" + globalLimit
                    + " connectionPendingChargeBytes=" + pending + " unfinishedWireChargeBytes=" + outstanding
                    + " globalUnfinishedWireChargeBytes=" + global.get() + " rejectedWireChargeBytes=" + rejected
                    + " attribution=large_producer_not_proven_mod_bug"
                    + sources.describe(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()), 0));
        } finally {
            context.close();
        }
    }
}
