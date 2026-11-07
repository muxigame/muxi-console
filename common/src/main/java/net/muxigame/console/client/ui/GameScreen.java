package net.muxigame.console.client.ui;

import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** A non-pausing input surface in both world-TV and fitted fullscreen modes. */
public final class GameScreen extends Screen {
    private final ClientActions actions;
    private final int port;
    private boolean worldView;
    private boolean closed;
    private boolean fHeld;
    public GameScreen(ClientActions actions, int port) {
        super(Component.translatable("ui.muxi_console.title"));
        if (port < 0 || port > 3) throw new IllegalArgumentException("Player seat outside range");
        this.actions = Objects.requireNonNull(actions); this.port = port;
    }
    public int port() { return port; }
    public boolean worldView() { return worldView; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return true; }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) { }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!worldView) {
            graphics.fill(0, 0, width, height, 0xff091b20);
            int pictureWidth = Math.min(width - 24, (height - 46) * 4 / 3);
            int pictureHeight = pictureWidth * 3 / 4;
            actions.renderPicture(graphics, (width - pictureWidth) / 2, (height - pictureHeight - 18) / 2,
                    pictureWidth, pictureHeight);
        }
        graphics.fill(0, height - 24, width, height, 0xd0203b42);
        graphics.drawString(font, "P" + (port + 1) + "  ·  F 切换" + (worldView ? "全屏" : "电视视角") + "  ·  F8 外接屏幕  ·  Esc 离开",
                10, height - 16, ConsoleScreen.CREAM, false);
        String status = Objects.requireNonNullElse(actions.status(), "");
        if (!status.isBlank()) graphics.drawString(font, font.plainSubstrByWidth(status, width - 20), 10, 10, ConsoleScreen.AMBER, false);
    }
    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (key == GLFW.GLFW_KEY_F) { if (!fHeld) worldView = !worldView; fHeld = true; return true; }
        return true;
    }
    @Override public boolean keyReleased(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_F) fHeld = false;
        return true;
    }
    @Override public void onClose() {
        if (!closed) { closed = true; actions.leave(); }
        if (minecraft != null && minecraft.screen == this) minecraft.setScreen(null);
    }
}
