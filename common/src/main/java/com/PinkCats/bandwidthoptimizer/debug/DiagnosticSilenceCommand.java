package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.compat.minecraft.CommandSourceCompat;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class DiagnosticSilenceCommand {

    private DiagnosticSilenceCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> buildCommand() {
        return Commands.literal("debug")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("off")
                        .executes(context -> disableAll(context.getSource())));
    }

    private static int disableAll(CommandSourceStack source) {
        DiagnosticSilencer.disableAll();
        CommandSourceCompat.sendSuccess(source, Component.literal(
                DiagnosticSilencer.disabledText("server")
        ), true);
        return Command.SINGLE_SUCCESS;
    }
}
