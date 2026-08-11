package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.integration.minecraft.CommandSourceCompat;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsRegistry;
import io.netty.channel.Channel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;

public final class PacketClassTraceControlService {

    private static volatile Sender sender = (player, payload) -> false;

    private PacketClassTraceControlService() {}

    public static void setSender(Sender newSender) {
        sender = newSender == null ? (player, payload) -> false : newSender;
    }

    public static int enable(CommandSourceStack source, int minutes, String classNames) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return fail(source, "Packet-class trace must be started by an in-game OP player");
        }

        Set<String> classes;
        try {
            classes = PacketClassTraceDiagnostic.requireExactClassNames(classNames);
        } catch (IllegalArgumentException exception) {
            return fail(source, exception.getMessage());
        }

        Channel channel = ServerBandwidthStatsRegistry.channelForPlayer(player);
        if (channel == null) {
            return fail(source, "Unable to resolve the invoking player's network connection");
        }
        int rate = PacketClassTraceDiagnostic.defaultMaxEventsPerMinute();
        PacketClassTraceControlPayload payload = PacketClassTraceControlPayload.enabled(minutes, rate, classes);
        if (!sender.send(player, payload)) {
            return fail(source, "The client did not negotiate BO packet-class trace control");
        }

        PacketClassTraceDiagnostic.configure(channel, classes, minutes, rate);
        CommandSourceCompat.sendSuccess(source, Component.literal(
                "BO packet-class trace enabled on client and server for "
                        + minutes + "m, classes=" + classes.size()
                        + ", maxEventsPerMinute=" + rate
        ), true);
        return 1;
    }

    public static int disable(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return fail(source, "Packet-class trace must be stopped by an in-game OP player");
        }
        Channel channel = ServerBandwidthStatsRegistry.channelForPlayer(player);
        PacketClassTraceDiagnostic.disable(channel);
        sender.send(player, PacketClassTraceControlPayload.disabled());
        CommandSourceCompat.sendSuccess(source, Component.literal(
                "BO packet-class trace disabled on client and server"
        ), true);
        return 1;
    }

    public static void acceptClient(Channel channel, byte[] bytes) {
        PacketClassTraceControlPayload payload = PacketClassTraceControlPayload.fromBytes(bytes);
        if (!payload.enabled()) {
            PacketClassTraceDiagnostic.disable(channel);
            return;
        }
        PacketClassTraceDiagnostic.configure(
                channel,
                Set.copyOf(payload.packetClasses()),
                payload.minutes(),
                payload.maxEventsPerMinute()
        );
    }

    private static int fail(CommandSourceStack source, String message) {
        CommandSourceCompat.sendSuccess(source, Component.literal("BO packet-class trace: " + message), false);
        return 0;
    }

    @FunctionalInterface
    public interface Sender {
        boolean send(ServerPlayer player, PacketClassTraceControlPayload payload);
    }
}
