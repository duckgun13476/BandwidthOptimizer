package networkprobeacceptance;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.minecraft.client.Minecraft;
final class ProbeClient {
    private static int ticks;
    private static boolean everLoaded;
    private static int loadedTicks;
    private static int resultTick=-1;
    private static com.PinkCats.bandwidthoptimizer.networkprobe.NetworkProbeRequest request;
    static void install() {
        if(!System.getProperty("networkprobe.policy","").isEmpty()) {ProbePolicy.install();return;}
        if(Boolean.getBoolean("networkprobe.lifecycle")) {ProbeLifecycle.install();return;}
        if(Boolean.getBoolean("networkprobe.clientRequest"))
            com.PinkCats.bandwidthoptimizer.networkprobe.NetworkAnalysisClient.FLOW.armed(true);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            var mc=Minecraft.getInstance();
            if(Boolean.getBoolean("networkprobe.clientRequest") && mc.level!=null && mc.player!=null && mc.getConnection()!=null
                    ) {
                request=com.PinkCats.bandwidthoptimizer.networkprobe.NetworkAnalysisClient.FLOW.ticket();
            }
            if(!everLoaded && mc.level!=null && mc.player!=null) {
                everLoaded=true;
                var loaded=new com.google.gson.JsonObject();
                loaded.addProperty("world_and_player_ever_loaded",true);
                ProbeAcceptance.write("client-world-loaded.json",loaded);
            }
            if(++ticks%20!=0) return;
            var report=ProbeAcceptance.read("client");
            if(report!=null && resultTick<0) {
                report.addProperty("world_and_player_loaded",mc.level!=null && mc.player!=null);
                report.addProperty("world_and_player_ever_loaded",everLoaded);
                if(mc.getConnection()!=null) {
                    var channel=((com.PinkCats.bandwidthoptimizer.mixin.minecraft.ConnectionAccessor)mc.getConnection().getConnection()).bandwidthoptimizer$getChannel();
                    report.addProperty("game_connection_remote_address",channel.remoteAddress().toString());
                }
                if(request!=null) {
                    report.addProperty("client_request_id",request.id().toString());
                    report.addProperty("client_request_status",request.status());
                    var flow=com.PinkCats.bandwidthoptimizer.networkprobe.NetworkAnalysisClient.FLOW;
                    report.addProperty("automatic_flow_state",flow.state());
                    report.addProperty("automatic_result_retained",flow.result()!=null && flow.result().key().testId().equals(request.id()));
                    com.PinkCats.bandwidthoptimizer.networkprobe.NetworkAnalysisClient.open(mc.screen);
                }
                ProbeAcceptance.write("client-result.json",report);
                resultTick=ticks;
            }
            if(resultTick>=0 && ticks>=resultTick+20) {
                if(request!=null) {
                    if(!(mc.screen instanceof com.PinkCats.bandwidthoptimizer.networkprobe.NetworkAnalysisScreen)) throw new AssertionError("Result screen not open");
                    net.minecraft.client.Screenshot.grab(mc.gameDirectory,mc.getMainRenderTarget(),message -> {});
                    var proof=new com.google.gson.JsonObject(); proof.addProperty("rendered_ticks",ticks-resultTick);
                    proof.addProperty("screen",mc.screen.getClass().getName()); ProbeAcceptance.write("result-screen.json",proof);
                    try {
                        var field=com.PinkCats.bandwidthoptimizer.networkprobe.NetworkAnalysisScreenBase.class.getDeclaredField("text");
                        field.setAccessible(true);
                        var text=new com.google.gson.JsonArray();
                        for(Object row:(java.util.List<?>)field.get(mc.screen)) {
                            if(text.size()>=256) throw new AssertionError("Unbounded result text");
                            text.add(((net.minecraft.network.chat.Component)row).getString());
                        }
                        proof.add("text",text);ProbeAcceptance.write("result-screen.json",proof);
                    } catch(ReflectiveOperationException failure) {throw new AssertionError(failure);}
                }
                mc.stop();
            }
            if(ticks>3000) mc.stop();
        });
    }
}
