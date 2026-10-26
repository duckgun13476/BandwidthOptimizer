package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.networkprobe.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({JoinMultiplayerScreen.class,PauseScreen.class})
public abstract class NetworkAnalysisMenuMixin extends Screen {
    protected NetworkAnalysisMenuMixin(Component title){super(title);}
    @Inject(method="init",at=@At("TAIL"))
    private void bandwidthoptimizer$networkAnalysisMenu(CallbackInfo ci){
        Screen self=(Screen)(Object)this;
        if(self instanceof JoinMultiplayerScreen) {
            // Rebuild the label through the button callback without retaining this menu in product state.
            Button toggle=NetworkAnalysisPlatform.toggle(width-42,2,18);
            addRenderableWidget(toggle);
        }
        Button results=NetworkAnalysisPlatform.menuButton(width-22,2,18,Component.literal("N"),Component.translatable("bo.network.open"),()->NetworkAnalysisClient.open(self));
        addRenderableWidget(results);
    }
}
