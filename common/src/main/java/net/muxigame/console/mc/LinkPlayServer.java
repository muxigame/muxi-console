package net.muxigame.console.mc;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.domain.*;
import net.muxigame.console.runtime.InputCodec;
import net.muxigame.console.runtime.PortInput;
import net.muxigame.console.runtime.AvFrame.Surface;
import net.muxigame.console.compat.SurfaceMetadata;

/** Two independent physical machines joined by their serial or wireless backend, with explicit peer consent. */
public final class LinkPlayServer {
    record Held(LinkLobby.Endpoint identity,Cartridge card,int model){}
    private static final class Upload {
        final UUID id=UUID.randomUUID();final String hash;final int size;final long frame;long touched=now();final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        Upload(JsonObject body){hash=Hashing.normalizeSha256(body.get("hash").getAsString());size=body.get("size").getAsInt();frame=body.get("frame").getAsLong();if(size<1||size>LinkStateStore.MAX_BYTES||frame<0)throw new DomainException("link_save","连线存档大小无效");}
    }
    static final class Room {
        final LinkLobby lobby;final Held[] held;final PortInput[] input={PortInput.IDLE,PortInput.IDLE};final long[] sequences={-1,-1},video={-1,-1},audio={-1,-1},lastInput={0,0};
        final LinkMedia.Assembler[] pictures;final LinkCoreIdentity core;final String[] singleFingerprints=new String[2];
        final FrameFlowWindow dsFlow=new FrameFlowWindow(6,180000);long dsTick;final boolean ds,n3ds,frameFlow;final int width,height;final java.util.concurrent.atomic.AtomicInteger dsReads=new java.util.concurrent.atomic.AtomicInteger();LinkDsPersistence.Save dsSave;
        MachineStateVault.Snapshot[] dsStates=new MachineStateVault.Snapshot[2];MachineProfileVault.Snapshot[] dsProfiles=new MachineProfileVault.Snapshot[2];LinkDsGroupVault.Snapshot dsGroup;
        boolean ready;double fps=59.727500569606,carry;long frame,lastSave,lastActivity=now();Upload upload;String reason="连线已结束";
        Room(Held left,Held right)throws IOException{lobby=new LinkLobby(left.identity,right.identity,now());core=LinkCoreIdentity.bundled(left.identity.platform());ds=left.identity.platform().equals("nds");n3ds=left.identity.platform().equals("n3ds");frameFlow=ds||n3ds;width=n3ds?400:ds?256:left.identity.platform().equals("gba")?240:160;height=n3ds?480:ds?384:left.identity.platform().equals("gba")?160:144;pictures=new LinkMedia.Assembler[]{new LinkMedia.Assembler(width,height),new LinkMedia.Assembler(width,height)};held=new Held[2];for(Held h:List.of(left,right))held[lobby.port(h.identity.actor())]=h;for(int i=0;i<2;i++){var endpoint=lobby.endpoints().get(i);singleFingerprints[i]=LinkCoreIdentity.stateFingerprint(endpoint.platform(),endpoint.machine());}}
    }
    static final class World {
        final ConsoleServer.WorldState state;final Map<UUID,Room> rooms=new LinkedHashMap<>();final LinkStateStore saves;
        World(MinecraftServer server,ConsoleServer.WorldState state)throws IOException{this.state=state;saves=new LinkStateStore(server.getWorldPath(LevelResource.ROOT).resolve("muxi-console/link"));}
    }
    private static final Map<MinecraftServer,World> WORLDS=new IdentityHashMap<>();
    static long now(){return System.currentTimeMillis();}
    private static World world(MinecraftServer server,ConsoleServer.WorldState state)throws IOException {World w=WORLDS.get(server);if(w==null){w=new World(server,state);WORLDS.put(server,w);}return w;}
    private static JsonObject object(){return new JsonObject();}
    static JsonObject address(Room room){JsonObject j=object();j.addProperty("link",room.lobby.id().toString());j.addProperty("epoch",room.lobby.epoch());return j;}
    static ServerPlayer player(MinecraftServer server,UUID actor){return server.getPlayerList().getPlayer(actor);}
    private static Room participant(World world,UUID actor){return world.rooms.values().stream().filter(r->r.lobby.port(actor)>=0).findFirst().orElse(null);}
    private static Room owned(World world,ServerPlayer player,JsonObject body){Room room=participant(world,player.getUUID());if(room==null)throw new DomainException("link_permission","你没有进行中的连线");room.lobby.require(player.getUUID(),UUID.fromString(body.get("link").getAsString()),body.get("epoch").getAsLong());return room;}
    private static Held held(ServerPlayer player,boolean allocate){
        if(player==null||!player.isAlive()||player.isSpectator())return null;
        for(InteractionHand hand:InteractionHand.values()){
            ItemStack item=player.getItemInHand(hand);if(!item.is(ConsoleContent.HANDHELD.get())||item.getCount()!=1)continue;
            var card=CardData.read(item);if(card.isEmpty()||!Set.of("gb","gbc","gba","nds","n3ds").contains(card.get().platform().id())||!HardwareCatalog.accepts(HardwareCatalog.model(item),card.get().platform()))continue;
            UUID machine=allocate?MachineData.ensure(item):MachineData.read(item).orElse(null);if(machine==null)continue;
            if(allocate)player.inventoryMenu.broadcastChanges();Cartridge c=card.get();return new Held(new LinkLobby.Endpoint(player.getUUID(),machine,c.instanceId(),c.sha256(),c.platform().id()),c,HardwareCatalog.model(item));
        }return null;
    }
    private static boolean physical(MinecraftServer server,Room room){for(Held h:room.held){Held current=held(player(server,h.identity.actor()),false);if(current==null||!current.identity.equals(h.identity))return false;}return true;}
    public static boolean isMachineLocked(ConsoleServer.WorldState state,UUID machine){return Link3dsPersistence.lockedMachine(state,machine)||LinkDsPersistence.lockedMachine(state,machine)||WORLDS.values().stream().filter(w->w.state==state).flatMap(w->w.rooms.values().stream()).anyMatch(r->r.lobby.locked()&&r.lobby.endpoints().stream().anyMatch(e->e.machine().equals(machine)));}
    public static boolean isCardLocked(ConsoleServer.WorldState state,UUID card){return Link3dsPersistence.lockedCard(state,card)||LinkDsPersistence.lockedCard(state,card)||WORLDS.values().stream().filter(w->w.state==state).flatMap(w->w.rooms.values().stream()).anyMatch(r->r.lobby.locked()&&r.lobby.endpoints().stream().anyMatch(e->e.card().equals(card)));}
    public static boolean isActorLocked(ConsoleServer.WorldState state,UUID actor){return Link3dsPersistence.lockedActor(state,actor)||LinkDsPersistence.lockedActor(state,actor)||WORLDS.values().stream().filter(w->w.state==state).flatMap(w->w.rooms.values().stream()).anyMatch(r->r.lobby.locked()&&r.lobby.port(actor)>=0);}
    public static boolean mayDownload(ServerPlayer player,String hash,JsonObject body){
        World world=WORLDS.get(player.server);if(world==null||!body.has("link")||!body.has("epoch"))return false;
        try{Room room=owned(world,player,body);return room.lobby.mayDownload(player.getUUID(),UUID.fromString(body.get("link").getAsString()),body.get("epoch").getAsLong(),hash)&&physical(player.server,room);}catch(RuntimeException denied){return false;}
    }
    public static boolean openIfLinked(ServerPlayer player){World w=WORLDS.get(player.server);Room r=w==null?null:participant(w,player.getUUID());if(r==null||r.lobby.stage()!=LinkLobby.Stage.RUNNING)return false;Wire.send(player,"link_show",address(r));return true;}
    public static boolean receive(ServerPlayer player,ConsoleServer.WorldState state,Wire.Message message)throws IOException {
        if(!message.operation().startsWith("link_"))return false;JsonObject body=message.body();
        try{
            World world=world(player.server,state);
            if(Link3dsPersistence.receive(player,world,message)||LinkDsPersistence.receive(player,world,message))return true;
            switch(message.operation()){
                case "link_list" -> list(player,world);
                case "link_invite" -> invite(player,world,body);
                case "link_accept" -> {Room r=owned(world,player,body);if(!physical(player.server,r))throw new DomainException("link_identity","玩家手中的掌机或卡带已改变");r.lobby.accept(player.getUUID(),r.lobby.id(),r.lobby.epoch(),now());broadcast(player.server,r,"link_prepare");}
                case "link_decline" -> {Room r=owned(world,player,body);if(r.lobby.stage()!=LinkLobby.Stage.INVITED)throw new DomainException("link_stage","邀请已处理");end(player.server,world,r,"连线邀请已取消");}
                case "link_prepared" -> {
                    Room r=owned(world,player,body);Held current=held(player,false);
                    if(current==null)throw new DomainException("link_prepare","手中的掌机已改变");
                    boolean ownStopped=state.rooms.values().stream().noneMatch(old->old.session.isController(player.getUUID())||current.identity.machine().equals(old.saveKey.machineUUID()));
                    // A may finish its ordinary checkpoint while B is still saving. Record each endpoint independently.
                    if(r.lobby.prepared(current.identity,r.lobby.id(),r.lobby.epoch(),now(),ownStopped))try{start(player.server,world,r);}catch(IOException|RuntimeException failure){end(player.server,world,r,"连线准备失败，原单机存档已保留");throw failure;}
                }
                case "link_ready" -> {Room r=host(world,player,body,false);if(r.frameFlow&&(r.ready||!body.has("frameFlow")||body.get("frameFlow").getAsInt()!=1))throw new DomainException("link_core","此无线连线需要更新双方客户端");if(!body.get("bridge").getAsString().equals(r.core.bridge())||!body.get("single").getAsString().equals(r.core.single()))throw new DomainException("link_core","连线核心版本不同");double fps=body.get("fps").getAsDouble();if(!Double.isFinite(fps)||fps<58||fps>61)throw new DomainException("link_core","连线核心帧率无效");r.fps=fps;if(r.frameFlow){r.dsFlow.reset(r.lobby.epoch(),r.frame,now());r.dsTick=0;}r.ready=true;r.lobby.ready();broadcast(player.server,r,"link_ready");}
                case "link_batch_done" -> {Room r=host(world,player,body,false);if(!r.frameFlow||!r.ready)throw new DomainException("link_permission","此连线没有进度确认通道");r.dsFlow.acknowledge(r.lobby.epoch(),body.get("tick").getAsLong(),body.get("frame").getAsLong(),now());}
                case "link_input" -> {
                    Room r=owned(world,player,body);running(player.server,r);int port=r.lobby.port(player.getUUID());long sequence=body.get("sequence").getAsLong();if(body.get("port").getAsInt()!=port||sequence<=r.sequences[port]||sequence>r.sequences[port]+120)throw new DomainException("link_input","连线输入顺序无效");if(now()-r.lastInput[port]<25)break;r.input[port]=InputCodec.decode(message.data());r.sequences[port]=sequence;r.lastInput[port]=now();r.lastActivity=now();
                }
                case "link_video" -> video(player,world,body,message.data());
                case "link_audio" -> audio(player,world,body,message.data());
                case "link_state_read" -> readState(player,world,body);
                case "link_save_begin" -> {Room r=cableHost(world,player,body,true);if(r.upload!=null)throw new DomainException("link_save","已有连线存档正在上传");Upload upload=new Upload(body);if(upload.frame>r.frame)throw new DomainException("link_save","连线存档帧无效");r.upload=upload;ack(player,body,upload);}
                case "link_save_piece" -> {Room r=cableHost(world,player,body,true);Upload upload=upload(r,body);if(body.get("offset").getAsInt()!=upload.bytes.size()||message.data().length<1||message.data().length>24576||message.data().length>upload.size-upload.bytes.size())throw new DomainException("link_save","连线存档分块无效");upload.bytes.write(message.data());upload.touched=now();ack(player,body,upload);}
                case "link_save_commit" -> commit(player,world,body);
                case "link_save_abort" -> {Room r=cableHost(world,player,body,true);if(r.upload!=null&&r.upload.id.toString().equals(body.get("upload").getAsString()))r.upload=null;}
                case "link_leave" -> {Room r=owned(world,player,body);close(player.server,world,r,"玩家结束连线");}
                case "link_failed" -> {Room r=owned(world,player,body);if(!r.lobby.host().equals(player.getUUID()))throw new DomainException("link_permission","仅宿主可报告核心失败");end(player.server,world,r,"连线核心未能继续，保留最近已提交的存档");}
                default -> throw new DomainException("link_operation","未知连线操作");
            }
        }catch(Exception failure){JsonObject error=body.deepCopy();error.remove("url");error.addProperty("message",failure instanceof DomainException?failure.getMessage():"连线操作未完成，请重试");error.addProperty("code",failure instanceof DomainException d?d.code():"link_io");Wire.send(player,"link_error",error);MuxiConsole.LOG.warn("Link operation failed actor={} operation={} type={}",player.getUUID(),message.operation(),failure.getClass().getSimpleName());}
        return true;
    }
    private static void list(ServerPlayer player,World world){
        Held own=held(player,true);JsonObject reply=object();reply.addProperty("compatible",own!=null);JsonArray peers=new JsonArray();
        for(ServerPlayer peer:player.server.getPlayerList().getPlayers()){if(peer==player)continue;Held h=held(peer,false);if(h==null||own!=null&&!LinkLobby.compatible(own.identity.platform(),h.identity.platform()))continue;JsonObject j=object();j.addProperty("player",peer.getUUID().toString());j.addProperty("name",peer.getGameProfile().getName());j.addProperty("platform",h.identity.platform());j.addProperty("title",h.card.title());j.addProperty("busy",participant(world,peer.getUUID())!=null);peers.add(j);}reply.add("players",peers);Wire.send(player,"link_list",reply);
    }
    private static void invite(ServerPlayer player,World world,JsonObject body)throws IOException{
        ServerPlayer peer=player(player.server,UUID.fromString(body.get("player").getAsString()));Held a=held(player,true),b=held(peer,true);
        if(a==null||b==null)throw new DomainException("link_platform","双方需要手持已装卡的兼容 GB、GBC、GBA 或 NDS 掌机");
        if(world.rooms.size()>=16||participant(world,player.getUUID())!=null||participant(world,peer.getUUID())!=null||LinkDsPersistence.lockedActor(world.state,player.getUUID())||LinkDsPersistence.lockedActor(world.state,peer.getUUID())||Link3dsPersistence.lockedActor(world.state,player.getUUID())||Link3dsPersistence.lockedActor(world.state,peer.getUUID()))throw new DomainException("link_busy","玩家已有邀请或连线，请先结束");
        if(!world.state.content.has(a.card.sha256())||!world.state.content.has(b.card.sha256()))throw new DomainException("link_rom","服务器缺少其中一张卡带的内容");
        Room room=new Room(a,b);world.rooms.put(room.lobby.id(),room);broadcast(player.server,room,"link_invited");
    }
    static JsonObject view(MinecraftServer server,Room r){
        JsonObject j=address(r);j.addProperty("hostPlayer",r.lobby.host().toString());j.addProperty("stage",r.lobby.stage().name());if(r.frameFlow)j.addProperty("frameFlow",true);JsonArray endpoints=new JsonArray();
        for(Held h:r.held){JsonObject e=object();var x=h.identity;e.addProperty("player",x.actor().toString());e.addProperty("machine",x.machine().toString());e.addProperty("instance",x.card().toString());e.addProperty("hash",x.content());e.addProperty("platform",x.platform());e.addProperty("size",h.card.size());e.addProperty("title",h.card.title());e.addProperty("model",h.model);var p=player(server,x.actor());e.addProperty("name",p==null?"离线玩家":p.getGameProfile().getName());endpoints.add(e);}j.add("endpoints",endpoints);return j;
    }
    private static void broadcast(MinecraftServer server,Room room,String operation){for(var endpoint:room.lobby.endpoints()){ServerPlayer p=player(server,endpoint.actor());if(p!=null)try{JsonObject j=view(server,room);j.addProperty("port",room.lobby.port(endpoint.actor()));j.addProperty("host",room.lobby.host().equals(endpoint.actor()));j.addProperty("reason",room.reason);Wire.send(p,operation,j);}catch(RuntimeException unavailable){MuxiConsole.LOG.warn("Link notification unavailable link={} actor={} operation={}",room.lobby.id(),endpoint.actor(),operation);}}}
    static JsonObject stateJson(MachineStateVault.Snapshot snapshot){JsonObject j=object();j.addProperty("generation",snapshot.generationID().toString());j.addProperty("size",snapshot.size());j.addProperty("hash",snapshot.hash());j.addProperty("fingerprint",snapshot.fingerprint());if(snapshot.profileGeneration()!=null)j.addProperty("profileGeneration",snapshot.profileGeneration().toString());return j;}
    private static void start(MinecraftServer server,World world,Room room)throws IOException {
        if(!physical(server,room))throw new DomainException("link_identity","连接前掌机或卡带已改变");
        if(world.state.rooms.values().stream().anyMatch(old->room.lobby.endpoints().stream().anyMatch(e->e.machine().equals(old.saveKey.machineUUID())||old.session.isController(e.actor()))))throw new DomainException("link_prepare","双方单机会话尚未完全退出");
        if(room.n3ds){Link3dsPersistence.start(server,world,room);return;}
        if(room.ds){LinkDsPersistence.start(server,world,room);return;}
        Optional<LinkStateStore.Group> group=world.saves.latest(room.lobby.endpoints(),room.core.bridge(),world.state.states);
        for(var endpoint:room.lobby.endpoints()){
            ServerPlayer p=player(server,endpoint.actor());JsonObject j=view(server,room);j.addProperty("port",room.lobby.port(endpoint.actor()));j.addProperty("host",room.lobby.host().equals(endpoint.actor()));j.addProperty("bridge",room.core.bridge());j.addProperty("single",room.core.single());
            if(room.lobby.host().equals(endpoint.actor())){JsonArray saves=new JsonArray();for(var e:room.lobby.endpoints()){var latest=world.state.states.latest(e.key());saves.add(latest.map(LinkPlayServer::stateJson).orElseGet(JsonObject::new));}j.add("states",saves);if(group.isPresent()){JsonObject g=object();g.addProperty("size",group.get().state().length);g.addProperty("hash",group.get().hash());j.add("groupState",g);}}
            Wire.send(p,"link_start",j);
        }
    }
    private static void running(MinecraftServer server,Room room){if(room.lobby.stage()!=LinkLobby.Stage.RUNNING||!physical(server,room))throw new DomainException("link_identity","连线或手中的掌机已改变");}
    static Room host(World world,ServerPlayer player,JsonObject body,boolean closing){Room room=owned(world,player,body);if(!room.lobby.host().equals(player.getUUID())||room.lobby.stage()!=LinkLobby.Stage.RUNNING&&!(closing&&room.lobby.stage()==LinkLobby.Stage.CLOSING))throw new DomainException("link_permission","当前玩家没有连线宿主权限");if(room.lobby.stage()==LinkLobby.Stage.RUNNING)running(player.server,room);return room;}
    private static Room cableHost(World world,ServerPlayer player,JsonObject body,boolean closing){Room room=host(world,player,body,closing);if(room.frameFlow)throw new DomainException("link_platform","此机型使用独立的进度保存通道");return room;}
    private static void readState(ServerPlayer player,World world,JsonObject body)throws IOException {
        Room r=cableHost(world,player,body,false);long offset=body.get("offset").getAsLong();byte[] data;
        if(body.get("kind").getAsString().equals("group")){var group=world.saves.latest(r.lobby.endpoints(),r.core.bridge(),world.state.states).orElseThrow(()->new IOException("Link group missing"));if(!group.hash().equals(body.get("hash").getAsString())||offset<0||offset>=group.state().length)throw new IOException("Link group changed");data=Arrays.copyOfRange(group.state(),(int)offset,(int)Math.min(offset+24576,group.state().length));}
        else{int endpoint=body.get("endpoint").getAsInt();if(endpoint<0||endpoint>1)throw new IOException("Invalid endpoint");data=world.state.states.read(r.lobby.endpoints().get(endpoint).key(),UUID.fromString(body.get("generation").getAsString()),offset,24576);}
        JsonObject reply=address(r);reply.addProperty("request",body.get("request").getAsString());reply.addProperty("offset",offset);Wire.send(player,"link_reply",reply,data);
    }
    private static Upload upload(Room room,JsonObject body){if(room.upload==null||!room.upload.id.toString().equals(body.get("upload").getAsString()))throw new DomainException("link_save","连线存档上传已改变");return room.upload;}
    private static void ack(ServerPlayer player,JsonObject request,Upload upload){JsonObject j=object();j.addProperty("request",request.get("request").getAsString());j.addProperty("upload",upload.id.toString());j.addProperty("offset",upload.bytes.size());Wire.send(player,"link_reply",j);}
    private static void commit(ServerPlayer player,World world,JsonObject body)throws IOException {
        Room room=cableHost(world,player,body,true);Upload upload=upload(room,body);byte[] state=upload.bytes.toByteArray();
        try{
            if(state.length!=upload.size||!Hashing.sha256(state).equals(upload.hash))throw new IOException("Link save checksum differs");
            List<byte[]> exports=LinkStateStore.exports(state,room.lobby.endpoints(),room.core.bridge());
            // One actor can upload only one vault generation. Finish each machine before beginning its partner.
            for(int i=0;i<2;i++){byte[] single=exports.get(i);UUID pending=null;
                try{var status=world.state.states.begin(player.getUUID(),room.lobby.endpoints().get(i).key(),room.core.single(),upload.frame,single.length,Hashing.sha256(single),now());pending=status.uploadId();
                    for(int offset=0;offset<single.length;offset+=24576)world.state.states.append(player.getUUID(),pending,offset,Arrays.copyOfRange(single,offset,Math.min(offset+24576,single.length)),now());world.state.states.commit(player.getUUID(),pending,now());pending=null;
                }finally{if(pending!=null)world.state.states.abort(player.getUUID(),pending);}
            }
            world.saves.publish(room.lobby.endpoints(),room.core.bridge(),upload.frame,state,world.state.states);room.lastSave=upload.frame;
            JsonObject reply=address(room);reply.addProperty("request",body.get("request").getAsString());reply.addProperty("hash",upload.hash);Wire.send(player,"link_reply",reply);
            if(room.lobby.stage()==LinkLobby.Stage.CLOSING)end(player.server,world,room,room.reason);
        }finally{room.upload=null;}
    }
    private static void video(ServerPlayer player,World world,JsonObject body,byte[] data)throws IOException {
        Room room=host(world,player,body,false);int port=body.get("port").getAsInt();if(port<0||port>1)throw new IOException("Invalid link target");long frame=body.get("frame").getAsLong();if(frame<=room.video[port]||!LinkMedia.frameWithinWindow(frame,room.frame,room.frameFlow?room.dsFlow.completedFrame():room.frame)) return;
        List<Surface> surfaces=SurfaceMetadata.read(body,room.width,room.height);List<Surface> expected=room.n3ds?List.of(new Surface("top",0,0,400,240,400,240,false),new Surface("bottom",40,240,320,240,320,240,true)):room.ds?List.of(new Surface("top",0,0,256,192,256,192,false),new Surface("bottom",0,192,256,192,256,192,true)):List.of(new Surface("primary",0,0,room.width,room.height,room.width,room.height,false));if(!surfaces.equals(expected))throw new IOException("Link video surfaces differ from the actual hardware layout");
        byte[] complete=room.pictures[port].accept(frame,body.get("offset").getAsInt(),body.get("total").getAsInt(),data,now());if(complete==null)return;room.video[port]=frame;ServerPlayer peer=player(player.server,room.lobby.endpoints().get(port).actor());if(peer==null||peer==player)return;
        for(int offset=0;offset<complete.length;offset+=LinkMedia.PART){JsonObject j=address(room);j.addProperty("port",port);j.addProperty("frame",frame);j.addProperty("offset",offset);j.addProperty("total",complete.length);SurfaceMetadata.write(j,surfaces);Wire.send(peer,"link_video",j,Arrays.copyOfRange(complete,offset,Math.min(offset+LinkMedia.PART,complete.length)));}
    }
    private static void audio(ServerPlayer player,World world,JsonObject body,byte[] data)throws IOException {
        Room room=host(world,player,body,false);int port=body.get("port").getAsInt();long frame=body.get("frame").getAsLong();double rate=body.get("rate").getAsDouble();if(port<0||port>1||!Double.isFinite(rate)||rate<8000||rate>192000)throw new IOException("Invalid link audio");LinkMedia.pcm(data);if(frame<=room.audio[port]||!LinkMedia.frameWithinWindow(frame,room.frame,room.frameFlow?room.dsFlow.completedFrame():room.frame))return;room.audio[port]=frame;
        ServerPlayer target=player(player.server,room.lobby.endpoints().get(port).actor());if(target!=null&&target!=player)Wire.send(target,"link_audio",body,data);
    }
    static void close(MinecraftServer server,World world,Room room,String reason){
        if(room.lobby.stage()==LinkLobby.Stage.INVITED||room.lobby.stage()==LinkLobby.Stage.PREPARING||!room.ready||player(server,room.lobby.host())==null){end(server,world,room,reason);return;}
        if(room.lobby.stage()==LinkLobby.Stage.CLOSING)return;room.upload=null;LinkDsPersistence.cancel(world,room);Link3dsPersistence.cancel(world,room);room.reason=reason;room.lobby.closing(now(),room.frameFlow?300_000:15_000);broadcast(server,room,"link_closing");
    }
    static void end(MinecraftServer server,World world,Room room,String reason){room.reason=reason;try{broadcast(server,room,"link_end");}finally{LinkDsPersistence.cancel(world,room);Link3dsPersistence.cancel(world,room);room.lobby.close();room.upload=null;world.rooms.remove(room.lobby.id());}}
    public static void disconnected(ServerPlayer player,ConsoleServer.WorldState state){World world=WORLDS.get(player.server);Room room=world==null?null:participant(world,player.getUUID());if(room!=null)close(player.server,world,room,"伙伴离线，已结束连线");}
    public static void tick(MinecraftServer server,ConsoleServer.WorldState state){World world=WORLDS.get(server);if(world==null)return;LinkDsPersistence.tick(server,world);Link3dsPersistence.tick(server,world);for(Room room:List.copyOf(world.rooms.values())){
        try{
            if(now()>room.lobby.deadline()){end(server,world,room,"连线等待超时，保留最近有效存档");continue;}
            if(room.upload!=null&&now()-room.upload.touched>15_000){close(server,world,room,"连线存档传输超时，保留最近有效存档");continue;}
            if(room.lobby.stage()!=LinkLobby.Stage.CLOSING&&!physical(server,room)){close(server,world,room,"掌机、卡带或玩家状态已改变");continue;}
            if(room.lobby.stage()==LinkLobby.Stage.RUNNING&&room.ready&&room.upload==null){
                ServerPlayer host=player(server,room.lobby.host());if(host==null){end(server,world,room,"宿主离线，保留最近有效存档");continue;}
                if(room.frameFlow){if(room.dsFlow.expired(room.lobby.epoch(),now())){close(server,world,room,"连线宿主长时间没有完成模拟，保留最近有效进度");continue;}if(!room.dsFlow.available(room.lobby.epoch()))continue;}
                room.carry+=room.fps/20;int count=Math.min(3,(int)room.carry);room.carry-=count;
                ByteArrayOutputStream buffer=new ByteArrayOutputStream();var out=new DataOutputStream(buffer);InputCodec.writePort(out,room.input[0]);InputCodec.writePort(out,room.input[1]);JsonObject j=address(room);j.addProperty("count",count);j.addProperty("frame",room.frame);room.frame+=count;if(room.frameFlow){j.addProperty("tick",room.dsTick);room.dsFlow.issued(room.lobby.epoch(),room.dsTick++,room.frame,now());}Wire.send(host,"link_step",j,buffer.toByteArray());
            }
        }catch(Exception failure){MuxiConsole.LOG.warn("Link tick failed link={} type={}",room.lobby.id(),failure.getClass().getSimpleName());close(server,world,room,"连线未能继续，正在保存");}
    }}
    public static void stopped(MinecraftServer server){World world=WORLDS.remove(server);if(world!=null){for(Room room:world.rooms.values()){LinkDsPersistence.cancel(world,room);Link3dsPersistence.cancel(world,room);room.lobby.close();room.upload=null;}world.rooms.clear();}LinkDsPersistence.stopped(server);Link3dsPersistence.stopped(server);}
    private LinkPlayServer(){}
}
