package net.muxigame.console.client.ui;

import java.util.*;
import com.google.gson.*;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.muxigame.console.client.*;
import net.muxigame.console.client.input.NativeControls;
import net.muxigame.console.domain.VideoLayout;
import net.muxigame.console.mc.HardwareCatalog;
import org.lwjgl.glfw.GLFW;

/** Two uncluttered fullscreen presentations of one running session. Escape only returns to the world. */
public class GameScreen extends Screen {
    public enum Mode { MODEL, SCREEN }
    protected final ClientActions actions;
    private final int port;
    private Mode mode;
    private final Set<Integer> inputKeys=new HashSet<>();
    private JsonObject presentation=new JsonObject();
    private long sequence;
    public GameScreen(ClientActions actions,int port){
        super(Component.translatable("ui.muxi_console.title"));
        if(port<0||port>3)throw new IllegalArgumentException("Player seat outside range");
        this.actions=Objects.requireNonNull(actions);this.port=port;
        mode=actions instanceof ConsoleClient c&&c.handheldSession()?Mode.MODEL:Mode.SCREEN;
    }
    protected GameScreen(ClientActions actions,int port,boolean ignored){this(actions,port);}
    public int port(){return port;}
    public boolean worldView(){return mode==Mode.MODEL;}
    public Mode mode(){return mode;}
    public JsonObject presentationSnapshot(){return presentation.deepCopy();}
    public Set<Integer> inputKeys(){return Set.copyOf(inputKeys);}
    public void clearInputKeys(){inputKeys.clear();NativeControls.clear();}
    @Override protected void init(){net.minecraft.client.KeyMapping.releaseAll();clearInputKeys();if(actions instanceof ConsoleClient c)c.resumeControls();}
    @Override public void removed(){net.minecraft.client.KeyMapping.releaseAll();clearInputKeys();if(actions instanceof ConsoleClient c)c.clearInputVisual();}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void renderBackground(GuiGraphics g,int x,int y,float partial){}
    private double aspect(){
        if(actions instanceof ConsoleClient c&&c.picture()!=null){var texture=minecraft.getTextureManager().getTexture(c.picture());if(texture instanceof DynamicTexture dynamic&&dynamic.getPixels()!=null)return dynamic.getPixels().getWidth()/(double)dynamic.getPixels().getHeight();}
        return 4.0/3;
    }
    private static JsonArray rect(float x,float y,float w,float h){JsonArray a=new JsonArray();a.add(x);a.add(y);a.add(w);a.add(h);return a;}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partial){
        g.fill(0,0,width,height,0xff101315);
        double aspect=aspect();int renderedModel=-1,highlighted=0;float[] video;JsonArray content;
        var item=HandheldRenderer.heldForSession();boolean model=mode==Mode.MODEL&&!item.isEmpty();
        if(model){
            renderedModel=HardwareCatalog.model(item);var b=HardwareCatalog.body(renderedModel);float[] bounds=b==null?new float[]{0,3,5.8f,16,12.4f,8.5f}:b.bounds();
            float unit=Math.min((width-24f)/(bounds[3]-bounds[0]),(height-24f)/(bounds[4]-bounds[1]));
            float cx=(bounds[0]+bounds[3])/2,cy=(bounds[1]+bounds[4])/2,cz=(bounds[2]+bounds[5])/2;
            float w=(bounds[3]-bounds[0])*unit,h=(bounds[4]-bounds[1])*unit;
            content=rect((width-w)/2,(height-h)/2,w,h);
            var planes=renderedModel==0?List.of(new HardwareCatalog.Plane(4.3f,5.2f,11.7f,10.75f,5.98f)):HardwareCatalog.screens(renderedModel);
            var p=planes.isEmpty()?new HardwareCatalog.Plane(bounds[0],bounds[1],bounds[3],bounds[4],bounds[2]):planes.get(0);
            float x0=width/2f-(p.x1()-cx)*unit,y0=height/2f-(p.y1()-cy)*unit;
            video=VideoLayout.fit(x0,y0,x0+(p.x1()-p.x0())*unit,y0+(p.y1()-p.y0())*unit,aspect);
            g.flush();Lighting.setupForFlatItems();var pose=g.pose();pose.pushPose();
            try{pose.translate(width/2f,height/2f,400);pose.scale(unit*16,-unit*16,unit*16);pose.mulPose(Axis.YP.rotationDegrees(180));pose.translate(-cx/16,-cy/16,-cz/16);highlighted=HandheldRenderer.presented(item,pose,g.bufferSource(),LightTexture.FULL_BRIGHT);g.flush();}
            finally{pose.popPose();Lighting.setupFor3DItems();}
        }else{
            video=VideoLayout.fit(3,3,width-3,height-3,aspect);int x=Math.round(video[0]),y=Math.round(video[1]),w=Math.max(1,Math.round(video[2]-video[0])),h=Math.max(1,Math.round(video[3]-video[1]));
            g.fill(x-2,y-2,x+w+2,y+h+2,0xff59646a);g.fill(x-1,y-1,x+w+1,y+h+1,0xff080a0b);actions.renderPicture(g,x,y,w,h);content=rect(x,y,w,h);
        }
        JsonObject out=new JsonObject();out.addProperty("sequence",++sequence);out.addProperty("mode",mode==Mode.MODEL?"model":"screen");out.addProperty("surfaceWidth",width);out.addProperty("surfaceHeight",height);out.addProperty("renderedModel",renderedModel);out.addProperty("modelRendered",model);out.addProperty("videoAspect",aspect);out.add("contentRect",content);out.add("videoRect",rect(video[0],video[1],video[2]-video[0],video[3]-video[1]));out.addProperty("highlightedSurfaces",highlighted);out.addProperty("widgetCount",children().size());out.addProperty("buttons",actions instanceof ConsoleClient c?c.localInputVisual().buttons():0);presentation=out;
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}
        inputKeys.add(key);NativeControls.key(key,scan,true);return true;
    }
    @Override public boolean keyReleased(int key,int scan,int modifiers){inputKeys.remove(key);NativeControls.key(key,scan,false);return true;}
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button==GLFW.GLFW_MOUSE_BUTTON_RIGHT){if(!HandheldRenderer.heldForSession().isEmpty())mode=mode==Mode.MODEL?Mode.SCREEN:Mode.MODEL;return true;}
        NativeControls.mouse(button,true);return true;
    }
    @Override public boolean mouseReleased(double x,double y,int button){if(button!=GLFW.GLFW_MOUSE_BUTTON_RIGHT)NativeControls.mouse(button,false);return true;}
    @Override public void onClose(){clearInputKeys();if(actions instanceof ConsoleClient c)c.releaseControls();if(minecraft!=null&&minecraft.screen==this)minecraft.setScreen(null);}
}
