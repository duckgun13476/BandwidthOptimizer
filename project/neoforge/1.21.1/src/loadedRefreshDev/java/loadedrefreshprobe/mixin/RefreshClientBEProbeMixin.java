package loadedrefreshprobe.mixin;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ClientPacketListener.class)
public class RefreshClientBEProbeMixin {
    @Inject(method="handleBlockEntityData",at=@At("TAIL"))
    private void observed(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
        loadedrefreshprobe.RefreshClientBEProbe.observe(packet);
    }
}
