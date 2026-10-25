package com.PinkCats.bandwidthoptimizer.networkprobe;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphics;
public final class NetworkAnalysisScreen extends NetworkAnalysisScreenBase {
 public NetworkAnalysisScreen(Screen parent) {super(parent);}
 @Override public void render(GuiGraphics g,int mouseX,int mouseY,float delta) {
  g.fill(0,0,width,height,0xE0101820);
  super.render(g,mouseX,mouseY,delta);
  for(var line:layout()) g.drawString(font,line.text(),line.x(),line.y(),0xFFFFFFFF,false);
 }
}
