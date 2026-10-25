package com.PinkCats.bandwidthoptimizer.networkprobe;

import com.PinkCats.bandwidthoptimizer.mixin.minecraft.ConnectionAccessor;
import io.netty.channel.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.UUID;

/** Client-thread adapter; no Screen or connection is retained in the analysis state. */
public final class NetworkAnalysisClient {
    public static final NetworkAnalysisFlow FLOW=new NetworkAnalysisFlow();
    private static boolean openRequested,wasActive;
    private static int resultPollTicks;
    private NetworkAnalysisClient() {}
    public static NetworkAnalysisFlow.Endpoint endpoint() {
        Minecraft mc=Minecraft.getInstance();
        if(mc.getConnection()==null || mc.isLocalServer()) return null;
        Channel channel=((ConnectionAccessor)mc.getConnection().getConnection()).bandwidthoptimizer$getChannel();
        if(channel==null || !channel.isActive()) return null;
        return new NetworkAnalysisFlow.Endpoint() {
            public String channelId(){return channel.id().asLongText();}
            public long generation(){return NetworkProbeService.generation(channel);}
            public boolean ready(){return mc.level!=null && mc.player!=null && mc.level.hasChunkAt(mc.player.blockPosition());}
            public boolean supported(){return NetworkAnalysisPlatform.supported();}
            public NetworkProbeRequest request(){return NetworkProbeService.request(channel);}
            public void cancel(UUID id){NetworkProbeService.cancel(channel,id);}
            public NetworkProbeProgress progress(){return NetworkProbeService.progress(channel);}
        };
    }
    public static void tick() {
        // The disabled path does not inspect the connection, allocate an endpoint or read world state.
        if(FLOW.armed() || FLOW.active() || openRequested) FLOW.tick(endpoint(),System.nanoTime());
        else if(resultPollTicks>0) {FLOW.tick(null,System.nanoTime());resultPollTicks--;}
        if(wasActive && !FLOW.active()) {
            // Cancel/close can reach the UI before the event loop publishes its partial result.
            // Poll only the bounded result store for at most 100 ticks, without retaining or
            // inspecting the old/new connection. Never restart a test while waiting.
            if(FLOW.ticket()!=null && FLOW.result()==null) resultPollTicks=100;
            NetworkAnalysisPlatform.finished();
        }
        if(FLOW.result()!=null) resultPollTicks=0;
        wasActive=FLOW.active();
        if(openRequested) { openRequested=false; open(NetworkAnalysisPlatform.screen()); }
    }
    public static void requestOpen(){openRequested=true;}
    public static void open(Screen parent){NetworkAnalysisPlatform.screen(new NetworkAnalysisScreen(parent));}
    public static void retry(){FLOW.retry(endpoint(),System.nanoTime());}
    public static void cancel(){FLOW.cancel(endpoint());}
    public static Component toggleLabel(){return Component.translatable(FLOW.armed()?"bo.network.selected":"bo.network.unselected");}
}
