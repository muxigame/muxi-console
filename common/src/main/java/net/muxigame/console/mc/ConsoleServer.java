package net.muxigame.console.mc;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.domain.*;
import net.muxigame.console.domain.storage.ObjectStorages;
import net.muxigame.console.runtime.PortInput;
import net.muxigame.console.runtime.InputCodec;
import net.muxigame.console.runtime.InputFrame;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Minecraft ownership adapter for the independently implemented domain. All calls run on its server thread. */
public final class ConsoleServer {
    private static final Map<MinecraftServer,WorldState> WORLDS=new IdentityHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong ROOM_GENERATIONS=new java.util.concurrent.atomic.AtomicLong(1);
    record WriterLease(UUID token,String dimension,BlockPos position,long expires){}
    record UploadLease(UUID upload,WriterLease writer,String title,UUID transaction){}
    public static final class Room {
        public final String key;public final DeviceEntity device;public final Cartridge card;public final MachineSaveKey saveKey;public final ConsoleSession session;public final InputTimeline inputs;
        public final Map<UUID,String> ready=new HashMap<>();public final Map<Long,Map<Integer,short[]>> analog=new TreeMap<>();
        public final Map<UUID,LoadingWatch> loading=new HashMap<>();
        public final FrameFlowWindow frameFlowWindow=new FrameFlowWindow(8,180000);public boolean frameFlow;public long frameFlowWaitTicks;
        public final HostFrameClock hostFrameClock=new HostFrameClock();public boolean hostClock,clockDispatched;public long clockLeaseSent;
        public final HoldSaveBarrier holdSaveBarrier=new HoldSaveBarrier();
        private final long[] clockInputSequences={-1,-1,-1,-1},clockInputAt=new long[4],clockInputRateAt=new long[4];
        private final double[] clockInputTokens={32,32,32,32};private final PortInput[] clockInputs={PortInput.IDLE,PortInput.IDLE,PortInput.IDLE,PortInput.IDLE};
        public final Map<Long,Map<Integer,PortInput>> fullInputs=new TreeMap<>();public final Map<Integer,PortInput> heldFullInputs=new HashMap<>();
        public final Map<Integer,int[]> heldInputs=new HashMap<>();
        public final Map<Long,Map<UUID,String>> crc=new HashMap<>();
        public final Set<UUID> requestedLeaves=new LinkedHashSet<>();public final Map<UUID,String> presentations=new HashMap<>();public final Set<UUID> openOnStart=new HashSet<>();public long handheldSaveDeadline;
        public long observedEpoch=0,frame=0,waiting=0,handoverStarted;public double fps=60,carry=0;public String fingerprint="";public UUID pendingJoin,pendingLeave;public int pendingPort;
        Room(String key,DeviceEntity d,Cartridge c,UUID machine,UUID host,int count){this.key=key;device=d;card=c;saveKey=new MachineSaveKey(machine,c.sha256());session=new ConsoleSession(machine,c,host,count,Math.multiplyExact(ROOM_GENERATIONS.getAndIncrement(),1L<<32));inputs=new InputTimeline(session,12);}
    }
    public static final class WorldState {
        public final ContentStore content;public final UploadManager uploads;public final MachineStateVault states;public final MachineProfileVault profiles;public final LinkDsGroupVault dsLinks;public final CloudMediaServer cloud;public final LocalMediaServer localMedia;public final Map<UUID,WriterLease>writers=new HashMap<>();public final Map<UUID,UploadLease>burns=new HashMap<>();public final Map<String,Room>rooms=new HashMap<>();
        WorldState(MinecraftServer server)throws IOException{
            Path p=server.getWorldPath(LevelResource.ROOT).resolve("muxi-console");content=new ContentStore(p.resolve("content"));
            // The deployment supplies an explicit external private file. No cloud configuration enters the world, JAR or client.
            String privateConfig=System.getProperty("muxi.console.storageConfig","").strip();
            if(!privateConfig.isEmpty()&&!Path.of(privateConfig).isAbsolute())throw new IOException("云存储配置必须指定服务器私有文件的绝对路径");
            var storage=privateConfig.isEmpty()?Optional.<net.muxigame.console.domain.storage.ObjectStorage>empty():ObjectStorages.load(Path.of(privateConfig));
            CloudMediaServer openedCloud=null;UploadManager openedUploads=null;MachineStateVault openedStates=null;MachineProfileVault openedProfiles=null;LinkDsGroupVault openedDsLinks=null;LocalMediaServer openedLocal=null;
            try{
                openedCloud=storage.isPresent()?new CloudMediaServer(content,storage.get()):null;
                openedUploads=new UploadManager(content,p.resolve("incoming"));openedStates=new MachineStateVault(p.resolve("states/machines"),new StateVault.Limits(8L*1024*1024*1024,512L*1024*1024,24576,180000,60000));openedProfiles=new MachineProfileVault(p.resolve("states/profiles"));openedDsLinks=new LinkDsGroupVault(p.resolve("link/nds"));if(openedCloud==null)openedLocal=new LocalMediaServer(openedUploads);
            }catch(IOException|RuntimeException failure){for(AutoCloseable resource:Arrays.asList(openedLocal,openedDsLinks,openedProfiles,openedStates,openedUploads,openedCloud))if(resource!=null)try{resource.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
            cloud=openedCloud;uploads=openedUploads;states=openedStates;profiles=openedProfiles;dsLinks=openedDsLinks;localMedia=openedLocal;
        }
    }
    public static WorldState state(MinecraftServer server)throws IOException{WorldState s=WORLDS.get(server);if(s==null){s=new WorldState(server);WORLDS.put(server,s);}return s;}
    public static List<Room> roomsSnapshot(MinecraftServer server){WorldState world=WORLDS.get(server);return world==null?List.of():List.copyOf(world.rooms.values());}
    public static JsonObject object(){return new JsonObject();}
    private static JsonObject cardJson(Cartridge c){JsonObject j=object();j.addProperty("instance",c.instanceId().toString());j.addProperty("platform",c.platform().id());j.addProperty("hash",c.sha256());j.addProperty("size",c.size());j.addProperty("title",c.title());return j;}
    public static String key(DeviceEntity d){return d.getLevel().dimension().location()+"/"+d.getBlockPos().asLong();}
    private static String dimension(ServerPlayer p){return p.serverLevel().dimension().location().toString();}
    private static boolean nearby(ServerPlayer p,DeviceEntity d){return d.getLevel()==p.serverLevel() && p.distanceToSqr(d.getBlockPos().getCenter())<=64;}
    private static DeviceEntity device(ServerPlayer p,JsonObject body){
        BlockPos pos=BlockPos.of(body.get("position").getAsLong());
        if(!p.serverLevel().hasChunkAt(pos) || !(p.serverLevel().getBlockEntity(pos) instanceof DeviceEntity d) || !nearby(p,d))throw new DomainException("reach","设备已超出操作范围");return primary(d);
    }
    private static DeviceEntity primary(DeviceEntity device){
        if(!device.largeScreen())return device;
        if(device.getLevel()!=null&&device.getLevel().hasChunkAt(device.screenAnchor())&&device.getLevel().getBlockEntity(device.screenAnchor()) instanceof DeviceEntity anchor&&anchor.kind()==DeviceBlock.Kind.SCREEN&&anchor.identity.equals(device.identity)&&anchor.screenPrimary())return anchor;
        throw new DomainException("screen","屏幕结构已不完整，请重新放置");
    }
    private static boolean activeCard(WorldState world,UUID instance){return world.rooms.values().stream().anyMatch(room->room.card.instanceId().equals(instance));}
    /** Normal save/leave keeps the room registered until its checkpoint is committed. */
    public static void requireCardIdle(Player player,UUID instance){
        if(player instanceof ServerPlayer server){WorldState world=WORLDS.get(server.server);if(world!=null&&(activeCard(world,instance)||LinkPlayServer.isCardLocked(world,instance)))throw new DomainException("save","卡带正在游玩、连线或保存，请先保存并离开后再插拔");}
    }
    private static boolean sameHandheld(ItemStack item,Room room){return item.is(ConsoleContent.HANDHELD.get())&&MachineData.read(item).map(room.saveKey.machineUUID()::equals).orElse(false)&&CardData.read(item).map(card->card.instanceId().equals(room.card.instanceId())&&card.sha256().equals(room.saveKey.contentSHA())).orElse(false);}
    private static boolean inHand(ServerPlayer player,Room room){return List.of(player.getMainHandItem(),player.getOffhandItem()).stream().anyMatch(item->sameHandheld(item,room));}
    private static boolean inInventory(ServerPlayer player,Room room){for(int slot=0;slot<player.getInventory().getContainerSize();slot++)if(sameHandheld(player.getInventory().getItem(slot),room))return true;return sameHandheld(player.containerMenu.getCarried(),room);}
    private static void requireMachineIdle(WorldState world,UUID machine){if(LinkPlayServer.isMachineLocked(world,machine)||world.rooms.values().stream().anyMatch(room->room.saveKey.machineUUID().equals(machine)))throw new DomainException("save","这台机器仍在游玩、连线或保存，请先保存并离开");}
    private static void requireInactive(WorldState world,DeviceEntity device){if(world.rooms.containsKey(key(device)))throw new DomainException("save","请先保存并离开游戏，再取出掌机");}
    private static void dock(ServerPlayer player,DeviceEntity device,ItemStack held)throws IOException{
        WorldState world=state(player.server);requireInactive(world,device);
        if(!device.dockedHandheld.isEmpty())throw new DomainException("dock","底座已有掌机，请先取出");
        var body=HardwareCatalog.body(HardwareCatalog.model(held));if(body==null||!body.handheld())throw new DomainException("hardware","请先将游戏机核心合成为掌机，再安装到底座");
        Optional<Cartridge> card=CardData.read(held);if(card.isPresent()&&activeCard(world,card.get().instanceId()))throw new DomainException("save","请先收起掌机等待保存完成，再放到底座");
        UUID machine=MachineData.ensure(held);requireMachineIdle(world,machine);device.dockedHandheld=held.split(1);device.powered=false;device.changed();player.inventoryMenu.broadcastChanges();player.displayClientMessage(Component.literal("掌机已安装；使用底座连接大屏幕"),true);
    }
    private static void ejectDock(ServerPlayer player,WorldState world,DeviceEntity device){
        requireInactive(world,device);if(!device.dockedHandheld.isEmpty()){ItemStack take=device.dockedHandheld;device.dockedHandheld=ItemStack.EMPTY;if(!player.getInventory().add(take))player.drop(take,false);player.inventoryMenu.broadcastChanges();}device.powered=false;device.changed();
    }
    private static void error(ServerPlayer p,String code,String message){JsonObject j=object();j.addProperty("code",code);j.addProperty("message",message==null?code:message);Wire.send(p,"error",j);}
    public static void interact(ServerPlayer p,BlockPos position,InteractionHand hand){
        try{
            if(!(p.serverLevel().getBlockEntity(position) instanceof DeviceEntity clicked)||!nearby(p,clicked))return;
            DeviceEntity d=primary(clicked);position=d.getBlockPos();ItemStack held=p.getItemInHand(hand);
            if(d.kind()==DeviceBlock.Kind.WRITER){
                WorldState s=state(p.server);WriterLease w=new WriterLease(UUID.randomUUID(),dimension(p),position,now()+300000);s.writers.put(p.getUUID(),w);
                BlockPos writerPosition=position;
                p.openMenu(new net.minecraft.world.SimpleMenuProvider((id,inventory,actor)->new WriterMenu(id,inventory,writerPosition,w.token),Component.literal("卡带刻录机")),data->{data.writeBlockPos(writerPosition);data.writeUtf(w.token.toString());});return;
            }
            if(d.kind()==DeviceBlock.Kind.SCREEN){
                DeviceEntity nearest=null;double dist=65;
                for(BlockPos pos:BlockPos.betweenClosed(position.offset(-8,-3,-8),position.offset(8,3,8))){if(p.serverLevel().getBlockEntity(pos) instanceof DeviceEntity other && (other.kind()==DeviceBlock.Kind.DESK||other.kind()==DeviceBlock.Kind.DOCK)){double n=pos.distSqr(position);if(n<dist){nearest=other;dist=n;}}}
                if(nearest==null)throw new DomainException("screen","附近没有可连接的主机");d.linkedConsole=nearest.getBlockPos();d.changed();p.displayClientMessage(Component.literal("屏幕已连接附近主机"),true);return;
            }
            if(d.kind()==DeviceBlock.Kind.DOCK){
                if(held.is(ConsoleContent.HANDHELD.get())){dock(p,d,held);return;}
                if(p.isShiftKeyDown()&&held.isEmpty()){ejectDock(p,state(p.server),d);return;}
                if(held.is(ConsoleContent.CARTRIDGE.get()))throw new DomainException("dock","请先将卡带装入掌机，再把整台掌机安装到底座");
            }
            if(held.is(ConsoleContent.CARTRIDGE.get())){
                if(!d.card.isEmpty())throw new DomainException("slot","请先取出当前卡带");
                Cartridge c=CardData.read(held).orElseThrow(()->new DomainException("card","卡带数据无效"));
                requireCardIdle(p,c.instanceId());
                if(!HardwareCatalog.accepts(d.getBlockState().getValue(DeviceBlock.MODEL),c.platform()))throw new DomainException("hardware","此卡带与主机机型不兼容");
                if(!state(p.server).content.has(c.sha256()))throw new DomainException("rom","服务器没有此卡带内容");
                stopDevice(d,"卡带已变更");d.card=held.split(1);d.changed();return;
            }
            if(held.is(ConsoleContent.CONTROLLER.get())){if(d.controllerCapacity()>=HardwareCatalog.ports(d.hardwareModel()))throw new DomainException("ports","此机型控制器接口已满");d.ports++;held.shrink(1);d.changed();return;}
            JsonObject j=view(d);Wire.send(p,"device_open",j);
        }catch(Exception e){MuxiConsole.LOG.warn("Console device interaction failed actor={} position={}",p.getUUID(),position,e);error(p,e instanceof DomainException de?de.code():"device",UserErrors.describe(e));}
    }
    public static JsonObject view(DeviceEntity d){JsonObject j=object();j.addProperty("position",d.getBlockPos().asLong());j.addProperty("kind",d.kind().name().toLowerCase(Locale.ROOT));j.addProperty("docked",!d.dockedHandheld.isEmpty());j.addProperty("powered",d.powered);j.addProperty("ports",d.controllerCapacity());j.addProperty("model",d.hardwareModel());j.addProperty("identity",d.identity.toString());CardData.read(d.media()).ifPresent(c->j.add("card",cardJson(c)));return j;}
    private static long now(){return System.currentTimeMillis();}
    static boolean hasBlank(ServerPlayer p){return p.containerMenu instanceof WriterMenu menu&&menu.stillValid(p)&&menu.hasBlank();}
    static WriterLease writer(ServerPlayer p,WorldState s,JsonObject b){
        // Hashing/caching a large local file can outlive five minutes before upload_begin.
        // The live, reachable exact menu/token is the authority; closing it retires the lease.
        WriterLease w=s.writers.get(p.getUUID());if(!(p.containerMenu instanceof WriterMenu menu)||!menu.stillValid(p)||w==null||!menu.matches(w.token) || !w.token.toString().equals(b.get("token").getAsString()) || !w.dimension.equals(dimension(p)))throw new DomainException("writer","请重新打开刻录机");
        if(p.distanceToSqr(w.position.getCenter())>64 || !(p.serverLevel().getBlockEntity(w.position) instanceof DeviceEntity d) || d.kind()!=DeviceBlock.Kind.WRITER)throw new DomainException("writer","刻录机已不可用");WriterLease renewed=new WriterLease(w.token,w.dimension,w.position,now()+300000);s.writers.put(p.getUUID(),renewed);return renewed;
    }
    public static void receive(ServerPlayer p,Wire.Message m){
        if(p.server.getPlayerList().getPlayer(p.getUUID())!=p)return;
        try{
            WorldState s=state(p.server);JsonObject b=m.body();
            if(s.cloud!=null&&s.cloud.receive(p,s,m))return;
            if(s.localMedia!=null&&s.localMedia.receive(p,s,m))return;
            if(LinkPlayServer.receive(p,s,m))return;
            if(net.muxigame.console.compat.BrowserLinkServer.receive(p,m))return;
            if(SharedPlayServer.receive(p,m))return;
            if(ProfileMessages.receive(p,s,m))return;
            if(StateMessages.receive(p,s,m))return;
            switch(m.operation()){
                case "upload_begin" -> {
                    UUID transaction=UUID.fromString(b.get("transaction").getAsString());WriterLease w=writer(p,s,b);if(!hasBlank(p))throw new DomainException("blank","需要一个空白卡带");
                    Platform platform=Platform.fromId(b.get("platform").getAsString());
                    var request=new UploadManager.Request(b.get("filename").getAsString(),platform,b.get("size").getAsLong(),b.get("hash").getAsString(),b.has("rights")&&b.get("rights").getAsBoolean());
                    var status=s.uploads.begin(p.getUUID(),request,now());String title=b.get("title").getAsString().strip();if(title.length()>80)title=title.substring(0,80);
                    s.burns.put(p.getUUID(),new UploadLease(status.id(),w,title,transaction));uploadAck(p,status,b);
                }
                case "upload_chunk" -> {UploadLease u=requireUpload(p,s,b);writer(p,s,b);var status=s.uploads.append(p.getUUID(),u.upload,b.get("offset").getAsLong(),m.data(),b.get("chunkHash").getAsString(),now());uploadAck(p,status,b);}
                case "upload_finish" -> {
                    UploadLease u=requireUpload(p,s,b);writer(p,s,b);if(!hasBlank(p))throw new DomainException("blank","请在刻录槽放入空白卡带");
                    UUID id=UUID.fromString(b.get("upload").getAsString());if(!u.upload.equals(id))throw new DomainException("upload","上传会话不匹配");
                    if(!(p.containerMenu instanceof WriterMenu menu)||!menu.matches(u.writer.token))throw new DomainException("writer","请在原刻录机窗口完成刻录");
                    RomDescriptor rom=s.uploads.finish(p.getUUID(),id,now());Cartridge c=s.content.issue(rom,u.title);menu.burn(CardData.create(c));s.burns.remove(p.getUUID(),u);JsonObject result=cardJson(c);result.addProperty("transaction",u.transaction.toString());Wire.send(p,"burned",result);
                }
                case "upload_abort" -> {UploadLease u=s.burns.get(p.getUUID());if(sameUpload(u,b)&&s.burns.remove(p.getUUID(),u))s.uploads.abort(p.getUUID(),u.upload);}
                case "rom_ticket" -> {
                    String hash=b.get("hash").getAsString();if(!hash.matches("[0-9a-f]{64}")||!mayDownload(p,hash,b))throw new DomainException("permission","需要持有相应卡带或当前主机控制权限");
                    if(s.cloud==null&&s.content.isRemote(hash)&&!s.content.hasLocal(hash))throw new DomainException("storage","此卡带内容位于云端，服务器尚未启用云存储，请联系服主");
                    JsonObject reply=object();reply.addProperty("request",b.get("request").getAsString());reply.addProperty("transport","local");Wire.send(p,"rom_transport",reply);
                }
                case "rom_read" -> {
                    String hash=b.get("hash").getAsString();if(!hash.matches("[0-9a-f]{64}") || !mayDownload(p,hash,b))throw new DomainException("permission","需要持有相应卡带或当前主机控制权限");
                    if(!s.content.hasLocal(hash))throw new DomainException("storage","此卡带内容未保存在本地，请联系服主检查云存储配置");
                    long offset=b.get("offset").getAsLong(),size=s.content.size(hash);if(offset<0||offset>=size)throw new DomainException("offset","下载位置无效");
                    JsonObject reply=object();reply.addProperty("request",b.get("request").getAsString());reply.addProperty("offset",offset);reply.addProperty("size",size);reply.addProperty("hash",hash);Wire.send(p,"rom_chunk",reply,s.content.read(hash,offset,24576));
                }
                case "device_action" -> action(p,s,b);
                case "handheld_start" -> {if(!b.has("presentation")||!b.get("presentation").getAsString().equals("held"))throw new DomainException("handheld","无效的掌机视图");startHandheld(p,InteractionHand.valueOf(b.get("hand").getAsString()),"held");}
                case "handheld_body" -> throw new DomainException("hardware","请在工作台用染料合成机型");
                case "load_progress" -> {Room r=ownedRoom(p,s,b);if(!r.ready.containsKey(p.getUUID()))r.loading.computeIfAbsent(p.getUUID(),id->new LoadingWatch(now())).observe(b.get("stage").getAsString(),b.get("bytes").getAsLong(),b.get("total").getAsLong(),now());}
                case "ready" -> {
                    Room r=ownedRoom(p,s,b);if(r.card.platform().sharedRuntime()&&!r.session.host().equals(p.getUUID()))throw new DomainException("core","此机型由主控制席运行核心，请重新加入控制席");String fp=b.get("fingerprint").getAsString();if(!fp.matches("[a-f0-9]{64}"))throw new DomainException("core","核心身份无效");
                    if(Set.of(Platform.NDS,Platform.N3DS,Platform.WII).contains(r.card.platform())){var profile=s.profiles.latest(new MachineProfileVault.Key(r.saveKey.machineUUID(),r.card.platform()));if(profile.isPresent()&&(!b.has("profileGeneration")||!profile.get().generation().toString().equals(b.get("profileGeneration").getAsString())))throw new DomainException("profile_stale","机器文件存档已改变，请重新加载机器");}
                    double fps=b.get("fps").getAsDouble();if(!Double.isFinite(fps)||fps<10||fps>120)throw new DomainException("core","核心帧率超出当前运行范围");
                    if(r.fingerprint.isEmpty()){r.fingerprint=fp;r.fps=fps;}else if(!r.fingerprint.equals(fp))throw new DomainException("core","多人玩家需要使用相同版本的模拟器核心");
                    if(r.card.platform().sharedRuntime()){
                        if(!b.has("hostClock")||b.get("hostClock").getAsInt()!=1)throw new DomainException("core","请同时更新客户端与服务器的游戏机模组");
                        if(!r.hostClock)r.hostFrameClock.reset(r.session.epoch(),r.fps,r.frame,System.nanoTime());
                        r.hostClock=true;r.frameFlow=false;
                    }
                    r.ready.put(p.getUUID(),fp);
                }
                case "clock_input" -> clockInput(p,s,m);
                case "clock_progress" -> {Room r=ownedRoomForSave(p,s,b);observeHostFrame(r,p.getUUID(),b.get("frame").getAsLong());}
                case "frame_done" -> {
                    Room r=ownedRoomForSave(p,s,b);if(!r.frameFlow||!r.session.host().equals(p.getUUID())||!r.ready.containsKey(p.getUUID()))throw new DomainException("authority","只有已准备的主控制席可确认模拟进度");
                    r.frameFlowWindow.acknowledge(r.session.epoch(),b.get("tick").getAsLong(),b.get("frame").getAsLong(),now());
                }
                case "input" -> {
                    Room r=ownedRoom(p,s,b);int port=b.get("port").getAsInt();long target=b.get("tick").getAsLong();boolean accepted=false;
                    try{short[] a=new short[4];if(b.getAsJsonArray("axes").size()!=4)throw new DomainException("input","摇杆数量无效");for(int i=0;i<4;i++){int v=b.getAsJsonArray("axes").get(i).getAsInt();if(v<Short.MIN_VALUE||v>Short.MAX_VALUE)throw new DomainException("input","摇杆范围无效");a[i]=(short)v;}
                        PortInput full=b.has("inputVersion")?InputCodec.decode(m.data()):new PortInput(b.get("buttons").getAsInt(),a[0],a[1],a[2],a[3]);
                        if(b.has("inputVersion")&&b.get("inputVersion").getAsInt()!=1||full.buttons()!=b.get("buttons").getAsInt()||full.leftX()!=a[0]||full.leftY()!=a[1]||full.rightX()!=a[2]||full.rightY()!=a[3])throw new DomainException("input","控制输入版本或主副数据不一致");
                        r.inputs.submit(p.getUUID(),b.get("epoch").getAsLong(),port,b.get("sequence").getAsLong(),target,full.buttons());r.analog.computeIfAbsent(target,n->new HashMap<>()).put(port,a);r.fullInputs.computeIfAbsent(target,n->new HashMap<>()).put(port,full);accepted=true;
                    }finally{JsonObject ack=object();ack.addProperty("room",r.key);ack.addProperty("epoch",r.session.epoch());ack.addProperty("accepted",accepted);ack.addProperty("nextSequence",r.inputs.expectedSequence(p.getUUID(),port));ack.addProperty("nextTick",r.inputs.nextTick());Wire.send(p,"input_ack",ack);}
                }
                case "leave" -> leave(p,s,"玩家离开控制席");
                case "leave_request" -> {Room r=ownedRoomForSave(p,s,b);r.requestedLeaves.add(p.getUUID());if(r.pendingJoin==null&&r.pendingLeave==null){r.pendingLeave=p.getUUID();r.handoverStarted=now();requestCheckpoint(p.server,r);}}
                case "runtime_failed" -> {Room r=ownedRoom(p,s,b);stopRoom(p.server,s,r,"模拟器启动或运行失败："+b.get("reason").getAsString());}
                case "frame_crc" -> {
                    Room r=ownedRoom(p,s,b);if(r.card.platform().sharedRuntime())return;long frame=b.get("frame").getAsLong();if(frame<0||frame>r.frame)throw new DomainException("frame","画面校验帧无效");
                    var reports=r.crc.computeIfAbsent(frame,f->new HashMap<>());reports.put(p.getUUID(),b.get("crc").getAsString());
                    if(reports.size()==r.session.controllers().size()){if(new HashSet<>(reports.values()).size()>1)stopRoom(p.server,s,r,"检测到模拟画面不一致，会话已停止以保护存档");r.crc.remove(frame);}r.crc.keySet().removeIf(f->f<r.frame-600);
                }
                default -> throw new DomainException("operation","未知操作");
            }
        }catch(Exception e){failed(p,m,e);}
    }
    static void failed(ServerPlayer p,Wire.Message m,Exception e){
            // Old input can already be in transit when a normal leave or epoch transition completes.
            if(e instanceof DomainException stale&&stale.code().equals("session")&&(Set.of("input","clock_input","clock_progress","ready","frame_crc","load_progress").contains(m.operation())||m.operation().startsWith("shared_")))return;
            JsonObject failure=object();failure.addProperty("code",e instanceof DomainException de?de.code():"request");failure.addProperty("message",UserErrors.describe(e));failure.addProperty("operation",m.operation());for(String identity:List.of("request","room","epoch","transaction","upload","token","saveTransaction","machine","content","platform","profileGeneration","generation"))if(m.body().has(identity)&&m.body().get(identity).isJsonPrimitive()&&m.body().get(identity).getAsString().length()<=256)failure.add(identity,m.body().get(identity));MuxiConsole.LOG.warn("Console request failed op={} actor={} code={}",m.operation(),p.getUUID(),failure.get("code").getAsString(),e);Wire.send(p,"error",failure);
            if(m.operation().startsWith("upload_")&&!Set.of("upload_abort","upload_touch","upload_ticket").contains(m.operation())){WorldState current=WORLDS.get(p.server);if(current!=null){UploadLease failed=current.burns.get(p.getUUID());if(sameUpload(failed,m.body())&&current.burns.remove(p.getUUID(),failed)){if(current.cloud!=null)current.cloud.abort(p.getUUID(),failed.upload);else if(current.localMedia!=null)current.localMedia.abort(p.getUUID(),failed.upload);else try{current.uploads.abort(p.getUUID(),failed.upload);}catch(IOException cleanup){MuxiConsole.LOG.warn("Failed upload cleanup",cleanup);}}}}
            if((m.operation().startsWith("profile_")&&!m.operation().equals("profile_read"))||m.operation().equals("save_progress")){WorldState current=WORLDS.get(p.server);if(current!=null)ProfileMessages.cancelFor(p.server,current,p.getUUID(),m.body());}
            if(m.operation().startsWith("state_")&&!m.operation().equals("state_read")){WorldState current=WORLDS.get(p.server);if(current!=null){StateMessages.cancelFor(p.server,current,p.getUUID(),m.body());ProfileMessages.cancelFor(p.server,current,p.getUUID(),m.body());}}
            if(Set.of("rom_read","rom_ticket").contains(m.operation())&&m.body().has("request")){JsonObject j=object();j.addProperty("request",m.body().get("request").getAsString());j.addProperty("reason",UserErrors.describe(e));j.addProperty("message",UserErrors.describe(e));j.addProperty("code",e instanceof DomainException de?de.code():"io");Wire.send(p,"rom_denied",j);}
    }
    static boolean sameUpload(UploadLease lease,JsonObject body){try{return lease!=null&&lease.transaction.toString().equals(body.get("transaction").getAsString())&&lease.writer.token.toString().equals(body.get("token").getAsString())&&(!body.has("upload")||lease.upload.toString().equals(body.get("upload").getAsString()));}catch(RuntimeException invalid){return false;}}
    static UploadLease requireUpload(ServerPlayer p,WorldState world,JsonObject body){UploadLease lease=world.burns.get(p.getUUID());if(!sameUpload(lease,body)||!body.has("upload"))throw new DomainException("stale_upload","上传事务已改变，请忽略旧窗口操作");return lease;}
    private static void uploadAck(ServerPlayer p,UploadManager.Status v,JsonObject original){JsonObject j=object();j.add("transaction",original.get("transaction"));j.addProperty("upload",v.id().toString());j.addProperty("offset",v.offset());j.addProperty("total",v.total());Wire.send(p,"upload_ack",j);}
    public static void writerClosed(ServerPlayer player,UUID token){
        WorldState world=WORLDS.get(player.server);if(world==null)return;WriterLease lease=world.writers.get(player.getUUID());if(lease!=null&&lease.token.equals(token))world.writers.remove(player.getUUID(),lease);
        if(world.cloud!=null)world.cloud.writerClosed(player.getUUID(),token);if(world.localMedia!=null)world.localMedia.writerClosed(player.getUUID(),token);
        UploadLease upload=world.burns.get(player.getUUID());if(upload!=null&&upload.writer.token.equals(token)&&world.burns.remove(player.getUUID(),upload)){if(world.cloud!=null)world.cloud.abort(player.getUUID(),upload.upload);else if(world.localMedia!=null)world.localMedia.abort(player.getUUID(),upload.upload);else try{world.uploads.abort(player.getUUID(),upload.upload);}catch(IOException failure){MuxiConsole.LOG.warn("Console writer close cleanup failed",failure);}}
    }
    private static void action(ServerPlayer p,WorldState s,JsonObject b){
        DeviceEntity d=device(p,b);if(d.kind()!=DeviceBlock.Kind.DESK&&d.kind()!=DeviceBlock.Kind.DOCK)throw new DomainException("device","此设备不是主机或掌机底座");
        switch(b.get("action").getAsString()){
            case "body" -> throw new DomainException("hardware","请取回机器，在工作台用染料合成机型");
            case "power" -> {if(d.hardwareModel()==0)throw new DomainException("hardware","游戏机核心需要先合成为具体机型");d.powered=!d.powered;if(!d.powered)stopDevice(d,"主机已关闭");d.changed();}
            case "eject" -> {if(d.kind()==DeviceBlock.Kind.DOCK){ejectDock(p,s,d);break;}if(!d.card.isEmpty()){CardData.read(d.card).ifPresent(c->requireCardIdle(p,c.instanceId()));int target=p.getInventory().getFreeSlot();if(target<0)throw new DomainException("slot","物品栏已满，卡带仍保留在主机中");p.getInventory().setItem(target,d.card);p.getInventory().setChanged();d.card=ItemStack.EMPTY;p.inventoryMenu.broadcastChanges();}stopDevice(d,"卡带已取出");d.powered=false;d.changed();}
            case "link" -> {Set<BlockPos> linked=new HashSet<>();for(BlockPos pos:BlockPos.betweenClosed(d.getBlockPos().offset(-8,-3,-8),d.getBlockPos().offset(8,3,8))){if(p.serverLevel().getBlockEntity(pos) instanceof DeviceEntity screen && screen.kind()==DeviceBlock.Kind.SCREEN){screen=primary(screen);if(linked.add(screen.getBlockPos())){screen.linkedConsole=d.getBlockPos();screen.changed();}}}p.displayClientMessage(Component.literal("已连接 "+linked.size()+" 个附近屏幕"),true);}
            case "join" -> {
                if(LinkPlayServer.isActorLocked(s,p.getUUID()))throw new DomainException("link_busy","请先结束当前游戏机连线");
                Cartridge card=CardData.read(d.media()).orElseThrow(()->new DomainException("card",d.kind()==DeviceBlock.Kind.DOCK?"请先安装已装卡的掌机":"请先插入卡带"));if(!d.powered)throw new DomainException("power","请先开机");
                if(d.hardwareModel()==0)throw new DomainException("hardware","游戏机核心需要先合成为具体机型");
                if(!HardwareCatalog.accepts(d.hardwareModel(),card.platform()))throw new DomainException("hardware","此卡带与主机机型不兼容");
                int available=d.controllerCapacity();int port=b.get("port").getAsInt();if(port<0||port>=available||port>=card.platform().controllerPorts())throw new DomainException("port","这个控制接口尚不可用");
                String roomKey=key(d);Room r=s.rooms.get(roomKey);
                if(r!=null&&r.session.isController(p.getUUID())){sendStart(p,r,true);return;}
                if(s.rooms.values().stream().anyMatch(room->room.session.isController(p.getUUID())))throw new DomainException("save","请先保存并离开当前游戏，再切换机器");
                r=s.rooms.get(roomKey);
                if(r==null){if(port!=0)throw new DomainException("port","请先由一号控制席启动会话");UUID machine=d.machineId();requireMachineIdle(s,machine);r=new Room(roomKey,d,card,machine,p.getUUID(),Math.min(available,card.platform().controllerPorts()));s.rooms.put(roomKey,r);}
                else if(!r.fingerprint.isEmpty()&&r.ready.containsKey(r.session.host())){if(r.pendingJoin!=null||r.pendingLeave!=null)throw new DomainException("save","正在同步存档，请稍候");if(r.session.controllers().containsKey(port))throw new DomainException("occupied","控制接口已被占用");r.pendingJoin=p.getUUID();r.pendingPort=port;r.handoverStarted=now();requestCheckpoint(p.server,r);return;}
                else r.session.grantControl(r.session.host(),p.getUUID(),port);
                r.openOnStart.add(p.getUUID());synchronize(p.server,r);
            }
            default -> throw new DomainException("operation","未知主机操作");
        }
        Wire.send(p,"device_state",view(d));
    }
    public static void handheld(ServerPlayer p,InteractionHand hand){
        try{
            if(LinkPlayServer.openIfLinked(p))return;
            ItemStack handheld=p.getItemInHand(hand);state(p.server);
            if(!handheld.is(ConsoleContent.HANDHELD.get()))throw new DomainException("handheld","请手持游戏机");
            if(HardwareCatalog.model(handheld)==0)throw new DomainException("hardware","请在工作台将游戏机核心与对应染料合成具体机型");
            if(!CardData.hasInserted(handheld))throw new DomainException("card","请在背包中用卡带右键游戏机，将卡带放入卡槽");
            if(CardData.read(handheld).isEmpty())throw new DomainException("card","卡槽数据异常，已保留原内容");
            startHandheld(p,hand,"fullscreen");
        }catch(Exception e){MuxiConsole.LOG.warn("Console handheld interaction failed actor={} hand={}",p.getUUID(),hand,e);error(p,e instanceof DomainException de?de.code():"handheld",UserErrors.describe(e));}
    }
    private static void startHandheld(ServerPlayer player,InteractionHand hand,String presentation)throws IOException{
        if(LinkPlayServer.isActorLocked(state(player.server),player.getUUID()))throw new DomainException("link_busy","请先结束当前游戏机连线");
        ItemStack handheld=player.getItemInHand(hand);if(!handheld.is(ConsoleContent.HANDHELD.get()))throw new DomainException("handheld","请手持游戏机");
        if(HardwareCatalog.model(handheld)==0)throw new DomainException("hardware","游戏机核心需要先合成为具体机型");
        Cartridge card=CardData.read(handheld).orElseThrow(()->new DomainException("card","掌机尚未装入卡带"));
        if(!HardwareCatalog.accepts(HardwareCatalog.model(handheld),card.platform()))throw new DomainException("hardware","卡带与掌机机型不兼容");
        boolean fresh=MachineData.read(handheld).isEmpty();UUID machine=MachineData.ensure(handheld);if(fresh)player.inventoryMenu.broadcastChanges();
        WorldState world=state(player.server);String roomKey="handheld/"+machine;Room existing=world.rooms.get(roomKey);
        if(existing!=null){if(existing.device!=null||!existing.session.isController(player.getUUID())||existing.handheldSaveDeadline!=0||!existing.card.instanceId().equals(card.instanceId())||!existing.saveKey.contentSHA().equals(card.sha256()))throw new DomainException("save","这台掌机的上一会话仍在保存，请稍候");existing.presentations.put(player.getUUID(),presentation);sendStart(player,existing,presentation.equals("fullscreen"));return;}
        if(world.rooms.values().stream().anyMatch(room->room.session.isController(player.getUUID())||room.card.instanceId().equals(card.instanceId())))throw new DomainException("save","请先正常离开当前游戏；掌机可收起等待保存完成");
        requireMachineIdle(world,machine);Room room=new Room(roomKey,null,card,machine,player.getUUID(),1);room.presentations.put(player.getUUID(),presentation);if(presentation.equals("fullscreen"))room.openOnStart.add(player.getUUID());world.rooms.put(room.key,room);synchronize(player.server,room);
    }
    private static void suspendHandheld(ServerPlayer player,Room room){
        if(room.handheldSaveDeadline!=0)return;room.handheldSaveDeadline=now()+15000;room.requestedLeaves.add(player.getUUID());
        if(room.pendingJoin==null&&room.pendingLeave==null){room.pendingLeave=player.getUUID();room.handoverStarted=now();requestCheckpoint(player.server,room);}
    }
    static Room ownedRoomForSave(ServerPlayer player,WorldState world,JsonObject body){
        Room room=world.rooms.get(body.get("room").getAsString());
        if(room==null||!room.session.isController(player.getUUID())||room.session.epoch()!=body.get("epoch").getAsLong())throw new DomainException("session","控制会话已失效");
        if(validParticipant(player,room))return room;
        if(room.device==null&&room.session.host().equals(player.getUUID())&&inInventory(player,room)){suspendHandheld(player,room);if(now()<=room.handheldSaveDeadline)return room;}
        throw new DomainException("session","保存会话已失效");
    }
    static Room ownedRoom(ServerPlayer p,WorldState s,JsonObject b){
        Room r=s.rooms.get(b.get("room").getAsString());if(r==null||!r.session.isController(p.getUUID())||r.session.epoch()!=b.get("epoch").getAsLong()||!validParticipant(p,r))throw new DomainException("session","控制会话已失效");return r;
    }
    public static boolean validParticipant(ServerPlayer p,Room r){
        if(r.device!=null)return nearby(p,r.device)&&r.device.powered&&CardData.read(r.device.media()).map(c->c.instanceId().equals(r.card.instanceId())&&c.sha256().equals(r.saveKey.contentSHA())&&r.device.machineId().equals(r.saveKey.machineUUID())).orElse(false);
        return r.handheldSaveDeadline==0&&inHand(p,r);
    }
    public static boolean mayDownload(ServerPlayer p,String hash,JsonObject b)throws IOException{
        if(LinkPlayServer.mayDownload(p,hash,b))return true;
        for(int i=0;i<p.getInventory().getContainerSize();i++)if(CardData.read(p.getInventory().getItem(i)).map(c->c.sha256().equals(hash)).orElse(false))return true;
        for(ItemStack item:List.of(p.containerMenu.getCarried()))if(CardData.read(item).map(c->c.sha256().equals(hash)).orElse(false))return true;
        WorldState s=state(p.server);if(!b.has("room")||!b.has("epoch"))return false;Room r=s.rooms.get(b.get("room").getAsString());
        return r!=null&&r.card.sha256().equals(hash)&&r.session.epoch()==b.get("epoch").getAsLong()&&r.session.isController(p.getUUID())&&(!r.card.platform().sharedRuntime()||r.session.host().equals(p.getUUID()))&&validParticipant(p,r);
    }
    private static void sendStart(ServerPlayer p,Room r){sendStart(p,r,false);}
    private static void sendStart(ServerPlayer p,Room r,boolean open){JsonObject j=cardJson(r.card);j.addProperty("machine",r.saveKey.machineUUID().toString());j.addProperty("content",r.saveKey.contentSHA());j.addProperty("presentation",r.presentations.getOrDefault(p.getUUID(),"fullscreen"));j.addProperty("open",open);boolean leader=p.getUUID().equals(r.session.host());j.addProperty("room",r.key);j.addProperty("epoch",r.session.epoch());j.addProperty("leader",leader);j.addProperty("shared",r.card.platform().sharedRuntime());j.addProperty("sharedPeers",Math.max(0,r.session.controllers().size()-1));j.addProperty("loadProgress",true);j.addProperty("romTickets",true);j.addProperty("frameFlow",false);if(r.card.platform().sharedRuntime())j.addProperty("hostClock",1);j.addProperty("position",r.device==null?Long.MIN_VALUE:r.device.getBlockPos().asLong());j.addProperty("port",r.session.controllers().entrySet().stream().filter(e->e.getValue().equals(p.getUUID())).findFirst().orElseThrow().getKey());if(!r.card.platform().sharedRuntime()||leader){StateMessages.attachLatest(WORLDS.get(p.server),r,j);ProfileMessages.attachLatest(WORLDS.get(p.server),r,j);}try{String name=WORLDS.get(p.server).content.describe(r.card.sha256()).filename();int dot=name.lastIndexOf('.');String extension=dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);if(r.card.platform().acceptsExtension(extension))j.addProperty("extension",extension);}catch(IOException|RuntimeException missing){MuxiConsole.LOG.warn("Console content metadata could not be attached hash={}",r.card.sha256(),missing);j.addProperty("loadError","卡带文件信息缺失，请联系服主检查内容存储");}Wire.send(p,"session_start",j);}
    private static void requestCheckpoint(MinecraftServer server,Room r){ServerPlayer host=server.getPlayerList().getPlayer(r.session.host());if(host!=null){JsonObject j=object();j.addProperty("room",r.key);j.addProperty("epoch",r.session.epoch());j.addProperty("hold",true);j.addProperty("holdToken",r.holdSaveBarrier.issue(UUID.randomUUID()));Wire.send(host,"checkpoint_request",j);}}
    static void cancelCheckpointHold(MinecraftServer server,Room room,String token){
        if(token==null||token.isEmpty()||!token.equals(room.holdSaveBarrier.token()))return;
        JsonObject resume=clockAddress(room);resume.addProperty("holdToken",token);ServerPlayer host=server.getPlayerList().getPlayer(room.session.host());if(host!=null)Wire.send(host,"clock_resume",resume);
        UUID waiting=room.pendingJoin;room.pendingJoin=null;room.pendingLeave=null;room.requestedLeaves.clear();room.holdSaveBarrier.clear();
        WorldState world=WORLDS.get(server);if(world!=null){StateMessages.cancel(server,world,room.session.host());ProfileMessages.cancel(server,world,room.session.host());}
        if(waiting!=null){ServerPlayer player=server.getPlayerList().getPlayer(waiting);if(player!=null)error(player,"save","存档未完成，已取消本次加入，请重试");}
    }
    /** The host owns the hardware clock, while the server still owns its epoch, physical seat and save scope. */
    public static void observeHostFrame(Room room,UUID actor,long completedFrame){
        if(!room.hostClock||!room.hostFrameClock.started()||!room.session.host().equals(actor)||!room.ready.containsKey(actor))throw new DomainException("authority","只有当前主控制席可以报告游戏进度");
        if(!room.clockDispatched&&completedFrame!=room.frame)throw new DomainException("host_clock","游戏机尚未获得实时运行授权");
        room.frame=room.hostFrameClock.observe(room.session.epoch(),completedFrame,System.nanoTime());
    }
    private static JsonObject clockAddress(Room room){JsonObject j=object();j.addProperty("room",room.key);j.addProperty("epoch",room.session.epoch());j.addProperty("port",room.session.controllers().entrySet().stream().filter(e->e.getValue().equals(room.session.host())).findFirst().orElseThrow().getKey());return j;}
    private static void clockLease(MinecraftServer server,Room room,boolean start)throws IOException{
        ServerPlayer host=server.getPlayerList().getPlayer(room.session.host());if(host==null)return;long time=System.nanoTime();
        if(!start&&time-room.clockLeaseSent<1_000_000_000L)return;
        JsonObject j=clockAddress(room);j.addProperty("leaseMillis",15000);j.addProperty("frame",room.frame);j.addProperty("fps",room.fps);
        if(start){
            List<PortInput> inputs=new ArrayList<>();for(int port=0;port<room.session.ports();port++)inputs.add(room.clockInputAt[port]!=0&&time-room.clockInputAt[port]<=300_000_000L?room.clockInputs[port].withoutMicrophone():PortInput.IDLE);
            Wire.send(host,"clock_start",j,InputCodec.encodeFrame(new InputFrame(inputs)));
        }else Wire.send(host,"clock_lease",j);
        room.clockLeaseSent=time;
    }
    private static void clockInput(ServerPlayer actor,WorldState world,Wire.Message message)throws IOException{
        JsonObject body=message.body();Room room=ownedRoom(actor,world,body);int port=body.get("port").getAsInt();long sequence=body.get("sequence").getAsLong();
        if(!room.card.platform().sharedRuntime()||port<0||port>=room.session.ports()||!room.session.controls(actor.getUUID(),port)||!room.ready.containsKey(actor.getUUID())||!body.has("inputVersion")||body.get("inputVersion").getAsInt()!=InputCodec.VERSION||sequence<0)throw new DomainException("authority","输入需要当前已准备的游戏机控制席");
        if(sequence<=room.clockInputSequences[port])return;
        long time=System.nanoTime(),previous=room.clockInputRateAt[port];room.clockInputSequences[port]=sequence;
        room.clockInputTokens[port]=Math.min(32,room.clockInputTokens[port]+(previous==0?0:Math.max(0,time-previous)/1e9*120));room.clockInputRateAt[port]=time;
        if(room.clockInputTokens[port]<1)return;room.clockInputTokens[port]--;
        PortInput input=InputCodec.decode(message.data());
        room.clockInputs[port]=input.withoutMicrophone();room.clockInputAt[port]=time;
        if(!room.hostClock||!room.clockDispatched||actor.getUUID().equals(room.session.host()))return;
        ServerPlayer host=actor.server.getPlayerList().getPlayer(room.session.host());if(host==null)return;
        JsonObject forwarded=object();forwarded.addProperty("room",room.key);forwarded.addProperty("epoch",room.session.epoch());forwarded.addProperty("port",port);forwarded.addProperty("sequence",sequence);forwarded.addProperty("inputVersion",InputCodec.VERSION);forwarded.addProperty("expiresMillis",300);
        Wire.send(host,"clock_input",forwarded,message.data());
    }
    static void saved(MinecraftServer server,WorldState world,Room r,MachineStateVault.Snapshot snapshot,String holdToken){
        if(world.rooms.get(r.key)!=r||!snapshot.key().equals(r.saveKey)||!snapshot.fingerprint().equals(r.fingerprint)||snapshot.frame()<r.frame)return;
        if(r.pendingJoin==null&&r.pendingLeave==null&&r.requestedLeaves.isEmpty())return;
        if(!r.holdSaveBarrier.accept(holdToken,snapshot.frame(),r.frame))return;
        r.holdSaveBarrier.clear();
        UUID joiningId=r.pendingJoin;r.pendingJoin=null;if(r.pendingLeave!=null)r.requestedLeaves.add(r.pendingLeave);r.pendingLeave=null;
        List<UUID> previous=List.copyOf(r.session.controllers().values());boolean changed=false;
        // Apply accepted departures against this committed state before opening another epoch.
        List<UUID> departures=r.requestedLeaves.stream().sorted(Comparator.comparing(id->!id.equals(r.session.host()))).toList();r.requestedLeaves.clear();
        for(UUID id:departures){changed|=r.session.leave(id);StateMessages.cancel(server,world,id);}
        JsonObject ended=object();ended.addProperty("room",r.key);ended.addProperty("epoch",r.observedEpoch);ended.addProperty("reason","存档已保存，玩家离开控制席");
        if(r.session.isClosed()||r.session.controllers().isEmpty()){
            world.rooms.remove(r.key);Set<UUID> notify=new HashSet<>(previous);if(joiningId!=null)notify.add(joiningId);for(UUID id:notify){ServerPlayer player=server.getPlayerList().getPlayer(id);if(player!=null)Wire.send(player,"session_end",ended);}return;
        }
        for(UUID id:departures){ServerPlayer player=server.getPlayerList().getPlayer(id);if(player!=null)Wire.send(player,"session_end",ended);}
        if(joiningId!=null){ServerPlayer joining=server.getPlayerList().getPlayer(joiningId);if(joining!=null&&r.device!=null&&nearby(joining,r.device)){r.session.grantControl(r.session.host(),joiningId,r.pendingPort);changed=true;}}
        if(joiningId!=null)r.openOnStart.add(joiningId);if(changed)synchronize(server,r);
    }
    static void saveProgress(Room room){long observed=now();if(room.pendingJoin!=null||room.pendingLeave!=null)room.handoverStarted=observed;if(room.handheldSaveDeadline!=0)room.handheldSaveDeadline=observed+15000;}
    private static void synchronize(MinecraftServer server,Room r){r.observedEpoch=r.session.epoch();r.inputs.resetForEpoch();r.ready.clear();r.loading.clear();r.analog.clear();r.heldInputs.clear();r.fullInputs.clear();r.heldFullInputs.clear();r.crc.clear();r.fingerprint="";r.frame=0;r.carry=0;r.waiting=0;r.frameFlow=false;r.frameFlowWaitTicks=0;r.hostClock=false;r.clockDispatched=false;r.hostFrameClock.clear();r.holdSaveBarrier.clear();r.clockLeaseSent=0;Arrays.fill(r.clockInputSequences,-1);Arrays.fill(r.clockInputAt,0);Arrays.fill(r.clockInputRateAt,0);Arrays.fill(r.clockInputTokens,32);Arrays.fill(r.clockInputs,PortInput.IDLE);r.frameFlowWindow.reset(r.session.epoch(),0,now());for(UUID id:r.session.controllers().values()){ServerPlayer p=server.getPlayerList().getPlayer(id);if(p!=null)sendStart(p,r,r.openOnStart.remove(id));}}
    private static void leave(ServerPlayer p,WorldState s,String reason){for(Room r:new ArrayList<>(s.rooms.values()))if(r.session.isController(p.getUUID())){List<UUID> previous=List.copyOf(r.session.controllers().values());long endingEpoch=r.session.epoch();r.session.leave(p.getUUID());JsonObject j=object();j.addProperty("room",r.key);j.addProperty("epoch",endingEpoch);j.addProperty("reason",reason);Wire.send(p,"session_end",j);if(r.session.isClosed()||r.session.controllers().isEmpty()){s.rooms.remove(r.key);for(UUID id:previous){ServerPlayer other=p.server.getPlayerList().getPlayer(id);if(other!=null&&!id.equals(p.getUUID()))Wire.send(other,"session_end",j);}}else synchronize(p.server,r);}}
    private static void stopRoom(MinecraftServer server,WorldState s,Room r,String reason){s.rooms.remove(r.key);MuxiConsole.LOG.info("Console session ended room={} epoch={} platform={} reason={}",r.key,r.session.epoch(),r.card.platform().id(),reason);JsonObject j=object();j.addProperty("room",r.key);j.addProperty("epoch",r.session.epoch());j.addProperty("reason",reason);for(UUID id:r.session.controllers().values()){StateMessages.cancel(server,s,id);ServerPlayer p=server.getPlayerList().getPlayer(id);if(p!=null)Wire.send(p,"session_end",j);}}
    private static void stopDevice(DeviceEntity d,String reason){if(d.getLevel() instanceof net.minecraft.server.level.ServerLevel level){WorldState s=WORLDS.get(level.getServer());if(s!=null){Room r=s.rooms.get(key(d));if(r!=null)stopRoom(level.getServer(),s,r,reason);}}}
    public static void removed(DeviceEntity d){stopDevice(d,"主机已移除");}
    public static void tick(ServerTickEvent.Post event){
        MinecraftServer server=event.getServer();WorldState s=WORLDS.get(server);if(s==null)return;
        LinkPlayServer.tick(server,s);
        if(server.getTickCount()%20==0)try{if(s.localMedia!=null)s.localMedia.expire();else s.uploads.expire(now());s.states.expire(now());s.profiles.expire(now());if(s.cloud!=null)s.cloud.expire();}catch(IOException e){MuxiConsole.LOG.warn("Upload expiration failed",e);}
        for(Room r:new ArrayList<>(s.rooms.values())){
            for(UUID id:new ArrayList<>(r.session.controllers().values())){ServerPlayer p=server.getPlayerList().getPlayer(id);if(p==null||!validParticipant(p,r)){
                if(p!=null&&r.device==null&&r.session.host().equals(id)&&inInventory(p,r)){suspendHandheld(p,r);if(now()<=r.handheldSaveDeadline)continue;stopRoom(server,s,r,"掌机收起后的保存超时，保留最近一次存档");}
                else if(p!=null)leave(p,s,"已离开主机或收起掌机");else stopRoom(server,s,r,"玩家已离线");
            }}
            if(!s.rooms.containsKey(r.key))continue;
            if(r.hostClock&&r.clockDispatched)try{clockLease(server,r,false);}catch(IOException failure){MuxiConsole.LOG.warn("Console clock lease could not be encoded room={}",r.key,failure);stopRoom(server,s,r,"游戏机运行授权未能续期，已保留最近一次存档");continue;}
            if(r.pendingJoin!=null||r.pendingLeave!=null){
                if(now()-r.handoverStarted<=60000)continue;
                cancelCheckpointHold(server,r,r.holdSaveBarrier.token());
            }
            if(r.session.epoch()!=r.observedEpoch)synchronize(server,r);
            if(!r.ready.keySet().containsAll(r.session.controllers().values())){long observed=now();boolean stalled=false;for(UUID id:r.session.controllers().values())if(!r.ready.containsKey(id)&&r.loading.computeIfAbsent(id,key->new LoadingWatch(observed)).expired(observed))stalled=true;if(stalled)stopRoom(server,s,r,"卡带加载长时间没有进展，请检查连接或客户端日志后重试");continue;}
            if(r.hostClock){
                if(!r.clockDispatched)try{r.hostFrameClock.reset(r.session.epoch(),r.fps,r.frame,System.nanoTime());clockLease(server,r,true);r.clockDispatched=true;}catch(IOException failure){MuxiConsole.LOG.warn("Console clock could not be started room={}",r.key,failure);stopRoom(server,s,r,"游戏机实时运行未能启动");}
                // Epoch/physical ownership and inputs are server controlled; CPU frames never wait on Minecraft ticks.
                ProfileMessages.isSerializing(server,r);continue;
            }
            if(ProfileMessages.isSerializing(server,r))continue;
            if(r.frameFlow){
                if(r.frameFlowWindow.expired(r.session.epoch(),now())){stopRoom(server,s,r,"客户端长时间没有完成模拟进度，已保留最近一次存档");continue;}
                if(!r.frameFlowWindow.available(r.session.epoch())){r.frameFlowWaitTicks++;continue;}
            }
            InputTimeline.Frame frame=r.inputs.advance();Map<Integer,short[]> axes=r.analog.remove(frame.tick());r.analog.keySet().removeIf(t->t<frame.tick());
            r.carry+=r.fps/20.0;int count=(int)r.carry;r.carry-=count;
            JsonObject j=object();j.addProperty("room",r.key);j.addProperty("epoch",frame.epoch());j.addProperty("tick",frame.tick());j.addProperty("frame",r.frame);j.addProperty("count",count);
            Map<Integer,PortInput> newFull=r.fullInputs.remove(frame.tick());r.fullInputs.keySet().removeIf(t->t<frame.tick());List<PortInput> completeInputs=new ArrayList<>();
            JsonArray ports=new JsonArray();for(int i=0;i<r.session.ports();i++){
                // Frontends report levels, so hold them across network RTT; absence for 6 ticks releases everything.
                short[] a=axes==null?null:axes.get(i);if(a!=null)r.heldInputs.put(i,new int[]{frame.mask(i),a[0],a[1],a[2],a[3],(int)frame.tick()});int[] held=r.heldInputs.get(i);JsonArray row=new JsonArray();boolean fresh=held!=null&&frame.tick()-held[5]<=6;for(int k=0;k<5;k++)row.add(fresh?held[k]:0);ports.add(row);
                PortInput incoming=newFull==null?null:newFull.get(i);if(incoming!=null)r.heldFullInputs.put(i,incoming.withoutMicrophone());completeInputs.add(fresh?(incoming!=null?incoming:r.heldFullInputs.getOrDefault(i,PortInput.IDLE)):PortInput.IDLE);
            }j.add("ports",ports);r.frame+=count;
            try{j.addProperty("inputVersion",1);byte[] inputBytes=InputCodec.encodeFrame(new InputFrame(completeInputs));if(r.frameFlow)r.frameFlowWindow.issued(r.session.epoch(),frame.tick(),r.frame,now());for(UUID id:r.session.controllers().values()){ServerPlayer p=server.getPlayerList().getPlayer(id);if(p!=null)Wire.send(p,"frames",j,inputBytes);}}
            catch(IOException invalid){MuxiConsole.LOG.warn("Console input frame could not be encoded room={}",r.key,invalid);stopRoom(server,s,r,"控制数据未能同步，已保留最近一次存档");}
        }
    }
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer p){WorldState s=WORLDS.get(p.server);if(s==null)return;leave(p,s,"玩家已离线");LinkPlayServer.disconnected(p,s);ProfileMessages.cancel(p.server,s,p.getUUID());if(s.cloud!=null)s.cloud.logout(p.getUUID());if(s.localMedia!=null)s.localMedia.logout(p.getUUID());s.writers.remove(p.getUUID());UploadLease u=s.burns.remove(p.getUUID());if(u!=null)try{if(s.cloud!=null)s.cloud.abort(p.getUUID(),u.upload);else if(s.localMedia!=null)s.localMedia.abort(p.getUUID(),u.upload);else s.uploads.abort(p.getUUID(),u.upload);}catch(IOException e){MuxiConsole.LOG.warn("Upload cancellation failed",e);}}}
    public static void stopped(ServerStoppedEvent event){LinkPlayServer.stopped(event.getServer());WorldState s=WORLDS.remove(event.getServer());StateMessages.stopped(event.getServer());ProfileMessages.stopped(event.getServer());if(s!=null){if(s.cloud!=null)s.cloud.close();if(s.localMedia!=null)s.localMedia.close();try{if(s.localMedia==null)s.uploads.close();}catch(IOException e){MuxiConsole.LOG.warn("Upload close failed",e);}try{s.dsLinks.close();}catch(IOException e){MuxiConsole.LOG.warn("DS group vault close failed",e);}try{s.states.close();}catch(IOException e){MuxiConsole.LOG.warn("State vault close failed",e);}try{s.profiles.close();}catch(IOException e){MuxiConsole.LOG.warn("Machine profile close failed",e);}}}
    private ConsoleServer(){}
}
