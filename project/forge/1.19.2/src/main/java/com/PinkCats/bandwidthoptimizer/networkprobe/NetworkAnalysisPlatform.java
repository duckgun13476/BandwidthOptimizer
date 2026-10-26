package com.PinkCats.bandwidthoptimizer.networkprobe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
/** Version-specific UI and optional channel capability bridge. */
public final class NetworkAnalysisPlatform {
 private NetworkAnalysisPlatform() {}
 public static net.minecraft.client.gui.screens.Screen screen() {return Minecraft.getInstance().screen;}
 public static void screen(net.minecraft.client.gui.screens.Screen screen) {Minecraft.getInstance().setScreen(screen);}
 public static boolean supported() {var mc=Minecraft.getInstance(); return mc.getConnection()!=null && NetworkProbePackets.canSendClient(mc.getConnection().getConnection());}
 public static Button button(int x,int y,int w,Component label,Runnable action) {return button(x,y,w,20,label,action);}
 public static Button button(int x,int y,int w,int h,Component label,Runnable action) {return new Button(x,y,w,h,label,b -> action.run());}
 public static Button menuButton(int x,int y,int w,Component label,Component description,Runnable action) {return new Button(x,y,w,16,label,b -> action.run(),(b,pose,mx,my) -> {var screen=screen();if(screen!=null) screen.renderTooltip(pose,description,mx,my);});}
 public static Button toggle(int x,int y,int w) {return new Button(x,y,w,16,NetworkAnalysisClient.compactToggleLabel(),b -> {NetworkAnalysisClient.FLOW.armed(!NetworkAnalysisClient.FLOW.armed());b.setMessage(NetworkAnalysisClient.compactToggleLabel());},(b,pose,mx,my) -> {var screen=screen();if(screen!=null) screen.renderTooltip(pose,NetworkAnalysisClient.toggleLabel(),mx,my);});}
 public static void finished() {var mc=Minecraft.getInstance();if(mc.player!=null) mc.player.displayClientMessage(Component.translatable("bo.network.finished").withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/bandwidthoptimizer networkresults")).withUnderlined(true)),false);}
}
