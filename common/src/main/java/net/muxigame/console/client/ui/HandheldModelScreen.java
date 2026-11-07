package net.muxigame.console.client.ui;

import com.google.gson.JsonObject;
import java.util.*;
import net.muxigame.console.domain.Platform;
import net.muxigame.console.mc.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Empty hardware can be configured without changing or creating cartridge contents. */
public final class HandheldModelScreen extends ConsoleScreen {
    private final JsonObject context;private final List<Integer> bodies=new ArrayList<>();private int index;
    public HandheldModelScreen(JsonObject context){super(Component.literal("掌机机型"));this.context=context.deepCopy();bodies.add(0);for(var b:HardwareCatalog.bodies())if(b.handheld())bodies.add(b.id());int found=bodies.indexOf(context.get("model").getAsInt());index=found<0?0:found;}
    @Override protected void init(){layout(400,206);button(left+14,top+63,70,"上一台",b->{index=(index+bodies.size()-1)%bodies.size();rebuildWidgets();});button(left+panelWidth-84,top+63,70,"下一台",b->{index=(index+1)%bodies.size();rebuildWidgets();});button(left+14,top+99,panelWidth-28,"按键与手柄",b->minecraft.setScreen(new ControlsScreen(this,platform())));button(left+14,top+panelHeight-36,70,"关闭",b->onClose());button(left+panelWidth-114,top+panelHeight-36,100,"采用此机型",b->{JsonObject request=context.deepCopy();request.addProperty("model",bodies.get(index));Wire.command("handheld_body",request);onClose();});}
    private Platform platform(){var b=HardwareCatalog.body(bodies.get(index));return b==null?Platform.NES:b.platform();}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partial){panel(g,"选择空掌机外壳，潜行使用以装入兼容卡带");var body=HardwareCatalog.body(bodies.get(index));String name=body==null?"通用掌机":body.name();g.drawCenteredString(font,name,left+panelWidth/2,top+69,CREAM);line(g,"装入卡带后，外壳保持所选机型",left+14,top+132,panelWidth-28,EDGE);super.render(g,mouseX,mouseY,partial);}
}
