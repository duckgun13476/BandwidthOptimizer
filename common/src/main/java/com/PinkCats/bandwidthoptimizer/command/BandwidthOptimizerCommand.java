package com.PinkCats.bandwidthoptimizer.command;

import com.PinkCats.bandwidthoptimizer.report.ChannelTransportCompressionCommand;
import com.PinkCats.bandwidthoptimizer.report.ChannelTransportPacketRankCommand;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsCommand;
import com.PinkCats.bandwidthoptimizer.compat.minecraft.CommandSourceCompat;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class BandwidthOptimizerCommand {
    private BandwidthOptimizerCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bandwidthoptimizer")
                .executes(context -> root(context.getSource()))
                .then(Commands.literal("test")
                        .requires(source -> source.hasPermission(2))
                        .then(ChannelTransportCompressionCommand.buildCommand())
                        .then(ChannelTransportPacketRankCommand.buildCommand()))
                .then(ServerBandwidthStatsCommand.buildCommand())
        );
    }


    private static int root(CommandSourceStack source) {
        CommandSourceCompat.sendSuccess(source, Component.literal(
                "BandwidthOptimizer commands: /bandwidthoptimizer hud (client) | "
                        + "/bandwidthoptimizer test transportreport run [ticks] | "
                        + "/bandwidthoptimizer test packetrank run [ticks] | "
                        + "/bandwidthoptimizer stats total|players [limit]|reset"
        ), false);
        return Command.SINGLE_SUCCESS;
    }
}
