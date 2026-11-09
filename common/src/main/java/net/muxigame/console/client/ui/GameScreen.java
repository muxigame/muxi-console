package net.muxigame.console.client.ui;

import java.util.*;
import com.google.gson.*;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.muxigame.console.client.*;
import net.muxigame.console.client.input.NativeControls;
import net.muxigame.console.domain.VideoLayout;
import net.muxigame.console.domain.ViewRotation;
import net.muxigame.console.domain.DualScreenLayout;
import net.muxigame.console.mc.HardwareCatalog;
import net.muxigame.console.runtime.AvFrame;
import org.lwjgl.glfw.GLFW;

/** Two uncluttered fullscreen presentations of one running session. Escape only returns to the world. */
public class GameScreen extends Screen {
    public enum Mode { MODEL, SCREEN }
    protected final ClientActions actions;
    private final int port;
    private Mode mode;
    private final String displayKey;
    private int rotation;
    private boolean sideBySide;
    private Button rotate,layout;
    private boolean widgetMouseDown;
    private final Set<Integer> inputKeys=new HashSet<>();
    private JsonObject presentation=new JsonObject();
    private long sequence;
    private final List<ShownSurface> shownSurfaces=new ArrayList<>();
    private boolean pointerDown;
    private record ShownSurface(AvFrame.Surface source,float[] rect){}
    public GameScreen(ClientActions actions,int port){
        super(Component.translatable("ui.muxi_console.title"));
        if(port<0||port>3)throw new IllegalArgumentException("Player seat outside range");
        this.actions=Objects.requireNonNull(actions);this.port=port;
        displayKey=actions instanceof ConsoleClient client?GameDisplayPreferences.key(client):null;
        rotation=GameDisplayPreferences.rotation(displayKey);
        sideBySide=GameDisplayPreferences.sideBySide(displayKey);
        mode=actions instanceof ConsoleClient c&&c.handheldSession()?Mode.MODEL:Mode.SCREEN;
    }
    protected GameScreen(ClientActions actions,int port,boolean ignored){this(actions,port);}
    public int port(){return port;}
    public boolean worldView(){return mode==Mode.MODEL;}
    public Mode mode(){return mode;}
    public int rotation(){return rotation;}
    public JsonObject presentationSnapshot(){return presentation.deepCopy();}
    public Set<Integer> inputKeys(){return Set.copyOf(inputKeys);}
    public void clearInputKeys(){inputKeys.clear();NativeControls.clear();pointerDown=false;widgetMouseDown=false;shownSurfaces.clear();if(actions instanceof ConsoleClient client)client.clearPointer();}
    @Override protected void init(){
        net.minecraft.client.KeyMapping.releaseAll();clearInputKeys();if(actions instanceof ConsoleClient c)c.resumeControls();
        rotate=addRenderableWidget(Button.builder(Component.literal("旋转"),ignored->{
            clearInputKeys();rotation=ViewRotation.normalize(rotation+1);GameDisplayPreferences.remember(displayKey,rotation);refreshRotationTooltip();
        }).bounds(width-40,4,36,20).build());refreshRotationTooltip();
        layout=addRenderableWidget(Button.builder(Component.literal("布局"),ignored->{
            clearInputKeys();sideBySide=!sideBySide;GameDisplayPreferences.rememberLayout(displayKey,sideBySide);refreshLayoutTooltip();
        }).bounds(width-80,4,36,20).build());layout.visible=false;refreshLayoutTooltip();
    }
    private void refreshRotationTooltip(){if(rotate!=null)rotate.setTooltip(Tooltip.create(Component.literal("顺时针旋转90° · 当前"+ViewRotation.degrees(rotation)+"°")));}
    private void refreshLayoutTooltip(){if(layout!=null)layout.setTooltip(Tooltip.create(Component.literal("双屏布局："+(sideBySide?"左右":"上下")+" · 点击切换")));}
    @Override public void removed(){net.minecraft.client.KeyMapping.releaseAll();clearInputKeys();if(actions instanceof ConsoleClient c)c.clearInputVisual();}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void renderBackground(GuiGraphics g,int x,int y,float partial){}
    private double aspect(){
        if(actions instanceof ConsoleClient c&&c.picture()!=null){var texture=minecraft.getTextureManager().getTexture(c.picture());if(texture instanceof DynamicTexture dynamic&&dynamic.getPixels()!=null)return dynamic.getPixels().getWidth()/(double)dynamic.getPixels().getHeight();}
        return 4.0/3;
    }
    private static JsonArray rect(float x,float y,float w,float h){JsonArray a=new JsonArray();a.add(x);a.add(y);a.add(w);a.add(h);return a;}
    private float[] actual(float[] area,int virtualWidth,int virtualHeight){
        var value=ViewRotation.forward(new ViewRotation.Rect(area[0],area[1],area[2],area[3]),virtualWidth,virtualHeight,rotation);
        return new float[]{(float)value.x0(),(float)value.y0(),(float)value.x1(),(float)value.y1()};
    }
    private static JsonArray bounds(float[] area){return rect(area[0],area[1],area[2]-area[0],area[3]-area[1]);}
    private static float[] area(ViewRotation.Rect rect){return new float[]{(float)rect.x0(),(float)rect.y0(),(float)rect.x1(),(float)rect.y1()};}
    private void renderSurface(GuiGraphics g,ConsoleClient client,AvFrame.Surface source,float[] area,int atlasWidth,int atlasHeight){
        int x=Math.round(area[0]),y=Math.round(area[1]),w=Math.max(1,Math.round(area[2]-area[0])),h=Math.max(1,Math.round(area[3]-area[1]));
        g.fill(x-2,y-2,x+w+2,y+h+2,0xff59646a);g.fill(x-1,y-1,x+w+1,y+h+1,0xff080a0b);
        g.blit(client.picture(),x,y,w,h,(float)source.x(),(float)source.y(),source.width(),source.height(),atlasWidth,atlasHeight);
        shownSurfaces.add(new ShownSurface(source,new float[]{x,y,x+w,y+h}));
    }
    private void loading(GuiGraphics g,float[] video,ConsoleClient.ProgressSnapshot progress){
        int x=Math.round(video[0]),y=Math.round(video[1]),w=Math.max(1,Math.round(video[2]-video[0])),h=Math.max(1,Math.round(video[3]-video[1]));
        g.pose().pushPose();g.pose().translate(0,0,600);g.fill(x,y,x+w,y+h,0xff0b1317);g.enableScissor(x,y,x+w,y+h);
        try{
            var lines=font.split(Component.literal(progress.text()),Math.max(1,w-12));int count=Math.min(lines.size(),Math.max(1,(h-18)/font.lineHeight)),top=y+(h-count*font.lineHeight)/2;
            for(int i=0;i<count;i++){var line=lines.get(i);g.drawString(font,line,x+(w-font.width(line))/2,top+i*font.lineHeight,progress.error().isEmpty()?0xffd4e5e9:0xffffb8a7,false);}
            if(progress.percent()>=0&&w>20&&h>24){int left=x+8,right=x+w-8,bottom=y+h-8;g.fill(left,bottom,right,bottom+2,0xff253b44);g.fill(left,bottom,left+(right-left)*progress.percent()/100,bottom+2,0xff5dc7ff);}
        }finally{g.disableScissor();g.pose().popPose();}
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partial){
        shownSurfaces.clear();
        var canvas=ViewRotation.canvas(width,height,rotation);int viewWidth=canvas.width(),viewHeight=canvas.height();
        double aspect=aspect();int renderedModel=-1,highlighted=0;float[] video,content;boolean arranged=false;
        var item=HandheldRenderer.heldForSession();boolean model=mode==Mode.MODEL&&!item.isEmpty();
        ConsoleClient sourceClient=actions instanceof ConsoleClient c?c:null;
        var sources=sourceClient==null?List.<AvFrame.Surface>of():sourceClient.surfaces();
        var topScreen=sources.stream().filter(s->s.id().equals("top")).findFirst().orElse(null);
        var bottomScreen=sources.stream().filter(s->s.id().equals("bottom")).findFirst().orElse(null);
        boolean dual=topScreen!=null&&bottomScreen!=null;
        if(layout!=null)layout.visible=!model&&dual;
        g.pose().pushPose();
        g.pose().translate(width/2f,height/2f,0);g.pose().mulPose(Axis.ZP.rotationDegrees(ViewRotation.degrees(rotation)));g.pose().translate(-viewWidth/2f,-viewHeight/2f,0);
        try{
        if(model){
            renderedModel=HardwareCatalog.model(item);float[] bounds=HardwareCatalog.presentationBounds(item);
            float unit=Math.min((viewWidth-24f)/(bounds[3]-bounds[0]),(viewHeight-24f)/(bounds[4]-bounds[1]));
            float cx=(bounds[0]+bounds[3])/2,cy=(bounds[1]+bounds[4])/2,cz=(bounds[2]+bounds[5])/2;
            float w=(bounds[3]-bounds[0])*unit,h=(bounds[4]-bounds[1])*unit;
            content=new float[]{(viewWidth-w)/2,(viewHeight-h)/2,(viewWidth+w)/2,(viewHeight+h)/2};
            var planes=renderedModel==0?List.of(new HardwareCatalog.Plane(4.3f,5.2f,11.7f,10.75f,5.98f)):HardwareCatalog.screens(renderedModel);
            var p=planes.isEmpty()?new HardwareCatalog.Plane(bounds[0],bounds[1],bounds[3],bounds[4],bounds[2]):planes.get(0);
            float x0=viewWidth/2f-(p.x1()-cx)*unit,y0=viewHeight/2f-(p.y1()-cy)*unit;
            video=VideoLayout.fit(x0,y0,x0+(p.x1()-p.x0())*unit,y0+(p.y1()-p.y0())*unit,aspect);
            if(actions instanceof ConsoleClient client&&client.picture()!=null)for(var plane:planes){
                var source=DeviceRenderer.surface(client.picture(),plane);if(source==null)continue;
                float left=viewWidth/2f-(plane.x1()-cx)*unit,top=viewHeight/2f-(plane.y1()-cy)*unit;
                float[] fitted=VideoLayout.fit(left,top,left+(plane.x1()-plane.x0())*unit,top+(plane.y1()-plane.y0())*unit,source.nativeWidth()/(double)source.nativeHeight());
                shownSurfaces.add(new ShownSurface(source,fitted));
                if(plane==p)video=fitted;
            }
            g.flush();Lighting.setupForFlatItems();var pose=g.pose();pose.pushPose();
            try{pose.translate(viewWidth/2f,viewHeight/2f,400);pose.scale(unit*16,-unit*16,unit*16);pose.mulPose(Axis.YP.rotationDegrees(180));pose.translate(-cx/16,-cy/16,-cz/16);highlighted=HandheldRenderer.presented(item,pose,g.bufferSource(),LightTexture.FULL_BRIGHT);g.flush();}
            finally{pose.popPose();Lighting.setupFor3DItems();}
        }else if(sideBySide&&dual&&sourceClient.picture()!=null&&minecraft.getTextureManager().getTexture(sourceClient.picture()) instanceof DynamicTexture texture&&texture.getPixels()!=null){
            var pair=DualScreenLayout.fit(new ViewRotation.Rect(3,3,viewWidth-3,viewHeight-3),topScreen.nativeWidth()/(double)topScreen.nativeHeight(),bottomScreen.nativeWidth()/(double)bottomScreen.nativeHeight(),6);
            int aw=texture.getPixels().getWidth(),ah=texture.getPixels().getHeight();
            renderSurface(g,sourceClient,topScreen,area(pair.first()),aw,ah);renderSurface(g,sourceClient,bottomScreen,area(pair.second()),aw,ah);
            content=area(pair.content());video=content;aspect=pair.content().width()/pair.content().height();arranged=true;
        }else{
            video=VideoLayout.fit(3,3,viewWidth-3,viewHeight-3,aspect);int x=Math.round(video[0]),y=Math.round(video[1]),w=Math.max(1,Math.round(video[2]-video[0])),h=Math.max(1,Math.round(video[3]-video[1]));
            g.fill(x-2,y-2,x+w+2,y+h+2,0xff59646a);g.fill(x-1,y-1,x+w+1,y+h+1,0xff080a0b);actions.renderPicture(g,x,y,w,h);content=new float[]{x,y,x+w,y+h};
            if(actions instanceof ConsoleClient client&&client.picture()!=null){
                var texture=minecraft.getTextureManager().getTexture(client.picture());
                if(texture instanceof DynamicTexture dynamic&&dynamic.getPixels()!=null){int aw=dynamic.getPixels().getWidth(),ah=dynamic.getPixels().getHeight();
                    for(var source:client.surfaces())shownSurfaces.add(new ShownSurface(source,new float[]{video[0]+source.x()*(video[2]-video[0])/aw,video[1]+source.y()*(video[3]-video[1])/ah,video[0]+(source.x()+source.width())*(video[2]-video[0])/aw,video[1]+(source.y()+source.height())*(video[3]-video[1])/ah}));
                }
            }
        }
        }finally{g.flush();g.pose().popPose();}
        ConsoleClient.ProgressSnapshot progress=actions instanceof ConsoleClient c?c.progressSnapshot():null;
        if(!model&&progress!=null&&(progress.loading()||!progress.error().isEmpty()))loading(g,actual(video,viewWidth,viewHeight),progress);
        JsonObject out=new JsonObject();out.addProperty("sequence",++sequence);out.addProperty("mode",mode==Mode.MODEL?"model":"screen");out.addProperty("surfaceWidth",width);out.addProperty("surfaceHeight",height);out.addProperty("rotation",rotation);out.addProperty("rotationDegrees",ViewRotation.degrees(rotation));out.addProperty("virtualWidth",viewWidth);out.addProperty("virtualHeight",viewHeight);out.addProperty("showWorld",true);out.addProperty("renderedModel",renderedModel);out.addProperty("modelRendered",model);out.addProperty("videoAspect",ViewRotation.aspect(aspect,rotation));out.add("contentRect",bounds(actual(content,viewWidth,viewHeight)));out.add("videoRect",bounds(actual(video,viewWidth,viewHeight)));out.add("virtualVideoRect",bounds(video));out.addProperty("highlightedSurfaces",highlighted);out.addProperty("widgetCount",children().size());out.addProperty("buttons",actions instanceof ConsoleClient c?c.localInputVisual().buttons():0);
        if(rotate!=null)out.add("rotationButtonRect",rect(rotate.getX(),rotate.getY(),rotate.getWidth(),rotate.getHeight()));
        out.addProperty("arrangement",arranged?"side_by_side":"stacked");out.addProperty("preferredArrangement",sideBySide?"side_by_side":"stacked");
        if(layout!=null&&layout.visible)out.add("layoutButtonRect",rect(layout.getX(),layout.getY(),layout.getWidth(),layout.getHeight()));
        JsonArray screens=new JsonArray();for(var shown:shownSurfaces){var source=shown.source();float[] area=shown.rect();JsonObject entry=new JsonObject();entry.addProperty("id",source.id());entry.addProperty("touchable",source.touchable());entry.addProperty("nativeWidth",source.nativeWidth());entry.addProperty("nativeHeight",source.nativeHeight());entry.add("sourceRect",rect(source.x(),source.y(),source.width(),source.height()));entry.add("virtualRect",bounds(area));entry.add("rect",bounds(actual(area,viewWidth,viewHeight)));screens.add(entry);}out.add("screens",screens);
        if(progress!=null){JsonObject load=new JsonObject();load.addProperty("stage",progress.stage());load.addProperty("bytes",progress.bytes());load.addProperty("total",progress.total());load.addProperty("percent",progress.percent());load.addProperty("text",progress.text());load.addProperty("loading",progress.loading());load.addProperty("error",progress.error());out.add("progress",load);}presentation=out;
        super.render(g,mouseX,mouseY,partial);ConsoleGameHud.renderOverlays(g,mouseX,mouseY,partial);
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(ConsoleGameHud.keyPressed(this,key,scan,modifiers))return true;
        if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}
        inputKeys.add(key);NativeControls.key(key,scan,true);if(actions instanceof ConsoleClient client)client.sampleControls();return true;
    }
    @Override public boolean keyReleased(int key,int scan,int modifiers){if(ConsoleGameHud.keyReleased(this,key,scan,modifiers))return true;inputKeys.remove(key);NativeControls.key(key,scan,false);if(actions instanceof ConsoleClient client)client.sampleControls();return true;}
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button==GLFW.GLFW_MOUSE_BUTTON_LEFT&&super.mouseClicked(x,y,button)){widgetMouseDown=true;return true;}
        if(button==GLFW.GLFW_MOUSE_BUTTON_RIGHT){if(!HandheldRenderer.heldForSession().isEmpty())mode=mode==Mode.MODEL?Mode.SCREEN:Mode.MODEL;pointerDown=false;shownSurfaces.clear();if(actions instanceof ConsoleClient client)client.clearPointer();return true;}
        if(button==GLFW.GLFW_MOUSE_BUTTON_LEFT&&pointerHardware()){pointerDown=true;updatePointer(x,y);return true;}
        NativeControls.mouse(button,true);if(actions instanceof ConsoleClient client)client.sampleControls();return true;
    }
    private boolean pointerHardware(){return actions instanceof ConsoleClient client&&client.surfaces().stream().anyMatch(AvFrame.Surface::touchable);}
    private boolean overWidget(double x,double y){return children().stream().anyMatch(child->child.isMouseOver(x,y));}
    private void updatePointer(double x,double y){
        if(!(actions instanceof ConsoleClient client))return;
        if(overWidget(x,y)){client.clearPointer();return;}
        var canvas=ViewRotation.canvas(width,height,rotation);var point=ViewRotation.inverse(x,y,canvas.width(),canvas.height(),rotation);
        for(var shown:shownSurfaces){float[] area=shown.rect();if(shown.source().touchable()&&point.x()>=area[0]&&point.x()<=area[2]&&point.y()>=area[1]&&point.y()<=area[3]){client.pointer(shown.source().id(),(point.x()-area[0])/(area[2]-area[0]),(point.y()-area[1])/(area[3]-area[1]),pointerDown);return;}}
        client.clearPointer();
    }
    @Override public void mouseMoved(double x,double y){super.mouseMoved(x,y);if(pointerHardware())updatePointer(x,y);}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(widgetMouseDown)return super.mouseDragged(x,y,button,dx,dy);if(button==GLFW.GLFW_MOUSE_BUTTON_LEFT&&pointerHardware()){pointerDown=true;updatePointer(x,y);return true;}return super.mouseDragged(x,y,button,dx,dy);}
    @Override public boolean mouseReleased(double x,double y,int button){if(widgetMouseDown){widgetMouseDown=false;super.mouseReleased(x,y,button);return true;}if(button==GLFW.GLFW_MOUSE_BUTTON_LEFT){pointerDown=false;if(pointerHardware())updatePointer(x,y);}if(button!=GLFW.GLFW_MOUSE_BUTTON_RIGHT)NativeControls.mouse(button,false);if(actions instanceof ConsoleClient client)client.sampleControls();return true;}
    @Override public void onClose(){clearInputKeys();if(actions instanceof ConsoleClient c)c.releaseControls();if(minecraft!=null&&minecraft.screen==this)minecraft.setScreen(null);}
}
