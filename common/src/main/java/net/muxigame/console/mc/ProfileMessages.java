package net.muxigame.console.mc;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.domain.*;

/** Authorized machine/platform native files. Profile commit alone never resumes or completes a room. */
final class ProfileMessages {
    private static final int MAX_TRACKED=16;
    private record Scope(UUID actor,String room,long epoch,UUID machine,String content,UUID transaction){ }
    private static final class Pending {
        final Scope scope;final UUID id,previous;final MachineProfileVault.Key key;final long size,frame;final String hash;
        long offset;
        Pending(Scope scope,StateVault.Status status,UUID previous,MachineProfileVault.Key key,long frame,String hash){this.scope=scope;id=status.uploadId();this.previous=previous;this.key=key;size=status.total();offset=status.offset();this.frame=frame;this.hash=hash;}
    }
    private record Committed(Scope scope,MachineProfileVault.Snapshot snapshot){ }
    private static final class Progress {
        final Scope scope;final SaveProgressWatch watch;long pausedFrame=-1;
        Progress(Scope scope,SaveProgressWatch watch){this.scope=scope;this.watch=watch;}
    }
    private static final class Tracking {
        final ConsoleServer.WorldState world;
        final Map<UUID,Pending> pending=new HashMap<>();
        final LinkedHashMap<UUID,Committed> committed=new LinkedHashMap<>();
        final Map<String,Progress> progress=new HashMap<>();
        final SaveTransactionTombstones<Scope> retired=new SaveTransactionTombstones<>();
        final Map<Scope,MachineProfileVault.Snapshot> finishedProfiles=new HashMap<>();
        Tracking(ConsoleServer.WorldState world){this.world=world;}
    }
    private static final Map<MinecraftServer,Tracking> TRACKING=new IdentityHashMap<>();
    private static Tracking tracking(MinecraftServer server,ConsoleServer.WorldState world){Tracking value=TRACKING.computeIfAbsent(server,ignored->new Tracking(world));if(value.world!=world)throw new IllegalStateException("Profile world identity changed");return value;}
    private static boolean nativeFiles(Platform platform){return platform==Platform.NDS||platform==Platform.N3DS||platform==Platform.WII;}
    private static MachineProfileVault.Key key(ConsoleServer.Room room){return new MachineProfileVault.Key(room.saveKey.machineUUID(),room.card.platform());}
    private static long now(){return System.currentTimeMillis();}
    private static void identity(JsonObject body,ConsoleServer.Room room){
        if(!body.has("machine")||!body.has("content")||!body.get("machine").getAsString().equals(room.saveKey.machineUUID().toString())||!body.get("content").getAsString().equals(room.saveKey.contentSHA())
                ||body.has("platform")&&!body.get("platform").getAsString().equals(room.card.platform().id()))throw new DomainException("save_identity","机器、游戏或平台身份已改变");
    }
    private static void host(UUID actor,ConsoleServer.Room room){if(!actor.equals(room.session.host()))throw new DomainException("permission","只有当前主控制席可以读写机器文件");}
    private static Scope scope(UUID actor,JsonObject body,ConsoleServer.Room room){
        identity(body,room);host(actor,room);return new Scope(actor,room.key,room.session.epoch(),room.saveKey.machineUUID(),room.saveKey.contentSHA(),UUID.fromString(body.get("saveTransaction").getAsString()));
    }
    private static Scope parsed(UUID actor,JsonObject body){return new Scope(actor,body.get("room").getAsString(),body.get("epoch").getAsLong(),UUID.fromString(body.get("machine").getAsString()),Hashing.normalizeSha256(body.get("content").getAsString()),UUID.fromString(body.get("saveTransaction").getAsString()));}
    private static boolean live(Scope scope,ConsoleServer.WorldState world){ConsoleServer.Room room=world.rooms.get(scope.room);return room!=null&&room.session.epoch()==scope.epoch&&room.saveKey.machineUUID().equals(scope.machine)&&room.saveKey.contentSHA().equals(scope.content)&&room.session.host().equals(scope.actor);}
    private static void prune(MinecraftServer server,ConsoleServer.WorldState world){
        Tracking t=tracking(server,world);Set<Scope> stale=new HashSet<>();for(Pending p:List.copyOf(t.pending.values()))if(!live(p.scope,world))stale.add(p.scope);
        for(var value:List.copyOf(t.committed.values()))if(!live(value.scope,world))stale.add(value.scope);for(var value:List.copyOf(t.progress.values()))if(!live(value.scope,world))stale.add(value.scope);
        for(Scope scope:stale)close(world,t,scope,SaveTransactionTombstones.Outcome.CANCELLED);
        t.finishedProfiles.keySet().retainAll(t.retired.retained(now()));
    }
    static void attachLatest(ConsoleServer.WorldState world,ConsoleServer.Room room,JsonObject target){
        if(!nativeFiles(room.card.platform()))return;target.addProperty("nativeFiles",true);
        try{world.profiles.latest(key(room)).ifPresent(snapshot->target.add("profile",metadata(snapshot)));}
        catch(IOException|RuntimeException invalid){target.addProperty("saveError","机器文件暂不可读取，请重新加入游戏");}
    }
    private static JsonObject metadata(MachineProfileVault.Snapshot snapshot){JsonObject j=new JsonObject();j.addProperty("machine",snapshot.key().machine().toString());j.addProperty("platform",snapshot.key().platform().id());j.addProperty("generation",snapshot.generation().toString());j.addProperty("hash",snapshot.hash());j.addProperty("size",snapshot.size());j.addProperty("frame",snapshot.frame());return j;}
    private static JsonObject address(Scope scope){JsonObject j=new JsonObject();j.addProperty("room",scope.room);j.addProperty("epoch",scope.epoch);j.addProperty("machine",scope.machine.toString());j.addProperty("content",scope.content);j.addProperty("saveTransaction",scope.transaction.toString());return j;}
    private static void ack(ServerPlayer player,Pending pending){JsonObject j=address(pending.scope);j.addProperty("platform",pending.key.platform().id());j.addProperty("upload",pending.id.toString());j.addProperty("offset",pending.offset);j.addProperty("total",pending.size);Wire.send(player,"profile_ack",j);}
    private static void committed(ServerPlayer player,Scope scope,MachineProfileVault.Snapshot snapshot,UUID upload,boolean unchanged){JsonObject j=metadata(snapshot);for(var e:address(scope).entrySet())j.add(e.getKey(),e.getValue());if(upload!=null)j.addProperty("upload",upload.toString());if(unchanged)j.addProperty("unchanged",true);Wire.send(player,"profile_committed",j);}
    static boolean receive(ServerPlayer player,ConsoleServer.WorldState world,Wire.Message message)throws IOException{
        String op=message.operation();if(!op.startsWith("profile_")&&!op.equals("save_progress"))return false;JsonObject body=message.body();prune(player.server,world);
        if(retired(player.server,world,player.getUUID(),body)){
            if(op.equals("profile_commit")||op.equals("profile_begin")){Scope old=parsed(player.getUUID(),body);var snapshot=tracking(player.server,world).finishedProfiles.get(old);if(snapshot!=null)committed(player,old,snapshot,body.has("upload")?UUID.fromString(body.get("upload").getAsString()):null,true);}
            return true;
        }
        if(op.equals("profile_read")){
            try{ConsoleServer.Room room=ConsoleServer.ownedRoom(player,world,body);identity(body,room);host(player.getUUID(),room);if(!nativeFiles(room.card.platform()))throw new DomainException("profile","此机型没有原生机器文件");
                UUID generation=UUID.fromString(body.get("generation").getAsString());long offset=body.get("offset").getAsLong();byte[] bytes=world.profiles.read(key(room),generation,offset,24576);JsonObject reply=new JsonObject();reply.add("request",body.get("request"));reply.addProperty("offset",offset);Wire.send(player,"profile_chunk",reply,bytes);
            }catch(IOException|RuntimeException denied){JsonObject reply=new JsonObject();reply.add("request",body.get("request"));reply.addProperty("reason","机器文件不可读取，请重新加入控制席");Wire.send(player,"profile_denied",reply);}return true;
        }
        ConsoleServer.Room room=ConsoleServer.ownedRoomForSave(player,world,body);Scope scope=scope(player.getUUID(),body,room);Tracking t=tracking(player.server,world);
        if(op.equals("save_progress")){Progress progress=watch(t,scope);String stage=body.get("stage").getAsString();if(progress.watch.observe(stage,body.get("bytes").getAsLong(),body.get("total").getAsLong(),now())){
                ConsoleServer.saveProgress(room);
                if(stage.equals("serializing")&&progress.pausedFrame<0){progress.pausedFrame=room.frame;JsonObject paused=address(scope);paused.addProperty("frame",progress.pausedFrame);Wire.send(player,"save_paused",paused);}
            }return true;}
        if(!nativeFiles(room.card.platform()))throw new DomainException("profile","此机型没有原生机器文件");
        switch(op){
            case "profile_begin"->{
                if(room.fingerprint.isEmpty())throw new DomainException("profile","模拟器尚未准备好");long frame=body.get("frame").getAsLong(),size=body.get("size").getAsLong();String hash=Hashing.normalizeSha256(body.get("hash").getAsString());
                if(frame<0||frame>room.frame||size<1||size>MachineProfileVault.LIMITS.maxStateBytes())throw new DomainException("profile","机器文件大小或存档帧无效");
                UUID previous=body.has("previous")?UUID.fromString(body.get("previous").getAsString()):null;watch(t,scope);
                Pending old=t.pending.get(player.getUUID());if(old!=null){if(!old.scope.equals(scope)||old.size!=size||old.frame!=frame||!old.hash.equals(hash)||!Objects.equals(old.previous,previous))throw new DomainException("profile_session","已有不同机器文件正在保存");ack(player,old);return true;}
                var current=world.profiles.latest(key(room));Committed done=t.committed.get(player.getUUID());
                if(done!=null&&done.scope.equals(scope)&&current.isPresent()&&current.get().generation().equals(done.snapshot.generation())&&current.get().hash().equals(hash)&&current.get().size()==size){committed(player,scope,current.get(),null,true);return true;}
                if(!Objects.equals(current.map(MachineProfileVault.Snapshot::generation).orElse(null),previous))throw new DomainException("profile_stale","机器文件已改变，请重新加载游戏");
                if(t.committed.size()>=MAX_TRACKED&&!t.committed.containsKey(player.getUUID())||t.pending.size()>=MAX_TRACKED&&!t.pending.containsKey(player.getUUID()))throw new DomainException("limit","同时保存的机器过多，请稍后重试");
                t.committed.remove(player.getUUID());
                if(current.isPresent()&&current.get().hash().equals(hash)&&current.get().size()==size){t.committed.put(player.getUUID(),new Committed(scope,current.get()));ConsoleServer.saveProgress(room);committed(player,scope,current.get(),null,true);return true;}
                var status=world.profiles.begin(player.getUUID(),key(room),previous,frame,size,hash,now());Pending upload=new Pending(scope,status,previous,key(room),frame,hash);t.pending.put(player.getUUID(),upload);ConsoleServer.saveProgress(room);ack(player,upload);
            }
            case "profile_piece"->{Pending p=require(t,scope,body);var status=world.profiles.append(player.getUUID(),p.id,body.get("offset").getAsLong(),message.data(),now());p.offset=status.offset();ConsoleServer.saveProgress(room);ack(player,p);}
            case "profile_commit"->{Pending p=require(t,scope,body);MachineProfileVault.Snapshot snapshot=world.profiles.commit(player.getUUID(),p.id,now());t.pending.remove(player.getUUID(),p);t.committed.put(player.getUUID(),new Committed(scope,snapshot));ConsoleServer.saveProgress(room);committed(player,scope,snapshot,p.id,false);}
            case "profile_abort"->cancelFor(player.server,world,player.getUUID(),body);
            default->throw new DomainException("profile","未知机器文件操作");
        }return true;
    }
    private static Progress watch(Tracking t,Scope scope){
        Progress previous=t.progress.get(scope.room);if(previous!=null){if(!previous.scope.equals(scope))throw new DomainException("save_session","上一项存档尚未结束");return previous;}
        if(t.progress.size()>=MAX_TRACKED)throw new DomainException("limit","同时保存的机器过多，请稍后重试");if(!t.retired.reserve(scope,now()))throw new DomainException("save_session","该项存档已经结束");Progress next=new Progress(scope,new SaveProgressWatch(now()));t.progress.put(scope.room,next);return next;
    }
    private static Pending require(Tracking t,Scope scope,JsonObject body){Pending p=t.pending.get(scope.actor);if(p==null||!p.scope.equals(scope)||!p.id.toString().equals(body.get("upload").getAsString()))throw new DomainException("profile_session","机器文件上传会话已改变");return p;}
    /** Called at state_begin after the normal room/host authorization, before beginning its state vault upload. */
    static UUID stateGeneration(ConsoleServer.WorldState world,ConsoleServer.Room room,UUID actor,JsonObject body)throws IOException{
        if(!nativeFiles(room.card.platform()))return null;Scope scope=scope(actor,body,room);Committed done=TRACKING.values().stream().filter(t->t.world==world).map(t->t.committed.get(actor)).filter(Objects::nonNull).filter(c->c.scope.equals(scope)).findFirst().orElse(null);
        UUID supplied=body.has("profileGeneration")?UUID.fromString(body.get("profileGeneration").getAsString()):null;
        var current=world.profiles.latest(key(room));if(done==null||supplied==null||!done.snapshot.generation().equals(supplied)||current.isEmpty()||!current.get().generation().equals(supplied))throw new DomainException("profile_stale","请先提交当前机器文件，再保存即时状态");return supplied;
    }
    /** Root calls before producing another authoritative frame. Expired preparation never freezes a room. */
    public static boolean isSerializing(MinecraftServer server,ConsoleServer.Room room){
        Tracking t=TRACKING.get(server);if(t==null||t.world.rooms.get(room.key)!=room)return false;Progress progress=t.progress.get(room.key);
        if(progress==null||progress.scope.epoch!=room.session.epoch()||!progress.scope.machine.equals(room.saveKey.machineUUID())||!progress.scope.content.equals(room.saveKey.contentSHA())||!progress.scope.actor.equals(room.session.host()))return false;
        if(progress.watch.expired(now())){
            Scope scope=progress.scope;JsonObject body=address(scope);StateMessages.cancelFor(server,t.world,scope.actor,body);close(t.world,t,scope,SaveTransactionTombstones.Outcome.EXPIRED);
            ServerPlayer player=server.getPlayerList().getPlayer(scope.actor);if(player!=null){body.addProperty("operation","save_progress");body.addProperty("code","save_timeout");body.addProperty("message","存档操作已超时，请重试");Wire.send(player,"error",body);}return false;
        }
        return progress.pausedFrame>=0&&progress.watch.isSerializing(now());
    }
    private static void abort(ConsoleServer.WorldState world,Tracking t,Pending pending){try{world.profiles.abort(pending.scope.actor,pending.id);t.pending.remove(pending.scope.actor,pending);}catch(IOException failure){MuxiConsole.LOG.warn("Native profile cleanup failed actor={}",pending.scope.actor);}}
    static boolean retired(MinecraftServer server,ConsoleServer.WorldState world,UUID actor,JsonObject body){
        try{Tracking t=TRACKING.get(server);return t!=null&&t.world==world&&t.retired.outcome(parsed(actor,body),now()).isPresent();}catch(RuntimeException missing){return false;}
    }
    private static void close(ConsoleServer.WorldState world,Tracking t,Scope scope,SaveTransactionTombstones.Outcome outcome){
        if(t.retired.outcome(scope,now()).isPresent())return;
        Committed committed=t.committed.get(scope.actor);if(outcome==SaveTransactionTombstones.Outcome.COMPLETED&&committed!=null&&committed.scope.equals(scope))t.finishedProfiles.put(scope,committed.snapshot);
        t.retired.retire(scope,outcome,now());Pending p=t.pending.get(scope.actor);if(p!=null&&p.scope.equals(scope))abort(world,t,p);
        if(committed!=null&&committed.scope.equals(scope))t.committed.remove(scope.actor,committed);Progress progress=t.progress.get(scope.room);if(progress!=null&&progress.scope.equals(scope))t.progress.remove(scope.room,progress);
    }
    static void cancelFor(MinecraftServer server,ConsoleServer.WorldState world,UUID actor,JsonObject body){
        try{Scope scope=parsed(actor,body);Tracking t=TRACKING.get(server);if(t==null||t.world!=world||t.retired.outcome(scope,now()).isPresent())return;Pending p=t.pending.get(actor);if(p!=null&&p.scope.equals(scope)&&body.has("upload")&&!p.id.toString().equals(body.get("upload").getAsString()))return;
            boolean known=p!=null&&p.scope.equals(scope)||t.committed.containsKey(actor)&&t.committed.get(actor).scope.equals(scope)||t.progress.containsKey(scope.room)&&t.progress.get(scope.room).scope.equals(scope);
            if(known)close(world,t,scope,SaveTransactionTombstones.Outcome.CANCELLED);
        }catch(RuntimeException stale){/* A malformed or late packet cannot cancel another scope or upload. */}
    }
    static void completeCleanup(MinecraftServer server,ConsoleServer.WorldState world,UUID actor,JsonObject body){try{Tracking t=TRACKING.get(server);if(t!=null&&t.world==world)close(world,t,parsed(actor,body),SaveTransactionTombstones.Outcome.COMPLETED);}catch(RuntimeException invalid){MuxiConsole.LOG.warn("Checkpoint completion history unavailable actor={}",actor);}}
    static void cancel(MinecraftServer server,ConsoleServer.WorldState world,UUID actor){Tracking t=TRACKING.get(server);if(t==null||t.world!=world)return;Set<Scope> scopes=new HashSet<>();Pending p=t.pending.get(actor);if(p!=null)scopes.add(p.scope);Committed c=t.committed.get(actor);if(c!=null)scopes.add(c.scope);for(Progress progress:List.copyOf(t.progress.values()))if(progress.scope.actor.equals(actor))scopes.add(progress.scope);for(Scope scope:scopes)close(world,t,scope,SaveTransactionTombstones.Outcome.CANCELLED);}
    static void stopped(MinecraftServer server){TRACKING.remove(server);}
    private ProfileMessages(){ }
}
