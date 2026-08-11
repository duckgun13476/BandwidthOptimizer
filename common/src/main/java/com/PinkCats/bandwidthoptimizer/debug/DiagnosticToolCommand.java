package com.PinkCats.bandwidthoptimizer.debug;

import com.PinkCats.bandwidthoptimizer.integration.minecraft.CommandSourceCompat;
import com.PinkCats.bandwidthoptimizer.gate.integration.minecraft.IdleGateBackgroundPacketGate;
import com.PinkCats.bandwidthoptimizer.server.stat.ServerBandwidthStatsCommand;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class DiagnosticToolCommand {

    private DiagnosticToolCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> buildCommand() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("debug")
                .requires(source -> CommandSourceCompat.hasPermission(source, 2))
                .executes(context -> list(context.getSource()))
                .then(Commands.literal("status")
                        .executes(context -> list(context.getSource())))
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource())))
                .then(Commands.literal("off")
                        .executes(context -> disableAll(context.getSource())))
                .then(Commands.literal("download-log")
                        .executes(context -> ServerBoLogExportService.request(
                                context.getSource(),
                                DiagnosticToolRegistry.DEFAULT_MINUTES
                        ))
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(
                                        DiagnosticToolRegistry.MIN_MINUTES,
                                        DiagnosticToolRegistry.MAX_MINUTES
                                ))
                                .executes(context -> ServerBoLogExportService.request(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "minutes")
                                ))))
                .then(ServerBandwidthStatsCommand.buildDebugCommand())
                .then(packetClassTraceCommand());

        for (DiagnosticToolRegistry.Tool tool : DiagnosticToolRegistry.Tool.values()) {
            if (tool == DiagnosticToolRegistry.Tool.PACKET_CLASS_TRACE) {
                continue;
            }
            root.then(toolCommand(tool));
        }
        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> packetClassTraceCommand() {
        return Commands.literal(DiagnosticToolRegistry.Tool.PACKET_CLASS_TRACE.id())
                .executes(context -> PacketClassTraceControlService.disable(context.getSource()))
                .then(Commands.argument("minutes", IntegerArgumentType.integer(
                                DiagnosticToolRegistry.MIN_MINUTES,
                                DiagnosticToolRegistry.MAX_MINUTES
                        ))
                        .then(Commands.argument("packetClasses", StringArgumentType.greedyString())
                                .executes(context -> PacketClassTraceControlService.enable(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "minutes"),
                                        StringArgumentType.getString(context, "packetClasses")
                                ))));
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
                        + "/bandwidthoptimizer debug packetClassTrace <5-300> <exact.class.Name[,more]>, "
                        + "/bandwidthoptimizer debug download-log <5-300> to save BO server logs on your client, "
                        + "or /bandwidthoptimizer debug off."
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int disableAll(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer) {
            PacketClassTraceControlService.disable(source);
        }
        PacketClassTraceDiagnostic.clearSessions();
        DiagnosticSilencer.disableAll();
        CommandSourceCompat.sendSuccess(source, Component.literal(
                DiagnosticSilencer.disabledText("server")
        ), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int toggle(CommandSourceStack source, DiagnosticToolRegistry.Tool tool) {
        boolean enabled = DiagnosticToolRegistry.toggle(tool);
        if (enabled) {
            resetToolSession(tool);
        }
        sendToolState(source, tool, enabled, "toggled");
        return Command.SINGLE_SUCCESS;
    }

    private static int enableFor(CommandSourceStack source, DiagnosticToolRegistry.Tool tool, int minutes) {
        DiagnosticToolRegistry.enable(tool, minutes);
        resetToolSession(tool);
        sendToolState(source, tool, true, "set");
        return Command.SINGLE_SUCCESS;
    }

    private static void resetToolSession(DiagnosticToolRegistry.Tool tool) {
        if (tool == DiagnosticToolRegistry.Tool.IDLE_GATE_TRAFFIC) {
            IdleGateBackgroundPacketGate.resetPassedPackets();
        }
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
