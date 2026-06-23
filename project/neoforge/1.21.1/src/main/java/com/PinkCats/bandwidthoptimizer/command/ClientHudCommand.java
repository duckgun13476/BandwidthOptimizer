package com.PinkCats.bandwidthoptimizer.command;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.client.hud.BandwidthOptimizerHudOverlay;
import com.PinkCats.bandwidthoptimizer.debug.DiagnosticRuntimeSwitch;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.Locale;

@EventBusSubscriber(modid = Bandwidthoptimizer.MODID, value = Dist.CLIENT)
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
                .then(buildDiagnoseCommand()));
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
        source.sendSuccess(() -> Component.literal(
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

    private static LiteralArgumentBuilder<CommandSourceStack> clientDiagnoseTopic(DiagnosticRuntimeSwitch.Topic topic) {
        return Commands.literal(topic.id())
                .then(Commands.literal("on")
                        .executes(context -> setDiagnoseTopic(context.getSource(), topic, true)))
                .then(Commands.literal("off")
                        .executes(context -> setDiagnoseTopic(context.getSource(), topic, false)));
    }

    private static int diagnoseStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                DiagnosticRuntimeSwitch.statusText()
                        + ". This only controls the local client."
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setDiagnoseTopic(CommandSourceStack source, DiagnosticRuntimeSwitch.Topic topic, boolean enabled) {
        DiagnosticRuntimeSwitch.setEnabled(topic, enabled);
        source.sendSuccess(() -> Component.literal(
                "BO client diagnose " + topic.id() + ' ' + onOff(enabled) + ". " + DiagnosticRuntimeSwitch.statusText()
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setDiagnoseAll(CommandSourceStack source, boolean enabled) {
        DiagnosticRuntimeSwitch.setAll(enabled);
        source.sendSuccess(() -> Component.literal(
                "BO client diagnose all " + onOff(enabled) + ". " + DiagnosticRuntimeSwitch.statusText()
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
