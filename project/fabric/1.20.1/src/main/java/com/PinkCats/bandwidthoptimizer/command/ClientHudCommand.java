package com.PinkCats.bandwidthoptimizer.command;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.client.hud.BandwidthOptimizerHudOverlay;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticRuntimeSwitch;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

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
                .then(buildDiagnoseCommand())
                .then(buildDiagnosticToolCommand()));
        dispatcher.register(literal("bandwidthoptimister")
                .then(buildDiagnosticToolCommand()));
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

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildDiagnoseCommand() {
        return literal("diagnose")
                .executes(context -> diagnoseStatus(context.getSource()))
                .then(literal("status")
                        .executes(context -> diagnoseStatus(context.getSource())))
                .then(literal("off")
                        .executes(context -> setDiagnoseAll(context.getSource(), false)))
                .then(literal("all")
                        .then(literal("on")
                                .executes(context -> setDiagnoseAll(context.getSource(), true)))
                        .then(literal("off")
                                .executes(context -> setDiagnoseAll(context.getSource(), false))))
                .then(clientDiagnoseTopic(DiagnosticRuntimeSwitch.Topic.MOVEMENT))
                .then(clientDiagnoseTopic(DiagnosticRuntimeSwitch.Topic.TRANSPORT))
                .then(clientDiagnoseTopic(DiagnosticRuntimeSwitch.Topic.CACHE));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildDiagnosticToolCommand() {
        LiteralArgumentBuilder<FabricClientCommandSource> root = literal("diagnosetool")
                .executes(context -> diagnosticToolStatus(context.getSource()))
                .then(literal("status")
                        .executes(context -> diagnosticToolStatus(context.getSource())));
        for (DiagnosticToolRegistry.Tool tool : DiagnosticToolRegistry.Tool.values()) {
            root.then(clientDiagnosticTool(tool));
        }
        return root;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> clientDiagnosticTool(DiagnosticToolRegistry.Tool tool) {
        return literal(tool.id())
                .executes(context -> toggleDiagnosticTool(context.getSource(), tool))
                .then(literal("on")
                        .executes(context -> setDiagnosticTool(context.getSource(), tool, true)))
                .then(literal("off")
                        .executes(context -> setDiagnosticTool(context.getSource(), tool, false)));
    }

    private static int diagnosticToolStatus(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal(
                DiagnosticToolRegistry.listText()
                        + "\nThis only controls the local client."
        ));
        return Command.SINGLE_SUCCESS;
    }

    private static int toggleDiagnosticTool(FabricClientCommandSource source, DiagnosticToolRegistry.Tool tool) {
        return setDiagnosticTool(source, tool, DiagnosticToolRegistry.toggle(tool));
    }

    private static int setDiagnosticTool(FabricClientCommandSource source, DiagnosticToolRegistry.Tool tool, boolean enabled) {
        DiagnosticToolRegistry.setEnabled(tool, enabled);
        source.sendFeedback(Component.literal(
                "BO diagnosetool " + tool.id()
                        + ' ' + onOff(enabled)
                        + ". Log prefix [BO:Diag:" + tool.id() + "]. This only controls the local client."
        ));
        return Command.SINGLE_SUCCESS;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> clientDiagnoseTopic(DiagnosticRuntimeSwitch.Topic topic) {
        return literal(topic.id())
                .then(literal("on")
                        .executes(context -> setDiagnoseTopic(context.getSource(), topic, true)))
                .then(literal("off")
                        .executes(context -> setDiagnoseTopic(context.getSource(), topic, false)));
    }

    private static int diagnoseStatus(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal(
                DiagnosticRuntimeSwitch.statusText()
                        + ". This only controls the local client."
        ));
        return Command.SINGLE_SUCCESS;
    }

    private static int setDiagnoseTopic(FabricClientCommandSource source, DiagnosticRuntimeSwitch.Topic topic, boolean enabled) {
        DiagnosticRuntimeSwitch.setEnabled(topic, enabled);
        source.sendFeedback(Component.literal(
                "BO client diagnose " + topic.id() + ' ' + onOff(enabled) + ". " + DiagnosticRuntimeSwitch.statusText()
        ));
        return Command.SINGLE_SUCCESS;
    }

    private static int setDiagnoseAll(FabricClientCommandSource source, boolean enabled) {
        DiagnosticRuntimeSwitch.setAll(enabled);
        source.sendFeedback(Component.literal(
                "BO client diagnose all " + onOff(enabled) + ". " + DiagnosticRuntimeSwitch.statusText()
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

