package com.PinkCats.bandwidthoptimizer.command;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.client.hud.BandwidthOptimizerHudOverlay;
import com.PinkCats.bandwidthoptimizer.chunk.debug.ChunkClientGapProbe;
import com.PinkCats.bandwidthoptimizer.compat.minecraft.CommandSourceCompat;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticLog;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticSilencer;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticToolRegistry;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
                .then(buildDebugCommand()));
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

    private static LiteralArgumentBuilder<CommandSourceStack> buildDebugCommand() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("debug")
                .executes(context -> diagnosticToolStatus(context.getSource()))
                .then(Commands.literal("status")
                        .executes(context -> diagnosticToolStatus(context.getSource())))
                .then(Commands.literal("list")
                        .executes(context -> diagnosticToolStatus(context.getSource())))
                .then(Commands.literal("off")
                        .executes(context -> disableAllDiagnostics(context.getSource())));
        for (DiagnosticToolRegistry.Tool tool : DiagnosticToolRegistry.Tool.values()) {
            root.then(clientDiagnosticTool(tool));
        }
        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> clientDiagnosticTool(DiagnosticToolRegistry.Tool tool) {
        return Commands.literal(tool.id())
                .executes(context -> toggleDiagnosticTool(context.getSource(), tool))
                .then(Commands.argument("minutes", IntegerArgumentType.integer(
                                DiagnosticToolRegistry.MIN_MINUTES,
                                DiagnosticToolRegistry.MAX_MINUTES
                        ))
                        .executes(context -> enableDiagnosticTool(
                                context.getSource(),
                                tool,
                                IntegerArgumentType.getInteger(context, "minutes")
                        )));
    }

    private static int diagnosticToolStatus(CommandSourceStack source) {
        CommandSourceCompat.sendSuccess(source, Component.literal(
                DiagnosticToolRegistry.listText()
                        + "\nUse /bandwidthoptimizer debug <name> to toggle for 30m, "
                        + "/bandwidthoptimizer debug <name> <5-300> to enable for minutes, "
                        + "or /bandwidthoptimizer debug off."
                        + " This only controls the local client."
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int toggleDiagnosticTool(CommandSourceStack source, DiagnosticToolRegistry.Tool tool) {
        boolean enabled = DiagnosticToolRegistry.toggle(tool);
        sendDiagnosticToolState(source, tool, enabled);
        return Command.SINGLE_SUCCESS;
    }

    private static int enableDiagnosticTool(CommandSourceStack source, DiagnosticToolRegistry.Tool tool, int minutes) {
        DiagnosticToolRegistry.enable(tool, minutes);
        sendDiagnosticToolState(source, tool, true);
        return Command.SINGLE_SUCCESS;
    }

    private static void sendDiagnosticToolState(CommandSourceStack source, DiagnosticToolRegistry.Tool tool, boolean enabled) {
        CommandSourceCompat.sendSuccess(source, Component.literal(
                "BO debug " + tool.id()
                        + ' ' + onOff(enabled)
                        + (enabled
                                ? " expiresIn=" + DiagnosticToolRegistry.formatRemaining(DiagnosticToolRegistry.remainingMillis(tool))
                                : "")
                        + ". Log prefix " + DiagnosticLog.prefix(tool) + ". This only controls the local client."
        ), false);
    }

    private static int disableAllDiagnostics(CommandSourceStack source) {
        DiagnosticSilencer.disableAll();
        ChunkClientGapProbe.setEnabled(false);
        CommandSourceCompat.sendSuccess(source, Component.literal(
                DiagnosticSilencer.disabledText("local client")
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
