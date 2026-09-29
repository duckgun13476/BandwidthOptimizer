package com.PinkCats.bandwidthoptimizer.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

public final class BandwidthOptimizerHudOverlay {

    private BandwidthOptimizerHudOverlay() {
    }

    public static void onLoggingIn() {
        BandwidthOptimizerHudOverlayCore.onLoggingIn();
    }

    public static void render(GuiGraphicsExtractor guiGraphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!BandwidthOptimizerHudOverlayCore.shouldRender(minecraft)) {
            String idleIndicator = BandwidthOptimizerHudOverlayCore.idleIndicator(minecraft);
            if (idleIndicator != null) {
                guiGraphics.textRenderer().accept(6, 6, coloredText(idleIndicator, 0xFFD166));
            }
            return;
        }

        BandwidthOptimizerHudOverlayCore.CachedHud hud = BandwidthOptimizerHudOverlayCore.currentHud(minecraft);
        if (hud.lines().isEmpty()) {
            return;
        }

        int x = 6;
        int y = 6;

        guiGraphics.fill(x, y, x + hud.boxWidth(), y + hud.boxHeight(), 0xA0101018);
        guiGraphics.fill(x, y, x + hud.boxWidth(), y + 1, 0xFF66D9EF);
        for (int index = 0; index < hud.lines().size(); index++) {
            String line = hud.lines().get(index);
            guiGraphics.textRenderer().accept(x + 5, y + 4 + index * hud.lineHeight(),
                    coloredText(line, BandwidthOptimizerHudOverlayCore.hudLineColor(line)));
        }
    }

    private static Component coloredText(String text, int color) {
        return Component.literal(text).withStyle(style -> style.withColor(color));
    }

    public static boolean isEnabled() {
        return BandwidthOptimizerHudOverlayCore.isEnabled();
    }

    public static void setEnabled(boolean enabled) {
        BandwidthOptimizerHudOverlayCore.setEnabled(enabled);
    }
}
