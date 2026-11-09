package net.muxigame.consoleqa;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.muxigame.console.client.ConsoleClient;
import net.muxigame.console.client.LinkPlayClient;
import net.muxigame.console.client.input.NativeControls;
import net.muxigame.console.client.ui.GameScreen;
import net.muxigame.console.domain.Hashing;
import net.muxigame.console.mc.Wire;

/** Real invitation entry and normal GUI input callbacks. All protocol observation is read-only. */
final class LinkActions {
    private static final ArrayDeque<JsonObject> events=new ArrayDeque<>();
    private static JsonObject lastStart=new JsonObject(),lastEnd=new JsonObject();
    private static long audioBytes,audioNonzero,audioCrossings,audioFrames,audioUnknownRatePackets;
    private static double audioSeconds;private static int minAudioRate=Integer.MAX_VALUE,maxAudioRate;private static short previousAudio;private static boolean havePreviousAudio;
    static void received(Wire.Message message){
        if(!message.operation().startsWith("link_"))return;JsonObject b=message.body(),event=new JsonObject();event.addProperty("operation",message.operation());
        for(String name:List.of("link","epoch","port","frame","reason","message"))if(b.has(name))event.add(name,b.get(name));
        event.addProperty("bytes",message.data().length);if(message.data().length>0)event.addProperty("sha256",Hashing.sha256(message.data()));
        events.add(event);while(events.size()>128)events.removeFirst();
        if(message.operation().equals("link_start")){lastStart=b.deepCopy();audioBytes=audioNonzero=audioCrossings=audioFrames=audioUnknownRatePackets=0;audioSeconds=0;minAudioRate=Integer.MAX_VALUE;maxAudioRate=0;havePreviousAudio=false;}
        if(message.operation().equals("link_end"))lastEnd=b.deepCopy();
        if(message.operation().equals("link_audio")){byte[] pcm=message.data();audioBytes+=pcm.length;int rate=b.has("rate")?b.get("rate").getAsInt():0;int frames=pcm.length/4;audioFrames+=frames;
            if(rate>=8000&&rate<=192000&&pcm.length%4==0){audioSeconds+=(double)frames/rate;minAudioRate=Math.min(minAudioRate,rate);maxAudioRate=Math.max(maxAudioRate,rate);}else audioUnknownRatePackets++;
            for(int i=0;i+3<pcm.length;i+=4){short sample=(short)((pcm[i]&255)|(pcm[i+1]&255)<<8);if(sample!=0)audioNonzero++;if(havePreviousAudio&&previousAudio<=0&&sample>0)audioCrossings++;previousAudio=sample;havePreviousAudio=true;}}
    }
    static void command(Minecraft mc,JsonObject j,QaOwner owner)throws Exception{
        if(mc.player==null||mc.getConnection()==null)throw new IllegalStateException("Actual connected player required");
        switch(j.get("action").getAsString()){
            case "linkqa_open"->{if(!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Empty actual cursor required before changing GUI");LinkPlayClient.INSTANCE.open();}
            case "linkqa_source"->{if(!(mc.screen instanceof GameScreen)||!LinkPlayClient.INSTANCE.ready())throw new IllegalStateException("Actual ready link game screen required");ConsoleClient.INSTANCE.setInputSource(()->NativeControls.sample(LinkPlayClient.INSTANCE.platform()));}
            case "linkqa_key"->{if(!(mc.screen instanceof GameScreen)||!LinkPlayClient.INSTANCE.ready())throw new IllegalStateException("Actual link screen required");int key=j.get("key").getAsInt();if(j.get("pressed").getAsBoolean())mc.screen.keyPressed(key,0,0);else mc.screen.keyReleased(key,0,0);}
            case "linkqa_boot"->{
                if(ConsoleClient.INSTANCE.standaloneRunning()||LinkPlayClient.INSTANCE.active())throw new IllegalStateException("Diagnostic-only firmware must be prepared before emulation");
                Path role=owner.validatedRoot().resolve(System.getProperty("qa.local.role"));if(!mc.gameDirectory.toPath().toRealPath().equals(role))throw new IllegalStateException("Owned real role root required");
                Path directory=role.resolve("muxi-console/firmware");Files.createDirectories(directory);if(Files.isSymbolicLink(directory)||!directory.toRealPath().equals(directory.toAbsolutePath()))throw new IllegalStateException("Ordinary private diagnostic firmware directory required");
                byte[] bytes;try(var input=LinkActions.class.getResourceAsStream("/diagnostics/original-link-boot.bin")){if(input==null)throw new IllegalStateException("Pinned first-party diagnostic boot missing");bytes=input.readNBytes(257);}
                if(bytes.length!=256||!Hashing.sha256(bytes).equals(owner.pin("link-boot.sha256")))throw new IllegalStateException("Diagnostic-only boot pin differs");
                for(String name:List.of("dmg_boot.bin","mgb_boot.bin")){Path target=directory.resolve(name);if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(target)||!Hashing.sha256(target).equals(owner.pin("link-boot.sha256")))throw new IllegalStateException("Refuse to overwrite existing firmware");}else Files.write(target,bytes,StandardOpenOption.CREATE_NEW);}
            }
            case "linkqa_observe"->{ }
            default->throw new IllegalArgumentException("Known real link frontend only");
        }
    }
    static JsonObject observe(){
        var link=LinkPlayClient.INSTANCE;JsonObject out=new JsonObject();out.addProperty("active",link.active());out.addProperty("ready",link.ready());out.addProperty("hasPicture",link.hasPicture());out.addProperty("port",link.port());out.addProperty("preparing",link.preparing());out.addProperty("status",link.status());out.addProperty("card",link.card()==null?"":link.card().toString());
        out.add("players",link.players());JsonObject invitation=link.invitation();out.add("invitation",invitation==null?new JsonObject():invitation);out.add("lastStart",lastStart.deepCopy());out.add("lastEnd",lastEnd.deepCopy());JsonArray history=new JsonArray();events.forEach(e->history.add(e.deepCopy()));out.add("events",history);
        JsonArray widgets=new JsonArray();var mc=Minecraft.getInstance();if(mc.screen!=null)for(var child:mc.screen.children())if(child instanceof net.minecraft.client.gui.components.Button button){JsonObject widget=new JsonObject();widget.addProperty("text",button.getMessage().getString());widget.addProperty("active",button.active);widgets.add(widget);}out.add("widgets",widgets);
        out.addProperty("receivedAudioBytes",audioBytes);out.addProperty("receivedAudioNonzero",audioNonzero);out.addProperty("receivedAudioCrossings",audioCrossings);out.addProperty("receivedAudioFrames",audioFrames);out.addProperty("receivedAudioSeconds",audioSeconds);out.addProperty("receivedAudioMinRate",minAudioRate==Integer.MAX_VALUE?0:minAudioRate);out.addProperty("receivedAudioMaxRate",maxAudioRate);out.addProperty("receivedAudioUnknownRatePackets",audioUnknownRatePackets);out.addProperty("physicalInput",false);return out;
    }
}
