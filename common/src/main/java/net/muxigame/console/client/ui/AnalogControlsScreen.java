package net.muxigame.console.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.muxigame.console.client.input.InputProfiles;
import net.muxigame.console.domain.Platform;
import org.lwjgl.glfw.GLFW;

public final class AnalogControlsScreen extends ConsoleScreen {
    private final Screen parent;private final Platform platform;private final InputProfiles profiles;private int pending=-1;private String message="";
    private static final String[] NAMES={"左摇杆 ←","左摇杆 →","左摇杆 ↑","左摇杆 ↓","右摇杆 ←","右摇杆 →","右摇杆 ↑","右摇杆 ↓"};
    public AnalogControlsScreen(Screen parent,Platform platform,InputProfiles profiles){super(Component.literal("摇杆的键盘按键"));this.parent=parent;this.platform=platform;this.profiles=profiles;}
    @Override protected void init(){layout(440,248);for(int i=0;i<8;i++){int direction=i,key=profiles.profile(platform).axisBinding(i);String label=key<0?"未绑定":ControlsScreen.keyName(key);button(left+14+(i/4)*(panelWidth/2-6),top+58+(i%4)*25,panelWidth/2-22,NAMES[i]+" · "+(pending==i?"按新按键…":label),b->{pending=direction;message="Esc 取消，Delete 清除；已占用的按键会从原功能移除";rebuildWidgets();});}button(left+14,top+panelHeight-35,70,"返回",b->onClose());button(left+panelWidth-90,top+panelHeight-35,76,"保存",b->{try{profiles.save();message="已保存";}catch(Exception e){message="保存失败";}});}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(pending<0)return super.keyPressed(key,scan,modifiers);if(key==GLFW.GLFW_KEY_ESCAPE){pending=-1;rebuildWidgets();return true;}try{profiles.bindAxis(platform,pending,key==GLFW.GLFW_KEY_DELETE?-1:key);pending=-1;message="已修改，点击保存写入配置";rebuildWidgets();}catch(IllegalArgumentException e){message="此键由游戏视角保留，请换一个";}return true;}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public void render(GuiGraphics g,int x,int y,float partial){panel(g,"键盘模拟摇杆；N64 默认左摇杆 WASD，十字键为方向键");line(g,message,left+14,top+panelHeight-57,panelWidth-28,AMBER);super.render(g,x,y,partial);}
}
