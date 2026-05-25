package com.PinkCats.bandwidthoptimizer.client.hud;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Bandwidthoptimizer.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BandwidthOptimizerHudOverlay {

    private BandwidthOptimizerHudOverlay() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        BandwidthOptimizerHudOverlayCore.onLoggingIn();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        BandwidthOptimizerHudOverlayCore.onLoggingOut("forge_client_logging_out");
    }

    @SubscribeEvent
    public static void render(RenderGuiOverlayEvent.Post event) {
        if (!VanillaGuiOverlay.HOTBAR.id().equals(event.getOverlay().id())) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!BandwidthOptimizerHudOverlayCore.shouldRender(minecraft)) {
            return;
        }

        BandwidthOptimizerHudOverlayCore.CachedHud hud = BandwidthOptimizerHudOverlayCore.currentHud(minecraft);
        if (hud.lines().isEmpty()) {
            return;
        }

        int x = 6;
        int y = 6;

        RenderSystem.enableBlend();
        GuiGraphics guiGraphics = event.getGuiGraphics();
        guiGraphics.fill(x, y, x + hud.boxWidth(), y + hud.boxHeight(), 0xA0101018);
        guiGraphics.fill(x, y, x + hud.boxWidth(), y + 1, 0xFF66D9EF);
        for (int index = 0; index < hud.lines().size(); index++) {
            String line = hud.lines().get(index);
            guiGraphics.drawString(minecraft.font, line, x + 5, y + 4 + index * hud.lineHeight(), BandwidthOptimizerHudOverlayCore.hudLineColor(line), false);
        }
    }

    public static boolean isEnabled() {
        return BandwidthOptimizerHudOverlayCore.isEnabled();
    }

    public static void setEnabled(boolean enabled) {
        BandwidthOptimizerHudOverlayCore.setEnabled(enabled);
    }
}
