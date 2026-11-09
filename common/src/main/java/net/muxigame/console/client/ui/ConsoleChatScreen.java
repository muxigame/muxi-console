package net.muxigame.console.client.ui;

import java.util.Objects;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.muxigame.console.client.ConsoleClient;
import net.muxigame.console.client.ConsoleGameHud;
import org.lwjgl.glfw.GLFW;

/** Vanilla chat/editor/history/commands, returning to the same still-authorized game presentation. */
public final class ConsoleChatScreen extends ChatScreen {
    private final GameScreen parent;
    private final String room;
    private final long epoch;
    private final int openingKey,openingScan;
    private final String openingKeyName;
    private boolean openingKeyDown;
    public ConsoleChatScreen(GameScreen parent,String initial){
        this(parent,initial,GLFW.GLFW_KEY_UNKNOWN,-1);
    }
    public ConsoleChatScreen(GameScreen parent,String initial,int openingKey,int openingScan){
        super(initial);this.parent=Objects.requireNonNull(parent);
        var session=ConsoleClient.INSTANCE.snapshot();room=session.room();epoch=session.epoch();
        this.openingKey=openingKey;this.openingScan=openingScan;
        openingKeyName=openingKey!=GLFW.GLFW_KEY_UNKNOWN||openingScan>=0?GLFW.glfwGetKeyName(openingKey,openingScan):null;
        openingKeyDown=openingKeyName!=null&&openingKeyName.length()==1;
    }
    public GameScreen parentGame(){return parent;}
    private boolean sameSession(){
        var session=ConsoleClient.INSTANCE.snapshot();return minecraft!=null&&minecraft.player!=null&&minecraft.level!=null&&
                !room.isEmpty()&&room.equals(session.room())&&epoch==session.epoch();
    }
    private void returnToGame(){
        if(minecraft==null||minecraft.screen!=this)return;
        parent.clearInputKeys();KeyMapping.releaseAll();ConsoleGameHud.returnedFromChat();
        minecraft.setScreen(sameSession()?parent:null);
    }
    @Override public void onClose(){returnToGame();}
    @Override public void handleChatInput(String message,boolean addHistory){
        // NeoForge's ChatScreen closes after Enter only if it is still the active screen.
        super.handleChatInput(message,addHistory);returnToGame();
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        // ChatScreen handles Esc with setScreen(null) rather than the virtual onClose hook.
        if(key==GLFW.GLFW_KEY_ESCAPE){returnToGame();return true;}
        if(ConsoleGameHud.keyPressed(parent,key,scan,modifiers))return true;
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean keyReleased(int key,int scan,int modifiers){
        if(key==openingKey&&(key!=GLFW.GLFW_KEY_UNKNOWN||scan==openingScan))openingKeyDown=false;
        return ConsoleGameHud.keyReleased(parent,key,scan,modifiers)||super.keyReleased(key,scan,modifiers);
    }
    @Override public boolean charTyped(char character,int modifiers){
        // GLFW sends the opening key's text after keyPressed has already installed this screen.
        // Match its current keyboard layout name only; unrelated text and IME output stay native.
        if(openingKeyDown&&openingKeyName.equalsIgnoreCase(String.valueOf(character)))return true;
        return super.charTyped(character,modifiers);
    }
    @Override public void resize(Minecraft minecraft,int width,int height){
        parent.resize(minecraft,width,height);ConsoleClient.INSTANCE.releaseControls();super.resize(minecraft,width,height);
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){
        if(sameSession())parent.render(graphics,mouseX,mouseY,partialTick);
        graphics.pose().pushPose();graphics.pose().translate(0,0,1200);
        try{super.render(graphics,mouseX,mouseY,partialTick);ConsoleGameHud.focusedChatRendered();}
        finally{graphics.pose().popPose();}
    }
}
