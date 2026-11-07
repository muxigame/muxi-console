package net.muxigame.console.client;

import com.google.gson.*;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.domain.*;
import net.muxigame.console.mc.*;
import net.muxigame.console.runtime.*;
import net.muxigame.console.client.ui.*;
import net.muxigame.console.client.input.InputProfiles;
import net.muxigame.console.compat.BrowserLinkClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;

/** Own client state machine: GUI, server transport and isolated worker meet only here. */
public final class ConsoleClient implements ClientActions {
    public static final ConsoleClient INSTANCE=new ConsoleClient();
    private final ExecutorService emulator=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"muxi-console-emulation");t.setDaemon(true);return t;});
    private final ExecutorService files=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"muxi-console-file-io");t.setDaemon(true);return t;});
    private final Map<UUID,CompletableFuture<Wire.Message>> downloads=new ConcurrentHashMap<>();
    private final Set<Long> ownedWorkers=ConcurrentHashMap.newKeySet();
    private final Map<Long,ProcessConsoleRuntime> liveRuntimes=new ConcurrentHashMap<>();
    private volatile long normalWorkerExits,forcedWorkerExits,failedWorkerExits;
    private volatile String restoredStateHash="",savedStateHash="";private volatile long restoredStates,savedStates;
    private volatile Session active;private volatile String status="就绪",lastError="";private volatile long displayedFrames,downloadsGranted,downloadsDenied;private volatile String frameCrc="";
    private DynamicTexture texture;private ResourceLocation picture;private int pictureWidth,pictureHeight;
    private Upload upload;private boolean preparingUpload;private RuntimeFiles runtimeFiles;
    private java.util.function.Supplier<PortInput> inputSource;
    private static final class Session {
        final JsonObject context;final String room;final long epoch,position;final int port;final Platform platform;final String hash;final long size;final UUID card;final boolean shared,leader;volatile boolean hasPicture;
        volatile ProcessConsoleRuntime runtime;final PcmPlayer audio=new PcmPlayer();final java.util.concurrent.atomic.AtomicInteger queued=new java.util.concurrent.atomic.AtomicInteger();volatile boolean ready,inputPending,closeRecorded,leaving;volatile long tick=-1,sequence=0,frame=0,lastInputTick=-1,lastCheckpointFrame=0;
        CompletableFuture<Void> checkpoint;SaveUpload saveUpload;
        Session(JsonObject j){context=j.deepCopy();room=j.get("room").getAsString();epoch=j.get("epoch").getAsLong();position=j.get("position").getAsLong();port=j.get("port").getAsInt();platform=Platform.fromId(j.get("platform").getAsString());hash=j.get("hash").getAsString();size=j.get("size").getAsLong();card=UUID.fromString(j.get("instance").getAsString());shared=platform.sharedRuntime();leader=j.get("leader").getAsBoolean();}
    }
    private static final class Upload {
        final JsonObject writer;final Path file,temporary;final RomDescriptor descriptor;UUID id;long offset;
        Upload(JsonObject w,Path f,RomDescriptor d,Path temporary){writer=w;file=f;descriptor=d;this.temporary=temporary;}
    }
    private static final class SaveUpload {
        final byte[] bytes;final String hash;final long frame;final CompletableFuture<Void> completed;UUID id;long offset;
        SaveUpload(byte[] data,long frame,CompletableFuture<Void> completed){bytes=data;hash=Hashing.sha256(data);this.frame=frame;this.completed=completed;}
    }
    public record Snapshot(String status,String room,long epoch,int port,boolean ready,long frames,String frameCrc,int width,int height,Set<Long> workers,long serverTick,long sequence,boolean workerRunning,String lastError,long downloadsGranted,long downloadsDenied,long normalWorkerExits,long forcedWorkerExits,long failedWorkerExits,String restoredStateHash,String savedStateHash,long restoredStates,long savedStates){}
    public Snapshot snapshot(){Session s=active;return new Snapshot(status,s==null?"":s.room,s==null?0:s.epoch,s==null?-1:s.port,s!=null&&s.ready,displayedFrames,frameCrc,pictureWidth,pictureHeight,Set.copyOf(ownedWorkers),s==null?-1:s.tick,s==null?0:s.sequence,s!=null&&s.runtime!=null&&s.runtime.isAlive(),lastError,downloadsGranted,downloadsDenied,normalWorkerExits,forcedWorkerExits,failedWorkerExits,restoredStateHash,savedStateHash,restoredStates,savedStates);}
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static JsonObject object(){return new JsonObject();}
    private RuntimeFiles files()throws IOException{if(runtimeFiles==null)runtimeFiles=new RuntimeFiles(mc().gameDirectory.toPath());return runtimeFiles;}
    public String status(){return status;}
    public void selectionChanged(){if(upload==null)status="选择文件并确认授权后，即可上传刻录";}
    public void receive(Wire.Message message){
        if(SharedPlayClient.INSTANCE.receive(message))return;
        if(BrowserLinkClient.INSTANCE.receive(message))return;
        JsonObject b=message.body();
        switch(message.operation()){
            case "writer_open" -> {leave();status="请选择游戏文件";mc().setScreen(new WriterScreen(b,this));}
            case "device_open" -> mc().setScreen(new DeviceScreen(b,this));
            case "handheld_config" -> mc().setScreen(new HandheldModelScreen(b));
            case "device_state" -> {if(mc().screen instanceof DeviceScreen)mc().setScreen(new DeviceScreen(b,this));}
            case "error" -> {status=b.get("message").getAsString();lastError=status;if(b.has("operation")){String operation=b.get("operation").getAsString();if(operation.startsWith("upload_")){cleanupUpload(upload);upload=null;}Session s=active;if(operation.startsWith("state_")&&s!=null){CompletableFuture<Void> save=s.checkpoint;s.saveUpload=null;s.checkpoint=null;s.leaving=false;if(save!=null)save.completeExceptionally(new IOException(status));}}if(mc().player!=null)mc().player.displayClientMessage(Component.literal(status),true);}
            case "upload_ack" -> {Upload u=upload;if(u!=null){u.id=UUID.fromString(b.get("upload").getAsString());u.offset=b.get("offset").getAsLong();sendUploadPiece(u);}}
            case "burned" -> {status="卡带已刻录："+b.get("title").getAsString();cleanupUpload(upload);upload=null;}
            case "rom_chunk","rom_denied","state_chunk","state_denied" -> {if(message.operation().equals("rom_denied"))downloadsDenied++;else if(message.operation().equals("rom_chunk"))downloadsGranted++;UUID id=UUID.fromString(b.get("request").getAsString());CompletableFuture<Wire.Message> f=downloads.remove(id);if(f!=null)f.complete(message);}
            case "checkpoint_request" -> {Session s=active;if(s!=null&&s.room.equals(b.get("room").getAsString())&&s.epoch==b.get("epoch").getAsLong()){status="正在保存并同步会话…";CompletableFuture<Void> previous=s.checkpoint;if(previous==null)checkpoint(s);else previous.whenComplete((v,e)->mc().execute(()->{if(active==s)checkpoint(s);}));}}
            case "state_ack" -> {Session s=active;if(s!=null&&s.saveUpload!=null){SaveUpload save=s.saveUpload;UUID id=UUID.fromString(b.get("upload").getAsString());if(save.id==null)save.id=id;if(!save.id.equals(id))return;save.offset=b.get("offset").getAsLong();sendStatePiece(s,save);}}
            case "state_committed" -> {Session s=active;if(s!=null&&s.saveUpload!=null){SaveUpload save=s.saveUpload;if(save.id.toString().equals(b.get("upload").getAsString())&&save.hash.equals(b.get("hash").getAsString())){savedStateHash=save.hash;savedStates++;s.saveUpload=null;s.checkpoint=null;save.completed.complete(null);}}}
            case "session_start" -> start(b);
            case "input_ack" -> {Session s=active;if(s!=null&&s.room.equals(b.get("room").getAsString())&&s.epoch==b.get("epoch").getAsLong()){s.sequence=b.get("nextSequence").getAsLong();s.inputPending=false;if(!b.get("accepted").getAsBoolean())s.lastInputTick=b.get("nextTick").getAsLong()-1;}}
            case "session_end" -> {status=b.get("reason").getAsString();stop();if(mc().screen instanceof GameScreen)mc().setScreen(null);}
            case "frames" -> frames(b);
            default -> { }
        }
    }
    public void upload(JsonObject context,Path file,Platform selected,boolean rights){
        upload(context,file,List.of(),selected,rights);
    }
    public void upload(JsonObject context,Path file,List<Path> dependencies,Platform selected,boolean rights){
        if(!rights){status="请先确认拥有上传和使用所需授权";return;}if(upload!=null||preparingUpload){status="已有上传进行中";return;}preparingUpload=true;
        status="正在检查文件…";List<Path> chosen=List.copyOf(dependencies);
        files.execute(()->{Path temporary=null;try{Path content=file;Platform platform=selected;boolean zip=file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
            if(zip&&(selected==null||selected==Platform.ARCADE)){Path preparation=files().root().resolve("upload-preparation");Files.createDirectories(preparation);if(Files.isSymbolicLink(preparation)||!preparation.toRealPath().equals(preparation.toAbsolutePath().normalize()))throw new IOException("媒体准备目录不安全");temporary=Files.createTempDirectory(preparation,"mxarc-");String name=file.getFileName().toString().toLowerCase(Locale.ROOT);content=temporary.resolve(name.substring(0,name.length()-4)+".mxarc");ArcadeBundle.pack(file,chosen,content);platform=Platform.ARCADE;}
            else if(!chosen.isEmpty())throw new IOException("父集和BIOS依赖只能添加到街机driver.zip");
            RomDescriptor d=new RomInspector().inspect(content,platform);Upload u=new Upload(context.deepCopy(),content,d,temporary);mc().execute(()->{upload=u;preparingUpload=false;JsonObject j=context.deepCopy();j.addProperty("filename",d.filename());j.addProperty("title",d.filename().replaceFirst("\\.[^.]+$",""));j.addProperty("platform",d.platform().id());j.addProperty("hash",d.sha256());j.addProperty("size",d.size());j.addProperty("rights",true);status="上传中 0%";Wire.command("upload_begin",j);});
        }catch(Exception e){deletePrepared(temporary);mc().execute(()->{preparingUpload=false;status=UserErrors.describe(e);});}});
    }
    private void cleanupUpload(Upload upload){if(upload!=null&&upload.temporary!=null)files.execute(()->deletePrepared(upload.temporary));}
    private void deletePrepared(Path directory){if(directory==null)return;try{Path allowed=files().root().resolve("upload-preparation").toAbsolutePath().normalize(),resolved=directory.toAbsolutePath().normalize();if(!resolved.startsWith(allowed)||!resolved.getFileName().toString().startsWith("mxarc-")||Files.isSymbolicLink(resolved))throw new IOException("Invalid media preparation cleanup");try(var entries=Files.list(resolved)){for(Path entry:entries.toList()){if(!Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(entry))throw new IOException("Unexpected media preparation entry");Files.delete(entry);}}Files.delete(resolved);}catch(IOException bad){MuxiConsole.LOG.debug("Prepared upload cleanup failed",bad);}}
    private void sendUploadPiece(Upload u){
        if(u.offset==u.descriptor.size()){JsonObject j=u.writer.deepCopy();j.addProperty("upload",u.id.toString());Wire.command("upload_finish",j);return;}
        files.execute(()->{try(var in=Files.newByteChannel(u.file,StandardOpenOption.READ)){
            long offset=u.offset;if(Files.size(u.file)!=u.descriptor.size())throw new IOException("选择的文件已改变");in.position(offset);java.nio.ByteBuffer buffer=java.nio.ByteBuffer.allocate((int)Math.min(24576,u.descriptor.size()-offset));while(buffer.hasRemaining()&&in.read(buffer)>=0){}byte[] bytes=buffer.array();
            JsonObject j=u.writer.deepCopy();j.addProperty("upload",u.id.toString());j.addProperty("offset",offset);j.addProperty("chunkHash",Hashing.sha256(bytes));mc().execute(()->{if(upload==u){status="上传中 "+(offset*100/u.descriptor.size())+"%";Wire.command("upload_chunk",j,bytes);}});
        }catch(Exception e){status=e.getMessage();mc().execute(()->{Wire.command("upload_abort",object());cleanupUpload(upload);upload=null;});}});
    }
    public CompletableFuture<Optional<byte[]>> probeDownload(String hash,String room,long epoch){
        UUID request=UUID.randomUUID();CompletableFuture<Wire.Message> result=new CompletableFuture<>();downloads.put(request,result);JsonObject j=object();j.addProperty("request",request.toString());j.addProperty("hash",hash);j.addProperty("offset",0);if(room!=null&&!room.isEmpty()){j.addProperty("room",room);j.addProperty("epoch",epoch);}mc().execute(()->Wire.command("rom_read",j));
        return result.orTimeout(15,TimeUnit.SECONDS).thenApply(m->m.operation().equals("rom_denied")?Optional.<byte[]>empty():Optional.of(m.data())).whenComplete((v,e)->downloads.remove(request));
    }
    private Path rom(Session s)throws Exception{
        Path cache=files().root().resolve("roms");Files.createDirectories(cache);if(Files.isSymbolicLink(cache)||!cache.toRealPath().equals(cache.toAbsolutePath().normalize()))throw new IOException("卡带缓存路径不安全");Path target=cache.resolve(s.hash+"."+s.platform.extensions().iterator().next());
        if(Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(target)&&Files.size(target)==s.size&&Hashing.sha256(target).equals(s.hash))return target;
        Path temp=Files.createTempFile(cache,"receive-",".tmp");
        try(var out=Files.newOutputStream(temp)){
            long offset=0;while(offset<s.size){if(active!=s)throw new IOException("会话已取消");UUID id=UUID.randomUUID();var result=new CompletableFuture<Wire.Message>();downloads.put(id,result);JsonObject j=object();j.addProperty("request",id.toString());j.addProperty("room",s.room);j.addProperty("epoch",s.epoch);j.addProperty("hash",s.hash);j.addProperty("offset",offset);mc().execute(()->Wire.command("rom_read",j));Wire.Message reply;
                try{reply=result.get(15,TimeUnit.SECONDS);}finally{downloads.remove(id);}if(reply.operation().equals("rom_denied"))throw new IOException("服务器拒绝提供此卡带内容");
                if(reply.body().get("offset").getAsLong()!=offset||reply.body().get("size").getAsLong()!=s.size||reply.data().length==0||reply.data().length>s.size-offset)throw new IOException("卡带下载格式无效");out.write(reply.data());offset+=reply.data().length;
            }
        }catch(Exception e){Files.deleteIfExists(temp);throw e;}
        if(!Hashing.sha256(temp).equals(s.hash)){Files.deleteIfExists(temp);throw new IOException("卡带下载校验失败");}Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);return target;
    }
    private void start(JsonObject context){
        Session existing=active;if(existing!=null&&existing.room.equals(context.get("room").getAsString())&&existing.epoch==context.get("epoch").getAsLong()){mc().setScreen(new GameScreen(this,existing.port));return;}
        stop();Session s=new Session(context);active=s;status="正在准备 "+s.platform.id().toUpperCase(Locale.ROOT)+"…";mc().setScreen(new GameScreen(this,s.port));
        restoredStateHash="";if(s.shared&&!s.leader){status="正在连接主机画面与控制席…";SharedPlayClient.INSTANCE.start(context);return;}
        emulator.execute(()->{try{
            if(s.context.has("saveError"))throw new IOException(s.context.get("saveError").getAsString());
            Path rom=rom(s);RuntimeFiles.Selected config=files().select(s.platform,rom,s.card);ProcessConsoleRuntime runtime=ProcessConsoleRuntime.open(config.launch());s.runtime=runtime;ownedWorkers.add(runtime.workerPid());liveRuntimes.put(runtime.workerPid(),runtime);
            restoredStateHash="";if(s.context.has("state")){JsonObject saved=s.context.getAsJsonObject("state");if(!saved.get("fingerprint").getAsString().equals(config.fingerprint()))throw new IOException("存档使用了不同版本的模拟器核心，请恢复原核心后再读取");runtime.loadState(downloadState(s,saved));restoredStateHash=saved.get("hash").getAsString();restoredStates++;}
            if(active!=s){close(s);return;}s.ready=true;JsonObject j=s.context.deepCopy();j.addProperty("fingerprint",config.fingerprint());j.addProperty("fps",runtime.info().fps());mc().execute(()->{if(active==s){status="等待其他控制器准备…";Wire.command("ready",j);}});
        }catch(Exception e){failed(s,e);}});
    }
    private void frames(JsonObject b){
        Session s=active;if(s==null||!s.ready||!s.room.equals(b.get("room").getAsString())||s.epoch!=b.get("epoch").getAsLong())return;
        s.tick=b.get("tick").getAsLong();if(s.shared&&!s.leader)return;if(s.queued.incrementAndGet()>120){failed(s,new IOException("模拟器跟不上服务器，会话已停止"));return;}
        JsonObject packet=b.deepCopy();emulator.execute(()->{try{
            if(active!=s)return;long first=packet.get("frame").getAsLong();if(first!=s.frame)throw new IOException("模拟帧序号不连续");int count=packet.get("count").getAsInt();if(count<0||count>6)throw new IOException("模拟帧批次无效");
            List<PortInput> ports=new ArrayList<>();for(JsonElement row:packet.getAsJsonArray("ports")){JsonArray a=row.getAsJsonArray();ports.add(new PortInput(a.get(0).getAsInt(),a.get(1).getAsShort(),a.get(2).getAsShort(),a.get(3).getAsShort(),a.get(4).getAsShort()));}
            InputFrame input=new InputFrame(ports);AvFrame result=null;java.util.List<short[]> sounds=new ArrayList<>();double soundRate=0;int soundSamples=0;
            for(int i=0;i<count;i++){result=s.runtime.step(input);s.audio.offer(result.stereoPcm(),result.sampleRate());if(s.shared&&s.leader){if(soundRate!=0&&soundRate!=result.sampleRate())throw new IOException("模拟器在一批帧内改变了音频格式");soundRate=result.sampleRate();sounds.add(result.stereoPcm());soundSamples+=result.stereoPcm().length;}s.frame++;}
            if(active==s&&s.shared&&s.leader&&soundSamples>0){if(soundSamples>2_000_000)throw new IOException("主机音频批次过大");short[] sound=new short[soundSamples];int offset=0;for(short[] piece:sounds){System.arraycopy(piece,0,sound,offset,piece.length);offset+=piece.length;}SharedPlayClient.INSTANCE.audio(s.frame,sound,soundRate);}
            if(result!=null){AvFrame f=result;CRC32 crc=new CRC32();crc.update(f.rgba());String digest=Long.toHexString(crc.getValue());long number=s.frame;
                if(s.context.has("leader")&&s.context.get("leader").getAsBoolean()&&number-s.lastCheckpointFrame>=300){s.lastCheckpointFrame=number;mc().execute(()->{if(active==s)checkpoint(s);});}
                if(!s.shared&&number%120<count){JsonObject check=s.context.deepCopy();check.addProperty("frame",number);check.addProperty("crc",digest);mc().execute(()->{if(active==s)Wire.command("frame_crc",check);});}
                mc().execute(()->{if(active==s){displayedFrames=number;frameCrc=digest;updatePicture(f);status="玩家 "+(s.port+1)+" · "+s.platform.id().toUpperCase(Locale.ROOT);}});
            }
        }catch(Exception e){failed(s,e);}finally{s.queued.decrementAndGet();}});
    }
    public void tick(){
        Session s=active;if(s==null)return;if(mc().player==null||mc().getConnection()==null){stop();return;}
        if(!s.ready)return;
        PortInput input=inputSource!=null?inputSource.get():mc().screen instanceof GameScreen?InputProfiles.get().sample(s.platform,mc().getWindow().getWindow()):PortInput.IDLE;
        submitInput(input);
    }
    /** Normal frontend input entry point; all controller ownership and sequence checks remain on the server. */
    public void submitInput(PortInput input){Session s=active;if(s==null||!s.ready||s.inputPending)return;long target=Math.max(s.lastInputTick+1,Math.max(0,s.tick+2));if(target>s.tick+12)return;JsonObject j=s.context.deepCopy();j.addProperty("sequence",s.sequence);j.addProperty("tick",target);j.addProperty("buttons",input.buttons());JsonArray a=new JsonArray();a.add(input.leftX());a.add(input.leftY());a.add(input.rightX());a.add(input.rightY());j.add("axes",a);s.inputPending=true;s.lastInputTick=target;Wire.command("input",j);}
    /** Optional frontend integration; null returns to this mod's physical keyboard/gamepad sampler. */
    public void setInputSource(java.util.function.Supplier<PortInput> source){inputSource=source;}
    public void device(JsonObject context,String action,int port){JsonObject j=context.deepCopy();j.addProperty("action",action);j.addProperty("port",port);Wire.command("device_action",j);}
    public void leave(){Session s=active;if(s!=null&&mc().getConnection()!=null&&s.ready){if(!s.leaving){s.leaving=true;inputSource=null;status="正在保存并离开…";Wire.command("leave_request",s.context.deepCopy());}return;}if(s!=null&&mc().getConnection()!=null)Wire.command("leave",object());stop();}
    public void stop(){Session s=active;active=null;inputSource=null;displayedFrames=0;frameCrc="";SharedPlayClient.INSTANCE.stop();BrowserLinkClient.INSTANCE.stop();downloads.values().forEach(f->f.cancel(false));downloads.clear();if(s!=null){if(s.checkpoint!=null)s.checkpoint.completeExceptionally(new IOException("会话已停止"));emulator.execute(()->close(s));}}
    private byte[] downloadState(Session s,JsonObject metadata)throws Exception{
        long size=metadata.get("size").getAsLong();if(size<1||size>32L*1024*1024)throw new IOException("存档大小超出当前范围");ByteArrayOutputStream output=new ByteArrayOutputStream((int)size);
        for(long offset=0;offset<size;){if(active!=s)throw new IOException("存档读取已取消");UUID id=UUID.randomUUID();CompletableFuture<Wire.Message> wait=new CompletableFuture<>();downloads.put(id,wait);JsonObject request=s.context.deepCopy();request.addProperty("request",id.toString());request.addProperty("generation",metadata.get("generation").getAsString());request.addProperty("offset",offset);mc().execute(()->Wire.command("state_read",request));Wire.Message response;
            try{response=wait.get(15,TimeUnit.SECONDS);}finally{downloads.remove(id);}if(response.operation().equals("state_denied"))throw new IOException("服务器拒绝或无法提供存档");if(response.body().get("offset").getAsLong()!=offset||response.data().length<1||response.data().length>size-offset)throw new IOException("存档分块格式无效");output.write(response.data());offset+=response.data().length;
        }byte[] state=output.toByteArray();if(!Hashing.sha256(state).equals(metadata.get("hash").getAsString()))throw new IOException("服务器存档校验失败");return state;
    }
    private CompletableFuture<Void> checkpoint(Session s){
        if(active!=s||!s.ready||!s.context.get("leader").getAsBoolean())return CompletableFuture.completedFuture(null);if(s.checkpoint!=null)return s.checkpoint;
        CompletableFuture<Void> complete=new CompletableFuture<>();s.checkpoint=complete;emulator.execute(()->{try{if(active!=s)throw new IOException("会话已改变");byte[] bytes=s.runtime.saveState();if(bytes.length>32*1024*1024)throw new IOException("状态超过当前服务端存档上限");SaveUpload save=new SaveUpload(bytes,s.frame,complete);mc().execute(()->{if(active!=s){complete.completeExceptionally(new IOException("会话已改变"));return;}s.saveUpload=save;JsonObject begin=s.context.deepCopy();begin.addProperty("frame",save.frame);begin.addProperty("size",bytes.length);begin.addProperty("hash",save.hash);Wire.command("state_begin",begin);});}catch(Exception failure){mc().execute(()->{if(active==s){s.checkpoint=null;s.saveUpload=null;status="存档未保存："+failure.getMessage();}complete.completeExceptionally(failure);});}});return complete;
    }
    private void sendStatePiece(Session s,SaveUpload save){
        if(active!=s||save.offset<0||save.offset>save.bytes.length)return;JsonObject request=s.context.deepCopy();request.addProperty("upload",save.id.toString());
        if(save.offset==save.bytes.length){Wire.command("state_commit",request);return;}int start=(int)save.offset;byte[] piece=Arrays.copyOfRange(save.bytes,start,Math.min(start+24576,save.bytes.length));request.addProperty("offset",save.offset);Wire.command("state_piece",request,piece);
    }
    private void close(Session s){if(s.closeRecorded)return;s.audio.close();ProcessConsoleRuntime r=s.runtime;if(r==null)return;boolean closedNormally=false;try{r.close();closedNormally=true;}catch(IOException e){MuxiConsole.LOG.warn("Worker close failed",e);}finally{s.closeRecorded=true;if(r.wasForcedShutdown())forcedWorkerExits++;else if(closedNormally&&r.exitCode().orElse(-1)==0&&r.nativeTemporaryFilesCleaned())normalWorkerExits++;else failedWorkerExits++;MuxiConsole.LOG.info("Worker closure pid={} exit={} forced={} nativeTempCleaned={}",r.workerPid(),r.exitCode().orElse(-1),r.wasForcedShutdown(),r.nativeTemporaryFilesCleaned());if(!r.isAlive()){ownedWorkers.remove(r.workerPid());liveRuntimes.remove(r.workerPid());}}}
    private void failed(Session s,Exception failure){
        mc().execute(()->{if(active!=s)return;status=failure.getMessage();JsonObject j=s.context.deepCopy();j.addProperty("reason",status==null?failure.getClass().getSimpleName():status);Wire.command("runtime_failed",j);stop();});
    }
    private void updatePicture(AvFrame frame){
        VideoOrientation.Image oriented=VideoOrientation.apply(frame.width(),frame.height(),frame.rgba(),frame.rotation());if(oriented.width()<1||oriented.height()<1)return;
        double aspect=frame.displayAspect();if((frame.rotation()&1)!=0)aspect=1/aspect;oriented=VideoLayout.display(oriented,aspect);int w=oriented.width(),h=oriented.height();
        BrowserLinkClient.INSTANCE.frame(displayedFrames,w,h,oriented.rgba());
        SharedPlayClient.INSTANCE.frame(displayedFrames,w,h,oriented.rgba());
        if(texture==null||pictureWidth!=w||pictureHeight!=h){if(texture!=null)mc().getTextureManager().release(picture);NativeImage image=new NativeImage(w,h,false);texture=new DynamicTexture(image);picture=mc().getTextureManager().register("muxi-console-game",texture);pictureWidth=w;pictureHeight=h;}
        NativeImage image=texture.getPixels();byte[] rgba=oriented.rgba();for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=(y*w+x)*4;int pixel=(rgba[i+3]&255)<<24|(rgba[i+2]&255)<<16|(rgba[i+1]&255)<<8|(rgba[i]&255);image.setPixelRGBA(x,y,pixel);}texture.upload();Session current=active;if(current!=null)current.hasPicture=true;
    }
    public SharedPlayClient.Lease sharedLease(){Session s=active;return s!=null&&s.shared?new SharedPlayClient.Lease(s.room,s.epoch,s.port,s.leader):null;}
    private Session sharedGuest(String room,long epoch){Session s=active;return s!=null&&s.shared&&!s.leader&&s.room.equals(room)&&s.epoch==epoch?s:null;}
    public void sharedReady(String room,long epoch){Session s=sharedGuest(room,epoch);if(s!=null){s.ready=true;status="控制席已连接，等待主机画面…";}}
    public void sharedPicture(String room,long epoch,long frame,int width,int height,byte[] rgba){Session s=sharedGuest(room,epoch);if(s==null||!s.ready||frame<=displayedFrames)return;displayedFrames=frame;CRC32 crc=new CRC32();crc.update(rgba);frameCrc=Long.toHexString(crc.getValue());updatePicture(new AvFrame(frame,width,height,0,rgba,new short[0],48000,false));status="玩家 "+(s.port+1)+" · "+s.platform.id().toUpperCase(Locale.ROOT)+" · 主机画面";}
    public void sharedAudio(String room,long epoch,long frame,short[] pcm){Session s=sharedGuest(room,epoch);if(s!=null&&s.ready)s.audio.offer(pcm,48000);}
    public ResourceLocation picture(){return active!=null&&active.ready&&active.hasPicture?picture:null;}
    public long screenConsole(){Session s=active;return s==null?Long.MIN_VALUE:s.position;}
    public void renderPicture(GuiGraphics graphics,int x,int y,int width,int height){
        graphics.fill(x,y,x+width,y+height,0xff101719);ResourceLocation p=picture();if(p==null)return;
        double scale=Math.min(width/(double)pictureWidth,height/(double)pictureHeight);int w=(int)(pictureWidth*scale),h=(int)(pictureHeight*scale);
        graphics.blit(p,x+(width-w)/2,y+(height-h)/2,w,h,0,0,pictureWidth,pictureHeight,pictureWidth,pictureHeight);
    }
    private ConsoleClient(){Runtime.getRuntime().addShutdownHook(new Thread(()->{stop();emulator.shutdown();try{emulator.awaitTermination(8,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}for(ProcessConsoleRuntime r:liveRuntimes.values())try{r.close();}catch(IOException e){MuxiConsole.LOG.warn("Shutdown worker close failed",e);}},"muxi-console-shutdown"));}
}
