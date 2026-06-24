package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.compat.minecraft.CommandSourceCompat;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class DiagnosticToolCommand {

    private DiagnosticToolCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> buildCommand() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("debug")
                .requires(source -> source.hasPermission(2))
                .executes(context -> list(context.getSource()))
                .then(Commands.literal("status")
                        .executes(context -> list(context.getSource())))
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource())))
                .then(Commands.literal("off")
                        .executes(context -> disableAll(context.getSource())));

        for (DiagnosticToolRegistry.Tool tool : DiagnosticToolRegistry.Tool.values()) {
            root.then(toolCommand(tool));
        }
        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> toolCommand(DiagnosticToolRegistry.Tool tool) {
        return Commands.literal(tool.id())
                .executes(context -> toggle(context.getSource(), tool))
                .then(Commands.argument("minutes", IntegerArgumentType.integer(
                                DiagnosticToolRegistry.MIN_MINUTES,
                                DiagnosticToolRegistry.MAX_MINUTES
                        ))
                        .executes(context -> enableFor(
                                context.getSource(),
                                tool,
                                IntegerArgumentType.getInteger(context, "minutes")
                        )));
    }

    private static int list(CommandSourceStack source) {
        CommandSourceCompat.sendSuccess(source, Component.literal(
                DiagnosticToolRegistry.listText()
                        + "\nUse /bandwidthoptimizer debug <name> to toggle for 30m, "
                        + "/bandwidthoptimizer debug <name> <5-300> to enable for minutes, "
                        + "or /bandwidthoptimizer debug off."
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int disableAll(CommandSourceStack source) {
        DiagnosticSilencer.disableAll();
        CommandSourceCompat.sendSuccess(source, Component.literal(
                DiagnosticSilencer.disabledText("server")
        ), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int toggle(CommandSourceStack source, DiagnosticToolRegistry.Tool tool) {
        boolean enabled = DiagnosticToolRegistry.toggle(tool);
        sendToolState(source, tool, enabled, "toggled");
        return Command.SINGLE_SUCCESS;
    }

    private static int enableFor(CommandSourceStack source, DiagnosticToolRegistry.Tool tool, int minutes) {
        DiagnosticToolRegistry.enable(tool, minutes);
        sendToolState(source, tool, true, "set");
        return Command.SINGLE_SUCCESS;
    }

    private static void sendToolState(
            CommandSourceStack source,
            DiagnosticToolRegistry.Tool tool,
            boolean enabled,
            String action
    ) {
        CommandSourceCompat.sendSuccess(source, Component.literal(
                "BO debug " + tool.id()
                        + ' ' + action
                        + ' ' + (enabled ? "on" : "off")
                        + (enabled
                                ? " expiresIn=" + DiagnosticToolRegistry.formatRemaining(DiagnosticToolRegistry.remainingMillis(tool))
                                : "")
                        + " cost=" + tool.cost().label()
                        + ". Log prefix " + DiagnosticLog.prefix(tool)
        ), true);
    }
}
