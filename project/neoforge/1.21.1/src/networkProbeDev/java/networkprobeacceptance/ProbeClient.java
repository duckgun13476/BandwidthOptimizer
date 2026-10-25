package networkprobeacceptance;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.minecraft.client.Minecraft;
final class ProbeClient {
    private static int ticks;
    private static boolean everLoaded;
    static void install() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            var mc=Minecraft.getInstance();
            if(!everLoaded && mc.level!=null && mc.player!=null) {
                everLoaded=true;
                var loaded=new com.google.gson.JsonObject();
                loaded.addProperty("world_and_player_ever_loaded",true);
                ProbeAcceptance.write("client-world-loaded.json",loaded);
            }
            if(++ticks%20!=0) return;
            var report=ProbeAcceptance.read("client");
            if(report!=null) {
                report.addProperty("world_and_player_loaded",mc.level!=null && mc.player!=null);
                report.addProperty("world_and_player_ever_loaded",everLoaded);
                ProbeAcceptance.write("client-result.json",report);
                mc.stop();
            }
            if(ticks>3000) mc.stop();
        });
    }
}
