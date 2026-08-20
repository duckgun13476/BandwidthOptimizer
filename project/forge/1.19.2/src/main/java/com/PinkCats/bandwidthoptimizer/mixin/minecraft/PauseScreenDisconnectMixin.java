package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.connection.ConnectionDisconnectClassifier;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ConnectionAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenDisconnectMixin {

    @Inject(method = "lambda$createPauseMenu$11", at = @At("HEAD"), remap = false)
    private void bandwidthoptimizer$markLocalDisconnect(Button button, CallbackInfo ci) {
        ClientPacketListener packetListener = Minecraft.getInstance().getConnection();
        if (packetListener == null || packetListener.getConnection() == null) {
            return;
        }
        ConnectionDisconnectClassifier.markLocalDisconnect(
                ((ConnectionAccessor) packetListener.getConnection()).bandwidthoptimizer$getChannel()
        );
    }
}
