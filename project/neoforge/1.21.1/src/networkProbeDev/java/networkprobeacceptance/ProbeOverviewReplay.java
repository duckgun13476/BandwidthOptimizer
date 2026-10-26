package networkprobeacceptance;

import com.PinkCats.bandwidthoptimizer.networkprobe.*;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

/** Opt-in render-only regression: replays a saved report without a server or network probe. */
final class ProbeOverviewReplay {
    private static int ticks,opened=-1;
    private static int step;
    static void install() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event)->{
            Minecraft mc=Minecraft.getInstance();
            if(++ticks>1200) {mc.stop();throw new AssertionError("Overview replay timed out");}
            if(opened<0 && mc.getOverlay()==null && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
                try {
                    var menu=mc.screen;
                    var entries=menu.children().stream().filter(child->child instanceof Button button && button.getMessage().getString().equals("N")).toList();
                    if(entries.size()!=1) throw new AssertionError("Expected exactly one compact menu entry, got "+entries.size());
                    ((Button)entries.get(0)).onPress();
                    var action=NetworkAnalysisScreenBase.class.getDeclaredField("action");action.setAccessible(true);
                    ((Button)action.get(mc.screen)).onPress();
                    if(!NetworkAnalysisClient.FLOW.state().equals("waiting_multiplayer") || !NetworkAnalysisClient.FLOW.armed()) throw new AssertionError("Menu start did not arm one multiplayer sample");
                    ((Button)action.get(mc.screen)).onPress();
                    if(NetworkAnalysisClient.FLOW.active() || NetworkAnalysisClient.FLOW.armed()) throw new AssertionError("Menu cancel left automatic sampling armed");
                    JsonObject menuProof=new JsonObject();menuProof.addProperty("single_entry",true);menuProof.addProperty("menu_start_waits_for_join",true);menuProof.addProperty("menu_cancel_disarms",true);ProbeAcceptance.write("menu-flow.json",menuProof);
                    JsonObject report=JsonParser.parseString(Files.readString(Path.of(System.getProperty("networkprobe.root"),"replay.json"))).getAsJsonObject();
                    var traceType=Class.forName("com.PinkCats.bandwidthoptimizer.networkprobe.NetworkProbeTrace");
                    var build=traceType.getDeclaredMethod("build",JsonObject.class);build.setAccessible(true);
                    report.add("trace",(JsonObject)build.invoke(null,report));
                    Path server=Path.of(System.getProperty("networkprobe.root"),"server-replay.json");
                    if(Files.exists(server)) report.getAsJsonObject("peer_summary").add("trace",(JsonObject)build.invoke(null,JsonParser.parseString(Files.readString(server)).getAsJsonObject()));
                    var flow=NetworkAnalysisClient.FLOW;
                    var field=NetworkAnalysisFlow.class.getDeclaredField("result");field.setAccessible(true);
                    field.set(flow,new NetworkProbeResults.Result(new NetworkProbeResults.Key("fixture",UUID.randomUUID(),false),report.toString()));
                    field=NetworkAnalysisFlow.class.getDeclaredField("state");field.setAccessible(true);field.set(flow,"completed");
                    NetworkAnalysisClient.open(menu); opened=ticks;
                } catch(Exception failure) {throw new AssertionError(failure);}
            }
            if(opened<0 || ticks-opened<30) return;
            try {
                if(!(mc.screen instanceof NetworkAnalysisScreenBase screen)) throw new AssertionError("Replay screen missing");
                var field=NetworkAnalysisScreenBase.class.getDeclaredField("text");field.setAccessible(true);
                JsonArray rows=new JsonArray();
                for(Object row:(List<?>)field.get(screen)) rows.add(((Component)row).getString());
                if(rows.size()>256) throw new AssertionError("Unbounded rows");
                JsonObject proof=new JsonObject();proof.add("text",rows);proof.addProperty("rendered_ticks",ticks-opened);
                proof.addProperty("step",step);proof.addProperty("screen",screen.getClass().getName());
                if(rows.toString().contains("NaN") || rows.toString().contains("bo.network.")) throw new AssertionError("Unresolved numeric/translated row: "+rows);
                if(step>=2 && step<=4) {
                    var rects=NetworkAnalysisScreenBase.class.getDeclaredMethod("chartRects");rects.setAccessible(true);
                    List<?> geometry=(List<?>)rects.invoke(screen);proof.addProperty("chart_rectangles",geometry.size());
                    if(geometry.size()<3 || geometry.size()>256) throw new AssertionError("Missing/unbounded actual chart geometry");
                }
                ProbeAcceptance.write(new String[]{"overview-summary.json","overview-details.json","chart-latency.json","chart-speed.json","chart-retransmissions.json","overview-return.json"}[step],proof);
                net.minecraft.client.Screenshot.grab(mc.gameDirectory,mc.getMainRenderTarget(),message->{});
                if(step==0) {
                    if(rows.size()!=4 || !rows.toString().contains("473.24") || rows.toString().contains("510.93")) throw new AssertionError("Quantified abnormal findings/normal filtering wrong: "+rows);
                    field=NetworkAnalysisScreenBase.class.getDeclaredField("detailsToggle");field.setAccessible(true);
                    ((Button)field.get(screen)).onPress();
                } else if(step==1) {
                    if(rows.size()<=10) throw new AssertionError("Technical details missing");
                    field=NetworkAnalysisScreenBase.class.getDeclaredField("detailsToggle");field.setAccessible(true);
                    ((Button)field.get(screen)).onPress();
                    field=NetworkAnalysisScreenBase.class.getDeclaredField("charts");field.setAccessible(true);field.setBoolean(screen,true);
                } else if(step<4) {
                    field=NetworkAnalysisScreenBase.class.getDeclaredField("next");field.setAccessible(true);((Button)field.get(screen)).onPress();
                } else if(step==4) {
                    field=NetworkAnalysisScreenBase.class.getDeclaredField("charts");field.setAccessible(true);field.setBoolean(screen,false);
                    field=NetworkAnalysisScreenBase.class.getDeclaredField("page");field.setAccessible(true);field.setInt(screen,0);
                } else {
                    if(rows.size()>8) throw new AssertionError("Overview return did not hide details");
                    mc.stop();
                }
                step++;opened=ticks;
            } catch(Exception failure) {throw new AssertionError(failure);}
        });
    }
}
