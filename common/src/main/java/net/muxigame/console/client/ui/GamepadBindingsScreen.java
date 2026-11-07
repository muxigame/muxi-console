package net.muxigame.console.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.muxigame.console.client.input.InputProfiles;
import net.muxigame.console.domain.Platform;
import org.lwjgl.glfw.GLFW;

/** Capture a physical press on the selected controller; logical masks are never used to learn a mapping. */
public final class GamepadBindingsScreen extends ConsoleScreen {
    private final Screen parent;private final Platform platform;private final InputProfiles profiles;private int pending=-1,previous,page;private String message="";
    private static final String[] BUTTONS={"A / Cross","B / Circle","X / Square","Y / Triangle","L1","R1","Back","Start","Guide","左杆按下","右杆按下","十字↑","十字→","十字↓","十字←","左扳机","右扳机"};
    public GamepadBindingsScreen(Screen parent,Platform platform,InputProfiles profiles){super(Component.literal("手柄按键绑定"));this.parent=parent;this.platform=platform;this.profiles=profiles;}
    @Override protected void init(){layout(476,264);for(int at=0;at<8;at++){int bit=page*8+at,physical=profiles.profile(platform).gamepadButton(bit);button(left+14+(at/4)*(panelWidth/2-6),top+58+(at%4)*25,panelWidth/2-22,InputProfiles.buttonName(platform,bit)+" · "+(pending==bit?"按手柄按键…":physical<0?"未绑定":BUTTONS[physical]),b->{pending=bit;previous=profiles.pressedGamepad(platform);message="按选中手柄的按钮或扳机；Esc 取消，Delete 清除";rebuildWidgets();});}button(left+14,top+169,100,page==0?"下一页":"上一页",b->{page=1-page;pending=-1;rebuildWidgets();});button(left+14,top+panelHeight-35,70,"返回",b->onClose());button(left+panelWidth-90,top+panelHeight-35,76,"保存",b->{try{profiles.save();message="已保存";}catch(Exception e){message="保存失败";}});}
    @Override public void tick(){super.tick();if(pending<0)return;int now=profiles.pressedGamepad(platform),pressed=now&~previous;previous=now;if(pressed!=0){profiles.bindGamepad(platform,pending,Integer.numberOfTrailingZeros(pressed));pending=-1;message="已修改，点击保存写入配置";rebuildWidgets();}}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(pending<0)return super.keyPressed(key,scan,modifiers);if(key==GLFW.GLFW_KEY_ESCAPE){pending=-1;rebuildWidgets();}else if(key==GLFW.GLFW_KEY_DELETE){profiles.bindGamepad(platform,pending,-1);pending=-1;rebuildWidgets();}return true;}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public void render(GuiGraphics g,int x,int y,float partial){panel(g,"在上级界面选择你的手柄，再点击要修改的功能");line(g,message,left+14,top+panelHeight-57,panelWidth-28,AMBER);super.render(g,x,y,partial);}
}
