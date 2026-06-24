package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.compat.minecraft.CommandSourceCompat;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class DiagnosticToolCommand {

    private DiagnosticToolCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> buildCommand() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("diagnosetool")
                .requires(source -> source.hasPermission(2))
                .executes(context -> list(context.getSource()));

        for (DiagnosticToolRegistry.Tool tool : DiagnosticToolRegistry.Tool.values()) {
            root.then(toolCommand(tool));
        }
        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> toolCommand(DiagnosticToolRegistry.Tool tool) {
        return Commands.literal(tool.id())
                .executes(context -> toggle(context.getSource(), tool))
                .then(Commands.literal("on")
                        .executes(context -> set(context.getSource(), tool, true)))
                .then(Commands.literal("off")
                        .executes(context -> set(context.getSource(), tool, false)));
    }

    private static int list(CommandSourceStack source) {
        CommandSourceCompat.sendSuccess(source, Component.literal(
                DiagnosticToolRegistry.listText()
                        + "\nUse /bandwidthoptimizer diagnosetool <name> to toggle once on, once off."
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int toggle(CommandSourceStack source, DiagnosticToolRegistry.Tool tool) {
        boolean enabled = DiagnosticToolRegistry.toggle(tool);
        sendToolState(source, tool, enabled, "toggled");
        return Command.SINGLE_SUCCESS;
    }

    private static int set(CommandSourceStack source, DiagnosticToolRegistry.Tool tool, boolean enabled) {
        DiagnosticToolRegistry.setEnabled(tool, enabled);
        sendToolState(source, tool, enabled, "set");
        return Command.SINGLE_SUCCESS;
    }

    private static void sendToolState(
            CommandSourceStack source,
            DiagnosticToolRegistry.Tool tool,
            boolean enabled,
            String action
    ) {
        CommandSourceCompat.sendSuccess(source, Component.literal(
                "BO diagnosetool " + tool.id()
                        + ' ' + action
                        + ' ' + (enabled ? "on" : "off")
                        + " cost=" + tool.cost().label()
                        + ". Log prefix " + DiagnosticLog.prefix(tool)
        ), true);
    }
}
