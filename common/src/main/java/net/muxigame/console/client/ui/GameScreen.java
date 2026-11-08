package net.muxigame.console.client.ui;

import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** A non-pausing input surface in both world-TV and fitted fullscreen modes. */
public class GameScreen extends Screen {
    protected final ClientActions actions;
    private final int port;
    private boolean worldView;
    private boolean closed;
    private boolean fHeld;
    public GameScreen(ClientActions actions, int port) { this(actions,port,false); }
    protected GameScreen(ClientActions actions,int port,boolean worldView) {
        super(Component.translatable("ui.muxi_console.title"));
        if (port < 0 || port > 3) throw new IllegalArgumentException("Player seat outside range");
        this.actions = Objects.requireNonNull(actions); this.port = port;this.worldView=worldView;
    }
    public int port() { return port; }
    @Override protected void init(){net.minecraft.client.KeyMapping.releaseAll();}
    protected String worldLabel(){return actions instanceof net.muxigame.console.client.ConsoleClient client&&client.handheldSession()?"手持视角":"电视视角";}
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
        graphics.drawString(font, "P" + (port + 1) + "  ·  F 切换" + (worldView ? "全屏" : worldLabel()) + "  ·  F8 释放操作  ·  Esc 保存离开",
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
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button==1&&worldView&&actions instanceof net.muxigame.console.client.ConsoleClient client&&client.handheldSession()){worldView=false;return true;}
        return true;
    }
    @Override public void onClose() {
        if (!closed) { closed = true; actions.leave(); }
        if (minecraft != null && minecraft.screen == this) minecraft.setScreen(null);
    }
}
