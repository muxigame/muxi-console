package net.muxigame.console.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

abstract class ConsoleScreen extends Screen {
    static final int INK = 0xff203b42, CREAM = 0xffe4e0d1, AMBER = 0xffedb66a, EDGE = 0xff708c89;
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
        graphics.fill(0, 0, width, height, 0x850f2026);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, EDGE);
        graphics.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, INK);
        graphics.fill(left + 14, top + 12, left + 42, top + 15, AMBER);
        graphics.drawString(font, title, left + 14, top + 23, CREAM, false);
        if (!subtitle.isEmpty()) line(graphics, subtitle, left + 14, top + 39, panelWidth - 28, EDGE);
    }
    protected void line(GuiGraphics graphics, String value, int x, int y, int maxWidth, int color) {
        graphics.drawString(font, font.plainSubstrByWidth(value, Math.max(0, maxWidth)), x, y, color, false);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) { }
}
