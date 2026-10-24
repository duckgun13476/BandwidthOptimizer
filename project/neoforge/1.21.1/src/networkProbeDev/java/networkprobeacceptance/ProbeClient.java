package networkprobeacceptance;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.minecraft.client.Minecraft;
final class ProbeClient {
    private static int ticks;
    static void install() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            if(++ticks%20!=0) return;
            var report=ProbeAcceptance.read("client");
            var mc=Minecraft.getInstance();
            if(report!=null) {
                report.addProperty("world_and_player_loaded",mc.level!=null && mc.player!=null);
                ProbeAcceptance.write("client-result.json",report);
                mc.stop();
            }
            if(ticks>3000) mc.stop();
        });
    }
}
