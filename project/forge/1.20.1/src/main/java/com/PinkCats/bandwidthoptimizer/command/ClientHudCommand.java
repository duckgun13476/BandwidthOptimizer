package com.PinkCats.bandwidthoptimizer.command;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.client.hud.BandwidthOptimizerHudOverlay;
import com.PinkCats.bandwidthoptimizer.chunk.debug.ChunkClientGapProbe;
import com.PinkCats.bandwidthoptimizer.compat.minecraft.CommandSourceCompat;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticRuntimeSwitch;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;

@Mod.EventBusSubscriber(modid = Bandwidthoptimizer.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientHudCommand {

    private static final long TOGGLE_COOLDOWN_MILLIS = 1_000L;

    private static long lastToggleAtMillis;

    private ClientHudCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("bandwidthoptimizer")
                .then(Commands.literal("hud")
                        .executes(context -> toggle(context.getSource())))
                .then(Commands.literal("chunkgap")
                        .executes(context -> toggleChunkGapProbe(context.getSource())))
                .then(buildDiagnoseCommand())
                .then(buildDiagnosticToolCommand()));
        dispatcher.register(Commands.literal("bandwidthoptimister")
                .then(buildDiagnosticToolCommand()));
    }

    private static int toggle(CommandSourceStack source) {
        long now = System.currentTimeMillis();
        long waitMillis = TOGGLE_COOLDOWN_MILLIS - (now - lastToggleAtMillis);
        if (waitMillis > 0L) {
            source.sendFailure(Component.literal(
                    "HUD toggle is on cooldown. Wait " + formatCooldownSeconds(waitMillis) + "s."
            ));
            return 0;
        }

        lastToggleAtMillis = now;
        boolean enabled = !BandwidthOptimizerHudOverlay.isEnabled();
        BandwidthOptimizerHudOverlay.setEnabled(enabled);
        CommandSourceCompat.sendSuccess(source, Component.literal(
                "Bandwidth optimizer HUD " + (enabled ? "enabled" : "disabled") + " for this client."
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildDiagnoseCommand() {
        return Commands.literal("diagnose")
                .executes(context -> diagnoseStatus(context.getSource()))
                .then(Commands.literal("status")
                        .executes(context -> diagnoseStatus(context.getSource())))
                .then(Commands.literal("off")
                        .executes(context -> setDiagnoseAll(context.getSource(), false)))
                .then(Commands.literal("all")
                        .then(Commands.literal("on")
                                .executes(context -> setDiagnoseAll(context.getSource(), true)))
                        .then(Commands.literal("off")
                                .executes(context -> setDiagnoseAll(context.getSource(), false))))
                .then(clientDiagnoseTopic(DiagnosticRuntimeSwitch.Topic.MOVEMENT))
                .then(clientDiagnoseTopic(DiagnosticRuntimeSwitch.Topic.TRANSPORT))
                .then(clientDiagnoseTopic(DiagnosticRuntimeSwitch.Topic.CACHE));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildDiagnosticToolCommand() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("diagnosetool")
                .executes(context -> diagnosticToolStatus(context.getSource()))
                .then(Commands.literal("status")
                        .executes(context -> diagnosticToolStatus(context.getSource())));
        for (DiagnosticToolRegistry.Tool tool : DiagnosticToolRegistry.Tool.values()) {
            root.then(clientDiagnosticTool(tool));
        }
        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> clientDiagnosticTool(DiagnosticToolRegistry.Tool tool) {
        return Commands.literal(tool.id())
                .executes(context -> toggleDiagnosticTool(context.getSource(), tool))
                .then(Commands.literal("on")
                        .executes(context -> setDiagnosticTool(context.getSource(), tool, true)))
                .then(Commands.literal("off")
                        .executes(context -> setDiagnosticTool(context.getSource(), tool, false)));
    }

    private static int diagnosticToolStatus(CommandSourceStack source) {
        CommandSourceCompat.sendSuccess(source, Component.literal(
                DiagnosticToolRegistry.listText()
                        + "\nThis only controls the local client."
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int toggleDiagnosticTool(CommandSourceStack source, DiagnosticToolRegistry.Tool tool) {
        return setDiagnosticTool(source, tool, DiagnosticToolRegistry.toggle(tool));
    }

    private static int setDiagnosticTool(CommandSourceStack source, DiagnosticToolRegistry.Tool tool, boolean enabled) {
        DiagnosticToolRegistry.setEnabled(tool, enabled);
        CommandSourceCompat.sendSuccess(source, Component.literal(
                "BO diagnosetool " + tool.id()
                        + ' ' + onOff(enabled)
                        + ". Log prefix [BO:Diag:" + tool.id() + "]. This only controls the local client."
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> clientDiagnoseTopic(DiagnosticRuntimeSwitch.Topic topic) {
        return Commands.literal(topic.id())
                .then(Commands.literal("on")
                        .executes(context -> setDiagnoseTopic(context.getSource(), topic, true)))
                .then(Commands.literal("off")
                        .executes(context -> setDiagnoseTopic(context.getSource(), topic, false)));
    }

    private static int diagnoseStatus(CommandSourceStack source) {
        CommandSourceCompat.sendSuccess(source, Component.literal(
                DiagnosticRuntimeSwitch.statusText()
                        + ". This only controls the local client."
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setDiagnoseTopic(CommandSourceStack source, DiagnosticRuntimeSwitch.Topic topic, boolean enabled) {
        DiagnosticRuntimeSwitch.setEnabled(topic, enabled);
        CommandSourceCompat.sendSuccess(source, Component.literal(
                "BO client diagnose " + topic.id() + ' ' + onOff(enabled) + ". " + DiagnosticRuntimeSwitch.statusText()
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setDiagnoseAll(CommandSourceStack source, boolean enabled) {
        DiagnosticRuntimeSwitch.setAll(enabled);
        CommandSourceCompat.sendSuccess(source, Component.literal(
                "BO client diagnose all " + onOff(enabled) + ". " + DiagnosticRuntimeSwitch.statusText()
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int toggleChunkGapProbe(CommandSourceStack source) {
        boolean enabled = !ChunkClientGapProbe.isEnabled();
        ChunkClientGapProbe.setEnabled(enabled);
        CommandSourceCompat.sendSuccess(source, Component.literal(
                "Chunk gap diagnostics " + (enabled ? "enabled" : "disabled")
                        + " for this client. Output is written to the client log."
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatCooldownSeconds(long waitMillis) {
        return String.format(Locale.ROOT, "%.1f", waitMillis / 1000.0D);
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }
}
