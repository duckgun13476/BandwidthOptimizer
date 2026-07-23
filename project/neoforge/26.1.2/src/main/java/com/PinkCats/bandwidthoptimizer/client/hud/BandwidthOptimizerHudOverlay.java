package com.PinkCats.bandwidthoptimizer.client.hud;

import com.PinkCats.bandwidthoptimizer.Bandwidthoptimizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
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
            return;
        }

        BandwidthOptimizerHudOverlayCore.CachedHud hud = BandwidthOptimizerHudOverlayCore.currentHud(minecraft);
        if (hud.lines().isEmpty()) {
            return;
        }

        int x = 6;
        int y = 6;

        GuiGraphicsExtractor guiGraphics = event.getGuiGraphics();
        guiGraphics.fill(x, y, x + hud.boxWidth(), y + hud.boxHeight(), 0xA0101018);
        guiGraphics.fill(x, y, x + hud.boxWidth(), y + 1, 0xFF66D9EF);
        for (int index = 0; index < hud.lines().size(); index++) {
            String line = hud.lines().get(index);
            guiGraphics.textRenderer().accept(x + 5, y + 4 + index * hud.lineHeight(), Component.literal(line));
        }
    }

    public static boolean isEnabled() {
        return BandwidthOptimizerHudOverlayCore.isEnabled();
    }

    public static void setEnabled(boolean enabled) {
        BandwidthOptimizerHudOverlayCore.setEnabled(enabled);
    }
}
