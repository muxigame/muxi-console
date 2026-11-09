package net.muxigame.console.compat;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.*;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.mc.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Server-owned physical displays relay bounded video/audio; ROM/control permissions stay in ConsoleServer. */
@EventBusSubscriber(modid=MuxiConsole.ID)
public final class BrowserLinkServer {
    private record Key(String dimension,long position,String side){}
    private static final class Binding {
        final Key key;final String room,originalUrl;final Object selectedData;final UUID selectedBy;long epoch;final Set<UUID> viewers=new HashSet<>();
        Binding(Key key,String room,String originalUrl,Object selectedData,UUID selectedBy,long epoch){this.key=key;this.room=room;this.originalUrl=originalUrl;this.selectedData=selectedData;this.selectedBy=selectedBy;this.epoch=epoch;}
    }
    private static final class Stream {long lastFrame=-1,lastNanos=System.nanoTime();double tokens=2;SharedPlayServer.Gate audio;}
    private static final class World {
        final Map<Key,Binding> bindings=new LinkedHashMap<>();final Map<String,Stream> streams=new HashMap<>();final Map<UUID,String> publishers=new HashMap<>();final Map<UUID,Long> refreshes=new HashMap<>();final Set<UUID> pendingRefresh=new HashSet<>();
    }
    private static final Map<MinecraftServer,World> WORLDS=new IdentityHashMap<>();
    private static World world(MinecraftServer server){return WORLDS.computeIfAbsent(server,s->new World());}
    private static String dimension(ServerPlayer p){return p.serverLevel().dimension().location().toString();}
    private static JsonObject target(Binding b){JsonObject j=new JsonObject();j.addProperty("dimension",b.key.dimension);j.addProperty("position",b.key.position);j.addProperty("side",b.key.side);j.addProperty("room",b.room);j.addProperty("epoch",b.epoch);return j;}
    private static ConsoleServer.Room room(ServerPlayer p,String key,long epoch)throws IOException{
        ConsoleServer.Room room=ConsoleServer.state(p.server).rooms.get(key);if(room==null||room.session.epoch()!=epoch||!room.session.isController(p.getUUID())||!ConsoleServer.validParticipant(p,room))throw new IOException("控制会话已失效");return room;
    }
    private static Optional<ConsoleServer.Room> current(ServerPlayer p)throws IOException{
        return ConsoleServer.state(p.server).rooms.values().stream().filter(r->r.session.isController(p.getUUID())&&ConsoleServer.validParticipant(p,r)).findFirst();
    }
    /** Same selection action as the real block-use listener; all checks execute again on the server. */
    public static void select(ServerPlayer p,BlockPos clicked,Direction face)throws IOException{
        if(!p.isShiftKeyDown()||!p.getMainHandItem().is(ConsoleContent.CONTROLLER.get())&&!p.getOffhandItem().is(ConsoleContent.CONTROLLER.get()))throw new IOException("手持控制器并潜行选择显示屏");
        if(p.distanceToSqr(clicked.getCenter())>64||!p.serverLevel().hasChunkAt(clicked))throw new IOException("显示屏超出选择范围");
        if(!BuiltInRegistries.BLOCK.getKey(p.serverLevel().getBlockState(clicked).getBlock()).toString().equals("webdisplays:screen"))throw new IOException("请选择 WebDisplays 显示屏");
        ConsoleServer.Room room=current(p).orElseThrow(()->new IOException("请先接入主机或掌机控制席"));
        WebDisplaysBridge.Target screen=WebDisplaysBridge.resolve(p.serverLevel(),clicked,face).orElseThrow(()->new IOException("请先按 WebDisplays 的正常流程建立显示面"));
        if(!WebDisplaysBridge.mayChange(screen,p.getUUID()))throw new IOException("没有此显示面的更改网址权限");
        Key key=new Key(dimension(p),screen.origin().asLong(),screen.side());World world=world(p.server);Binding existing=world.bindings.get(key);
        if(existing!=null&&existing.room.equals(room.key)){remove(p.server,world,existing);p.displayClientMessage(Component.literal("外接屏幕已断开，恢复原网页"),true);return;}
        if(world.bindings.size()>=32||world.bindings.values().stream().filter(b->b.room.equals(room.key)).count()>=8)throw new IOException("当前外接屏幕数量已达上限");
        String original;try{original=WebDisplaysBridge.url(screen);}catch(ReflectiveOperationException bad){throw new IOException("显示屏接口不可用",bad);}
        if(existing!=null)remove(p.server,world,existing);Binding binding=new Binding(key,room.key,original,screen.data(),p.getUUID(),room.session.epoch());world.bindings.put(key,binding);
        refresh(p.server,world,binding);publishers(p.server,world);p.displayClientMessage(Component.literal("已连接实时游戏音画；再次潜行使用可断开"),true);
    }
    @SubscribeEvent public static void clicked(PlayerInteractEvent.RightClickBlock event){
        if(!(event.getEntity() instanceof ServerPlayer p)||!p.isShiftKeyDown()||!p.getMainHandItem().is(ConsoleContent.CONTROLLER.get())&&!p.getOffhandItem().is(ConsoleContent.CONTROLLER.get()))return;
        if(!BuiltInRegistries.BLOCK.getKey(p.serverLevel().getBlockState(event.getPos()).getBlock()).toString().equals("webdisplays:screen"))return;
        event.setCanceled(true);event.setCancellationResult(InteractionResult.SUCCESS);try{select(p,event.getPos(),event.getFace());}catch(IOException bad){p.displayClientMessage(Component.literal(bad.getMessage()),true);}
    }
    /** Called before the ordinary command switch. These operations never grant access to ROM or saves. */
    public static boolean receive(ServerPlayer p,Wire.Message message)throws IOException{
        if(!message.operation().startsWith("browser_"))return false;JsonObject j=message.body();
        if(message.operation().equals("browser_select")){select(p,BlockPos.of(j.get("position").getAsLong()),Direction.valueOf(j.get("side").getAsString()));return true;}
        if(message.operation().equals("browser_refresh")){World w=world(p.server);w.pendingRefresh.add(p.getUUID());refreshPlayer(p,w);return true;}
        if(!Set.of("browser_frame","browser_audio").contains(message.operation()))throw new IOException("未知外接屏幕操作");
        String key=j.get("room").getAsString();long epoch=j.get("epoch").getAsLong(),number=j.get("frame").getAsLong();ConsoleServer.Room current=ConsoleServer.state(p.server).rooms.get(key);if(current==null||current.session.epoch()!=epoch)return true;ConsoleServer.Room room=room(p,key,epoch);
        if(!room.session.host().equals(p.getUUID())||!room.ready.containsKey(p.getUUID())||number<0||number>room.frame)throw new IOException("只有当前主控制席可以提供游戏画面");
        World world=world(p.server);List<Binding> bindings=world.bindings.values().stream().filter(b->b.room.equals(key)&&b.epoch==epoch&&valid(p.server,b)).toList();if(bindings.isEmpty())return true;
        Stream stream=world.streams.computeIfAbsent(key+"/"+epoch,k->new Stream());long now=System.nanoTime();
        if(message.operation().equals("browser_audio")){
            if(j.get("rate").getAsInt()!=48000)throw new IOException("外接屏幕音频需要48kHz双声道PCM");int part=j.get("part").getAsInt(),parts=j.get("parts").getAsInt(),total=j.get("totalBytes").getAsInt();if(stream.audio==null)stream.audio=new SharedPlayServer.Gate(epoch,now);if(!stream.audio.audio(number,part,parts,total,message.data().length,now))return true;
            JsonObject outgoing=new JsonObject();outgoing.addProperty("room",key);outgoing.addProperty("epoch",epoch);outgoing.addProperty("frame",number);outgoing.addProperty("rate",48000);outgoing.addProperty("part",part);outgoing.addProperty("parts",parts);outgoing.addProperty("totalBytes",total);
            Set<UUID> sent=new HashSet<>();for(Binding binding:bindings)for(UUID id:binding.viewers){ServerPlayer viewer=p.server.getPlayerList().getPlayer(id);if(viewer!=null&&!room.session.isController(id)&&near(viewer,binding)&&viewer.distanceToSqr(BlockPos.of(binding.key.position).getCenter())<24*24&&sent.add(id))Wire.send(viewer,"browser_audio",outgoing,message.data());}return true;
        }
        stream.tokens=Math.min(2,stream.tokens+(now-stream.lastNanos)/1e9*20);stream.lastNanos=now;
        if(number<=stream.lastFrame||stream.tokens<1)return true;stream.tokens-=1;
        BrowserFrameCodec.Image image=BrowserFrameCodec.decode(message.data());var planes=SurfaceMetadata.read(j,image.width(),image.height());stream.lastFrame=number;
        JsonObject outgoing=new JsonObject();outgoing.addProperty("room",key);outgoing.addProperty("epoch",epoch);outgoing.addProperty("frame",number);outgoing.addProperty("width",image.width());outgoing.addProperty("height",image.height());outgoing.addProperty("console",room.device==null?Long.MIN_VALUE:room.device.getBlockPos().asLong());outgoing.addProperty("dimension",dimension(p));
        SurfaceMetadata.write(outgoing,planes);Set<UUID> sent=new HashSet<>();for(Binding binding:bindings)for(UUID id:binding.viewers){ServerPlayer viewer=p.server.getPlayerList().getPlayer(id);if(viewer!=null&&near(viewer,binding)&&sent.add(id))Wire.send(viewer,"browser_frame",outgoing,message.data());}return true;
    }
    private static boolean near(ServerPlayer p,Binding b){return dimension(p).equals(b.key.dimension)&&p.distanceToSqr(BlockPos.of(b.key.position).getCenter())<=32*32;}
    private static boolean valid(MinecraftServer server,Binding b){
        ServerPlayer actor=server.getPlayerList().getPlayer(b.selectedBy);if(actor==null||!dimension(actor).equals(b.key.dimension))return false;
        if(b.key.side.equals("builtin"))try{ConsoleServer.Room room=ConsoleServer.state(server).rooms.get(b.room);BlockPos position=BlockPos.of(b.key.position);return room!=null&&room.device!=null&&actor.serverLevel().hasChunkAt(position)&&actor.serverLevel().getBlockEntity(position)==b.selectedData&&b.selectedData instanceof DeviceEntity screen&&screen.kind()==DeviceBlock.Kind.SCREEN&&Objects.equals(screen.linkedConsole,room.device.getBlockPos());}catch(IOException unavailable){return false;}
        try{ConsoleServer.Room r=ConsoleServer.state(server).rooms.get(b.room);return r!=null&&r.session.isController(actor.getUUID())&&ConsoleServer.validParticipant(actor,r)&&WebDisplaysBridge.at(actor.serverLevel(),BlockPos.of(b.key.position),b.key.side).map(t->{try{return t.data()==b.selectedData&&WebDisplaysBridge.mayChange(t,actor.getUUID())&&Objects.equals(WebDisplaysBridge.url(t),b.originalUrl);}catch(ReflectiveOperationException bad){return false;}}).orElse(false);}
        catch(IOException bad){return false;}
    }
    private static void refresh(MinecraftServer server,World world,Binding b)throws IOException{
        ConsoleServer.Room r=ConsoleServer.state(server).rooms.get(b.room);if(r==null)return;boolean newEpoch=b.epoch!=r.session.epoch();b.epoch=r.session.epoch();
        for(ServerPlayer p:server.getPlayerList().getPlayers()){boolean nearby=near(p,b)&&(!b.key.side.equals("builtin")||!r.session.isController(p.getUUID()));if(nearby&&(b.viewers.add(p.getUUID())||newEpoch))Wire.send(p,"browser_bind",target(b));else if(!nearby&&b.viewers.remove(p.getUUID()))Wire.send(p,"browser_unbind",target(b));}
        b.viewers.removeIf(id->server.getPlayerList().getPlayer(id)==null);
    }
    private static void remove(MinecraftServer server,World world,Binding b){world.bindings.remove(b.key);for(UUID id:b.viewers){ServerPlayer p=server.getPlayerList().getPlayer(id);if(p!=null)Wire.send(p,"browser_unbind",target(b));}}
    private static void publishers(MinecraftServer server,World world)throws IOException{
        Map<UUID,String> active=new HashMap<>();for(Binding b:world.bindings.values()){ConsoleServer.Room r=ConsoleServer.state(server).rooms.get(b.room);if(r!=null&&!b.viewers.isEmpty())active.put(r.session.host(),b.room+"/"+r.session.epoch());}
        for(UUID id:new HashSet<>(world.publishers.keySet()))if(!Objects.equals(active.get(id),world.publishers.get(id))){ServerPlayer p=server.getPlayerList().getPlayer(id);if(p!=null){JsonObject j=new JsonObject();j.addProperty("enabled",false);Wire.send(p,"browser_publish",j);}}
        for(var entry:active.entrySet())if(!Objects.equals(entry.getValue(),world.publishers.get(entry.getKey()))){ServerPlayer p=server.getPlayerList().getPlayer(entry.getKey());if(p!=null){ConsoleServer.Room r=current(p).orElse(null);if(r!=null){JsonObject j=new JsonObject();j.addProperty("enabled",true);j.addProperty("room",r.key);j.addProperty("epoch",r.session.epoch());Wire.send(p,"browser_publish",j);}}}
        world.publishers.clear();world.publishers.putAll(active);Set<String> live=new HashSet<>(active.values());world.streams.keySet().removeIf(s->!live.contains(s));
    }
    private static void refreshPlayer(ServerPlayer p,World world)throws IOException{
        long now=System.nanoTime();Long previous=world.refreshes.get(p.getUUID());if(previous!=null&&now-previous<1_000_000_000L)return;world.refreshes.put(p.getUUID(),now);world.pendingRefresh.remove(p.getUUID());
        for(Binding b:world.bindings.values())if(valid(p.server,b)&&near(p,b)&&(!b.key.side.equals("builtin")||!ConsoleServer.state(p.server).rooms.get(b.room).session.isController(p.getUUID()))){b.viewers.add(p.getUUID());Wire.send(p,"browser_bind",target(b));}
        ConsoleServer.Room r=current(p).orElse(null);if(r!=null&&r.session.host().equals(p.getUUID())&&world.bindings.values().stream().anyMatch(b->b.room.equals(r.key))){JsonObject enable=new JsonObject();enable.addProperty("enabled",true);enable.addProperty("room",r.key);enable.addProperty("epoch",r.session.epoch());Wire.send(p,"browser_publish",enable);}
    }
    private static void discoverScreens(MinecraftServer server,World world)throws IOException{
        for(ConsoleServer.Room room:ConsoleServer.roomsSnapshot(server))if(room.device!=null){DeviceEntity source=room.device;var level=source.getLevel();if(level==null)continue;String dimension=level.dimension().location().toString();int found=0;
            for(BlockPos position:BlockPos.betweenClosed(source.getBlockPos().offset(-8,-3,-8),source.getBlockPos().offset(8,3,8))){if(!level.hasChunkAt(position)||!(level.getBlockEntity(position) instanceof DeviceEntity screen)||screen.kind()!=DeviceBlock.Kind.SCREEN||screen.largeScreen()&&!screen.screenPrimary()||!Objects.equals(screen.linkedConsole,source.getBlockPos()))continue;
                Key key=new Key(dimension,position.asLong(),"builtin");if(!world.bindings.containsKey(key)&&world.bindings.size()<64){Binding binding=new Binding(key,room.key,"",screen,room.session.host(),room.session.epoch());world.bindings.put(key,binding);refresh(server,world,binding);}if(++found>=8)break;
            }
        }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){MinecraftServer server=event.getServer();World world=WORLDS.get(server);if(world==null){if(ConsoleServer.roomsSnapshot(server).isEmpty())return;world=world(server);}
        try{if(server.getTickCount()%20==0)discoverScreens(server,world);for(Binding b:new ArrayList<>(world.bindings.values())){if(!valid(server,b))remove(server,world,b);else refresh(server,world,b);}publishers(server,world);for(UUID id:new ArrayList<>(world.pendingRefresh)){ServerPlayer p=server.getPlayerList().getPlayer(id);if(p==null)world.pendingRefresh.remove(id);else refreshPlayer(p,world);}world.refreshes.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);}catch(IOException bad){MuxiConsole.LOG.debug("Display binding unavailable",bad);}
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){WORLDS.remove(event.getServer());}
    public static int bindingCount(MinecraftServer server){World world=WORLDS.get(server);return world==null?0:(int)world.bindings.values().stream().filter(b->!b.key.side.equals("builtin")).count();}
    private BrowserLinkServer(){}
}
