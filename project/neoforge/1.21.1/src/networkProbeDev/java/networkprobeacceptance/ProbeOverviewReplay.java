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
    private static boolean detailed;
    static void install() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event)->{
            Minecraft mc=Minecraft.getInstance();
            if(++ticks>1200) {mc.stop();throw new AssertionError("Overview replay timed out");}
            if(opened<0 && mc.getOverlay()==null && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
                try {
                    JsonObject report=JsonParser.parseString(Files.readString(Path.of(System.getProperty("networkprobe.root"),"replay.json"))).getAsJsonObject();
                    var flow=NetworkAnalysisClient.FLOW;
                    var field=NetworkAnalysisFlow.class.getDeclaredField("result");field.setAccessible(true);
                    field.set(flow,new NetworkProbeResults.Result(new NetworkProbeResults.Key("fixture",UUID.randomUUID(),false),report.toString()));
                    field=NetworkAnalysisFlow.class.getDeclaredField("state");field.setAccessible(true);field.set(flow,"completed");
                    NetworkAnalysisClient.open(mc.screen); opened=ticks;
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
                proof.addProperty("details",detailed);proof.addProperty("screen",screen.getClass().getName());
                ProbeAcceptance.write(detailed?"overview-details.json":"overview-summary.json",proof);
                net.minecraft.client.Screenshot.grab(mc.gameDirectory,mc.getMainRenderTarget(),message->{});
                if(!detailed) {
                    if(rows.size()!=5) throw new AssertionError("Expected title/state and three actual abnormal findings: "+rows);
                    field=NetworkAnalysisScreenBase.class.getDeclaredField("detailsToggle");field.setAccessible(true);
                    ((Button)field.get(screen)).onPress();detailed=true;opened=ticks;
                } else {
                    if(rows.size()<=10) throw new AssertionError("Technical details missing");
                    // Exercise the actual widget again, verifying that normal rows disappear on return.
                    field=NetworkAnalysisScreenBase.class.getDeclaredField("detailsToggle");field.setAccessible(true);
                    ((Button)field.get(screen)).onPress();
                    field=NetworkAnalysisScreenBase.class.getDeclaredField("text");field.setAccessible(true);
                    if(((List<?>)field.get(screen)).size()!=5) throw new AssertionError("Overview return did not hide details");
                    mc.stop();
                }
            } catch(Exception failure) {throw new AssertionError(failure);}
        });
    }
}
