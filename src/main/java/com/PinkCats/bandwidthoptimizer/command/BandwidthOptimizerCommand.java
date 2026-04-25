package com.PinkCats.bandwidthoptimizer.command;

import com.PinkCats.bandwidthoptimizer.report.ChannelTransportCompressionCommand;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class BandwidthOptimizerCommand {
    private BandwidthOptimizerCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bandwidthoptimizer")
                .requires(source -> source.hasPermission(2))
                .executes(context -> root(context.getSource()))
                .then(Commands.literal("test")
                        .then(ChannelTransportCompressionCommand.buildCommand()))
        );
    }

     private static int root(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "BandwidthOptimizer commands: /bandwidthoptimizer test transportreport run [ticks]"
        ), false);
        return Command.SINGLE_SUCCESS;
    }
}
