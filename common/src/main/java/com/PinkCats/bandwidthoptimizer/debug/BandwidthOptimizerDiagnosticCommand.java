package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.compat.minecraft.CommandSourceCompat;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class BandwidthOptimizerDiagnosticCommand {
    private BandwidthOptimizerDiagnosticCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> buildCommand() {
        return Commands.literal("diagnose")
                .requires(source -> source.hasPermission(2))
                .executes(context -> status(context.getSource()))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource())))
                .then(Commands.literal("off")
                        .executes(context -> disableAll(context.getSource())))
                .then(Commands.literal("all")
                        .then(Commands.literal("on")
                                .executes(context -> setAll(context.getSource(), true)))
                        .then(Commands.literal("off")
                                .executes(context -> disableAll(context.getSource()))))
                .then(topicCommand(DiagnosticRuntimeSwitch.Topic.MOVEMENT))
                .then(topicCommand(DiagnosticRuntimeSwitch.Topic.TRANSPORT))
                .then(topicCommand(DiagnosticRuntimeSwitch.Topic.CACHE));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> topicCommand(DiagnosticRuntimeSwitch.Topic topic) {
        return Commands.literal(topic.id())
                .executes(context -> status(context.getSource()))
                .then(Commands.literal("on")
                        .executes(context -> setTopic(context.getSource(), topic, true)))
                .then(Commands.literal("off")
                        .executes(context -> setTopic(context.getSource(), topic, false)));
    }

    private static int status(CommandSourceStack source) {
        CommandSourceCompat.sendSuccess(source, Component.literal(
                DiagnosticRuntimeSwitch.statusText()
                        + ". Use /bandwidthoptimizer diagnose <movement|transport|cache|all> on|off."
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setTopic(CommandSourceStack source, DiagnosticRuntimeSwitch.Topic topic, boolean enabled) {
        DiagnosticRuntimeSwitch.setEnabled(topic, enabled);
        CommandSourceCompat.sendSuccess(source, Component.literal(
                "BO diagnose " + topic.id() + ' ' + onOff(enabled) + ". " + DiagnosticRuntimeSwitch.statusText()
        ), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int setAll(CommandSourceStack source, boolean enabled) {
        DiagnosticRuntimeSwitch.setAll(enabled);
        CommandSourceCompat.sendSuccess(source, Component.literal(
                "BO diagnose all " + onOff(enabled) + ". " + DiagnosticRuntimeSwitch.statusText()
        ), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int disableAll(CommandSourceStack source) {
        DiagnosticSilencer.disableAll();
        CommandSourceCompat.sendSuccess(source, Component.literal(
                DiagnosticSilencer.disabledText("server")
        ), true);
        return Command.SINGLE_SUCCESS;
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }
}
