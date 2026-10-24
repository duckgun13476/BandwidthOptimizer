package networkprobeacceptance;

import com.google.gson.*;
import java.nio.file.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod("networkprobeacceptance")
public final class ProbeAcceptance {
    static final Path ROOT=Path.of(System.getProperty("networkprobe.root"));
    private int ticks, playerSeen=-1, completeTick=-1;
    public ProbeAcceptance(IEventBus ignored) {
        if(FMLEnvironment.dist==Dist.CLIENT) ProbeClient.install();
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            ticks++;
            var server=e.getServer();
            if(!server.getPlayerList().getPlayers().isEmpty() && playerSeen<0) playerSeen=ticks;
            if(playerSeen>=0 && ticks==playerSeen+100)
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"bo network test NetProbe 1 16");
            if(ticks%20==0 && completeTick<0) {
                JsonObject report=read("server");
                if(report!=null) {
                    write("server-result.json",report);
                    completeTick=ticks;
                }
            }
            if(completeTick>=0 && ticks>completeTick+100) server.halt(false);
            if(ticks>2400) { write("server-timeout.json",new JsonObject()); server.halt(false); }
        });
    }
    static JsonObject read(String side) {
        Path file=ROOT.resolve(side+"/bandwidthoptimizer-native/diagnostics/network/probes.jsonl");
        if(!Files.exists(file)) return null;
        try {
            String text=Files.readString(file);
            int start=text.indexOf('{'), end=text.indexOf('\n',start);
            if(start<0) return null;
            return JsonParser.parseString(text.substring(start,end<0?text.length():end)).getAsJsonObject();
        } catch(Exception failure) { return null; }
    }
    static void write(String name,JsonObject value) {
        try { Files.writeString(ROOT.resolve(name),value.toString()); }
        catch(Exception failure) { throw new RuntimeException(failure); }
    }
}
