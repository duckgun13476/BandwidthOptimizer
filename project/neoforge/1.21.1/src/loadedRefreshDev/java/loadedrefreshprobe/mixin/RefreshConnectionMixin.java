package loadedrefreshprobe.mixin;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import loadedrefreshprobe.RefreshWireProbe;

@Mixin(value=Connection.class, priority=2000)
public abstract class RefreshConnectionMixin {
    @Inject(method="sendPacket", at=@At("HEAD"))
    private void probe(Packet<?> packet, PacketSendListener listener, boolean flush, CallbackInfo ci) {
        RefreshWireProbe.observe(packet, 0);
    }
}
