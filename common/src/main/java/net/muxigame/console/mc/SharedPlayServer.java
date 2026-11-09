package net.muxigame.console.mc;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.*;
import net.muxigame.console.compat.BrowserFrameCodec;
import net.muxigame.console.domain.Platform;
import net.minecraft.server.level.ServerPlayer;

/** Host-authoritative pixels and PCM for occupied controller seats; spectators get no shared-play capability. */
public final class SharedPlayServer {
    public static final int AUDIO_PART_BYTES=24576, AUDIO_MAX_PARTS=4, AUDIO_MAX_BYTES=AUDIO_PART_BYTES*AUDIO_MAX_PARTS;
    private static final Map<ConsoleServer.Room,Gate> GATES=new WeakHashMap<>();
    /** Pure bounded sequence/rate policy; values never retain Room or player objects. */
    public static final class Gate {
        public final long epoch;private long lastVideo=-1,lastAudio=-1,audioStarted,lastVideoNanos,lastAudioNanos;private double videoTokens=2,audioTokens=AUDIO_MAX_BYTES;private int nextPart,parts,total;
        public Gate(long epoch,long now){if(epoch<0)throw new IllegalArgumentException("epoch");this.epoch=epoch;lastVideoNanos=lastAudioNanos=now;}
        public boolean video(long frame,long now){if(frame<0||frame<=lastVideo)return false;videoTokens=Math.min(2,videoTokens+Math.max(0,now-lastVideoNanos)/1e9*20);lastVideoNanos=now;if(videoTokens<1)return false;videoTokens--;lastVideo=frame;return true;}
        public static void audioBounds(int part,int parts,int total,int bytes){
            if(total<4||total>AUDIO_MAX_BYTES||total%4!=0||parts<1||parts>AUDIO_MAX_PARTS||parts!=(total+AUDIO_PART_BYTES-1)/AUDIO_PART_BYTES||part<0||part>=parts||bytes!=Math.min(AUDIO_PART_BYTES,total-part*AUDIO_PART_BYTES)||bytes%4!=0)throw new IllegalArgumentException("Invalid 48k stereo PCM packet bounds");
        }
        public boolean audio(long frame,int part,int count,int length,int bytes,long now){
            audioBounds(part,count,length,bytes);if(frame<0||frame<lastAudio)return false;
            audioTokens=Math.min(AUDIO_MAX_BYTES,audioTokens+Math.max(0,now-lastAudioNanos)/1e9*192000);lastAudioNanos=now;if(audioTokens<bytes)return false;
            if(frame>lastAudio){if(part!=0)return false;lastAudio=frame;nextPart=0;parts=count;total=length;audioStarted=now;}
            if(now-audioStarted>1_000_000_000L||part!=nextPart||count!=parts||length!=total)return false;
            nextPart++;audioTokens-=bytes;return true;
        }
        public long lastVideo(){return lastVideo;}public long lastAudio(){return lastAudio;}
    }
    private static Gate gate(ConsoleServer.Room room){Gate gate=GATES.get(room);if(gate==null||gate.epoch!=room.session.epoch()){gate=new Gate(room.session.epoch(),System.nanoTime());GATES.put(room,gate);}return gate;}
    private static boolean shared(ConsoleServer.Room room){return room.card.platform().sharedRuntime();}
    private static JsonObject reply(ConsoleServer.Room room,int port,long frame){JsonObject j=new JsonObject();j.addProperty("room",room.key);j.addProperty("epoch",room.session.epoch());j.addProperty("port",port);if(frame>=0)j.addProperty("frame",frame);return j;}
    /** Returns true for every shared_* operation, after rechecking actual current server membership. */
    public static boolean receive(ServerPlayer actor,Wire.Message message)throws IOException{
        if(!message.operation().startsWith("shared_"))return false;JsonObject body=message.body();String key=body.get("room").getAsString();long epoch=body.get("epoch").getAsLong();ConsoleServer.Room room=ConsoleServer.state(actor.server).rooms.get(key);
        // Ordinary epoch transitions can leave authenticated old pixels already in transit.
        if(room==null||room.session.epoch()!=epoch)return true;int port=body.get("port").getAsInt();
        if(!shared(room)||!room.session.controls(actor.getUUID(),port)||!ConsoleServer.validParticipant(actor,room))throw new IOException("共享画面需要当前控制席权限");
        boolean host=room.session.host().equals(actor.getUUID());
        if(message.operation().equals("shared_attach")){if(host)throw new IOException("主控制席必须运行模拟器");room.ready.put(actor.getUUID(),"shared");Wire.send(actor,"shared_ready",reply(room,port,-1));return true;}
        if(!message.operation().equals("shared_frame")&&!message.operation().equals("shared_audio"))throw new IOException("未知共享游戏操作");
        if(!host||!room.ready.containsKey(actor.getUUID())||room.fingerprint.isEmpty())throw new IOException("只有已准备的主控制席可以发送共享游戏音画");
        long frame=body.get("frame").getAsLong();if(room.hostClock)ConsoleServer.observeHostFrame(room,actor.getUUID(),frame);if(frame<0||frame>room.frame)throw new IOException("共享画面批次超出模拟进度");Gate gate=gate(room);long now=System.nanoTime();
        if(message.operation().equals("shared_frame")){
            if(!gate.video(frame,now))return true;BrowserFrameCodec.Image image=BrowserFrameCodec.decode(message.data());var surfaces=net.muxigame.console.compat.SurfaceMetadata.read(body,image.width(),image.height());
            for(var entry:room.session.controllers().entrySet()){ServerPlayer target=target(actor,room,entry.getValue());if(target==null)continue;JsonObject j=reply(room,entry.getKey(),frame);j.addProperty("width",image.width());j.addProperty("height",image.height());net.muxigame.console.compat.SurfaceMetadata.write(j,surfaces);Wire.send(target,"shared_frame",j,message.data());}
        }else{
            int part=body.get("part").getAsInt(),parts=body.get("parts").getAsInt(),total=body.get("totalBytes").getAsInt();if(body.get("rate").getAsInt()!=48000)throw new IOException("共享音频需要48kHz双声道PCM");
            if(!gate.audio(frame,part,parts,total,message.data().length,now))return true;
            for(var entry:room.session.controllers().entrySet()){ServerPlayer target=target(actor,room,entry.getValue());if(target==null)continue;JsonObject j=reply(room,entry.getKey(),frame);j.addProperty("part",part);j.addProperty("parts",parts);j.addProperty("totalBytes",total);j.addProperty("rate",48000);Wire.send(target,"shared_audio",j,message.data());}
        }return true;
    }
    private static ServerPlayer target(ServerPlayer source,ConsoleServer.Room room,UUID id){if(id.equals(room.session.host())||!"shared".equals(room.ready.get(id)))return null;ServerPlayer player=source.server.getPlayerList().getPlayer(id);return player!=null&&room.session.isController(id)&&ConsoleServer.validParticipant(player,room)?player:null;}
    private SharedPlayServer(){}
}
