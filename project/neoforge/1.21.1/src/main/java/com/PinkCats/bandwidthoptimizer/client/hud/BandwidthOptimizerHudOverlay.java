package com.PinkCats.bandwidthoptimizer.client.hud;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

@EventBusSubscriber(modid = Bandwidthoptimizer.MODID, value = Dist.CLIENT)
public final class BandwidthOptimizerHudOverlay {

    private BandwidthOptimizerHudOverlay() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        BandwidthOptimizerHudOverlayCore.onLoggingIn();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        BandwidthOptimizerHudOverlayCore.onLoggingOut("neoforge_client_logging_out");
    }

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!BandwidthOptimizerHudOverlayCore.shouldRender(minecraft)) {
            String idleIndicator = BandwidthOptimizerHudOverlayCore.idleIndicator(minecraft);
            if (idleIndicator != null) {
                event.getGuiGraphics().drawString(minecraft.font, idleIndicator, 6, 6, 0xFFD166, false);
            }
            return;
        }

        BandwidthOptimizerHudOverlayCore.CachedHud hud = BandwidthOptimizerHudOverlayCore.currentHud(minecraft);
        if (hud.lines().isEmpty()) {
            return;
        }

        int x = 6;
        int y = 6;

        GuiGraphics guiGraphics = event.getGuiGraphics();
        RenderSystem.enableBlend();
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
