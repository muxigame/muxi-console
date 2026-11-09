package net.muxigame.console.mc;

import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.domain.*;

/** Two ordinary machine checkpoints and native files. This does not persist a Room's network queues. */
final class Link3dsPersistence {
    private static final long MAX_BYTES=512L*1024*1024;
    private static final class Selection {
        final MachineStateVault.Snapshot[] states=new MachineStateVault.Snapshot[2];
        final MachineProfileVault.Snapshot[] profiles=new MachineProfileVault.Snapshot[2];
        final AtomicInteger reads=new AtomicInteger();
    }
    private static final class Save {
        final Io io;final LinkPlayServer.Room room;final UUID actor,transaction;final long epoch,frame;
        final MachineStateVault.Snapshot[] states=new MachineStateVault.Snapshot[2];
        final MachineProfileVault.Snapshot[] profiles=new MachineProfileVault.Snapshot[2];
        final AtomicInteger queued=new AtomicInteger();volatile boolean cancelled;volatile long touched=System.currentTimeMillis(),nextCleanup;
        UUID profileUpload,stateUpload;int profilePort=-1,statePort=-1;boolean completed;
        Save(Io io,LinkPlayServer.Room room,UUID actor,UUID transaction,long frame){this.io=io;this.room=room;this.actor=actor;this.transaction=transaction;this.frame=frame;epoch=room.lobby.epoch();}
        void check()throws IOException{if(cancelled||io.closed||Thread.currentThread().isInterrupted())throw new InterruptedIOException("3DS checkpoint transaction retired");}
    }
    private static final class Io {
        final MinecraftServer server;final LinkPlayServer.World world;
        final ExecutorService executor=Executors.newSingleThreadExecutor(task->{Thread t=new Thread(task,"playstation-3ds-vault");t.setDaemon(true);return t;});
        final Set<Save> jobs=ConcurrentHashMap.newKeySet();final Map<LinkPlayServer.Room,Save> active=new IdentityHashMap<>();
        final Map<LinkPlayServer.Room,Selection> selections=new IdentityHashMap<>();final Set<LinkPlayServer.Room> finalChecks=Collections.newSetFromMap(new IdentityHashMap<>());
        final AtomicInteger reads=new AtomicInteger();final LinkSaveTransactions transactions=new LinkSaveTransactions();volatile boolean closed;
        Io(MinecraftServer server,LinkPlayServer.World world){this.server=server;this.world=world;}
    }
    private static final Map<LinkPlayServer.World,Io> WORLDS=new IdentityHashMap<>();
    private static Io io(MinecraftServer server,LinkPlayServer.World world){return WORLDS.computeIfAbsent(world,w->new Io(server,w));}
    private static boolean live(Io io,LinkPlayServer.Room room,long epoch){return !io.closed&&io.world.rooms.get(room.lobby.id())==room&&room.lobby.epoch()==epoch;}
    private static boolean live(Save save){return !save.cancelled&&live(save.io,save.room,save.epoch)&&save.io.active.get(save.room)==save;}
    private static LinkSaveTransactions.Key scope(LinkPlayServer.Room room,UUID actor,UUID transaction){return new LinkSaveTransactions.Key(room.lobby.id(),room.lobby.epoch(),actor,transaction);}
    private static boolean n3ds(LinkPlayServer.Room room){return room.lobby.endpoints().stream().allMatch(e->e.platform().equals("n3ds"));}
    static boolean saving(LinkPlayServer.World world,LinkPlayServer.Room room){Io io=WORLDS.get(world);return io!=null&&(io.active.containsKey(room)||io.finalChecks.contains(room));}
    static boolean lockedMachine(ConsoleServer.WorldState state,UUID machine){return WORLDS.values().stream().filter(i->i.world.state==state).flatMap(i->i.jobs.stream()).anyMatch(s->s.room.lobby.endpoints().stream().anyMatch(e->e.machine().equals(machine)));}
    static boolean lockedActor(ConsoleServer.WorldState state,UUID actor){return WORLDS.values().stream().filter(i->i.world.state==state).flatMap(i->i.jobs.stream()).anyMatch(s->s.room.lobby.port(actor)>=0);}
    static boolean lockedCard(ConsoleServer.WorldState state,UUID card){return WORLDS.values().stream().filter(i->i.world.state==state).flatMap(i->i.jobs.stream()).anyMatch(s->s.room.lobby.endpoints().stream().anyMatch(e->e.card().equals(card)));}
    private static MachineProfileVault.Key profileKey(LinkPlayServer.Room room,int port){return new MachineProfileVault.Key(room.lobby.endpoints().get(port).machine(),Platform.N3DS);}
    private static int port(JsonObject body)throws IOException{int value=body.get("endpoint").getAsInt();if(value<0||value>1)throw new IOException("Invalid 3DS checkpoint endpoint");return value;}
    private static JsonObject profile(MachineProfileVault.Snapshot saved){JsonObject j=new JsonObject();j.addProperty("generation",saved.generation().toString());j.addProperty("size",saved.size());j.addProperty("hash",saved.hash());j.addProperty("frame",saved.frame());return j;}
    static void start(MinecraftServer server,LinkPlayServer.World world,LinkPlayServer.Room room){
        if(!n3ds(room))throw new DomainException("link_platform","此存档通道只用于 3DS");
        Io io=io(server,world);long epoch=room.lobby.epoch();Selection selected=new Selection();
        io.executor.execute(()->{try{
            for(int p=0;p<2;p++){
                selected.profiles[p]=world.state.profiles.latest(profileKey(room,p)).orElse(null);
                var saved=world.state.states.latest(room.lobby.endpoints().get(p).key()).orElse(null);
                if(saved!=null&&(!saved.fingerprint().equals(room.singleFingerprints[p])||!Objects.equals(saved.profileGeneration(),selected.profiles[p]==null?null:selected.profiles[p].generation())))saved=null;
                selected.states[p]=saved;
            }
            server.execute(()->{if(!live(io,room,epoch))return;try{io.selections.put(room,selected);room.frame=room.lastSave=0;
                for(var endpoint:room.lobby.endpoints()){
                    ServerPlayer target=LinkPlayServer.player(server,endpoint.actor());if(target==null){LinkPlayServer.close(server,world,room,"伙伴离线，取消连接");return;}
                    JsonObject j=LinkPlayServer.view(server,room);boolean host=room.lobby.host().equals(endpoint.actor());j.addProperty("port",room.lobby.port(endpoint.actor()));j.addProperty("host",host);j.addProperty("bridge",room.core.bridge());j.addProperty("single",room.core.single());j.addProperty("frameBase",0);j.addProperty("queueCheckpoint",false);
                    if(host){JsonArray states=new JsonArray(),profiles=new JsonArray();for(int p=0;p<2;p++){states.add(selected.states[p]==null?new JsonObject():LinkPlayServer.stateJson(selected.states[p]));profiles.add(selected.profiles[p]==null?new JsonObject():profile(selected.profiles[p]));}j.add("states",states);j.add("profiles",profiles);}
                    Wire.send(target,"link_start",j);
                }
            }catch(RuntimeException unavailable){LinkPlayServer.end(server,world,room,"3DS 连接已改变，原进度已保留");}});
        }catch(IOException|RuntimeException failure){server.execute(()->{if(live(io,room,epoch))LinkPlayServer.end(server,world,room,"3DS 进度暂不可读取，原存档已保留");});}});
    }
    @FunctionalInterface private interface Work{JsonObject run()throws Exception;}
    private static void send(Io io,UUID actor,JsonObject request,JsonObject payload,byte[] data){ServerPlayer player=LinkPlayServer.player(io.server,actor);if(player==null)return;JsonObject j=new JsonObject();for(String name:List.of("link","epoch","request","saveTransaction"))if(request.has(name))j.add(name,request.get(name));for(var e:payload.entrySet())j.add(e.getKey(),e.getValue());try{Wire.send(player,"link_threeds_reply",j,data);}catch(RuntimeException disconnected){MuxiConsole.LOG.warn("3DS checkpoint reply unavailable actor={}",actor);}}
    private static void failed(Io io,UUID actor,JsonObject request,Throwable failure){JsonObject j=new JsonObject();for(String name:List.of("link","epoch","request","saveTransaction"))if(request.has(name))j.add(name,request.get(name));j.addProperty("code","link_threeds_io");j.addProperty("message","3DS 进度传输未完成，保留最近有效存档");ServerPlayer p=LinkPlayServer.player(io.server,actor);if(p!=null)try{Wire.send(p,"link_error",j);}catch(RuntimeException ignored){}MuxiConsole.LOG.warn("3DS checkpoint request failed actor={} type={}",actor,failure.getClass().getSimpleName());}
    private static void submit(Save save,JsonObject request,Work work){
        if(save.queued.incrementAndGet()>8){save.queued.decrementAndGet();throw new DomainException("link_save_busy","3DS 存档传输窗口已满");}
        save.touched=System.currentTimeMillis();JsonObject captured=request.deepCopy();
        save.io.executor.execute(()->{try{save.check();JsonObject result=work.run();save.check();save.io.server.execute(()->{if(!live(save))return;
            if(save.completed){save.io.transactions.finish(scope(save.room,save.actor,save.transaction),SaveTransactionTombstones.Outcome.COMPLETED,System.currentTimeMillis());save.room.lastSave=save.frame;save.io.active.remove(save.room);save.io.jobs.remove(save);Selection selected=save.io.selections.get(save.room);if(selected!=null){System.arraycopy(save.states,0,selected.states,0,2);System.arraycopy(save.profiles,0,selected.profiles,0,2);}}
            send(save.io,save.actor,captured,result,new byte[0]);if(save.completed&&save.room.lobby.stage()==LinkLobby.Stage.CLOSING)LinkPlayServer.end(save.io.server,save.io.world,save.room,save.room.reason);
        });}catch(Exception failure){if(!save.cancelled)save.io.server.execute(()->{if(live(save))failed(save.io,save.actor,captured,failure);});}finally{save.queued.decrementAndGet();if(save.cancelled)cleanup(save);}});
    }
    private static Save save(ServerPlayer player,LinkPlayServer.World world,LinkPlayServer.Room room,JsonObject body,boolean create){
        Io io=io(player.server,world);UUID tx=UUID.fromString(body.get("saveTransaction").getAsString());long frame=body.get("frame").getAsLong();
        if(frame<1||frame<=room.lastSave||frame>room.frame||room.lobby.stage()==LinkLobby.Stage.RUNNING&&frame>room.dsFlow.completedFrame())throw new DomainException("link_save_frame","3DS 存档帧无效");
        Save save=io.active.get(room);if(save==null){if(!create||io.finalChecks.contains(room))throw new DomainException("link_save_session","3DS 存档事务不可用");if(io.jobs.size()>=16)throw new DomainException("link_save_busy","同时保存的连线过多");if(!io.transactions.begin(scope(room,player.getUUID(),tx),System.currentTimeMillis()))throw new DomainException("link_save_session","3DS 存档事务已结束");save=new Save(io,room,player.getUUID(),tx,frame);io.active.put(room,save);io.jobs.add(save);}
        if(save.cancelled||save.epoch!=room.lobby.epoch()||!save.actor.equals(player.getUUID())||!save.transaction.equals(tx)||save.frame!=frame)throw new DomainException("link_save_session","3DS 存档事务已改变");return save;
    }
    static boolean receive(ServerPlayer player,LinkPlayServer.World world,Wire.Message message)throws IOException{
        String op=message.operation();if(!op.startsWith("link_threeds_"))return false;JsonObject b=message.body();LinkPlayServer.Room room=LinkPlayServer.host(world,player,b,!op.equals("link_threeds_read"));if(!n3ds(room))throw new DomainException("link_platform","此存档通道只用于 3DS");Io io=io(player.server,world);
        if(op.equals("link_threeds_read")){read(io,room,player.getUUID(),b);return true;}
        if(op.equals("link_threeds_final_check")){finalCheck(io,room,player.getUUID(),b);return true;}
        UUID tx=UUID.fromString(b.get("saveTransaction").getAsString());var scope=scope(room,player.getUUID(),tx);
        if(io.transactions.terminal(scope,System.currentTimeMillis())){JsonObject j=new JsonObject();j.addProperty("retired",true);send(io,player.getUUID(),b,j,new byte[0]);return true;}
        if(op.equals("link_threeds_abort")){Save old=io.active.get(room);if(old!=null&&old.transaction.equals(tx))cancel(world,room);else io.transactions.finish(scope,SaveTransactionTombstones.Outcome.CANCELLED,System.currentTimeMillis());JsonObject j=new JsonObject();j.addProperty("aborted",true);send(io,player.getUUID(),b,j,new byte[0]);return true;}
        Save save=save(player,world,room,b,op.equals("link_threeds_profile_begin"));byte[] data=message.data().clone();
        switch(op){
            case "link_threeds_profile_begin"->{int p=port(b);long size=b.get("size").getAsLong();String hash=Hashing.normalizeSha256(b.get("hash").getAsString());if(size<1||size>MAX_BYTES)throw new IOException("3DS profile exceeds bound");submit(save,b,()->{if(save.profileUpload!=null||save.profiles[p]!=null)throw new IOException("3DS profile already selected");var key=profileKey(room,p);var current=world.state.profiles.latest(key);JsonObject j=new JsonObject();j.addProperty("endpoint",p);if(current.isPresent()&&current.get().size()==size&&current.get().hash().equals(hash)){save.profiles[p]=current.get();j.addProperty("committed",true);j.add("profile",profile(current.get()));}else{var status=world.state.profiles.begin(save.actor,key,current.map(MachineProfileVault.Snapshot::generation).orElse(null),save.frame,size,hash,System.currentTimeMillis());save.profileUpload=status.uploadId();save.profilePort=p;j.addProperty("upload",status.uploadId().toString());j.addProperty("offset",status.offset());}return j;});}
            case "link_threeds_profile_piece"->{UUID id=UUID.fromString(b.get("upload").getAsString());long offset=b.get("offset").getAsLong();submit(save,b,()->{if(!id.equals(save.profileUpload))throw new IOException("3DS profile upload changed");var status=world.state.profiles.append(save.actor,id,offset,data,System.currentTimeMillis());JsonObject j=new JsonObject();j.addProperty("offset",status.offset());return j;});}
            case "link_threeds_profile_commit"->{UUID id=UUID.fromString(b.get("upload").getAsString());submit(save,b,()->{if(!id.equals(save.profileUpload)||save.profilePort<0)throw new IOException("3DS profile upload changed");int p=save.profilePort;var stored=world.state.profiles.commit(save.actor,id,System.currentTimeMillis());save.profileUpload=null;save.profilePort=-1;save.profiles[p]=stored;JsonObject j=new JsonObject();j.addProperty("committed",true);j.addProperty("endpoint",p);j.add("profile",profile(stored));return j;});}
            case "link_threeds_state_begin"->{int p=port(b);long size=b.get("size").getAsLong();String hash=Hashing.normalizeSha256(b.get("hash").getAsString());if(size<1||size>MAX_BYTES)throw new IOException("3DS state exceeds bound");submit(save,b,()->{if(save.profileUpload!=null||save.profiles[0]==null||save.profiles[1]==null||save.stateUpload!=null||save.states[p]!=null)throw new IOException("Both 3DS profile barriers must finish before state upload");var status=world.state.states.begin(save.actor,room.lobby.endpoints().get(p).key(),room.singleFingerprints[p],save.frame,size,hash,System.currentTimeMillis(),save.profiles[p].generation());save.stateUpload=status.uploadId();save.statePort=p;JsonObject j=new JsonObject();j.addProperty("upload",status.uploadId().toString());j.addProperty("offset",status.offset());return j;});}
            case "link_threeds_state_piece"->{UUID id=UUID.fromString(b.get("upload").getAsString());long offset=b.get("offset").getAsLong();submit(save,b,()->{if(!id.equals(save.stateUpload))throw new IOException("3DS state upload changed");var status=world.state.states.append(save.actor,id,offset,data,System.currentTimeMillis());JsonObject j=new JsonObject();j.addProperty("offset",status.offset());return j;});}
            case "link_threeds_state_commit"->{UUID id=UUID.fromString(b.get("upload").getAsString());submit(save,b,()->{if(!id.equals(save.stateUpload)||save.statePort<0)throw new IOException("3DS state upload changed");int p=save.statePort;var stored=world.state.states.commit(save.actor,id,System.currentTimeMillis());save.stateUpload=null;save.statePort=-1;save.states[p]=stored;JsonObject j=new JsonObject();j.addProperty("committed",true);j.addProperty("endpoint",p);j.add("state",LinkPlayServer.stateJson(stored));return j;});}
            case "link_threeds_finish"->submit(save,b,()->{if(save.profileUpload!=null||save.stateUpload!=null)throw new IOException("3DS uploads are incomplete");for(int p=0;p<2;p++)if(!current(world,room,p,save.states[p],save.profiles[p]))throw new IOException("3DS checkpoint generation changed");save.completed=true;JsonObject j=new JsonObject();j.addProperty("committed",true);j.addProperty("frame",save.frame);return j;});
            default->throw new DomainException("link_operation","未知 3DS 存档操作");
        }return true;
    }
    private static boolean current(LinkPlayServer.World world,LinkPlayServer.Room room,int p,MachineStateVault.Snapshot state,MachineProfileVault.Snapshot profile)throws IOException{
        return Link3dsCheckpointBinding.current(world.state.states,world.state.profiles,room.lobby.endpoints().get(p).key(),room.singleFingerprints[p],state,profile);
    }
    private static void read(Io io,LinkPlayServer.Room room,UUID actor,JsonObject request)throws IOException{
        Selection selected=io.selections.get(room);if(selected==null)throw new IOException("3DS artifacts are not ready");int p=port(request);String kind=request.get("kind").getAsString(),hash=Hashing.normalizeSha256(request.get("hash").getAsString());UUID generation=UUID.fromString(request.get("generation").getAsString());long offset=request.get("offset").getAsLong(),epoch=room.lobby.epoch();JsonObject captured=request.deepCopy();
        if(selected.reads.incrementAndGet()>8){selected.reads.decrementAndGet();throw new IOException("3DS artifact read window is full");}if(io.reads.incrementAndGet()>128){io.reads.decrementAndGet();selected.reads.decrementAndGet();throw new IOException("Too many 3DS artifact reads");}
        io.executor.execute(()->{try{byte[] data;if(kind.equals("profile")){var saved=selected.profiles[p];if(saved==null||!saved.generation().equals(generation)||!saved.hash().equals(hash))throw new IOException("3DS profile selection differs");data=io.world.state.profiles.read(profileKey(room,p),generation,offset,24576);}else if(kind.equals("endpoint")){var saved=selected.states[p];if(saved==null||!saved.generationID().equals(generation)||!saved.hash().equals(hash))throw new IOException("3DS state selection differs");data=io.world.state.states.read(saved.key(),generation,offset,24576);}else throw new IOException("Unknown 3DS artifact type");io.server.execute(()->{if(live(io,room,epoch)){JsonObject j=new JsonObject();j.addProperty("offset",offset);send(io,actor,captured,j,data);}});}catch(IOException|RuntimeException failure){io.server.execute(()->{if(live(io,room,epoch))failed(io,actor,captured,failure);});}finally{selected.reads.decrementAndGet();io.reads.decrementAndGet();}});
    }
    private static void finalCheck(Io io,LinkPlayServer.Room room,UUID actor,JsonObject request)throws IOException{
        long frame=request.get("frame").getAsLong();if(frame<room.dsFlow.completedFrame()||frame>room.frame)throw new IOException("Invalid final 3DS frame");
        if(room.lobby.stage()!=LinkLobby.Stage.CLOSING||io.active.containsKey(room)||!io.finalChecks.add(room))throw new IOException("3DS final check is not ready");
        long epoch=room.lobby.epoch();Selection selected=io.selections.get(room);JsonObject captured=request.deepCopy();long lastSave=room.lastSave;
        io.executor.execute(()->{try{boolean unchanged=frame==0||selected!=null&&frame==lastSave&&current(io.world,room,0,selected.states[0],selected.profiles[0])&&current(io.world,room,1,selected.states[1],selected.profiles[1]);io.server.execute(()->{try{if(!live(io,room,epoch))return;JsonObject j=new JsonObject();j.addProperty("unchanged",unchanged);send(io,actor,captured,j,new byte[0]);if(unchanged)LinkPlayServer.end(io.server,io.world,room,room.reason);}finally{io.finalChecks.remove(room);}});}catch(IOException|RuntimeException failure){io.server.execute(()->{try{if(live(io,room,epoch))failed(io,actor,captured,failure);}finally{io.finalChecks.remove(room);}});}});
    }
    static void cancel(LinkPlayServer.World world,LinkPlayServer.Room room){Io io=WORLDS.get(world);if(io==null)return;Save old=io.active.remove(room);io.selections.remove(room);if(old==null)return;io.transactions.finish(scope(room,old.actor,old.transaction),SaveTransactionTombstones.Outcome.CANCELLED,System.currentTimeMillis());old.cancelled=true;io.executor.execute(()->cleanup(old));}
    private static void cleanup(Save save){long now=System.currentTimeMillis();if(now<save.nextCleanup)return;save.nextCleanup=now+1000;boolean clean=true;try{if(save.profileUpload!=null){save.io.world.state.profiles.abort(save.actor,save.profileUpload);save.profileUpload=null;}}catch(IOException|RuntimeException failure){clean=false;}try{if(save.stateUpload!=null){save.io.world.state.states.abort(save.actor,save.stateUpload);save.stateUpload=null;}}catch(IOException|RuntimeException failure){clean=false;}if(clean)save.io.jobs.remove(save);}
    static void tick(MinecraftServer server,LinkPlayServer.World world){Io io=WORLDS.get(world);if(io==null)return;for(Save save:List.copyOf(io.jobs)){if(save.cancelled){if(System.currentTimeMillis()>=save.nextCleanup&&save.queued.compareAndSet(0,1))io.executor.execute(()->{try{cleanup(save);}finally{save.queued.decrementAndGet();}});}else if(System.currentTimeMillis()-save.touched>180000&&io.active.get(save.room)==save)LinkPlayServer.close(server,world,save.room,"3DS 存档传输超时，保留最近有效进度");}}
    static void stopped(MinecraftServer server){for(Io io:List.copyOf(WORLDS.values()))if(io.server==server){io.closed=true;io.jobs.forEach(save->save.cancelled=true);io.executor.shutdown();try{if(!io.executor.awaitTermination(20,TimeUnit.SECONDS))io.executor.shutdownNow();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();io.executor.shutdownNow();}WORLDS.remove(io.world);}}
    private Link3dsPersistence(){}
}
