package net.muxigame.console.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Flat module controls use the same authored palette as our devices. */
final class ConsoleButton extends Button {
    ConsoleButton(int x, int y, int width, int height, Component label, OnPress action) {
        super(x, y, width, height, label, action, DEFAULT_NARRATION);
    }
    @Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int border = active && isHoveredOrFocused() ? ConsoleScreen.AMBER : ConsoleScreen.EDGE;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, border);
        graphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1,
                active && isHoveredOrFocused() ? 0xff38565b : 0xff203b42);
        var font = Minecraft.getInstance().font;
        String text = font.plainSubstrByWidth(getMessage().getString(), Math.max(0, width - 8));
        graphics.drawCenteredString(font, text, getX() + width / 2, getY() + (height - 8) / 2,
                active ? ConsoleScreen.CREAM : 0xff70847f);
    }
}
