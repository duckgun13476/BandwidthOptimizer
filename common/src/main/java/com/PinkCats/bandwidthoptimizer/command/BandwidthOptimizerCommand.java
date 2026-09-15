package com.PinkCats.bandwidthoptimizer.command;

import com.PinkCats.bandwidthoptimizer.report.unified.BandwidthReportCommand;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.CommandSourceCompat;
import com.PinkCats.bandwidthoptimizer.integration.bungeecord.ProxyControlChannelRegistry;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolCommand;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class BandwidthOptimizerCommand {
    private BandwidthOptimizerCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        ProxyControlChannelRegistry.reload();
        var root = dispatcher.register(Commands.literal("bandwidthoptimizer")
                .executes(context -> root(context.getSource()))
                .then(Commands.literal("stats")
                        .requires(source -> CommandSourceCompat.hasPermission(source, 2))
                        .executes(context -> BandwidthReportCommand.upload(context.getSource())))
                .then(Commands.literal("reload")
                        .requires(source -> CommandSourceCompat.hasPermission(source, 2))
                        .executes(context -> reload(context.getSource())))
                .then(DiagnosticToolCommand.buildCommand())
        );
        dispatcher.register(Commands.literal("bo").redirect(root));
    }


    private static int root(CommandSourceStack source) {
        CommandSourceCompat.sendSuccess(source, Component.translatable(
                "command.bandwidthoptimizer.help"
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int reload(CommandSourceStack source) {
        ProxyControlChannelRegistry.ReloadResult result = ProxyControlChannelRegistry.reload();
        if (!result.success()) {
            source.sendFailure(Component.literal("BO config reload failed; the previous proxy channel list remains active: " + result.detail()));
            return 0;
        }
        CommandSourceCompat.sendSuccess(source, Component.literal(
                "BO config reloaded: " + result.additionalChannelCount() + " additional proxy control channel(s)."
        ), false);
        return Command.SINGLE_SUCCESS;
    }
}
