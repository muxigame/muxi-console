package net.muxigame.consoleqa;

import com.google.gson.*;
import java.io.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.muxigame.console.client.ConsoleClient;
import net.muxigame.console.client.LinkPlayClient;
import net.muxigame.console.client.ui.GameScreen;
import net.muxigame.console.mc.Wire;
import net.muxigame.console.runtime.*;

/** Normal DS callbacks and read-only actual CPU frame/input observations. No emulator setters. */
final class LinkDsActions {
    private static JsonArray stepInputs=new JsonArray();private static long stepFrame=-1,readBytes,replies;
    private static final LinkedHashMap<String,JsonObject> checkpoints=new LinkedHashMap<>();
    static void tick(){
        var link=LinkPlayClient.INSTANCE;var c=link.dsCheckpoint();if(c.link().isEmpty())return;String id=c.link()+"/"+c.epoch()+"/"+c.frame()+"/"+c.ending();JsonObject row=checkpoints.computeIfAbsent(id,ignored->{JsonObject value=new JsonObject();value.addProperty("link",c.link());value.addProperty("frame",c.frame());value.addProperty("ending",c.ending());value.addProperty("maxFrameDuringUpload",c.frame());value.addProperty("uploadTicks",0);return value;});
        row.addProperty("stage",c.stage());row.addProperty("inFlight",c.inFlight());row.addProperty("captureNanos",c.captureNanos());row.addProperty("uploadNanos",c.uploadNanos());row.addProperty("uploadedBytes",c.uploadedBytes());row.addProperty("totalBytes",c.totalBytes());row.addProperty("peakQueued",c.peakQueued());
        if(link.active()&&c.inFlight()&&c.stage().equals("uploading")){row.addProperty("uploadTicks",row.get("uploadTicks").getAsInt()+1);row.addProperty("maxFrameDuringUpload",Math.max(row.get("maxFrameDuringUpload").getAsLong(),ConsoleClient.INSTANCE.snapshot().frames()));}
        while(checkpoints.size()>16)checkpoints.remove(checkpoints.keySet().iterator().next());
    }
    static void received(Wire.Message message){
        if(message.operation().equals("link_start")){stepInputs=new JsonArray();stepFrame=-1;readBytes=replies=0;}
        if(message.operation().equals("link_ds_reply")){readBytes+=message.data().length;replies++;}
        if(message.operation().equals("link_step"))try{var input=new DataInputStream(new ByteArrayInputStream(message.data()));JsonArray ports=new JsonArray();ports.add(new Gson().toJsonTree(InputCodec.readPort(input)));ports.add(new Gson().toJsonTree(InputCodec.readPort(input)));if(input.available()!=0)throw new IOException("Trailing actual input");stepInputs=ports;stepFrame=message.body().get("frame").getAsLong();}catch(IOException failure){throw new IllegalStateException(failure);}
    }
    static void command(Minecraft mc,JsonObject j){
        if(j.get("action").getAsString().equals("linkds_observe"))return;
        if(!(mc.screen instanceof GameScreen screen)||!LinkPlayClient.INSTANCE.ready()||LinkPlayClient.INSTANCE.platform()!=net.muxigame.console.domain.Platform.NDS)throw new IllegalStateException("Actual ready DS link surface required");
        if(!j.get("action").getAsString().equals("linkds_pointer"))throw new IllegalArgumentException("Known DS frontend action only");
        String id=j.get("surface").getAsString();double x=j.get("x").getAsDouble(),y=j.get("y").getAsDouble();if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||x>1||y<0||y>1)throw new IllegalArgumentException("Normalized touch location required");
        JsonObject area=null;for(var value:screen.presentationSnapshot().getAsJsonArray("screens")){var candidate=value.getAsJsonObject();if(candidate.get("id").getAsString().equals(id)&&candidate.get("touchable").getAsBoolean())area=candidate;}
        if(area==null)throw new IllegalStateException("Actual rendered touchable lower screen required");var rect=area.getAsJsonArray("rect");double gx=rect.get(0).getAsDouble()+x*rect.get(2).getAsDouble(),gy=rect.get(1).getAsDouble()+y*rect.get(3).getAsDouble();if(j.get("pressed").getAsBoolean())screen.mouseClicked(gx,gy,0);else screen.mouseReleased(gx,gy,0);
    }
    private static boolean green(int pixel){return (pixel>>>8&255)>180&&(pixel&255)<80;}
    static JsonObject observe(Minecraft mc){
        tick();JsonObject out=new JsonObject();out.add("checkpoint",new Gson().toJsonTree(LinkPlayClient.INSTANCE.dsCheckpoint()));JsonArray captures=new JsonArray();checkpoints.values().forEach(row->captures.add(row.deepCopy()));out.add("checkpoints",captures);out.add("stepInputs",stepInputs.deepCopy());out.addProperty("stepFrame",stepFrame);out.addProperty("readBytes",readBytes);out.addProperty("replies",replies);out.add("surfaces",new Gson().toJsonTree(ConsoleClient.INSTANCE.surfaces()));out.add("input",new Gson().toJsonTree(ConsoleClient.INSTANCE.inputFeedback().input()));
        var texture=ConsoleClient.INSTANCE.picture();if(texture!=null&&mc.getTextureManager().getTexture(texture)instanceof DynamicTexture dynamic&&dynamic.getPixels()!=null){var image=dynamic.getPixels();if(image.getWidth()==256&&image.getHeight()==384){int counter=0,sequence=0;for(int bit=0;bit<8;bit++)if(green(image.getPixelRGBA(36+bit*8,36)))counter|=1<<bit;for(int bit=0;bit<16;bit++)if(green(image.getPixelRGBA(36+bit*8,12)))sequence|=1<<bit;out.addProperty("counter",counter);out.addProperty("peerSequence",sequence);out.addProperty("radioWitness",green(image.getPixelRGBA(12,12)));}}
        return out;
    }
    private LinkDsActions(){}
}
