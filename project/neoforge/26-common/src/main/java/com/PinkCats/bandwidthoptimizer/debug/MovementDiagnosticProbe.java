package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.channel.ChannelIdentity;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class MovementDiagnosticProbe {
    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final long CORRECTION_LOG_MIN_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);
    private static final int MOVE_BURST_THRESHOLD = 80;
    private static final ConcurrentHashMap<String, MoveWindow> MOVE_WINDOWS = new ConcurrentHashMap<>();

    private MovementDiagnosticProbe() {
    }

    public static void BO_Diag_movementCorrection(Channel channel, Packet<?> packet) {
        observeConnectionSend(channel, packet);
    }

    public static void BO_Diag_movementBurst(ChannelHandlerContext context, PacketFlow flow, List<Object> out, int startIndex) {
        observeDecodedPackets(context, flow, out, startIndex);
    }

    public static void observeConnectionSend(Channel channel, Packet<?> packet) {
        if (!isEnabled(DiagnosticToolRegistry.Tool.MOVEMENT_CORRECTION)
                || channel == null || !(packet instanceof ClientboundPlayerPositionPacket positionPacket)) {
            return;
        }
        MoveWindow window = state(channel);
        long now = System.nanoTime();
        if (!window.markCorrection(now)) {
            return;
        }
        DiagnosticLog.warn(
                DiagnosticToolRegistry.Tool.MOVEMENT_CORRECTION,
                "channel={}, packetChange={}, packetId={}, relative={}, pendingTasks={}, writable={}",
                ChannelIdentity.longText(channel), positionPacket.change(), positionPacket.id(), positionPacket.relatives(),
                pendingTasks(channel), channel.isWritable()
        );
    }

    public static void observeDecodedPackets(ChannelHandlerContext context, PacketFlow flow, List<Object> out, int startIndex) {
        if (!isEnabled(DiagnosticToolRegistry.Tool.MOVEMENT_BURST)
                || context == null || flow != PacketFlow.SERVERBOUND || out == null || out.size() <= startIndex) {
            return;
        }
        int moves = 0;
        for (int index = Math.max(startIndex, 0); index < out.size(); index++) {
            if (out.get(index) instanceof ServerboundMovePlayerPacket) {
                moves++;
            }
        }
        if (moves <= 0) {
            return;
        }
        MoveBurst burst = state(context.channel()).recordMoves(moves, System.nanoTime());
        if (burst == null) {
            return;
        }
        DiagnosticLog.warn(DiagnosticToolRegistry.Tool.MOVEMENT_BURST,
                "channel={}, moves={}, windowMs={}, pendingTasks={}, writable={}",
                ChannelIdentity.longText(context.channel()), burst.movePackets(), burst.windowMillis(),
                pendingTasks(context.channel()), context.channel().isWritable());
    }

    private static MoveWindow state(Channel channel) {
        return MOVE_WINDOWS.computeIfAbsent(ChannelIdentity.longText(channel), ignored -> new MoveWindow());
    }

    private static int pendingTasks(Channel channel) {
        return channel == null || !(channel.eventLoop() instanceof SingleThreadEventExecutor executor) ? -1 : executor.pendingTasks();
    }

    private static boolean isEnabled(DiagnosticToolRegistry.Tool tool) {
        return DiagnosticToolRegistry.isEnabled(tool) || DiagnosticRuntimeSwitch.isEnabled(DiagnosticRuntimeSwitch.Topic.MOVEMENT);
    }

    private static final class MoveWindow {
        private long windowStartNanos = System.nanoTime();
        private int movePackets;
        private long lastCorrectionLogNanos;

        synchronized MoveBurst recordMoves(int moves, long nowNanos) {
            movePackets += Math.max(moves, 0);
            long elapsed = nowNanos - windowStartNanos;
            if (elapsed < WINDOW_NANOS) return null;
            MoveBurst burst = movePackets >= MOVE_BURST_THRESHOLD
                    ? new MoveBurst(movePackets, TimeUnit.NANOSECONDS.toMillis(Math.max(elapsed, 0L))) : null;
            windowStartNanos = nowNanos;
            movePackets = 0;
            return burst;
        }

        synchronized boolean markCorrection(long nowNanos) {
            if (nowNanos - lastCorrectionLogNanos < CORRECTION_LOG_MIN_NANOS) return false;
            lastCorrectionLogNanos = nowNanos;
            return true;
        }
    }

    private record MoveBurst(int movePackets, long windowMillis) {
    }
}
