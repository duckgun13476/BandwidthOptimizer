package com.PinkCats.bandwidthoptimizer.command;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.client.hud.BandwidthOptimizerHudOverlay;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticSilencer;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import java.util.Locale;

public final class ClientHudCommand {

    private static final long TOGGLE_COOLDOWN_MILLIS = 1_000L;

    private static long lastToggleAtMillis;

    private ClientHudCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("bandwidthoptimizer")
                .then(literal("hud")
                        .executes(context -> toggle(context.getSource())))
                .then(buildDebugCommand()));
    }

    private static int toggle(FabricClientCommandSource source) {
        long now = System.currentTimeMillis();
        long waitMillis = TOGGLE_COOLDOWN_MILLIS - (now - lastToggleAtMillis);
        if (waitMillis > 0L) {
            source.sendError(Component.literal(
                    "HUD toggle is on cooldown. Wait " + formatCooldownSeconds(waitMillis) + "s."
            ));
            return 0;
        }

        lastToggleAtMillis = now;
        boolean enabled = !BandwidthOptimizerHudOverlay.isEnabled();
        BandwidthOptimizerHudOverlay.setEnabled(enabled);
        source.sendFeedback(Component.literal(
                "Bandwidth optimizer HUD " + (enabled ? "enabled" : "disabled") + " for this client."
        ));
        return Command.SINGLE_SUCCESS;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildDebugCommand() {
        LiteralArgumentBuilder<FabricClientCommandSource> root = literal("debug")
                .executes(context -> diagnosticToolStatus(context.getSource()))
                .then(literal("status")
                        .executes(context -> diagnosticToolStatus(context.getSource())))
                .then(literal("list")
                        .executes(context -> diagnosticToolStatus(context.getSource())))
                .then(literal("off")
                        .executes(context -> disableAllDiagnostics(context.getSource())));
        for (DiagnosticToolRegistry.Tool tool : DiagnosticToolRegistry.Tool.values()) {
            root.then(clientDiagnosticTool(tool));
        }
        return root;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> clientDiagnosticTool(DiagnosticToolRegistry.Tool tool) {
        return literal(tool.id())
                .executes(context -> toggleDiagnosticTool(context.getSource(), tool))
                .then(argument("minutes", IntegerArgumentType.integer(
                                DiagnosticToolRegistry.MIN_MINUTES,
                                DiagnosticToolRegistry.MAX_MINUTES
                        ))
                        .executes(context -> enableDiagnosticTool(
                                context.getSource(),
                                tool,
                                IntegerArgumentType.getInteger(context, "minutes")
                        )));
    }

    private static int diagnosticToolStatus(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal(
                DiagnosticToolRegistry.listText()
                        + "\nUse /bandwidthoptimizer debug <name> to toggle for 30m, "
                        + "/bandwidthoptimizer debug <name> <5-300> to enable for minutes, "
                        + "or /bandwidthoptimizer debug off."
                        + " This only controls the local client."
        ));
        return Command.SINGLE_SUCCESS;
    }

    private static int toggleDiagnosticTool(FabricClientCommandSource source, DiagnosticToolRegistry.Tool tool) {
        boolean enabled = DiagnosticToolRegistry.toggle(tool);
        sendDiagnosticToolState(source, tool, enabled);
        return Command.SINGLE_SUCCESS;
    }

    private static int enableDiagnosticTool(FabricClientCommandSource source, DiagnosticToolRegistry.Tool tool, int minutes) {
        DiagnosticToolRegistry.enable(tool, minutes);
        sendDiagnosticToolState(source, tool, true);
        return Command.SINGLE_SUCCESS;
    }

    private static void sendDiagnosticToolState(FabricClientCommandSource source, DiagnosticToolRegistry.Tool tool, boolean enabled) {
        source.sendFeedback(Component.literal(
                "BO debug " + tool.id()
                        + ' ' + onOff(enabled)
                        + (enabled
                                ? " expiresIn=" + DiagnosticToolRegistry.formatRemaining(DiagnosticToolRegistry.remainingMillis(tool))
                                : "")
                        + ". Log prefix " + DiagnosticLog.prefix(tool) + ". This only controls the local client."
        ));
    }

    private static int disableAllDiagnostics(FabricClientCommandSource source) {
        DiagnosticSilencer.disableAll();
        source.sendFeedback(Component.literal(
                DiagnosticSilencer.disabledText("local client")
        ));
        return Command.SINGLE_SUCCESS;
    }

    private static String formatCooldownSeconds(long waitMillis) {
        return String.format(Locale.ROOT, "%.1f", waitMillis / 1000.0D);
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }
}

