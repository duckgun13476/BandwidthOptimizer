package networkprobeacceptance;
import com.PinkCats.bandwidthoptimizer.networkprobe.*;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ConnectionAccessor;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
final class ProbePolicy {
    private static int ticks,seen=-1;
    private static boolean cancelled;
    static void install() {
        NetworkAnalysisClient.FLOW.armed(true);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post ignored) -> {
            ticks++; var mc=Minecraft.getInstance();var flow=NetworkAnalysisClient.FLOW;
            String policy=System.getProperty("networkprobe.policy");
            String expected=policy.equals("unsupported")?"unsupported":policy.equals("cancel")?"cancelled":"server_disabled";
            if(policy.equals("cancel") && !cancelled && flow.state().equals("running") && flow.progress()!=null && flow.progress().sentBytes()>=32768) {
                NetworkAnalysisClient.cancel();cancelled=true;
            }
            if(ticks>1000) throw new AssertionError("Policy timeout "+flow.state());
            if(seen<0 && flow.state().equals(expected) && (!policy.equals("cancel") || flow.result()!=null)) {
                if(mc.level==null || mc.player==null || mc.getConnection()==null) throw new AssertionError("Policy disconnected game");
                var channel=((ConnectionAccessor)mc.getConnection().getConnection()).bandwidthoptimizer$getChannel();
                if(NetworkProbeService.resultKey(channel)!=null || NetworkProbeService.progress(channel)!=null) throw new AssertionError("Rejected policy created bulk session");
                if(expected.equals("unsupported") && flow.ticket()!=null) throw new AssertionError("Absent capability sent a request");
                NetworkAnalysisClient.open(mc.screen);seen=ticks;
            }
            if(seen>=0 && ticks-seen>=20) {
                var proof=new com.google.gson.JsonObject();proof.addProperty("status","PASS");proof.addProperty("state",flow.state());proof.addProperty("world_loaded",mc.level!=null && mc.player!=null);
                proof.addProperty("no_bulk_session",true);proof.addProperty("request_created",flow.ticket()!=null);
                if(policy.equals("cancel")) {
                    if(flow.result()==null || !flow.result().key().testId().equals(flow.ticket().id()) || !flow.result().json().get("outcome").getAsString().equals("operator_cancelled")) throw new AssertionError("Cancelled result lost or wrong owner");
                    proof.addProperty("cancelled_partial_result_retained",true);
                }
                net.minecraft.client.Screenshot.grab(mc.gameDirectory,mc.getMainRenderTarget(),message -> {});
                ProbeAcceptance.write("policy-done.json",proof);mc.stop();
            }
        });
    }
}
