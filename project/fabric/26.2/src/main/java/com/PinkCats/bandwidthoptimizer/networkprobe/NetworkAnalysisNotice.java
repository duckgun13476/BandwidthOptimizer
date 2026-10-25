package com.PinkCats.bandwidthoptimizer.networkprobe;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
final class NetworkAnalysisNotice {
 private NetworkAnalysisNotice() {}
 static net.minecraft.client.gui.screens.Screen screen() {return Minecraft.getInstance().gui.screen();}
 static void screen(net.minecraft.client.gui.screens.Screen screen) {Minecraft.getInstance().gui.setScreen(screen);}
 static void show(Component message) {var mc=Minecraft.getInstance();mc.gui.hud.getChat().addClientSystemMessage(message);}
}
