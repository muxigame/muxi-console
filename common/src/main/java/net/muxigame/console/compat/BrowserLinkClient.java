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
import net.muxigame.console.mc.SharedPlayServer;
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

/** A nearby viewer receives display pixels and sound, without starting a core or obtaining ROM/save bytes. */
@EventBusSubscriber(modid=MuxiConsole.ID,value=Dist.CLIENT)
public final class BrowserLinkClient {
    public static final BrowserLinkClient INSTANCE=new BrowserLinkClient();
    private record Key(String dimension,long position,String side){}
    private record Publish(String room,long epoch){}
    private static final class Binding {
        final Key key;final String room;final long epoch;final BrowserCanvas canvas=new BrowserCanvas();WebDisplaysBridge.Target target;String original;Object loadedBrowser;boolean sawDocument,suspended;
        Binding(Key key,String room,long epoch){this.key=key;this.room=room;this.epoch=epoch;}
    }
    private static final class Surface {final String room,dimension;final long epoch,console;long frame=-1;byte[] png;BrowserFrameCodec.Image image;DynamicTexture texture;ResourceLocation picture;java.util.List<net.muxigame.console.runtime.AvFrame.Surface> planes=java.util.List.of();
        final net.muxigame.console.client.PcmPlayer audio=new net.muxigame.console.client.PcmPlayer();final net.muxigame.console.client.SharedPlayClient.AudioAssembler assembler=new net.muxigame.console.client.SharedPlayClient.AudioAssembler();
        Surface(String room,long epoch,long console,String dimension){this.room=room;this.epoch=epoch;this.console=console;this.dimension=dimension;}
    }
    private final Map<Key,Binding> bindings=new LinkedHashMap<>();private final Map<String,Surface> surfaces=new HashMap<>();
    private final ExecutorService encoder=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"muxi-console-display-encoder");t.setDaemon(true);return t;});private final AtomicBoolean encoding=new AtomicBoolean();
    private volatile Publish publish;private volatile long generation;private long nextFrameNanos,receivedFrames,javascriptSubmissions,nextToggleNanos;private String lastCrc="",lastError="";private int fps=20,maxWidth=512;private boolean configLoaded;
    private final AtomicBoolean audioEncoding=new AtomicBoolean();private net.muxigame.console.client.audio.StereoResampler audioResampler=new net.muxigame.console.client.audio.StereoResampler(48000);
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
        var sourceSurfaces=ConsoleClient.INSTANCE.surfaces();encoder.execute(()->{try{byte[] png=BrowserFrameCodec.encode(width,height,pixels,configuredWidth);mc().execute(()->{if(generation!=selectedGeneration||publish!=enabled||mc().getConnection()==null)return;JsonObject j=new JsonObject();j.addProperty("room",enabled.room);j.addProperty("epoch",enabled.epoch);j.addProperty("frame",number);SurfaceMetadata.write(j,SurfaceMetadata.scale(sourceSurfaces,width,height,java.nio.ByteBuffer.wrap(png).getInt(16),java.nio.ByteBuffer.wrap(png).getInt(20)));Wire.command("browser_frame",j,png);});}catch(IOException bad){lastError="外接画面编码失败："+bad.getMessage();}finally{encoding.set(false);}});
    }
    /** Called before the normal client event switch; true means this adapter consumed the event. */
    public boolean receive(Wire.Message message){
        if(!message.operation().startsWith("browser_"))return false;JsonObject j=message.body();
        try{switch(message.operation()){
            case "browser_publish" -> {publish=j.get("enabled").getAsBoolean()?new Publish(j.get("room").getAsString(),j.get("epoch").getAsLong()):null;audioResampler=new net.muxigame.console.client.audio.StereoResampler(48000);nextFrameNanos=0;}
            case "browser_audio" -> {
                String room=j.get("room").getAsString();long epoch=j.get("epoch").getAsLong();if(bindings.values().stream().noneMatch(b->b.room.equals(room)&&b.epoch==epoch))return true;
                if(room.equals(ConsoleClient.INSTANCE.snapshot().room()))return true;Surface surface=surfaces.get(key(room,epoch));if(surface==null)return true;if(j.get("rate").getAsInt()!=48000)throw new IOException("Invalid display audio rate");
                short[] pcm=surface.assembler.accept(j.get("frame").getAsLong(),j.get("part").getAsInt(),j.get("parts").getAsInt(),j.get("totalBytes").getAsInt(),message.data(),System.nanoTime());
                if(pcm!=null&&mc().player!=null){double distance=bindings.values().stream().filter(b->b.room.equals(room)&&b.epoch==epoch).mapToDouble(b->mc().player.distanceToSqr(BlockPos.of(b.key.position).getCenter())).min().orElse(Double.POSITIVE_INFINITY);double gain=Math.max(0,1-Math.sqrt(distance)/24);if(gain>0){for(int i=0;i<pcm.length;i++)pcm[i]=(short)(pcm[i]*gain);surface.audio.offer(pcm,48000);}}
            }
            case "browser_bind" -> {Key key=new Key(j.get("dimension").getAsString(),j.get("position").getAsLong(),j.get("side").getAsString());String room=j.get("room").getAsString();long epoch=j.get("epoch").getAsLong();Binding old=bindings.get(key);if(old==null||!old.room.equals(room)||old.epoch!=epoch){if(old!=null)restore(old);bindings.put(key,new Binding(key,room,epoch));}prune();}
            case "browser_unbind" -> {Key key=new Key(j.get("dimension").getAsString(),j.get("position").getAsLong(),j.get("side").getAsString());Binding old=bindings.remove(key);if(old!=null)restore(old);prune();}
            case "browser_frame" -> {String room=j.get("room").getAsString();long epoch=j.get("epoch").getAsLong();if(bindings.values().stream().noneMatch(b->b.room.equals(room)&&b.epoch==epoch))return true;String key=key(room,epoch);Surface surface=surfaces.get(key);long number=j.get("frame").getAsLong();if(number<0||surface!=null&&surface.frame>=number)return true;BrowserFrameCodec.Image image=BrowserFrameCodec.decode(message.data());if(surface==null){surface=new Surface(room,epoch,j.get("console").getAsLong(),j.get("dimension").getAsString());surfaces.put(key,surface);}surface.frame=number;surface.png=message.data().clone();surface.image=image;surface.planes=SurfaceMetadata.read(j,image.width(),image.height());upload(surface);receivedFrames++;CRC32 crc=new CRC32();crc.update(image.rgba());lastCrc=Long.toHexString(crc.getValue());for(Binding b:bindings.values())if(b.room.equals(room)&&b.epoch==epoch)paint(b,surface);}
            default -> { }
        }}catch(Exception|LinkageError bad){lastError="外接屏幕不可用："+Objects.toString(bad.getMessage(),bad.getClass().getSimpleName());MuxiConsole.LOG.debug("Optional browser display unavailable",bad);}return true;
    }
    public boolean publishing(){return publish!=null;}
    public void audio(long frame,short[] pcm,double rate){
        Publish enabled=publish;if(enabled==null||pcm==null||pcm.length==0||pcm.length%2!=0||!Double.isFinite(rate)||rate<8000||rate>192000||pcm.length/(2*rate)>.5||!audioEncoding.compareAndSet(false,true))return;
        long selected=generation;short[] samples=pcm.clone();var converter=audioResampler;encoder.execute(()->{try{byte[] data=net.muxigame.console.client.SharedPlayClient.encode(converter.convert(samples,rate));if(data.length==0)return;int parts=(data.length+SharedPlayServer.AUDIO_PART_BYTES-1)/SharedPlayServer.AUDIO_PART_BYTES;
            mc().execute(()->{if(selected!=generation||publish!=enabled||mc().getConnection()==null)return;for(int part=0;part<parts;part++){JsonObject body=new JsonObject();body.addProperty("room",enabled.room);body.addProperty("epoch",enabled.epoch);body.addProperty("frame",frame);body.addProperty("part",part);body.addProperty("parts",parts);body.addProperty("totalBytes",data.length);body.addProperty("rate",48000);int from=part*SharedPlayServer.AUDIO_PART_BYTES;Wire.command("browser_audio",body,Arrays.copyOfRange(data,from,Math.min(data.length,from+SharedPlayServer.AUDIO_PART_BYTES)));}});
        }catch(RuntimeException failure){lastError="外接屏幕音频未能编码";}finally{audioEncoding.set(false);}});
    }
    private void upload(Surface surface){BrowserFrameCodec.Image frame=surface.image;int w=frame.width(),h=frame.height();if(surface.texture==null||surface.texture.getPixels().getWidth()!=w||surface.texture.getPixels().getHeight()!=h){if(surface.picture!=null)mc().getTextureManager().release(surface.picture);surface.texture=new DynamicTexture(new NativeImage(w,h,false));surface.picture=mc().getTextureManager().register("muxi-console-relayed",surface.texture);}NativeImage image=surface.texture.getPixels();byte[] rgba=frame.rgba();for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=(y*w+x)*4;image.setPixelRGBA(x,y,(rgba[i+3]&255)<<24|(rgba[i+2]&255)<<16|(rgba[i+1]&255)<<8|(rgba[i]&255));}surface.texture.upload();}
    /** Built-in world screens may show a nearby relay without starting a spectator emulator. */
    public ResourceLocation pictureFor(long consolePosition){if(mc().level==null)return null;String dimension=mc().level.dimension().location().toString();for(Surface s:surfaces.values())if(s.console==consolePosition&&s.dimension.equals(dimension))return s.picture;return null;}
    /** Actual browser instance for native QA to inspect; this never creates a fake browser or page. */
    public Optional<Object> nativeBrowser(long displayPosition,String side){for(Binding b:bindings.values())if(b.key.position==displayPosition&&b.key.side.equals(side)&&b.target!=null)try{return Optional.ofNullable(WebDisplaysBridge.browser(b.target));}catch(ReflectiveOperationException ignored){}return Optional.empty();}
    public java.util.List<net.muxigame.console.runtime.AvFrame.Surface> surfacesFor(ResourceLocation picture){if(picture==null)return java.util.List.of();for(Surface surface:surfaces.values())if(picture.equals(surface.picture))return surface.planes;return java.util.List.of();}
    private void paint(Binding b,Surface surface)throws ReflectiveOperationException{
        if(b.key.side.equals("builtin"))return;
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
    private void prune(){Set<String> required=new HashSet<>();for(Binding b:bindings.values())required.add(key(b.room,b.epoch));for(String id:new ArrayList<>(surfaces.keySet()))if(!required.contains(id)){Surface removed=surfaces.remove(id);removed.audio.close();if(removed.picture!=null)mc().getTextureManager().release(removed.picture);}}
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
    private BrowserLinkClient(){}
}
