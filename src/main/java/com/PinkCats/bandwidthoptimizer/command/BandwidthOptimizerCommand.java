package com.PinkCats.bandwidthoptimizer.command;

import com.PinkCats.bandwidthoptimizer.Old.network.ModNetwork;
import com.PinkCats.bandwidthoptimizer.Old.network.message.ServerToClientAttachmentPacket;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportCompressionCommand;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class BandwidthOptimizerCommand {
    private BandwidthOptimizerCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bandwidthoptimizer")
                .requires(source -> source.hasPermission(2))
                .executes(context -> root(context.getSource()))
                .then(Commands.literal("channel")
                        .executes(context -> channelRoot(context.getSource()))
                        .then(Commands.literal("ping")
                                .then(Commands.argument("payload", StringArgumentType.greedyString())
                                        .executes(context -> pingChannel(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "payload")
                                        ))))
                        .then(Commands.literal("increment")
                                .executes(context -> incrementChannel(context.getSource(), 0))
                                .then(Commands.argument("value", IntegerArgumentType.integer())
                                        .executes(context -> incrementChannel(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "value")
                                        )))))
                .then(Commands.literal("test")
                        .then(ChannelTransportCompressionCommand.buildCommand()))
                );
    }


    private static int root(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "BandwidthOptimizer commands: /bandwidthoptimizer channel ping <payload>"
                        + " | /bandwidthoptimizer channel increment [value]"
                        + " | /bandwidthoptimizer test transportreport run [ticks]"
                        + " | /bandwidthoptimizer hud"
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int channelRoot(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "Channel commands: /bandwidthoptimizer channel ping <payload> | /bandwidthoptimizer channel increment [value]"
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int pingChannel(CommandSourceStack source, String payload) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception exception) {
            source.sendFailure(Component.literal("This command must be run by an in-game player."));
            return 0;
        }

        String correlationId = UUID.randomUUID().toString();
        ModNetwork.sendToPlayer(player, new ServerToClientAttachmentPacket(
                correlationId,
                "ping",
                0,
                payload
        ));

        source.sendSuccess(() -> Component.literal(
                "Sent channel payload to client. correlationId=" + correlationId
        ), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int incrementChannel(CommandSourceStack source, int value) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception exception) {
            source.sendFailure(Component.literal("This command must be run by an in-game player."));
            return 0;
        }

        String correlationId = UUID.randomUUID().toString();
        ModNetwork.sendToPlayer(player, new ServerToClientAttachmentPacket(
                correlationId,
                "increment",
                value,
                "server-start=" + value
        ));

        source.sendSuccess(() -> Component.literal(
                "Sent increment test to client. correlationId=" + correlationId + ", value=" + value
        ), true);
        return Command.SINGLE_SUCCESS;
    }

}
