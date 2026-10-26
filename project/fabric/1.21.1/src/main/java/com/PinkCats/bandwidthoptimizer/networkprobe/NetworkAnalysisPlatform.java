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
 public static boolean supported() {var mc=Minecraft.getInstance(); return mc.getConnection()!=null && net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(NetworkProbeBytePayload.TYPE);}
 public static Button button(int x,int y,int w,Component label,Runnable action) {return button(x,y,w,20,label,action);}
 public static Button button(int x,int y,int w,int h,Component label,Runnable action) {return Button.builder(label,b -> action.run()).bounds(x,y,w,h).build();}
 public static Button menuButton(int x,int y,int w,Component label,Component description,Runnable action) {return Button.builder(label,b -> action.run()).tooltip(net.minecraft.client.gui.components.Tooltip.create(description)).createNarration(s -> description.copy()).bounds(x,y,w,16).build();}
 public static Button toggle(int x,int y,int w) {return Button.builder(NetworkAnalysisClient.compactToggleLabel(),b -> {NetworkAnalysisClient.FLOW.armed(!NetworkAnalysisClient.FLOW.armed());b.setMessage(NetworkAnalysisClient.compactToggleLabel());b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(NetworkAnalysisClient.toggleLabel()));}).tooltip(net.minecraft.client.gui.components.Tooltip.create(NetworkAnalysisClient.toggleLabel())).createNarration(s -> NetworkAnalysisClient.toggleLabel().copy()).bounds(x,y,w,16).build();}
 public static void finished() {var mc=Minecraft.getInstance();if(mc.player!=null) mc.player.displayClientMessage(Component.translatable("bo.network.finished").withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/bandwidthoptimizer networkresults")).withUnderlined(true)),false);}
}
