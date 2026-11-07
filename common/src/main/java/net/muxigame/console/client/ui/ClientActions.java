package net.muxigame.console.client.ui;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import net.minecraft.client.gui.GuiGraphics;
import net.muxigame.console.domain.Platform;

/** UI boundary. Device/network/runtime ownership remains with the client coordinator. */
public interface ClientActions {
    void upload(JsonObject context, Path file, Platform selectedOrNull, boolean rights);
    void device(JsonObject context, String action, int port);
    void leave();
    void renderPicture(GuiGraphics graphics, int x, int y, int width, int height);
    String status();
    default void selectionChanged(){}
}
