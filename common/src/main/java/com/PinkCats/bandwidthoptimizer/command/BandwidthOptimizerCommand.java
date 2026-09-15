package com.PinkCats.bandwidthoptimizer.command;

import com.PinkCats.bandwidthoptimizer.report.unified.BandwidthReportCommand;
import com.PinkCats.bandwidthoptimizer.integration.minecraft.CommandSourceCompat;
import com.PinkCats.bandwidthoptimizer.integration.bungeecord.ProxyControlChannelRegistry;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolCommand;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
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
                .then(Commands.literal("proxy")
                        .requires(source -> CommandSourceCompat.hasPermission(source, 2))
                        .executes(context -> listProxyChannels(context.getSource()))
                        .then(Commands.literal("add")
                                .then(Commands.argument("channel", StringArgumentType.greedyString())
                                        .executes(context -> mutateProxyChannel(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "channel"),
                                                true
                                        ))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("channel", StringArgumentType.greedyString())
                                        .executes(context -> mutateProxyChannel(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "channel"),
                                                false
                                        )))))
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

    private static int listProxyChannels(CommandSourceStack source) {
        ProxyControlChannelRegistry.ChannelSnapshot snapshot = ProxyControlChannelRegistry.snapshot();
        sendChannelList(source, "Built-in proxy control channels", snapshot.builtIn());
        sendChannelList(source, "Additional proxy control channels", snapshot.additional());
        return Command.SINGLE_SUCCESS;
    }

    private static void sendChannelList(CommandSourceStack source, String title, java.util.List<String> channels) {
        CommandSourceCompat.sendSuccess(source, Component.literal(title + " (" + channels.size() + "):"), false);
        if (channels.isEmpty()) {
            CommandSourceCompat.sendSuccess(source, Component.literal("  (none)"), false);
            return;
        }
        for (int index = 0; index < channels.size(); index += 8) {
            int end = Math.min(index + 8, channels.size());
            CommandSourceCompat.sendSuccess(source, Component.literal("  " + String.join(", ", channels.subList(index, end))), false);
        }
    }

    private static int mutateProxyChannel(CommandSourceStack source, String channel, boolean add) {
        ProxyControlChannelRegistry.MutationResult result = add
                ? ProxyControlChannelRegistry.add(channel)
                : ProxyControlChannelRegistry.remove(channel);
        if (!result.success()) {
            source.sendFailure(Component.literal("BO proxy channel update failed; the current list remains active: " + result.detail()));
            return 0;
        }
        String action = add ? "add" : "remove";
        CommandSourceCompat.sendSuccess(source, Component.literal(
                "BO proxy channel " + action + ": " + result.detail() + "; "
                        + result.additionalChannelCount() + " additional channel(s) active."
        ), false);
        return Command.SINGLE_SUCCESS;
    }
}
