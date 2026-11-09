package net.muxigame.consoleqa;

import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.muxigame.console.client.*;
import net.muxigame.console.client.input.NativeControls;
import net.muxigame.console.client.ui.GameScreen;
import net.muxigame.console.domain.*;
import net.muxigame.console.mc.Wire;
import net.muxigame.console.runtime.*;

/** Normal frontend callbacks, exact wire observations, and reads of the original guest's actual SD records. */
final class Link3dsActions {
    private static JsonArray endpoints=new JsonArray(),stepInputs=new JsonArray();private static long stepFrame=-1,readBytes;
    static void received(Wire.Message message){
        if(message.operation().equals("link_start")){JsonArray rows=message.body().getAsJsonArray("endpoints");if(rows!=null&&rows.size()==2&&rows.get(0).getAsJsonObject().get("platform").getAsString().equals("n3ds")){endpoints=rows.deepCopy();stepInputs=new JsonArray();stepFrame=-1;readBytes=0;}}
        if(message.operation().equals("link_threeds_reply"))readBytes+=message.data().length;
        if(message.operation().equals("link_step")&&LinkPlayClient.INSTANCE.platform()==Platform.N3DS)try{var in=new DataInputStream(new ByteArrayInputStream(message.data()));JsonArray ports=new JsonArray();ports.add(new Gson().toJsonTree(InputCodec.readPort(in)));ports.add(new Gson().toJsonTree(InputCodec.readPort(in)));if(in.available()!=0)throw new IOException("Unexpected actual input tail");stepInputs=ports;stepFrame=message.body().get("frame").getAsLong();}catch(IOException failure){throw new IllegalStateException(failure);}
    }
    static void command(Minecraft mc,JsonObject j){
        String action=j.get("action").getAsString();if(action.equals("link3ds_observe"))return;
        if(!(mc.screen instanceof GameScreen screen)||!ConsoleClient.INSTANCE.snapshot().ready()||ConsoleClient.INSTANCE.snapshot().width()!=400)throw new IllegalStateException("Actual ready 3DS game screen required");
        if(action.equals("link3ds_source")){ConsoleClient.INSTANCE.setInputSource(()->NativeControls.sample(Platform.N3DS));return;}
        if(action.equals("link3ds_key")){int key=j.get("key").getAsInt();if(j.get("pressed").getAsBoolean())screen.keyPressed(key,0,0);else screen.keyReleased(key,0,0);return;}
        if(!action.equals("link3ds_pointer"))throw new IllegalArgumentException("Known 3DS callback action required");
        double x=j.get("x").getAsDouble(),y=j.get("y").getAsDouble();if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||x>1||y<0||y>1)throw new IllegalArgumentException("Normalized actual touch position required");
        JsonObject area=null;for(var v:screen.presentationSnapshot().getAsJsonArray("screens")){var row=v.getAsJsonObject();if(row.get("id").getAsString().equals("bottom")&&row.get("touchable").getAsBoolean())area=row;}
        if(area==null)throw new IllegalStateException("Rendered 3DS bottom surface required");var rect=area.getAsJsonArray("rect");double gx=rect.get(0).getAsDouble()+rect.get(2).getAsDouble()*x,gy=rect.get(1).getAsDouble()+rect.get(3).getAsDouble()*y;if(j.get("pressed").getAsBoolean())screen.mouseClicked(gx,gy,0);else screen.mouseReleased(gx,gy,0);
    }
    static JsonObject words(byte[] bytes,boolean relocation){
        if(bytes.length!=(relocation?12:48))throw new IllegalArgumentException("Complete original guest record required");ByteBuffer data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);JsonObject row=new JsonObject();String[] keys=relocation?new String[]{"magic","writes","role"}:new String[]{"magic","role","stage","frame","sent","received","peerRole","peerSequence","peerChallenge","result","localNode","totalNodes"};for(String key:keys)row.addProperty(key,Integer.toUnsignedLong(data.getInt()));row.addProperty("sha256",Hashing.sha256(bytes));return row;
    }
    private static JsonObject read(Path profile,String name,int size)throws IOException{
        Path path=profile.resolve("Azahar/sdmc").resolve(name);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))return null;if(!path.toAbsolutePath().normalize().equals(path.toRealPath()))throw new IOException("Redirected diagnostic SD record");byte[] bytes=Files.readAllBytes(path);if(bytes.length<size)return null;if(bytes.length>size)throw new IOException("Unexpected diagnostic SD record size");JsonObject row=words(bytes,size==12);row.addProperty("path",path.toString());return row;
    }
    static JsonObject observe(Minecraft mc){
        JsonObject out=new JsonObject();out.add("surfaces",new Gson().toJsonTree(ConsoleClient.INSTANCE.surfaces()));out.add("input",new Gson().toJsonTree(ConsoleClient.INSTANCE.inputFeedback().input()));out.add("stepInputs",stepInputs.deepCopy());out.addProperty("stepFrame",stepFrame);out.addProperty("readBytes",readBytes);out.add("checkpoint",new Gson().toJsonTree(LinkPlayClient.INSTANCE.dsCheckpoint()));JsonArray machines=new JsonArray();
        for(var v:endpoints){JsonObject e=v.getAsJsonObject(),row=new JsonObject();String id=e.get("machine").getAsString();UUID.fromString(id);row.addProperty("machine",id);row.add("actor",e.get("player"));row.add("content",e.get("hash"));Path profile=mc.gameDirectory.toPath().toAbsolutePath().normalize().resolve("muxi-console/machine-profiles").resolve(id).resolve("n3ds");
            try{JsonObject radio=read(profile,"muxi-original-uds-evidence.bin",48),writes=read(profile,"muxi-original-relocation.bin",12);if(radio!=null)row.add("radio",radio);if(writes!=null)row.add("save",writes);}catch(NoSuchFileException transientWrite){/* fopen/fwrite scheduling may expose an incomplete record. */}catch(IOException failure){row.addProperty("readError",failure.getClass().getSimpleName());}machines.add(row);}
        out.add("machines",machines);return out;
    }
    private Link3dsActions(){}
}
