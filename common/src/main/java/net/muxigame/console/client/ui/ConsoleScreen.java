package net.muxigame.console.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

abstract class ConsoleScreen extends Screen {
    private static final ResourceLocation BACKGROUND=ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");
    static final int CREAM = 0xff404040, AMBER = 0xff8b3b18, EDGE = 0xff606060;
    protected int left, top, panelWidth, panelHeight;
    protected ConsoleScreen(Component title) { super(title); }
    protected void layout(int preferredWidth, int preferredHeight) {
        panelWidth = Math.max(220, Math.min(preferredWidth, width - 20));
        panelHeight = Math.max(180, Math.min(preferredHeight, height - 20));
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
    }
    protected ConsoleButton button(int x, int y, int w, String label, net.minecraft.client.gui.components.Button.OnPress action) {
        return addRenderableWidget(new ConsoleButton(x, y, w, 20, Component.literal(label), action));
    }
    protected void panel(GuiGraphics graphics, String subtitle) {
        graphics.fill(0, 0, width, height, 0x80000000);
        graphics.fill(left+7,top+7,left+panelWidth-7,top+panelHeight-7,0xffc6c6c6);
        // Reuse the ordinary container frame without drawing unused inventory slots.
        frame(graphics,0,0,7,7,0,0,7,7);
        frame(graphics,7,0,panelWidth-14,7,7,0,162,7);
        frame(graphics,panelWidth-7,0,7,7,169,0,7,7);
        frame(graphics,0,7,7,panelHeight-14,0,7,7,208);
        frame(graphics,panelWidth-7,7,7,panelHeight-14,169,7,7,208);
        frame(graphics,0,panelHeight-7,7,7,0,215,7,7);
        frame(graphics,7,panelHeight-7,panelWidth-14,7,7,215,162,7);
        frame(graphics,panelWidth-7,panelHeight-7,7,7,169,215,7,7);
        graphics.drawString(font, title, left + 14, top + 14, CREAM, false);
        if (!subtitle.isEmpty()) line(graphics, subtitle, left + 14, top + 39, panelWidth - 28, EDGE);
    }
    private void frame(GuiGraphics graphics,int x,int y,int w,int h,int u,int v,int sourceWidth,int sourceHeight){
        graphics.blit(BACKGROUND,left+x,top+y,w,h,(float)u,(float)v,sourceWidth,sourceHeight,256,256);
    }
    protected void line(GuiGraphics graphics, String value, int x, int y, int maxWidth, int color) {
        graphics.drawString(font, font.plainSubstrByWidth(value, Math.max(0, maxWidth)), x, y, color, false);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) { }
}
