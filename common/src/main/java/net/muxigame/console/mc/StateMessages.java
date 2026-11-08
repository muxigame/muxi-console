package net.muxigame.console.mc;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.domain.*;

/** Opaque, bounded state transport; every chunk is authorized against the current physical session. */
final class StateMessages {
    private record Pending(UUID id,String room,long epoch,MachineSaveKey key){}
    private static final Map<MinecraftServer,Map<UUID,Pending>> PENDING=new IdentityHashMap<>();
    private static Map<UUID,Pending> pending(MinecraftServer s){return PENDING.computeIfAbsent(s,k->new HashMap<>());}
    static void attachLatest(ConsoleServer.WorldState world,ConsoleServer.Room room,JsonObject target){
        try{world.states.latest(room.saveKey).ifPresent(v->target.add("state",json(v)));}
        catch(IOException|RuntimeException invalid){target.addProperty("saveError","服务器存档暂不可读取："+invalid.getMessage());}
    }
    private static JsonObject json(MachineStateVault.Snapshot snapshot){JsonObject j=new JsonObject();j.addProperty("machine",snapshot.machineUUID().toString());j.addProperty("content",snapshot.contentSHA());j.addProperty("generation",snapshot.generationID().toString());j.addProperty("hash",snapshot.hash());j.addProperty("size",snapshot.size());j.addProperty("fingerprint",snapshot.fingerprint());j.addProperty("frame",snapshot.frame());return j;}
    private static void key(JsonObject body,ConsoleServer.Room room){if(!body.has("machine")||!body.has("content")||!body.get("machine").getAsString().equals(room.saveKey.machineUUID().toString())||!body.get("content").getAsString().equals(room.saveKey.contentSHA()))throw new DomainException("save_identity","存档的机器或游戏身份已改变");}
    static boolean receive(ServerPlayer player,ConsoleServer.WorldState world,Wire.Message message)throws IOException{
        String op=message.operation();if(!op.startsWith("state_"))return false;JsonObject body=message.body();ConsoleServer.Room room=op.equals("state_read")?ConsoleServer.ownedRoom(player,world,body):ConsoleServer.ownedRoomForSave(player,world,body);
        if(op.equals("state_read")){
            try{key(body,room);if(room.card.platform().sharedRuntime()&&!player.getUUID().equals(room.session.host()))throw new DomainException("permission","此机型的远程控制席只接收游戏音画");UUID generation=UUID.fromString(body.get("generation").getAsString());long offset=body.get("offset").getAsLong();byte[] bytes=world.states.read(room.saveKey,generation,offset,24576);JsonObject reply=new JsonObject();reply.addProperty("request",body.get("request").getAsString());reply.addProperty("offset",offset);Wire.send(player,"state_chunk",reply,bytes);}
            catch(IOException|RuntimeException denied){JsonObject reply=new JsonObject();reply.addProperty("request",body.get("request").getAsString());reply.addProperty("reason",denied.getMessage());Wire.send(player,"state_denied",reply);}
            return true;
        }
        key(body,room);if(!player.getUUID().equals(room.session.host()))throw new DomainException("save","只有当前主控制席可以保存会话状态");
        switch(op){
            case "state_begin" -> {
                if(room.fingerprint.isEmpty())throw new DomainException("save","模拟器尚未准备好");long frame=body.get("frame").getAsLong();if(frame<0||frame>room.frame)throw new DomainException("save","存档帧不在会话范围内");
                var status=world.states.begin(player.getUUID(),room.saveKey,room.fingerprint,frame,body.get("size").getAsLong(),body.get("hash").getAsString(),System.currentTimeMillis());pending(player.server).put(player.getUUID(),new Pending(status.uploadId(),room.key,room.session.epoch(),room.saveKey));ack(player,status);
            }
            case "state_piece" -> {
                Pending transfer=pending(player.server).get(player.getUUID());UUID id=UUID.fromString(body.get("upload").getAsString());if(transfer==null||!transfer.id.equals(id)||!transfer.room.equals(room.key)||transfer.epoch!=room.session.epoch()||!transfer.key.equals(room.saveKey))throw new DomainException("save","存档上传会话已改变");
                var status=world.states.append(player.getUUID(),id,body.get("offset").getAsLong(),message.data(),System.currentTimeMillis());ack(player,status);
            }
            case "state_commit" -> {
                Pending transfer=pending(player.server).get(player.getUUID());UUID id=UUID.fromString(body.get("upload").getAsString());if(transfer==null||!transfer.id.equals(id)||!transfer.room.equals(room.key)||transfer.epoch!=room.session.epoch()||!transfer.key.equals(room.saveKey))throw new DomainException("save","存档提交会话已改变");
                MachineStateVault.Snapshot saved=world.states.commit(player.getUUID(),id,System.currentTimeMillis());pending(player.server).remove(player.getUUID());JsonObject done=json(saved);done.addProperty("upload",id.toString());Wire.send(player,"state_committed",done);ConsoleServer.saved(player.server,world,room,saved);
            }
            case "state_abort" -> cancel(player.server,world,player.getUUID());
            default -> throw new DomainException("save","未知存档操作");
        }return true;
    }
    private static void ack(ServerPlayer player,StateVault.Status status){JsonObject j=new JsonObject();j.addProperty("upload",status.uploadId().toString());j.addProperty("offset",status.offset());j.addProperty("total",status.total());Wire.send(player,"state_ack",j);}
    static void cancel(MinecraftServer server,ConsoleServer.WorldState world,UUID actor){Pending p=pending(server).remove(actor);if(p!=null)try{world.states.abort(actor,p.id);}catch(IOException e){MuxiConsole.LOG.warn("State upload cleanup failed",e);}}
    static void stopped(MinecraftServer server){PENDING.remove(server);}
}
