package com.PinkCats.bandwidthoptimizer.Old.command;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.PinkCats.bandwidthoptimizer.Old.network.client.ClientOptimizationHudOverlay;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
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
    private static long lastToggleAt;

    private ClientHudCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("bandwidthoptimizer")
                .then(Commands.literal("hud")
                        .executes(context -> toggle(context.getSource()))));
    }

    private static int toggle(CommandSourceStack source) {
        long now = System.currentTimeMillis();
        long waitMillis = TOGGLE_COOLDOWN_MILLIS - (now - lastToggleAt);
        if (waitMillis > 0L) {
            source.sendFailure(Component.literal(
                    "HUD toggle is on cooldown. Wait " + formatCooldownSeconds(waitMillis) + "s."
            ));
            return 0;
        }

        lastToggleAt = now;
        boolean enabled = !ClientOptimizationHudOverlay.isEnabled();
        ClientOptimizationHudOverlay.setEnabled(enabled);
        source.sendSuccess(() -> Component.literal(
                "Bandwidth optimizer HUD " + (enabled ? "enabled" : "disabled") + " for this client."
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatCooldownSeconds(long millis) {
        return String.format(Locale.ROOT, "%.1f", millis / 1000.0D);
    }
}
