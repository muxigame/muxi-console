package net.muxigame.console.client.ui;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.muxigame.console.domain.Platform;

/** UI boundary. Device/network/runtime ownership remains with the client coordinator. */
public interface ClientActions {
    void upload(JsonObject context, Path file, Platform selectedOrNull, boolean rights);
    default void upload(JsonObject context,Path file,List<Path> dependencies,Platform selectedOrNull,boolean rights){if(!dependencies.isEmpty())throw new UnsupportedOperationException("Arcade dependencies are not supported by this frontend");upload(context,file,selectedOrNull,rights);}
    void device(JsonObject context, String action, int port);
    void leave();
    void renderPicture(GuiGraphics graphics, int x, int y, int width, int height);
    String status();
    default void selectionChanged(){}
}
