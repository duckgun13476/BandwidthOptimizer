package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.connection.ConnectionDisconnectClassifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenDisconnectMixin {

    @Inject(method = "onDisconnect()V", at = @At("HEAD"))
    private void bandwidthoptimizer$markLocalDisconnect(CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener packetListener = minecraft == null ? null : minecraft.getConnection();
        if (packetListener == null || packetListener.getConnection() == null) {
            return;
        }
        ConnectionDisconnectClassifier.markLocalDisconnect(
                ((ConnectionAccessor) packetListener.getConnection()).bandwidthoptimizer$getChannel()
        );
    }
}
