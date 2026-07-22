package com.PinkCats.bandwidthoptimizer.mixin.minecraft;

import com.PinkCats.bandwidthoptimizer.gate.integration.create.CreateBlockEntityUpdateGate;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerCommandMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleChatCommand", at = @At("HEAD"))
    private void bandwidthoptimizer$markTeleportCommandBootstrap(
            ServerboundChatCommandPacket packet,
            CallbackInfo callbackInfo
    ) {
        if (packet == null || !bandwidthoptimizer$isTeleportLikeCommand(packet.command())) {
            return;
        }
        CreateBlockEntityUpdateGate.markCommandTeleportBootstrap(this.player);
    }

    @Unique
    private static boolean bandwidthoptimizer$isTeleportLikeCommand(String command) {
        if (command == null) {
            return false;
        }
        String normalized = command.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("tp ")
                || normalized.equals("tp")
                || normalized.startsWith("teleport ")
                || normalized.equals("teleport");
    }
}
