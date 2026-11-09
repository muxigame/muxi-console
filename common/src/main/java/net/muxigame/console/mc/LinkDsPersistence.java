package net.muxigame.console.mc;

import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.domain.*;

/** File-based DS barriers. All vault IO is serialized outside the Minecraft thread; retired IO keeps its machine lease. */
final class LinkDsPersistence {
    static final class Save {
        final Io io;final LinkPlayServer.Room room;final UUID actor,transaction;final long epoch,frame;final MachineProfileVault.Snapshot[] profiles=new MachineProfileVault.Snapshot[2];final boolean[] profileDone=new boolean[2];
        volatile boolean cancelled;final AtomicInteger queued=new AtomicInteger();final AtomicBoolean cleanupQueued=new AtomicBoolean();volatile long cleanupAfterNanos;boolean cleanupWarned;volatile long touched=System.currentTimeMillis();UUID profileUpload,groupUpload,stateUpload;int profilePort=-1;LinkDsGroupVault.Snapshot committed;
        Save(Io io,LinkPlayServer.Room room,UUID actor,UUID transaction,long frame){this.io=io;this.room=room;this.actor=actor;this.transaction=transaction;this.frame=frame;epoch=room.lobby.epoch();}
        void check()throws IOException{if(cancelled||io.closed||Thread.currentThread().isInterrupted())throw new InterruptedIOException("DS checkpoint transaction retired");}
    }
    private static final class Io {
        final MinecraftServer server;final LinkPlayServer.World world;final ScheduledExecutorService executor;final Set<Save> jobs=ConcurrentHashMap.newKeySet();final Set<LinkPlayServer.Room> finalChecks=ConcurrentHashMap.newKeySet();final AtomicInteger reads=new AtomicInteger();final LinkSaveTransactions transactions=new LinkSaveTransactions();volatile boolean closed;
        Io(MinecraftServer server,LinkPlayServer.World world){this.server=server;this.world=world;executor=Executors.newSingleThreadScheduledExecutor(task->{Thread t=new Thread(task,"playstation-ds-vault");t.setDaemon(true);return t;});executor.scheduleAtFixedRate(()->{if(closed)return;try{world.state.dsLinks.expire(System.currentTimeMillis());}catch(IOException|RuntimeException failure){MuxiConsole.LOG.warn("DS checkpoint expiration failed type={}",failure.getClass().getSimpleName());}},1,1,TimeUnit.SECONDS);}
    }
    private static final Map<LinkPlayServer.World,Io> WORLDS=new IdentityHashMap<>();
    private static Io io(MinecraftServer server,LinkPlayServer.World world){return WORLDS.computeIfAbsent(world,w->new Io(server,w));}
    static LinkDsGroupVault.Key key(LinkPlayServer.Room room){return new LinkDsGroupVault.Key(room.lobby.endpoints().stream().map(LinkLobby.Endpoint::key).toList(),room.core.bridge());}
    private static boolean live(Io io,LinkPlayServer.Room room,long epoch){return !io.closed&&io.world.rooms.get(room.lobby.id())==room&&room.lobby.epoch()==epoch;}
    private static boolean live(Save save){return !save.cancelled&&live(save.io,save.room,save.epoch)&&save.room.dsSave==save;}
    private static LinkSaveTransactions.Key scope(LinkPlayServer.Room room,long epoch,UUID actor,UUID transaction){return new LinkSaveTransactions.Key(room.lobby.id(),epoch,actor,transaction);}
    private static LinkSaveTransactions.Key scope(Save save){return scope(save.room,save.epoch,save.actor,save.transaction);}
    static boolean lockedMachine(ConsoleServer.WorldState state,UUID machine){return WORLDS.values().stream().filter(io->io.world.state==state).flatMap(io->io.jobs.stream()).anyMatch(job->job.room.lobby.endpoints().stream().anyMatch(e->e.machine().equals(machine)));}
    static boolean lockedActor(ConsoleServer.WorldState state,UUID actor){return WORLDS.values().stream().filter(io->io.world.state==state).flatMap(io->io.jobs.stream()).anyMatch(job->job.room.lobby.port(actor)>=0);}
    static boolean lockedCard(ConsoleServer.WorldState state,UUID card){return WORLDS.values().stream().filter(io->io.world.state==state).flatMap(io->io.jobs.stream()).anyMatch(job->job.room.lobby.endpoints().stream().anyMatch(e->e.card().equals(card)));}
    private static JsonObject profile(MachineProfileVault.Snapshot saved){JsonObject j=new JsonObject();j.addProperty("generation",saved.generation().toString());j.addProperty("size",saved.size());j.addProperty("hash",saved.hash());j.addProperty("frame",saved.frame());return j;}
    private static JsonObject group(LinkDsGroupVault.Snapshot saved){JsonObject j=new JsonObject();j.addProperty("generation",saved.generation().toString());j.addProperty("size",saved.size());j.addProperty("hash",saved.hash());j.addProperty("frame",saved.frame());return j;}
    static void start(MinecraftServer server,LinkPlayServer.World world,LinkPlayServer.Room room){
        Io io=io(server,world);long epoch=room.lobby.epoch();io.executor.execute(()->{
            try{
                MachineStateVault.Snapshot[] states=new MachineStateVault.Snapshot[2];MachineProfileVault.Snapshot[] profiles=new MachineProfileVault.Snapshot[2];
                for(int i=0;i<2;i++){var endpoint=room.lobby.endpoints().get(i);profiles[i]=world.state.profiles.latest(new MachineProfileVault.Key(endpoint.machine(),Platform.NDS)).orElse(null);states[i]=world.state.states.latest(endpoint.key()).orElse(null);if(states[i]!=null&&!Objects.equals(states[i].profileGeneration(),profiles[i]==null?null:profiles[i].generation()))states[i]=null;}
                LinkDsGroupVault.Snapshot group=world.state.dsLinks.latest(key(room),world.state.states,world.state.profiles).orElse(null);
                server.execute(()->{if(!live(io,room,epoch))return;try{room.dsStates=states;room.dsProfiles=profiles;room.dsGroup=group;room.frame=room.lastSave=group==null?0:group.frame();
                    for(var endpoint:room.lobby.endpoints()){ServerPlayer target=LinkPlayServer.player(server,endpoint.actor());if(target==null){LinkPlayServer.close(server,world,room,"伙伴离线，取消连接");return;}JsonObject j=LinkPlayServer.view(server,room);j.addProperty("port",room.lobby.port(endpoint.actor()));j.addProperty("host",room.lobby.host().equals(endpoint.actor()));j.addProperty("bridge",room.core.bridge());j.addProperty("single",room.core.single());j.addProperty("frameBase",room.frame);if(room.lobby.host().equals(endpoint.actor())){JsonArray stateRows=new JsonArray(),profileRows=new JsonArray();for(int i=0;i<2;i++){stateRows.add(states[i]==null?new JsonObject():LinkPlayServer.stateJson(states[i]));profileRows.add(profiles[i]==null?new JsonObject():profile(profiles[i]));}j.add("states",stateRows);j.add("profiles",profileRows);if(group!=null)j.add("groupState",group(group));}Wire.send(target,"link_start",j);}
                }catch(RuntimeException unavailable){LinkPlayServer.close(server,world,room,"伙伴连接已改变，原进度已保留");}});
            }catch(IOException|RuntimeException failure){server.execute(()->{if(live(io,room,epoch))LinkPlayServer.end(server,world,room,"无线进度暂不可读取，原存档已保留");});}
        });
    }
    @FunctionalInterface private interface Work{JsonObject run()throws Exception;}
    private static JsonObject reply(JsonObject request){JsonObject j=new JsonObject();for(String name:List.of("link","epoch","request","saveTransaction"))if(request.has(name))j.add(name,request.get(name));return j;}
    private static void send(Io io,UUID actor,JsonObject request,JsonObject payload,byte[] data){ServerPlayer player=LinkPlayServer.player(io.server,actor);if(player==null)return;JsonObject j=reply(request);for(var entry:payload.entrySet())j.add(entry.getKey(),entry.getValue());try{Wire.send(player,"link_ds_reply",j,data);}catch(RuntimeException disconnected){MuxiConsole.LOG.warn("DS checkpoint reply unavailable actor={}",actor);}}
    private static void failed(Io io,UUID actor,JsonObject request,Throwable failure){ServerPlayer player=LinkPlayServer.player(io.server,actor);if(player!=null){JsonObject j=reply(request);j.addProperty("code","link_ds_io");j.addProperty("message","无线进度传输未完成，保留最近有效存档");try{Wire.send(player,"link_error",j);}catch(RuntimeException disconnected){MuxiConsole.LOG.warn("DS checkpoint failure reply unavailable actor={}",actor);}}MuxiConsole.LOG.warn("DS checkpoint request failed actor={} type={}",actor,failure.getClass().getSimpleName());}
    private static void submit(Save save,JsonObject request,Work work){
        if(save.queued.incrementAndGet()>8){save.queued.decrementAndGet();throw new DomainException("link_save_busy","无线存档传输窗口已满");}save.touched=System.currentTimeMillis();JsonObject captured=request.deepCopy();
        save.io.executor.execute(()->{try{save.check();JsonObject result=work.run();save.check();save.io.server.execute(()->{if(live(save)){if(save.committed!=null)save.io.transactions.finish(scope(save),SaveTransactionTombstones.Outcome.COMPLETED,System.currentTimeMillis());send(save.io,save.actor,captured,result,new byte[0]);if(save.committed!=null){save.room.lastSave=save.frame;save.room.dsGroup=save.committed;save.room.dsProfiles=save.profiles.clone();save.room.dsSave=null;if(save.room.lobby.stage()==LinkLobby.Stage.CLOSING)LinkPlayServer.end(save.io.server,save.io.world,save.room,save.room.reason);}}});}
            catch(Exception failure){if(!save.cancelled)save.io.server.execute(()->{if(live(save))failed(save.io,save.actor,captured,failure);});}
            finally{save.queued.decrementAndGet();if(save.cancelled)queueCleanup(save);}});
    }
    private static Save save(ServerPlayer player,LinkPlayServer.World world,LinkPlayServer.Room room,JsonObject body,boolean create){
        UUID transaction=UUID.fromString(body.get("saveTransaction").getAsString());long frame=body.get("frame").getAsLong();if(frame<1||frame<=room.lastSave||frame>room.frame||room.lobby.stage()==LinkLobby.Stage.RUNNING&&frame>room.dsFlow.completedFrame())throw new DomainException("link_save_frame","无线存档帧无效");Save save=room.dsSave;
        if(save==null){if(!create)throw new DomainException("link_save_session","无线存档事务不可用");Io io=io(player.server,world);if(io.finalChecks.contains(room))throw new DomainException("link_save_busy","请等待结束状态回执");if(io.jobs.size()>=16)throw new DomainException("link_save_busy","同时保存的连线过多");if(!io.transactions.begin(scope(room,room.lobby.epoch(),player.getUUID(),transaction),System.currentTimeMillis()))throw new DomainException("link_save_session","无线存档事务已结束");save=new Save(io,room,player.getUUID(),transaction,frame);io.jobs.add(save);room.dsSave=save;}
        if(save.cancelled||save.epoch!=room.lobby.epoch()||!save.actor.equals(player.getUUID())||!save.transaction.equals(transaction)||save.frame!=frame)throw new DomainException("link_save_session","无线存档事务已改变");return save;
    }
    static boolean receive(ServerPlayer player,LinkPlayServer.World world,Wire.Message message)throws IOException{
        String op=message.operation();if(!op.startsWith("link_ds_"))return false;JsonObject b=message.body();LinkPlayServer.Room room=LinkPlayServer.host(world,player,b,!op.equals("link_ds_read"));if(!room.ds)throw new DomainException("link_platform","此存档通道只用于无线机型");
        if(op.equals("link_ds_read")){read(player,world,room,b);return true;}
        if(op.equals("link_ds_final_check")){
            if(room.lobby.stage()!=LinkLobby.Stage.CLOSING||room.dsSave!=null)throw new DomainException("link_save_stage","请先结束旧的无线保存事务");long frame=b.get("frame").getAsLong();if(!LinkSaveTransactions.finalFrameInRange(frame,room.frame,room.dsFlow.completedFrame()))throw new DomainException("link_save_frame","结束存档帧无效");Io finalIo=io(player.server,world);if(!finalIo.finalChecks.add(room))throw new DomainException("link_save_busy","请等待结束状态回执");long epoch=room.lobby.epoch();JsonObject captured=b.deepCopy();UUID actor=player.getUUID();
            finalIo.executor.execute(()->{try{var latest=world.state.dsLinks.latest(key(room),world.state.states,world.state.profiles);boolean unchanged=frame==0||latest.isPresent()&&latest.get().frame()==frame;finalIo.server.execute(()->{try{if(!live(finalIo,room,epoch))return;JsonObject result=new JsonObject();result.addProperty("unchanged",unchanged);send(finalIo,actor,captured,result,new byte[0]);if(unchanged)LinkPlayServer.end(finalIo.server,world,room,room.reason);}finally{finalIo.finalChecks.remove(room);}});}catch(IOException|RuntimeException failure){finalIo.server.execute(()->{try{if(live(finalIo,room,epoch))failed(finalIo,actor,captured,failure);}finally{finalIo.finalChecks.remove(room);}});}});return true;
        }
        Io io=io(player.server,world);UUID transaction=UUID.fromString(b.get("saveTransaction").getAsString());var scope=scope(room,room.lobby.epoch(),player.getUUID(),transaction);
        if(io.transactions.terminal(scope,System.currentTimeMillis())){JsonObject result=new JsonObject();result.addProperty("retired",true);send(io,player.getUUID(),b,result,new byte[0]);return true;}
        if(op.equals("link_ds_abort")){if(room.dsSave!=null&&room.dsSave.transaction.equals(transaction))cancel(world,room);else io.transactions.finish(scope,SaveTransactionTombstones.Outcome.CANCELLED,System.currentTimeMillis());JsonObject result=new JsonObject();result.addProperty("aborted",true);send(io,player.getUUID(),b,result,new byte[0]);return true;}
        Save save=save(player,world,room,b,op.equals("link_ds_profile_begin"));byte[] data=message.data().clone();
        switch(op){
            case "link_ds_profile_begin"->{int port=b.get("endpoint").getAsInt();if(port<0||port>1)throw new IOException("Invalid DS profile endpoint");long size=b.get("size").getAsLong();String hash=Hashing.normalizeSha256(b.get("hash").getAsString());if(size<1||size>MachineProfileVault.LIMITS.maxStateBytes())throw new IOException("DS profile exceeds bound");submit(save,b,()->{
                if(save.profileUpload!=null)throw new IOException("Another DS profile is uploading");var endpoint=room.lobby.endpoints().get(port);var key=new MachineProfileVault.Key(endpoint.machine(),Platform.NDS);var current=world.state.profiles.latest(key);JsonObject result=new JsonObject();result.addProperty("endpoint",port);
                // The room and retired-job leases exclude other machine writers. Re-read the actual generation,
                // including a cancelled autosave's committed profile whose ACK may have been revoked.
                if(current.isPresent()&&current.get().hash().equals(hash)&&current.get().size()==size){save.profiles[port]=current.get();save.profileDone[port]=true;result.addProperty("committed",true);result.add("profile",profile(current.get()));}
                else{var status=world.state.profiles.begin(save.actor,key,current.map(MachineProfileVault.Snapshot::generation).orElse(null),save.frame,size,hash,System.currentTimeMillis());save.profileUpload=status.uploadId();save.profilePort=port;result.addProperty("upload",status.uploadId().toString());result.addProperty("offset",status.offset());}return result;});}
            case "link_ds_profile_piece"->{UUID id=UUID.fromString(b.get("upload").getAsString());long offset=b.get("offset").getAsLong();submit(save,b,()->{if(!id.equals(save.profileUpload))throw new IOException("DS profile upload changed");var status=world.state.profiles.append(save.actor,id,offset,data,System.currentTimeMillis());JsonObject result=new JsonObject();result.addProperty("upload",id.toString());result.addProperty("offset",status.offset());return result;});}
            case "link_ds_profile_commit"->{UUID id=UUID.fromString(b.get("upload").getAsString());submit(save,b,()->{if(!id.equals(save.profileUpload)||save.profilePort<0)throw new IOException("DS profile upload changed");int port=save.profilePort;var stored=world.state.profiles.commit(save.actor,id,System.currentTimeMillis());save.profileUpload=null;save.profilePort=-1;save.profiles[port]=stored;save.profileDone[port]=true;JsonObject result=new JsonObject();result.addProperty("committed",true);result.addProperty("endpoint",port);result.add("profile",profile(stored));return result;});}
            case "link_ds_save_begin"->{long size=b.get("size").getAsLong();String hash=Hashing.normalizeSha256(b.get("hash").getAsString());submit(save,b,()->{if(!save.profileDone[0]||!save.profileDone[1]||save.groupUpload!=null)throw new IOException("Both DS profile barriers must finish first");var status=world.state.dsLinks.begin(save.actor,key(room),save.frame,size,hash,System.currentTimeMillis());save.groupUpload=status.uploadId();JsonObject result=new JsonObject();result.addProperty("upload",status.uploadId().toString());result.addProperty("offset",status.offset());return result;});}
            case "link_ds_save_piece"->{UUID id=UUID.fromString(b.get("upload").getAsString());long offset=b.get("offset").getAsLong();submit(save,b,()->{if(!id.equals(save.groupUpload))throw new IOException("DS group upload changed");var status=world.state.dsLinks.append(save.actor,id,offset,data,System.currentTimeMillis());JsonObject result=new JsonObject();result.addProperty("upload",id.toString());result.addProperty("offset",status.offset());return result;});}
            case "link_ds_save_commit"->{UUID id=UUID.fromString(b.get("upload").getAsString());submit(save,b,()->{
                if(!id.equals(save.groupUpload))throw new IOException("DS group upload changed");var staged=world.state.dsLinks.finish(save.actor,id,System.currentTimeMillis());List<LinkDsGroupVault.Barrier> barriers=new ArrayList<>();
                for(int i=0;i<2;i++){
                    save.check();var exported=staged.endpoints().get(i);var profile=save.profiles[i];if(save.stateUpload!=null)throw new IOException("A retired DS endpoint upload still owns its lease");MachineStateVault.Snapshot single;
                    try{var status=world.state.states.begin(save.actor,exported.key(),room.singleFingerprints[i],save.frame,exported.size(),exported.hash(),System.currentTimeMillis(),profile.generation());save.stateUpload=status.uploadId();
                        try(var input=java.nio.file.Files.newInputStream(exported.path())){long offset=0;byte[] piece=new byte[24576];for(int n;(n=input.read(piece))!=-1;){save.check();world.state.states.append(save.actor,save.stateUpload,offset,Arrays.copyOf(piece,n),System.currentTimeMillis());offset+=n;}if(offset!=exported.size())throw new IOException("DS endpoint staging size changed");}
                        save.check();single=world.state.states.commit(save.actor,save.stateUpload,System.currentTimeMillis());save.stateUpload=null;
                    }finally{if(save.stateUpload!=null){world.state.states.abort(save.actor,save.stateUpload);save.stateUpload=null;}}
                    barriers.add(new LinkDsGroupVault.Barrier(exported.key(),exported.hash(),single.generationID(),single.frame(),profile.generation(),profile.hash()));
                }
                save.check();var stored=world.state.dsLinks.commit(save.actor,id,barriers,world.state.states,world.state.profiles,System.currentTimeMillis());save.groupUpload=null;JsonObject result=new JsonObject();result.addProperty("hash",stored.hash());result.addProperty("generation",stored.generation().toString());
                save.committed=stored;save.io.jobs.remove(save);return result;
            });}
            default->throw new DomainException("link_operation","未知无线存档操作");
        }return true;
    }
    private static void read(ServerPlayer player,LinkPlayServer.World world,LinkPlayServer.Room room,JsonObject request)throws IOException{
        Io io=io(player.server,world);long epoch=room.lobby.epoch(),offset=request.get("offset").getAsLong();String kind=request.get("kind").getAsString(),hash=request.get("hash").getAsString();UUID generation=UUID.fromString(request.get("generation").getAsString());int endpoint=request.has("endpoint")?request.get("endpoint").getAsInt():-1;JsonObject captured=request.deepCopy();UUID actor=player.getUUID();if(room.dsReads.incrementAndGet()>8){room.dsReads.decrementAndGet();throw new IOException("DS artifact read window is full");}if(io.reads.incrementAndGet()>128){io.reads.decrementAndGet();room.dsReads.decrementAndGet();throw new IOException("Too many DS artifact reads");}
        io.executor.execute(()->{try{byte[] data;
            if(kind.equals("group")){var saved=room.dsGroup;if(saved==null||!saved.generation().equals(generation)||!saved.hash().equals(hash))throw new IOException("DS group selection differs");data=world.state.dsLinks.read(key(room),generation,offset,24576);}
            else{if(endpoint<0||endpoint>1)throw new IOException("Invalid DS artifact endpoint");var key=room.lobby.endpoints().get(endpoint).key();if(kind.equals("profile")){var saved=room.dsProfiles[endpoint];if(saved==null||!saved.generation().equals(generation)||!saved.hash().equals(hash))throw new IOException("DS profile selection differs");data=world.state.profiles.read(new MachineProfileVault.Key(key.machineUUID(),Platform.NDS),generation,offset,24576);}else if(kind.equals("endpoint")){var saved=room.dsStates[endpoint];if(saved==null||!saved.generationID().equals(generation)||!saved.hash().equals(hash))throw new IOException("DS endpoint selection differs");data=world.state.states.read(key,generation,offset,24576);}else throw new IOException("Unknown DS artifact type");}
            io.server.execute(()->{if(live(io,room,epoch)){JsonObject result=new JsonObject();result.addProperty("offset",offset);send(io,actor,captured,result,data);}});
        }catch(IOException|RuntimeException failure){io.server.execute(()->{if(live(io,room,epoch))failed(io,actor,captured,failure);});}finally{io.reads.decrementAndGet();room.dsReads.decrementAndGet();}});
    }
    static void cancel(LinkPlayServer.World world,LinkPlayServer.Room room){Save old=room.dsSave;if(old==null)return;old.io.transactions.finish(scope(old),SaveTransactionTombstones.Outcome.CANCELLED,System.currentTimeMillis());old.cancelled=true;room.dsSave=null;queueCleanup(old);}
    private static void queueCleanup(Save save){
        long now=System.nanoTime();if(!save.cancelled||save.io.closed||!save.io.jobs.contains(save)||save.queued.get()!=0||save.cleanupAfterNanos!=0&&now-save.cleanupAfterNanos<0||!save.cleanupQueued.compareAndSet(false,true))return;
        try{save.io.executor.execute(()->{try{cleanup(save);}finally{save.cleanupAfterNanos=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);save.cleanupQueued.set(false);}});}catch(RejectedExecutionException stopping){save.cleanupQueued.set(false);}
    }
    private static void cleanup(Save save){
        boolean clean=true;Throwable cause=null;
        try{if(save.profileUpload!=null){save.io.world.state.profiles.abort(save.actor,save.profileUpload);save.profileUpload=null;}}catch(IOException|RuntimeException failure){clean=false;cause=failure;}
        try{if(save.groupUpload!=null){save.io.world.state.dsLinks.abort(save.actor,save.groupUpload);save.groupUpload=null;}}catch(IOException|RuntimeException failure){clean=false;cause=failure;}
        try{if(save.stateUpload!=null){save.io.world.state.states.abort(save.actor,save.stateUpload);save.stateUpload=null;}}catch(IOException|RuntimeException failure){clean=false;cause=failure;}
        if(clean)save.io.jobs.remove(save);else if(!save.cleanupWarned){save.cleanupWarned=true;MuxiConsole.LOG.warn("DS checkpoint cleanup retained machine leases actor={} type={}",save.actor,cause.getClass().getSimpleName());}
    }
    static void tick(MinecraftServer server,LinkPlayServer.World world){Io io=WORLDS.get(world);if(io==null)return;for(Save save:List.copyOf(io.jobs)){if(save.cancelled)queueCleanup(save);else if(System.currentTimeMillis()-save.touched>180000&&save.room.dsSave==save)LinkPlayServer.close(server,world,save.room,"无线存档传输超时，保留最近有效进度");}}
    static void stopped(MinecraftServer server){for(Io io:List.copyOf(WORLDS.values()))if(io.server==server){io.closed=true;for(Save save:List.copyOf(io.jobs)){save.cancelled=true;io.executor.execute(()->cleanup(save));}io.executor.shutdown();try{if(!io.executor.awaitTermination(20,TimeUnit.SECONDS))io.executor.shutdownNow();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();io.executor.shutdownNow();}WORLDS.remove(io.world);}}
    private LinkDsPersistence(){}
}
