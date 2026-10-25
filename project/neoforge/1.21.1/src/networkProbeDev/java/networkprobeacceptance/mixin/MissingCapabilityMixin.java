package networkprobeacceptance.mixin;
import com.PinkCats.bandwidthoptimizer.networkprobe.NetworkProbePackets;
import net.neoforged.bus.api.IEventBus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Removes only the optional diagnostic capability in the isolated server fixture. */
@Mixin(value=NetworkProbePackets.class,remap=false)
abstract class MissingCapabilityMixin {
    @Inject(method="register",at=@At("HEAD"),cancellable=true,remap=false)
    private static void absent(IEventBus bus,CallbackInfo ci) {if(Boolean.getBoolean("networkprobe.noCapability")) ci.cancel();}
}
