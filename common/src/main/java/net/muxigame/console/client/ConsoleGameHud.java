package net.muxigame.console.client;

import com.google.gson.JsonObject;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.muxigame.console.client.ui.ConsoleChatScreen;
import net.muxigame.console.client.ui.GameScreen;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Keep selected vanilla overlays above the console screen without rendering the whole HUD twice. */
public final class ConsoleGameHud {
    private static final Set<ResourceLocation> HIDDEN_LAYERS=Set.of(
            VanillaGuiLayers.CROSSHAIR,VanillaGuiLayers.HOTBAR,VanillaGuiLayers.JUMP_METER,
            VanillaGuiLayers.EXPERIENCE_BAR,VanillaGuiLayers.PLAYER_HEALTH,VanillaGuiLayers.ARMOR_LEVEL,
            VanillaGuiLayers.FOOD_LEVEL,VanillaGuiLayers.VEHICLE_HEALTH,VanillaGuiLayers.AIR_LEVEL,
            VanillaGuiLayers.SELECTED_ITEM_NAME,VanillaGuiLayers.SPECTATOR_TOOLTIP,VanillaGuiLayers.EXPERIENCE_LEVEL,
            VanillaGuiLayers.EFFECTS,VanillaGuiLayers.BOSS_OVERLAY,VanillaGuiLayers.SCOREBOARD_SIDEBAR,
            VanillaGuiLayers.OVERLAY_MESSAGE,VanillaGuiLayers.TITLE,VanillaGuiLayers.TAB_LIST,
            VanillaGuiLayers.DEBUG_OVERLAY,VanillaGuiLayers.CHAT);
    private static boolean debugDown,debugChord;
    private static long renderFrames,levelRenderFrames,debugRenderFrames,chatRenderFrames,chatFocusedRenderFrames;
    private static long chatOpenCount,chatReturnCount,debugToggles;

    public static boolean active(){var screen=Minecraft.getInstance().screen;return screen instanceof GameScreen||screen instanceof ConsoleChatScreen;}
    public static boolean hideVanillaLayer(ResourceLocation name){return active()&&HIDDEN_LAYERS.contains(name);}
    public static void worldRendered(){if(active())levelRenderFrames++;}
    public static void resetKeys(){debugDown=false;debugChord=false;}

    /** GUI conflict context must not prevent the ordinary Minecraft chat bindings from matching. */
    private static boolean matches(KeyMapping binding,int key,int scan){
        return binding.matches(key,scan)&&binding.getKeyModifier().isActive(KeyConflictContext.UNIVERSAL);
    }
    public static boolean keyPressed(GameScreen parent,int key,int scan,int modifiers){
        var mc=Minecraft.getInstance();
        if(key==GLFW.GLFW_KEY_F3){if(!debugDown){debugDown=true;debugChord=false;}return true;}
        if(debugDown){
            debugChord=true;
            // Reserve F3 chords instead of sending them to the emulator or injecting GUI world actions.
            return true;
        }
        if(mc.screen!=parent||mc.player==null||mc.getConnection()==null)return false;
        boolean command=matches(mc.options.keyCommand,key,scan),chat=matches(mc.options.keyChat,key,scan);
        if(!command&&!chat)return false;
        if(!mc.getChatStatus().isChatAllowed(mc.isLocalServer())){
            mc.player.displayClientMessage(mc.getChatStatus().getMessage(),false);return true;
        }
        parent.clearInputKeys();ConsoleClient.INSTANCE.releaseControls();KeyMapping.releaseAll();resetKeys();
        var screen=new ConsoleChatScreen(parent,command?"/":"",key,scan);mc.setScreen(screen);
        if(mc.screen==screen)chatOpenCount++;return true;
    }
    public static boolean keyReleased(GameScreen parent,int key,int scan,int modifiers){
        if(key!=GLFW.GLFW_KEY_F3)return false;
        if(debugDown&&!debugChord){Minecraft.getInstance().getDebugOverlay().toggleOverlay();debugToggles++;}
        resetKeys();return true;
    }

    /** Called after the complete, unrotated game screen; ChatScreen renders its focused chat itself. */
    public static void renderOverlays(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){
        var mc=Minecraft.getInstance();if(!active()||mc.level==null||mc.player==null)return;
        renderFrames++;
        if(mc.options.hideGui)return;
        graphics.pose().pushPose();graphics.pose().translate(0,0,1000);
        try{
            if(mc.getDebugOverlay().showDebugScreen()){mc.getDebugOverlay().render(graphics);debugRenderFrames++;}
            if(mc.screen instanceof GameScreen){
                var chat=mc.gui.getChat();
                var event=NeoForge.EVENT_BUS.post(new CustomizeGuiOverlayEvent.Chat(mc.getWindow(),graphics,mc.getTimer(),0,graphics.guiHeight()-40));
                graphics.pose().pushPose();
                try{
                    graphics.pose().translate(event.getPosX(),(event.getPosY()-graphics.guiHeight()+40)/chat.getScale(),0);
                    chat.render(graphics,mc.gui.getGuiTicks(),mouseX,mouseY,false);chatRenderFrames++;
                }finally{graphics.pose().popPose();}
            }
        }finally{graphics.pose().popPose();}
    }
    public static void focusedChatRendered(){chatFocusedRenderFrames++;}
    public static void returnedFromChat(){chatReturnCount++;resetKeys();}
    /** Observes real render calls and vanilla state. It never adds chat messages or advances a game. */
    public static JsonObject snapshot(){
        var mc=Minecraft.getInstance();JsonObject out=new JsonObject();
        out.addProperty("debugVisible",mc.getDebugOverlay().showDebugScreen());
        out.addProperty("chatScreen",mc.screen instanceof ConsoleChatScreen);out.addProperty("chatFocused",mc.gui.getChat().isChatFocused());
        out.addProperty("chatAllowed",mc.player!=null&&mc.getChatStatus().isChatAllowed(mc.isLocalServer()));
        out.addProperty("renderFrames",renderFrames);out.addProperty("overlayRenderCount",renderFrames);out.addProperty("lastRenderSequence",renderFrames);
        out.addProperty("levelRenderFrames",levelRenderFrames);out.addProperty("worldRenderCount",levelRenderFrames);
        out.addProperty("debugRenderFrames",debugRenderFrames);out.addProperty("chatRenderFrames",chatRenderFrames);
        out.addProperty("chatFocusedRenderFrames",chatFocusedRenderFrames);out.addProperty("chatOpenCount",chatOpenCount);
        out.addProperty("chatReturnCount",chatReturnCount);out.addProperty("debugToggles",debugToggles);return out;
    }
    private ConsoleGameHud(){}
}
