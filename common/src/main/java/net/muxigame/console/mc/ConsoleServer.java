package net.muxigame.console.mc;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.domain.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Minecraft ownership adapter for the independently implemented domain. All calls run on its server thread. */
public final class ConsoleServer {
    private static final Map<MinecraftServer,WorldState> WORLDS=new IdentityHashMap<>();
    private record WriterLease(UUID token,String dimension,BlockPos position,long expires){}
    private record UploadLease(UUID upload,WriterLease writer,String title){}
    public static final class Room {
        public final String key;public final DeviceEntity device;public final Cartridge card;public final ConsoleSession session;public final InputTimeline inputs;
        public final Map<UUID,String> ready=new HashMap<>();public final Map<Long,Map<Integer,short[]>> analog=new TreeMap<>();
        public final Map<Integer,int[]> heldInputs=new HashMap<>();
        public final Map<Long,Map<UUID,String>> crc=new HashMap<>();
        public final Set<UUID> requestedLeaves=new LinkedHashSet<>();public final Map<UUID,String> presentations=new HashMap<>();public final Set<UUID> openOnStart=new HashSet<>();public long handheldSaveDeadline;
        public long observedEpoch=0,frame=0,waiting=0,handoverStarted;public double fps=60,carry=0;public String fingerprint="";public UUID pendingJoin,pendingLeave;public int pendingPort;
        Room(String key,DeviceEntity d,Cartridge c,UUID host,int count){this.key=key;device=d;card=c;session=new ConsoleSession(d==null?UUID.randomUUID():d.identity,c,host,count);inputs=new InputTimeline(session,12);}
    }
    public static final class WorldState {
        public final ContentStore content;public final UploadManager uploads;public final StateVault states;public final Map<UUID,WriterLease>writers=new HashMap<>();public final Map<UUID,UploadLease>burns=new HashMap<>();public final Map<String,Room>rooms=new HashMap<>();
        WorldState(MinecraftServer server)throws IOException{Path p=server.getWorldPath(LevelResource.ROOT).resolve("muxi-console");content=new ContentStore(p.resolve("content"));uploads=new UploadManager(content,p.resolve("incoming"));states=new StateVault(p.resolve("states"));}
    }
    public static WorldState state(MinecraftServer server)throws IOException{WorldState s=WORLDS.get(server);if(s==null){s=new WorldState(server);WORLDS.put(server,s);}return s;}
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
    private static boolean inHand(ServerPlayer player,UUID instance){return List.of(player.getMainHandItem(),player.getOffhandItem()).stream().filter(item->item.is(ConsoleContent.HANDHELD.get())).anyMatch(item->CardData.read(item).map(card->card.instanceId().equals(instance)).orElse(false));}
    private static boolean inInventory(ServerPlayer player,UUID instance){for(int slot=0;slot<player.getInventory().getContainerSize();slot++){ItemStack item=player.getInventory().getItem(slot);if(item.is(ConsoleContent.HANDHELD.get())&&CardData.read(item).map(card->card.instanceId().equals(instance)).orElse(false))return true;}return false;}
    private static void requireInactive(WorldState world,DeviceEntity device){if(world.rooms.containsKey(key(device)))throw new DomainException("save","请先按Esc保存并离开游戏，再取出掌机");}
    private static void dock(ServerPlayer player,DeviceEntity device,ItemStack held)throws IOException{
        WorldState world=state(player.server);requireInactive(world,device);
        if(!device.dockedHandheld.isEmpty())throw new DomainException("dock","底座已有掌机，请先取出");
        var body=HardwareCatalog.body(HardwareCatalog.model(held));if(HardwareCatalog.model(held)!=0&&(body==null||!body.handheld()))throw new DomainException("hardware","只能安装手持式游戏机");
        Optional<Cartridge> card=CardData.read(held);if(card.isPresent()&&activeCard(world,card.get().instanceId()))throw new DomainException("save","请先按Esc保存并离开掌机，再放到底座");
        device.dockedHandheld=held.split(1);device.powered=false;device.changed();player.inventoryMenu.broadcastChanges();player.displayClientMessage(Component.literal("掌机已安装；使用底座连接大屏幕"),true);
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
                JsonObject j=object();j.addProperty("token",w.token.toString());j.addProperty("position",position.asLong());Wire.send(p,"writer_open",j);return;
            }
            if(d.kind()==DeviceBlock.Kind.SCREEN){
                DeviceEntity nearest=null;double dist=65;
                for(BlockPos pos:BlockPos.betweenClosed(position.offset(-8,-3,-8),position.offset(8,3,8))){if(p.serverLevel().getBlockEntity(pos) instanceof DeviceEntity other && (other.kind()==DeviceBlock.Kind.DESK||other.kind()==DeviceBlock.Kind.DOCK)){double n=pos.distSqr(position);if(n<dist){nearest=other;dist=n;}}}
                if(nearest==null)throw new DomainException("screen","附近没有可连接的木犀主机");d.linkedConsole=nearest.getBlockPos();d.changed();p.displayClientMessage(Component.literal("屏幕已连接附近主机"),true);return;
            }
            if(d.kind()==DeviceBlock.Kind.DOCK){
                if(held.is(ConsoleContent.HANDHELD.get())){dock(p,d,held);return;}
                if(p.isShiftKeyDown()&&held.isEmpty()){ejectDock(p,state(p.server),d);return;}
                if(held.is(ConsoleContent.CARTRIDGE.get()))throw new DomainException("dock","请先将卡带装入掌机，再把整台掌机安装到底座");
            }
            if(held.is(ConsoleContent.CARTRIDGE.get())){
                if(!d.card.isEmpty())throw new DomainException("slot","请先取出当前卡带");
                Cartridge c=CardData.read(held).orElseThrow(()->new DomainException("card","卡带数据无效"));
                if(!HardwareCatalog.accepts(d.getBlockState().getValue(DeviceBlock.MODEL),c.platform()))throw new DomainException("hardware","此卡带与主机机型不兼容");
                if(!state(p.server).content.has(c.sha256()))throw new DomainException("rom","服务器没有此卡带内容");
                stopDevice(d,"卡带已变更");d.card=held.split(1);d.changed();return;
            }
            if(held.is(ConsoleContent.CONTROLLER.get())){if(d.controllerCapacity()>=HardwareCatalog.ports(d.hardwareModel()))throw new DomainException("ports","此机型控制器接口已满");d.ports++;held.shrink(1);d.changed();return;}
            JsonObject j=view(d);Wire.send(p,"device_open",j);
        }catch(Exception e){error(p,e instanceof DomainException de?de.code():"device",e.getMessage());}
    }
    public static JsonObject view(DeviceEntity d){JsonObject j=object();j.addProperty("position",d.getBlockPos().asLong());j.addProperty("kind",d.kind().name().toLowerCase(Locale.ROOT));j.addProperty("docked",!d.dockedHandheld.isEmpty());j.addProperty("powered",d.powered);j.addProperty("ports",d.controllerCapacity());j.addProperty("model",d.hardwareModel());j.addProperty("identity",d.identity.toString());CardData.read(d.media()).ifPresent(c->j.add("card",cardJson(c)));return j;}
    private static long now(){return System.currentTimeMillis();}
    private static boolean hasBlank(ServerPlayer p){for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(ConsoleContent.BLANK.get()))return true;return false;}
    private static void consumeBlank(ServerPlayer p){for(int i=0;i<p.getInventory().getContainerSize();i++){ItemStack s=p.getInventory().getItem(i);if(s.is(ConsoleContent.BLANK.get())){s.shrink(1);return;}}throw new DomainException("blank","需要一个空白卡带");}
    private static WriterLease writer(ServerPlayer p,WorldState s,JsonObject b){
        WriterLease w=s.writers.get(p.getUUID());if(w==null || w.expires<now() || !w.token.toString().equals(b.get("token").getAsString()) || !w.dimension.equals(dimension(p)))throw new DomainException("writer","请重新打开刻录机");
        if(p.distanceToSqr(w.position.getCenter())>64 || !(p.serverLevel().getBlockEntity(w.position) instanceof DeviceEntity d) || d.kind()!=DeviceBlock.Kind.WRITER)throw new DomainException("writer","刻录机已不可用");WriterLease renewed=new WriterLease(w.token,w.dimension,w.position,now()+300000);s.writers.put(p.getUUID(),renewed);return renewed;
    }
    public static void receive(ServerPlayer p,Wire.Message m){
        try{
            WorldState s=state(p.server);JsonObject b=m.body();
            if(net.muxigame.console.compat.BrowserLinkServer.receive(p,m))return;
            if(SharedPlayServer.receive(p,m))return;
            if(StateMessages.receive(p,s,m))return;
            switch(m.operation()){
                case "upload_begin" -> {
                    WriterLease w=writer(p,s,b);if(!hasBlank(p))throw new DomainException("blank","需要一个空白卡带");
                    Platform platform=Platform.fromId(b.get("platform").getAsString());
                    var request=new UploadManager.Request(b.get("filename").getAsString(),platform,b.get("size").getAsLong(),b.get("hash").getAsString(),b.has("rights")&&b.get("rights").getAsBoolean());
                    var status=s.uploads.begin(p.getUUID(),request,now());String title=b.get("title").getAsString().strip();if(title.length()>80)title=title.substring(0,80);
                    s.burns.put(p.getUUID(),new UploadLease(status.id(),w,title));uploadAck(p,status);
                }
                case "upload_chunk" -> {UploadLease u=s.burns.get(p.getUUID());if(u==null)throw new DomainException("upload","没有进行中的上传");writer(p,s,b);var status=s.uploads.append(p.getUUID(),UUID.fromString(b.get("upload").getAsString()),b.get("offset").getAsLong(),m.data(),b.get("chunkHash").getAsString(),now());uploadAck(p,status);}
                case "upload_finish" -> {
                    UploadLease u=s.burns.get(p.getUUID());if(u==null)throw new DomainException("upload","没有进行中的上传");writer(p,s,b);if(!hasBlank(p))throw new DomainException("blank","空白卡带已不在物品栏中");
                    UUID id=UUID.fromString(b.get("upload").getAsString());if(!u.upload.equals(id))throw new DomainException("upload","上传会话不匹配");
                    RomDescriptor rom=s.uploads.finish(p.getUUID(),id,now());Cartridge c=s.content.issue(rom,u.title);consumeBlank(p);ItemStack card=CardData.create(c);if(!p.getInventory().add(card))p.drop(card,false);p.inventoryMenu.broadcastChanges();s.burns.remove(p.getUUID());Wire.send(p,"burned",cardJson(c));
                }
                case "upload_abort" -> {UploadLease u=s.burns.remove(p.getUUID());if(u!=null)s.uploads.abort(p.getUUID(),u.upload);}
                case "rom_read" -> {
                    String hash=b.get("hash").getAsString();if(!hash.matches("[0-9a-f]{64}") || !mayDownload(p,hash,b))throw new DomainException("permission","需要持有相应卡带或当前主机控制权限");
                    long offset=b.get("offset").getAsLong(),size=s.content.size(hash);if(offset<0||offset>=size)throw new DomainException("offset","下载位置无效");
                    JsonObject reply=object();reply.addProperty("request",b.get("request").getAsString());reply.addProperty("offset",offset);reply.addProperty("size",size);reply.addProperty("hash",hash);Wire.send(p,"rom_chunk",reply,s.content.read(hash,offset,24576));
                }
                case "device_action" -> action(p,s,b);
                case "handheld_start" -> {if(!b.has("presentation")||!b.get("presentation").getAsString().equals("held"))throw new DomainException("handheld","无效的掌机视图");startHandheld(p,InteractionHand.valueOf(b.get("hand").getAsString()),"held");}
                case "handheld_body" -> {InteractionHand hand=InteractionHand.valueOf(b.get("hand").getAsString());ItemStack item=p.getItemInHand(hand);int model=b.get("model").getAsInt();var body=HardwareCatalog.body(model);if(!item.is(ConsoleContent.HANDHELD.get())||CardData.read(item).isPresent()||model!=0&&(body==null||!body.handheld()))throw new DomainException("hardware","请手持空掌机选择有效机型");HardwareCatalog.variant(item,model);p.inventoryMenu.broadcastChanges();}
                case "ready" -> {
                    Room r=ownedRoom(p,s,b);if(r.card.platform().sharedRuntime()&&!r.session.host().equals(p.getUUID()))throw new DomainException("core","此机型由主控制席运行核心，请重新加入控制席");String fp=b.get("fingerprint").getAsString();if(!fp.matches("[a-f0-9]{64}"))throw new DomainException("core","核心身份无效");
                    double fps=b.get("fps").getAsDouble();if(!Double.isFinite(fps)||fps<10||fps>120)throw new DomainException("core","核心帧率超出当前运行范围");
                    if(r.fingerprint.isEmpty()){r.fingerprint=fp;r.fps=fps;}else if(!r.fingerprint.equals(fp))throw new DomainException("core","多人玩家需要使用相同版本的模拟器核心");r.ready.put(p.getUUID(),fp);
                }
                case "input" -> {
                    Room r=ownedRoom(p,s,b);int port=b.get("port").getAsInt();long target=b.get("tick").getAsLong();boolean accepted=false;
                    try{short[] a=new short[4];if(b.getAsJsonArray("axes").size()!=4)throw new DomainException("input","摇杆数量无效");for(int i=0;i<4;i++){int v=b.getAsJsonArray("axes").get(i).getAsInt();if(v<Short.MIN_VALUE||v>Short.MAX_VALUE)throw new DomainException("input","摇杆范围无效");a[i]=(short)v;}
                        r.inputs.submit(p.getUUID(),b.get("epoch").getAsLong(),port,b.get("sequence").getAsLong(),target,b.get("buttons").getAsInt());r.analog.computeIfAbsent(target,n->new HashMap<>()).put(port,a);accepted=true;
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
        }catch(Exception e){
            // Old input can already be in transit when a normal leave or epoch transition completes.
            if(e instanceof DomainException stale&&stale.code().equals("session")&&(Set.of("input","ready","frame_crc").contains(m.operation())||m.operation().startsWith("shared_")))return;
            JsonObject failure=object();failure.addProperty("code",e instanceof DomainException de?de.code():"request");failure.addProperty("message",UserErrors.describe(e));failure.addProperty("operation",m.operation());Wire.send(p,"error",failure);
            if(m.operation().startsWith("upload_")&&!m.operation().equals("upload_abort")){WorldState current=WORLDS.get(p.server);if(current!=null){UploadLease failed=current.burns.remove(p.getUUID());if(failed!=null)try{current.uploads.abort(p.getUUID(),failed.upload);}catch(IOException cleanup){MuxiConsole.LOG.warn("Failed upload cleanup",cleanup);}}}
            if(m.operation().startsWith("state_")&&!m.operation().equals("state_read")){WorldState current=WORLDS.get(p.server);if(current!=null)StateMessages.cancel(p.server,current,p.getUUID());}
            if(m.operation().equals("rom_read")&&m.body().has("request")){JsonObject j=object();j.addProperty("request",m.body().get("request").getAsString());j.addProperty("reason",e.getMessage());Wire.send(p,"rom_denied",j);}
        }
    }
    private static void uploadAck(ServerPlayer p,UploadManager.Status v){JsonObject j=object();j.addProperty("upload",v.id().toString());j.addProperty("offset",v.offset());j.addProperty("total",v.total());Wire.send(p,"upload_ack",j);}
    private static void action(ServerPlayer p,WorldState s,JsonObject b){
        DeviceEntity d=device(p,b);if(d.kind()!=DeviceBlock.Kind.DESK&&d.kind()!=DeviceBlock.Kind.DOCK)throw new DomainException("device","此设备不是主机或掌机底座");
        switch(b.get("action").getAsString()){
            case "body" -> {if(d.kind()==DeviceBlock.Kind.DOCK)throw new DomainException("hardware","底座保留安装的掌机机型");if(d.powered||!d.card.isEmpty())throw new DomainException("hardware","取出卡带并关机后才能选择机型");int model=b.get("port").getAsInt();if(model<0||model>22)throw new DomainException("hardware","未知机型");if(d.ports>HardwareCatalog.ports(model))throw new DomainException("ports","请先选择具有足够控制接口的机型");p.serverLevel().setBlock(d.getBlockPos(),d.getBlockState().setValue(DeviceBlock.MODEL,model),3);d.changed();}
            case "power" -> {d.powered=!d.powered;if(!d.powered)stopDevice(d,"主机已关闭");d.changed();}
            case "eject" -> {if(d.kind()==DeviceBlock.Kind.DOCK){ejectDock(p,s,d);break;}stopDevice(d,"卡带已取出");if(!d.card.isEmpty()){ItemStack take=d.card;d.card=ItemStack.EMPTY;if(!p.getInventory().add(take))p.drop(take,false);p.inventoryMenu.broadcastChanges();}d.powered=false;d.changed();}
            case "link" -> {Set<BlockPos> linked=new HashSet<>();for(BlockPos pos:BlockPos.betweenClosed(d.getBlockPos().offset(-8,-3,-8),d.getBlockPos().offset(8,3,8))){if(p.serverLevel().getBlockEntity(pos) instanceof DeviceEntity screen && screen.kind()==DeviceBlock.Kind.SCREEN){screen=primary(screen);if(linked.add(screen.getBlockPos())){screen.linkedConsole=d.getBlockPos();screen.changed();}}}p.displayClientMessage(Component.literal("已连接 "+linked.size()+" 个附近屏幕"),true);}
            case "join" -> {
                Cartridge card=CardData.read(d.media()).orElseThrow(()->new DomainException("card",d.kind()==DeviceBlock.Kind.DOCK?"请先安装已装卡的掌机":"请先插入卡带"));if(!d.powered)throw new DomainException("power","请先开机");
                if(!HardwareCatalog.accepts(d.hardwareModel(),card.platform()))throw new DomainException("hardware","此卡带与主机机型不兼容");
                int available=d.controllerCapacity();int port=b.get("port").getAsInt();if(port<0||port>=available||port>=card.platform().controllerPorts())throw new DomainException("port","这个控制接口尚不可用");
                String roomKey=key(d);Room r=s.rooms.get(roomKey);
                if(r!=null&&r.session.isController(p.getUUID())){sendStart(p,r,true);return;}
                leave(p,s,"切换控制席");
                r=s.rooms.get(roomKey);
                if(r==null){if(port!=0)throw new DomainException("port","请先由一号控制席启动会话");r=new Room(roomKey,d,card,p.getUUID(),Math.min(available,card.platform().controllerPorts()));s.rooms.put(roomKey,r);}
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
            ItemStack handheld=p.getItemInHand(hand);WorldState s=state(p.server);Optional<Cartridge> cart=CardData.read(handheld);
            if(p.isShiftKeyDown()){
                if(cart.isPresent()){if(activeCard(s,cart.get().instanceId()))throw new DomainException("save","请先收起掌机等待保存完成，再潜行取出卡带");ItemStack c=CardData.create(cart.get());CardData.clear(handheld);if(!p.getInventory().add(c))p.drop(c,false);return;}
                for(int i=0;i<p.getInventory().getContainerSize();i++){ItemStack c=p.getInventory().getItem(i);if(c.is(ConsoleContent.CARTRIDGE.get())){Cartridge data=CardData.read(c).orElseThrow();if(!HardwareCatalog.accepts(HardwareCatalog.model(handheld),data.platform()))continue;CardData.write(handheld,data);c.shrink(1);p.inventoryMenu.broadcastChanges();return;}}
                throw new DomainException("card","物品栏中需要一张已刻录卡带");
            }
            if(cart.isEmpty()){JsonObject config=object();config.addProperty("hand",hand.name());config.addProperty("model",HardwareCatalog.model(handheld));Wire.send(p,"handheld_config",config);return;}
            startHandheld(p,hand,"fullscreen");
        }catch(Exception e){error(p,e instanceof DomainException de?de.code():"handheld",e.getMessage());}
    }
    private static void startHandheld(ServerPlayer player,InteractionHand hand,String presentation)throws IOException{
        ItemStack handheld=player.getItemInHand(hand);if(!handheld.is(ConsoleContent.HANDHELD.get()))throw new DomainException("handheld","请手持游戏机");
        Cartridge card=CardData.read(handheld).orElseThrow(()->new DomainException("card","掌机尚未装入卡带"));
        if(!HardwareCatalog.accepts(HardwareCatalog.model(handheld),card.platform()))throw new DomainException("hardware","卡带与掌机机型不兼容");
        WorldState world=state(player.server);String roomKey="handheld/"+card.instanceId();Room existing=world.rooms.get(roomKey);
        if(existing!=null){if(existing.device!=null||!existing.session.isController(player.getUUID())||existing.handheldSaveDeadline!=0)throw new DomainException("save","这张卡带的上一会话仍在保存，请稍候");existing.presentations.put(player.getUUID(),presentation);sendStart(player,existing,presentation.equals("fullscreen"));return;}
        if(world.rooms.values().stream().anyMatch(room->room.session.isController(player.getUUID())||room.card.instanceId().equals(card.instanceId())))throw new DomainException("save","请先正常离开当前游戏；掌机可收起等待保存完成");
        Room room=new Room(roomKey,null,card,player.getUUID(),1);room.presentations.put(player.getUUID(),presentation);if(presentation.equals("fullscreen"))room.openOnStart.add(player.getUUID());world.rooms.put(room.key,room);synchronize(player.server,room);
    }
    private static void suspendHandheld(ServerPlayer player,Room room){
        if(room.handheldSaveDeadline!=0)return;room.handheldSaveDeadline=now()+15000;room.requestedLeaves.add(player.getUUID());
        if(room.pendingJoin==null&&room.pendingLeave==null){room.pendingLeave=player.getUUID();room.handoverStarted=now();requestCheckpoint(player.server,room);}
    }
    static Room ownedRoomForSave(ServerPlayer player,WorldState world,JsonObject body){
        Room room=world.rooms.get(body.get("room").getAsString());
        if(room==null||!room.session.isController(player.getUUID())||room.session.epoch()!=body.get("epoch").getAsLong())throw new DomainException("session","控制会话已失效");
        if(validParticipant(player,room))return room;
        if(room.device==null&&room.session.host().equals(player.getUUID())&&inInventory(player,room.card.instanceId())){suspendHandheld(player,room);if(now()<=room.handheldSaveDeadline)return room;}
        throw new DomainException("session","保存会话已失效");
    }
    static Room ownedRoom(ServerPlayer p,WorldState s,JsonObject b){
        Room r=s.rooms.get(b.get("room").getAsString());if(r==null||!r.session.isController(p.getUUID())||r.session.epoch()!=b.get("epoch").getAsLong()||!validParticipant(p,r))throw new DomainException("session","控制会话已失效");return r;
    }
    public static boolean validParticipant(ServerPlayer p,Room r){
        if(r.device!=null)return nearby(p,r.device)&&r.device.powered&&CardData.read(r.device.media()).map(c->c.instanceId().equals(r.card.instanceId())).orElse(false);
        return r.handheldSaveDeadline==0&&inHand(p,r.card.instanceId());
    }
    public static boolean mayDownload(ServerPlayer p,String hash,JsonObject b)throws IOException{
        for(int i=0;i<p.getInventory().getContainerSize();i++)if(CardData.read(p.getInventory().getItem(i)).map(c->c.sha256().equals(hash)).orElse(false))return true;
        for(ItemStack item:List.of(p.containerMenu.getCarried()))if(CardData.read(item).map(c->c.sha256().equals(hash)).orElse(false))return true;
        WorldState s=state(p.server);if(!b.has("room")||!b.has("epoch"))return false;Room r=s.rooms.get(b.get("room").getAsString());
        return r!=null&&r.card.sha256().equals(hash)&&r.session.epoch()==b.get("epoch").getAsLong()&&r.session.isController(p.getUUID())&&(!r.card.platform().sharedRuntime()||r.session.host().equals(p.getUUID()))&&validParticipant(p,r);
    }
    private static void sendStart(ServerPlayer p,Room r){sendStart(p,r,false);}
    private static void sendStart(ServerPlayer p,Room r,boolean open){JsonObject j=cardJson(r.card);j.addProperty("presentation",r.presentations.getOrDefault(p.getUUID(),"fullscreen"));j.addProperty("open",open);boolean leader=p.getUUID().equals(r.session.host());j.addProperty("room",r.key);j.addProperty("epoch",r.session.epoch());j.addProperty("leader",leader);j.addProperty("shared",r.card.platform().sharedRuntime());j.addProperty("position",r.device==null?Long.MIN_VALUE:r.device.getBlockPos().asLong());j.addProperty("port",r.session.controllers().entrySet().stream().filter(e->e.getValue().equals(p.getUUID())).findFirst().orElseThrow().getKey());if(!r.card.platform().sharedRuntime()||leader)StateMessages.attachLatest(WORLDS.get(p.server),r,j);Wire.send(p,"session_start",j);}
    private static void requestCheckpoint(MinecraftServer server,Room r){ServerPlayer host=server.getPlayerList().getPlayer(r.session.host());if(host!=null){JsonObject j=object();j.addProperty("room",r.key);j.addProperty("epoch",r.session.epoch());Wire.send(host,"checkpoint_request",j);}}
    static void saved(MinecraftServer server,WorldState world,Room r,StateVault.Snapshot snapshot){
        if(!world.rooms.containsKey(r.key)||snapshot.frame()<r.frame)return;
        UUID joiningId=r.pendingJoin;r.pendingJoin=null;if(r.pendingLeave!=null)r.requestedLeaves.add(r.pendingLeave);r.pendingLeave=null;
        List<UUID> previous=List.copyOf(r.session.controllers().values());boolean changed=false;
        // Apply accepted departures against this committed state before opening another epoch.
        List<UUID> departures=r.requestedLeaves.stream().sorted(Comparator.comparing(id->!id.equals(r.session.host()))).toList();r.requestedLeaves.clear();
        for(UUID id:departures){changed|=r.session.leave(id);StateMessages.cancel(server,world,id);}
        JsonObject ended=object();ended.addProperty("reason","存档已保存，玩家离开控制席");
        if(r.session.isClosed()||r.session.controllers().isEmpty()){
            world.rooms.remove(r.key);Set<UUID> notify=new HashSet<>(previous);if(joiningId!=null)notify.add(joiningId);for(UUID id:notify){ServerPlayer player=server.getPlayerList().getPlayer(id);if(player!=null)Wire.send(player,"session_end",ended);}return;
        }
        for(UUID id:departures){ServerPlayer player=server.getPlayerList().getPlayer(id);if(player!=null)Wire.send(player,"session_end",ended);}
        if(joiningId!=null){ServerPlayer joining=server.getPlayerList().getPlayer(joiningId);if(joining!=null&&r.device!=null&&nearby(joining,r.device)){r.session.grantControl(r.session.host(),joiningId,r.pendingPort);changed=true;}}
        if(joiningId!=null)r.openOnStart.add(joiningId);if(changed)synchronize(server,r);
    }
    private static void synchronize(MinecraftServer server,Room r){r.observedEpoch=r.session.epoch();r.inputs.resetForEpoch();r.ready.clear();r.analog.clear();r.heldInputs.clear();r.crc.clear();r.fingerprint="";r.frame=0;r.carry=0;r.waiting=0;for(UUID id:r.session.controllers().values()){ServerPlayer p=server.getPlayerList().getPlayer(id);if(p!=null)sendStart(p,r,r.openOnStart.remove(id));}}
    private static void leave(ServerPlayer p,WorldState s,String reason){for(Room r:new ArrayList<>(s.rooms.values()))if(r.session.isController(p.getUUID())){List<UUID> previous=List.copyOf(r.session.controllers().values());r.session.leave(p.getUUID());JsonObject j=object();j.addProperty("reason",reason);Wire.send(p,"session_end",j);if(r.session.isClosed()||r.session.controllers().isEmpty()){s.rooms.remove(r.key);for(UUID id:previous){ServerPlayer other=p.server.getPlayerList().getPlayer(id);if(other!=null&&!id.equals(p.getUUID()))Wire.send(other,"session_end",j);}}else synchronize(p.server,r);}}
    private static void stopRoom(MinecraftServer server,WorldState s,Room r,String reason){s.rooms.remove(r.key);JsonObject j=object();j.addProperty("reason",reason);for(UUID id:r.session.controllers().values()){StateMessages.cancel(server,s,id);ServerPlayer p=server.getPlayerList().getPlayer(id);if(p!=null)Wire.send(p,"session_end",j);}}
    private static void stopDevice(DeviceEntity d,String reason){if(d.getLevel() instanceof net.minecraft.server.level.ServerLevel level){WorldState s=WORLDS.get(level.getServer());if(s!=null){Room r=s.rooms.get(key(d));if(r!=null)stopRoom(level.getServer(),s,r,reason);}}}
    public static void removed(DeviceEntity d){stopDevice(d,"主机已移除");}
    public static void tick(ServerTickEvent.Post event){
        MinecraftServer server=event.getServer();WorldState s=WORLDS.get(server);if(s==null)return;
        if(server.getTickCount()%20==0)try{s.uploads.expire(now());s.states.expire(now());}catch(IOException e){MuxiConsole.LOG.warn("Upload expiration failed",e);}
        for(Room r:new ArrayList<>(s.rooms.values())){
            for(UUID id:new ArrayList<>(r.session.controllers().values())){ServerPlayer p=server.getPlayerList().getPlayer(id);if(p==null||!validParticipant(p,r)){
                if(p!=null&&r.device==null&&r.session.host().equals(id)&&inInventory(p,r.card.instanceId())){suspendHandheld(p,r);if(now()<=r.handheldSaveDeadline)continue;stopRoom(server,s,r,"掌机收起后的保存超时，保留最近一次存档");}
                else if(p!=null)leave(p,s,"已离开主机或收起掌机");else stopRoom(server,s,r,"玩家已离线");
            }}
            if(!s.rooms.containsKey(r.key))continue;
            if(r.pendingJoin!=null||r.pendingLeave!=null){if(now()-r.handoverStarted>60000){UUID waiting=r.pendingJoin!=null?r.pendingJoin:r.pendingLeave;ServerPlayer waitingPlayer=server.getPlayerList().getPlayer(waiting);if(waitingPlayer!=null)error(waitingPlayer,"save","存档同步超时，原会话已继续");r.pendingJoin=null;r.pendingLeave=null;if(!r.requestedLeaves.isEmpty()){r.pendingLeave=r.requestedLeaves.iterator().next();r.handoverStarted=now();requestCheckpoint(server,r);continue;}}else continue;}
            if(r.session.epoch()!=r.observedEpoch)synchronize(server,r);
            if(!r.ready.keySet().containsAll(r.session.controllers().values())){if(++r.waiting>1200)stopRoom(server,s,r,"模拟器准备超时");continue;}
            InputTimeline.Frame frame=r.inputs.advance();Map<Integer,short[]> axes=r.analog.remove(frame.tick());r.analog.keySet().removeIf(t->t<frame.tick());
            r.carry+=r.fps/20.0;int count=(int)r.carry;r.carry-=count;
            JsonObject j=object();j.addProperty("room",r.key);j.addProperty("epoch",frame.epoch());j.addProperty("tick",frame.tick());j.addProperty("frame",r.frame);j.addProperty("count",count);
            JsonArray ports=new JsonArray();for(int i=0;i<r.session.ports();i++){
                // Frontends report levels, so hold them across network RTT; absence for 6 ticks releases everything.
                short[] a=axes==null?null:axes.get(i);if(a!=null)r.heldInputs.put(i,new int[]{frame.mask(i),a[0],a[1],a[2],a[3],(int)frame.tick()});int[] held=r.heldInputs.get(i);JsonArray row=new JsonArray();boolean fresh=held!=null&&frame.tick()-held[5]<=6;for(int k=0;k<5;k++)row.add(fresh?held[k]:0);ports.add(row);
            }j.add("ports",ports);r.frame+=count;
            for(UUID id:r.session.controllers().values()){ServerPlayer p=server.getPlayerList().getPlayer(id);if(p!=null)Wire.send(p,"frames",j);}
        }
    }
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer p){WorldState s=WORLDS.get(p.server);if(s==null)return;leave(p,s,"玩家已离线");s.writers.remove(p.getUUID());UploadLease u=s.burns.remove(p.getUUID());if(u!=null)try{s.uploads.abort(p.getUUID(),u.upload);}catch(IOException e){MuxiConsole.LOG.warn("Upload cancellation failed",e);}}}
    public static void stopped(ServerStoppedEvent event){WorldState s=WORLDS.remove(event.getServer());StateMessages.stopped(event.getServer());if(s!=null){try{s.uploads.close();}catch(IOException e){MuxiConsole.LOG.warn("Upload close failed",e);}try{s.states.close();}catch(IOException e){MuxiConsole.LOG.warn("State vault close failed",e);}}}
    private ConsoleServer(){}
}
