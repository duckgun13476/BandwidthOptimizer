package networkprobeacceptance;

import com.PinkCats.bandwidthoptimizer.networkprobe.*;
import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ConnectionAccessor;
import com.google.gson.JsonObject;
import io.netty.channel.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.UUID;

/** Actual PLAY lifecycle through two loopback Velocity backends; no synthetic login packets. */
final class ProbeLifecycle {
    private static int stage,ticks,stageTick;
    private static long changedAt,completedAt,generation;
    private static String originalChannel,completedChannel;
    private static UUID first,second;
    private static final JsonObject proof=new JsonObject();
    private static Channel channel(Minecraft mc) {return mc.getConnection()==null?null:((ConnectionAccessor)mc.getConnection().getConnection()).bandwidthoptimizer$getChannel();}
    private static boolean ready(Minecraft mc) {return mc.player!=null && mc.level!=null && mc.level.hasChunkAt(mc.player.blockPosition());}
    private static void require(boolean b,String reason) {if(!b) throw new AssertionError(reason);}
    static void install() {
        NetworkAnalysisClient.FLOW.armed(true);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post ignored) -> {
            var mc=Minecraft.getInstance(); var flow=NetworkAnalysisClient.FLOW; var channel=channel(mc);long now=System.nanoTime();ticks++;
            if(ticks>4500) throw new AssertionError("Lifecycle timeout stage="+stage+" state="+flow.state());
            switch(stage) {
                case 0 -> {
                    if(flow.progress()!=null && flow.progress().sentBytes()>=32768 && flow.state().equals("running")) {
                        first=flow.ticket().id();originalChannel=channel.id().asLongText();generation=NetworkProbeService.generation(channel);
                        changedAt=now;mc.getConnection().sendCommand("server two");stage=1;
                    }
                }
                case 1 -> {
                    if(channel!=null && NetworkProbeService.generation(channel)>generation && ready(mc) && flow.state().equals("connection_changed")) {
                        require(originalChannel.equals(channel.id().asLongText()),"Proxy frontend TCP must persist through switch");
                        require(flow.result()!=null && flow.result().key().testId().equals(first),"Interrupted result must retain old test identity");
                        proof.addProperty("active_backend_switch_stopped_test",true);proof.addProperty("frontend_socket_persisted",true);stage=2;
                    }
                }
                case 2 -> {
                    require(!flow.active() && flow.ticket().id().equals(first),"Backend switch automatically restarted test");
                    if(now-changedAt>36_000_000_000L) {NetworkAnalysisClient.retry();stage=3;}
                }
                case 3 -> {
                    if(flow.state().equals("completed") && flow.result()!=null) {
                        second=flow.ticket().id();require(!second.equals(first),"Explicit retry needs a fresh test id");
                        require(flow.result().key().testId().equals(second),"Retry result owner mismatch");
                        require(flow.result().json().get("peer_summary_status").getAsString().equals("received"),"Second backend summary missing");
                        completedChannel=channel.id().asLongText();completedAt=now;proof.addProperty("second_backend_completed",true);
                        mc.level.disconnect();mc.disconnect(new JoinMultiplayerScreen(new TitleScreen()));stageTick=ticks;stage=4;
                    }
                }
                case 4 -> {
                    if(channel==null && ticks-stageTick>=20) {
                        String address=System.getProperty("networkprobe.reconnectAddress","127.0.0.1:25854");
                        ConnectScreen.startConnecting(mc.screen,mc,ServerAddress.parseString(address),new ServerData("Lifecycle reconnect",address,ServerData.Type.OTHER),false,null);
                        stage=5;stageTick=ticks;
                    }
                }
                case 5 -> {
                    if(ready(mc) && ticks-stageTick>120) {
                        require(!channel.id().asLongText().equals(completedChannel),"Actual reconnect needs a new channel");
                        require(!flow.active() && flow.ticket().id().equals(second) && flow.result().key().testId().equals(second),"Reconnect contaminated old result or restarted test");
                        require(NetworkProbeService.resultKey(channel)==null,"Reconnect created a diagnostic session");
                        proof.addProperty("actual_reconnect_no_automatic_test",true);proof.addProperty("completed_result_frozen",true);stage=6;
                    }
                }
                case 6 -> {if(now-completedAt>36_000_000_000L) {NetworkAnalysisClient.retry();stage=7;}}
                case 7 -> {
                    if(flow.state().equals("running") && flow.progress()!=null && flow.progress().sentBytes()>=32768) {
                        NetworkAnalysisClient.cancel();stage=8;stageTick=ticks;
                    }
                }
                case 8 -> {
                    if(ticks-stageTick>=30) {
                        require(flow.state().equals("cancelled") && !flow.active(),"Cancellation did not stay cancelled");
                        require(NetworkProbeService.resultKey(channel)==null,"Cancellation retained an active session");
                        require(ready(mc),"Cancellation disconnected the game");
                        proof.addProperty("active_cancel_released_session",true);proof.addProperty("world_still_loaded",true);
                        NetworkAnalysisClient.open(mc.screen);stage=9;stageTick=ticks;
                    }
                }
                case 9 -> {
                    if(ticks-stageTick>=20) {
                        net.minecraft.client.Screenshot.grab(mc.gameDirectory,mc.getMainRenderTarget(),message -> {});
                        proof.addProperty("status","PASS");proof.addProperty("first_test_id",first.toString());proof.addProperty("second_test_id",second.toString());
                        ProbeAcceptance.write("lifecycle-done.json",proof);mc.stop();stage=10;
                    }
                }
            }
        });
    }
}
