package net.muxigame.console.compat;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.client.ConsoleClient;
import net.muxigame.console.client.ui.GameScreen;
import net.muxigame.console.mc.Wire;
import net.muxigame.console.mc.ConsoleContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.glfw.GLFW;

/** Receives only PNG pixels. A viewer never starts an emulator, requests a cartridge or obtains a save. */
@EventBusSubscriber(modid=MuxiConsole.ID,value=Dist.CLIENT)
public final class BrowserLinkClient {
    public static final BrowserLinkClient INSTANCE=new BrowserLinkClient();
    private record Key(String dimension,long position,String side){}
    private record Publish(String room,long epoch){}
    private static final class Binding {
        final Key key;final String room;final long epoch;final BrowserCanvas canvas=new BrowserCanvas();WebDisplaysBridge.Target target;String original;Object loadedBrowser;boolean sawDocument,suspended;
        Binding(Key key,String room,long epoch){this.key=key;this.room=room;this.epoch=epoch;}
    }
    private static final class Surface {final String room,dimension;final long epoch,console;long frame=-1;byte[] png;BrowserFrameCodec.Image image;DynamicTexture texture;ResourceLocation picture;
        Surface(String room,long epoch,long console,String dimension){this.room=room;this.epoch=epoch;this.console=console;this.dimension=dimension;}
    }
    private final Map<Key,Binding> bindings=new LinkedHashMap<>();private final Map<String,Surface> surfaces=new HashMap<>();
    private final ExecutorService encoder=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"muxi-console-display-encoder");t.setDaemon(true);return t;});private final AtomicBoolean encoding=new AtomicBoolean();
    private volatile Publish publish;private volatile long generation;private long nextFrameNanos,receivedFrames,javascriptSubmissions,nextToggleNanos;private String lastCrc="",lastError="";private int fps=20,maxWidth=512;private boolean configLoaded;
    public record Snapshot(int bindings,int surfaces,long receivedFrames,long javascriptSubmissions,int readyBrowsers,boolean publishing,int fps,int maxWidth,String rgbaCrc,String lastError){}
    public Snapshot snapshot(){int ready=0;for(Binding b:bindings.values())if(b.sawDocument&&!b.suspended)ready++;return new Snapshot(bindings.size(),surfaces.size(),receivedFrames,javascriptSubmissions,ready,publish!=null,fps,maxWidth,lastCrc,lastError);}
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static String key(String room,long epoch){return room+"/"+epoch;}
    private void config(){if(configLoaded)return;configLoaded=true;Path file=mc().gameDirectory.toPath().resolve("config/muxi-console-browser.json");
        if(!Files.isRegularFile(file))return;try{if(Files.size(file)>4096)throw new IOException("Browser configuration too large");JsonObject j=Wire.JSON.fromJson(Files.readString(file),JsonObject.class);if(j.has("fps"))fps=Math.max(1,Math.min(20,j.get("fps").getAsInt()));if(j.has("maxWidth"))maxWidth=Math.max(64,Math.min(512,j.get("maxWidth").getAsInt()));}catch(Exception bad){lastError="外接屏幕设置无效，使用默认设置";fps=20;maxWidth=512;}
    }
    /** Stable hook called with the same oriented pixels that feed the built-in game display. */
    public void frame(long number,int width,int height,byte[] orientedRgba){
        Publish enabled=publish;if(enabled==null)return;var active=ConsoleClient.INSTANCE.snapshot();if(!active.ready()||!enabled.room.equals(active.room())||enabled.epoch!=active.epoch())return;
        config();long now=System.nanoTime();if(now<nextFrameNanos||!encoding.compareAndSet(false,true))return;nextFrameNanos=now+1_000_000_000L/fps;long selectedGeneration=generation;byte[] pixels=orientedRgba.clone();int configuredWidth=maxWidth;
        encoder.execute(()->{try{byte[] png=BrowserFrameCodec.encode(width,height,pixels,configuredWidth);mc().execute(()->{if(generation!=selectedGeneration||publish!=enabled||mc().getConnection()==null)return;JsonObject j=new JsonObject();j.addProperty("room",enabled.room);j.addProperty("epoch",enabled.epoch);j.addProperty("frame",number);Wire.command("browser_frame",j,png);});}catch(IOException bad){lastError="外接画面编码失败："+bad.getMessage();}finally{encoding.set(false);}});
    }
    /** Called before the normal client event switch; true means this adapter consumed the event. */
    public boolean receive(Wire.Message message){
        if(!message.operation().startsWith("browser_"))return false;JsonObject j=message.body();
        try{switch(message.operation()){
            case "browser_publish" -> {publish=j.get("enabled").getAsBoolean()?new Publish(j.get("room").getAsString(),j.get("epoch").getAsLong()):null;nextFrameNanos=0;}
            case "browser_bind" -> {Key key=new Key(j.get("dimension").getAsString(),j.get("position").getAsLong(),j.get("side").getAsString());String room=j.get("room").getAsString();long epoch=j.get("epoch").getAsLong();Binding old=bindings.get(key);if(old==null||!old.room.equals(room)||old.epoch!=epoch){if(old!=null)restore(old);bindings.put(key,new Binding(key,room,epoch));}prune();}
            case "browser_unbind" -> {Key key=new Key(j.get("dimension").getAsString(),j.get("position").getAsLong(),j.get("side").getAsString());Binding old=bindings.remove(key);if(old!=null)restore(old);prune();}
            case "browser_frame" -> {String room=j.get("room").getAsString();long epoch=j.get("epoch").getAsLong();if(bindings.values().stream().noneMatch(b->b.room.equals(room)&&b.epoch==epoch))return true;String key=key(room,epoch);Surface surface=surfaces.get(key);long number=j.get("frame").getAsLong();if(number<0||surface!=null&&surface.frame>=number)return true;BrowserFrameCodec.Image image=BrowserFrameCodec.decode(message.data());if(surface==null){surface=new Surface(room,epoch,j.get("console").getAsLong(),j.get("dimension").getAsString());surfaces.put(key,surface);}surface.frame=number;surface.png=message.data().clone();surface.image=image;upload(surface);receivedFrames++;CRC32 crc=new CRC32();crc.update(image.rgba());lastCrc=Long.toHexString(crc.getValue());for(Binding b:bindings.values())if(b.room.equals(room)&&b.epoch==epoch)paint(b,surface);}
            default -> { }
        }}catch(Exception|LinkageError bad){lastError="外接屏幕不可用："+Objects.toString(bad.getMessage(),bad.getClass().getSimpleName());MuxiConsole.LOG.debug("Optional browser display unavailable",bad);}return true;
    }
    private void upload(Surface surface){BrowserFrameCodec.Image frame=surface.image;int w=frame.width(),h=frame.height();if(surface.texture==null||surface.texture.getPixels().getWidth()!=w||surface.texture.getPixels().getHeight()!=h){if(surface.picture!=null)mc().getTextureManager().release(surface.picture);surface.texture=new DynamicTexture(new NativeImage(w,h,false));surface.picture=mc().getTextureManager().register("muxi-console-relayed",surface.texture);}NativeImage image=surface.texture.getPixels();byte[] rgba=frame.rgba();for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=(y*w+x)*4;image.setPixelRGBA(x,y,(rgba[i+3]&255)<<24|(rgba[i+2]&255)<<16|(rgba[i+1]&255)<<8|(rgba[i]&255));}surface.texture.upload();}
    /** Built-in world screens may show a nearby relay without starting a spectator emulator. */
    public ResourceLocation pictureFor(long consolePosition){if(mc().level==null)return null;String dimension=mc().level.dimension().location().toString();for(Surface s:surfaces.values())if(s.console==consolePosition&&s.dimension.equals(dimension))return s.picture;return null;}
    /** Actual browser instance for native QA to inspect; this never creates a fake browser or page. */
    public Optional<Object> nativeBrowser(long displayPosition,String side){for(Binding b:bindings.values())if(b.key.position==displayPosition&&b.key.side.equals(side)&&b.target!=null)try{return Optional.ofNullable(WebDisplaysBridge.browser(b.target));}catch(ReflectiveOperationException ignored){}return Optional.empty();}
    private void paint(Binding b,Surface surface)throws ReflectiveOperationException{
        if(b.suspended||b.target==null||surface.png==null)return;Object browser=WebDisplaysBridge.browser(b.target);if(browser==null)return;
        String url=WebDisplaysBridge.browserUrl(browser);if(!Objects.equals(url,b.canvas.url()))return;b.sawDocument=true;WebDisplaysBridge.execute(browser,b.canvas.script(surface.frame,surface.png));javascriptSubmissions++;
    }
    private void tick(){if(mc().player==null||mc().level==null){clear(false);return;}for(Binding b:bindings.values()){
        if(b.suspended||!b.key.dimension.equals(mc().level.dimension().location().toString()))continue;
        try{WebDisplaysBridge.Target target=WebDisplaysBridge.at(mc().level,BlockPos.of(b.key.position),b.key.side).orElse(null);if(target==null)continue;
            if(b.target==null||b.target.data()!=target.data()){restore(b);b.target=target;b.original=WebDisplaysBridge.url(target);b.loadedBrowser=null;b.sawDocument=false;WebDisplaysBridge.localUrl(target,b.canvas.url());}
            else if(!Objects.equals(WebDisplaysBridge.url(target),b.canvas.url())){b.suspended=true;continue;}
            Object browser=WebDisplaysBridge.browser(target);if(browser!=null&&browser!=b.loadedBrowser){b.loadedBrowser=browser;b.sawDocument=false;WebDisplaysBridge.load(browser,b.canvas.url());}
            if(browser!=null&&b.sawDocument&&!Objects.equals(WebDisplaysBridge.browserUrl(browser),b.canvas.url())){b.suspended=true;continue;}
            Surface surface=surfaces.get(key(b.room,b.epoch));if(surface!=null&&!b.sawDocument)paint(b,surface);
        }catch(Exception|LinkageError bad){lastError="外接屏幕接口不可用";}
    }}
    private void restore(Binding b){if(b.target==null||b.original==null)return;try{boolean stillOwned=Objects.equals(WebDisplaysBridge.url(b.target),b.canvas.url());if(stillOwned){WebDisplaysBridge.localUrl(b.target,b.original);Object browser=WebDisplaysBridge.browser(b.target);if(browser!=null&&Objects.equals(WebDisplaysBridge.browserUrl(browser),b.canvas.url()))WebDisplaysBridge.load(browser,b.original);}}catch(Exception|LinkageError ignored){}b.target=null;b.loadedBrowser=null;}
    private void prune(){Set<String> required=new HashSet<>();for(Binding b:bindings.values())required.add(key(b.room,b.epoch));for(String id:new ArrayList<>(surfaces.keySet()))if(!required.contains(id)){Surface removed=surfaces.remove(id);if(removed.picture!=null)mc().getTextureManager().release(removed.picture);}}
    private void clear(boolean refresh){generation++;publish=null;for(Binding b:bindings.values())restore(b);bindings.clear();prune();if(refresh&&mc().getConnection()!=null)Wire.command("browser_refresh",new JsonObject());}
    /** Session transition hook. A server refresh may restore valid other-player spectator bindings. */
    public void stop(){if(mc().isSameThread())clear(true);else mc().execute(()->clear(false));}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){INSTANCE.tick();}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){INSTANCE.clear(false);}
    @SubscribeEvent public static void clicked(PlayerInteractEvent.RightClickBlock event){if(!event.getLevel().isClientSide||event.getEntity()!=mc().player||!event.getEntity().isShiftKeyDown()||event.getFace()==null)return;
        var player=event.getEntity();if(!player.getMainHandItem().is(ConsoleContent.CONTROLLER.get())&&!player.getOffhandItem().is(ConsoleContent.CONTROLLER.get()))return;
        if(!BuiltInRegistries.BLOCK.getKey(event.getLevel().getBlockState(event.getPos()).getBlock()).toString().equals("webdisplays:screen"))return;
        event.setCanceled(true);event.setCancellationResult(InteractionResult.SUCCESS);JsonObject j=new JsonObject();j.addProperty("position",event.getPos().asLong());j.addProperty("side",event.getFace().name());Wire.command("browser_select",j);
    }
    @SubscribeEvent public static void screenKey(ScreenEvent.KeyPressed.Pre event){if(event.getKeyCode()==GLFW.GLFW_KEY_F8&&event.getScreen() instanceof GameScreen){event.setCanceled(true);long now=System.nanoTime();if(now<INSTANCE.nextToggleNanos)return;INSTANCE.nextToggleNanos=now+250_000_000;mc().setScreen(null);if(mc().player!=null)mc().player.displayClientMessage(Component.literal("手持木犀控制器，潜行使用网页屏幕进行连接；F8 返回游戏"),true);}}
    @SubscribeEvent public static void worldKey(InputEvent.Key event){if(event.getKey()==GLFW.GLFW_KEY_F8&&event.getAction()==GLFW.GLFW_PRESS&&mc().screen==null){long now=System.nanoTime();if(now<INSTANCE.nextToggleNanos)return;var s=ConsoleClient.INSTANCE.snapshot();if(s.ready()&&s.port()>=0){INSTANCE.nextToggleNanos=now+250_000_000;mc().setScreen(new GameScreen(ConsoleClient.INSTANCE,s.port()));}}}
    private BrowserLinkClient(){}
}
