package com.PinkCats.bandwidthoptimizer.networkprobe;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
public final class NetworkAnalysisScreen extends NetworkAnalysisScreenBase {
 public NetworkAnalysisScreen(Screen parent) {super(parent);}
 @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta) {
  g.fill(0,0,width,height,0xE0101820);
  super.extractRenderState(g,mouseX,mouseY,delta);
  for(var rect:chartRects()) g.fill(rect.x1(),rect.y1(),rect.x2(),rect.y2(),rect.color());
  for(var line:layout(mouseX,mouseY)) g.textRenderer().accept(line.x(),line.y(),line.text());
 }
}
