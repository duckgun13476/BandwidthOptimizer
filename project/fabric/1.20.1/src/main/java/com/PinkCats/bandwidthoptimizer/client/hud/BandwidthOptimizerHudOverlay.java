package com.PinkCats.bandwidthoptimizer.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class BandwidthOptimizerHudOverlay {

    private BandwidthOptimizerHudOverlay() {
    }

    public static void onLoggingIn() {
        BandwidthOptimizerHudOverlayCore.onLoggingIn();
    }

    public static void render(GuiGraphics guiGraphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!BandwidthOptimizerHudOverlayCore.shouldRender(minecraft)) {
            String idleIndicator = BandwidthOptimizerHudOverlayCore.idleIndicator(minecraft);
            if (idleIndicator != null) {
                guiGraphics.drawString(minecraft.font, idleIndicator, 6, 6, 0xFFD166, false);
            }
            return;
        }

        BandwidthOptimizerHudOverlayCore.CachedHud hud = BandwidthOptimizerHudOverlayCore.currentHud(minecraft);
        if (hud.lines().isEmpty()) {
            return;
        }

        int x = 6;
        int y = 6;

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
