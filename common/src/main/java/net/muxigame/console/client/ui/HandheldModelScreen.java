package net.muxigame.console.client.ui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Read-only compatibility screen for an old configuration packet; hardware changes require real crafting. */
public final class HandheldModelScreen extends ConsoleScreen {
    public HandheldModelScreen(JsonObject ignored){super(Component.translatable("ui.muxi_console.core"));}
    @Override protected void init(){
        layout(360,180);
        button(left+14,top+86,panelWidth-28,Component.translatable("ui.muxi_console.bindings").getString(),b->net.muxigame.console.client.input.NativeControls.openSettings(this));
        button(left+14,top+panelHeight-34,panelWidth-28,Component.translatable("gui.done").getString(),b->onClose());
    }
    @Override public void render(GuiGraphics graphics,int x,int y,float partial){
        panel(graphics,"");
        var lines=font.split(Component.translatable("ui.muxi_console.reshape_hint"),panelWidth-28);
        for(int i=0;i<Math.min(3,lines.size());i++)graphics.drawString(font,lines.get(i),left+14,top+45+i*font.lineHeight,CREAM,false);
        super.render(graphics,x,y,partial);
    }
}
