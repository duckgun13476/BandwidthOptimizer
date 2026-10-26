package com.PinkCats.bandwidthoptimizer.networkprobe;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.vertex.PoseStack;
public final class NetworkAnalysisScreen extends NetworkAnalysisScreenBase {
 public NetworkAnalysisScreen(Screen parent) {super(parent);}
 @Override public void render(PoseStack g,int mouseX,int mouseY,float delta) {
  net.minecraft.client.gui.GuiComponent.fill(g,0,0,width,height,0xE0101820);
  super.render(g,mouseX,mouseY,delta);
  for(var rect:chartRects()) net.minecraft.client.gui.GuiComponent.fill(g,rect.x1(),rect.y1(),rect.x2(),rect.y2(),rect.color());
  for(var line:layout()) font.drawShadow(g,line.text(),line.x(),line.y(),0xFFFFFFFF);
 }
}
