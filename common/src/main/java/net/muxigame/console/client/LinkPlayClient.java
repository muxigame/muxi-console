package net.muxigame.console.client;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.client.transfer.*;
import net.muxigame.console.client.ui.*;
import net.muxigame.console.domain.*;
import net.muxigame.console.mc.*;
import net.muxigame.console.mc.Wire;
import net.muxigame.console.runtime.*;
import net.muxigame.console.compat.SurfaceMetadata;

/** Host runs two real machines; the partner receives only its own machine's pixels and PCM. */
public final class LinkPlayClient {
    public static final LinkPlayClient INSTANCE=new LinkPlayClient();
    private record Request(Session session,long epoch,CompletableFuture<Wire.Message> result){}
    private record Outbound(UUID id,Request request){}
    static final class Session {
        volatile JsonObject context;final UUID id;final int port;final boolean host;final JsonArray endpoints;final LoadProgress progress;final LinkCoreIdentity core;
        final PcmPlayer audio=new PcmPlayer();final AtomicInteger queued=new AtomicInteger();final LinkMedia.Assembler pictures;final int width,height;final boolean ds,n3ds,frameFlow;
        volatile LinkRuntime runtime;volatile LinkDsRuntime dsRuntime;volatile Link3dsRuntime threeDsRuntime;volatile DsCheckpoint dsCheckpoint,latestDsCheckpoint;volatile IOException runtimeCloseFailure;volatile int peakQueued;long nextBatchTick;volatile boolean ready,hasPicture,closing,cancelled,saving,failed;volatile long frames,executed,nextStep,sequence,lastSave,lastSaveAttempt;volatile String crc="",error="";
        Session(JsonObject body)throws IOException{context=body.deepCopy();try{core=new LinkCoreIdentity(Hashing.normalizeSha256(body.get("bridge").getAsString()),Hashing.normalizeSha256(body.get("single").getAsString()));}catch(RuntimeException malformed){throw new IOException("Invalid immutable link core identity",malformed);}id=UUID.fromString(body.get("link").getAsString());port=body.get("port").getAsInt();host=body.get("host").getAsBoolean();endpoints=body.getAsJsonArray("endpoints").deepCopy();if(port<0||port>1||endpoints.size()!=2)throw new IllegalArgumentException("Invalid link endpoints");String platform=own().get("platform").getAsString();if(!LinkLobby.compatible(platform,endpoints.get(1-port).getAsJsonObject().get("platform").getAsString()))throw new IOException("Incompatible link platforms");ds=platform.equals("nds");n3ds=platform.equals("n3ds");frameFlow=ds||n3ds;width=n3ds?400:ds?256:platform.equals("gba")?240:160;height=n3ds?480:ds?384:platform.equals("gba")?160:144;long base=body.has("frameBase")?body.get("frameBase").getAsLong():0;if(base<0)throw new IOException("Invalid link frame base");executed=nextStep=lastSave=lastSaveAttempt=base;pictures=new LinkMedia.Assembler(width,height);progress=new LoadProgress(UUID.fromString(own().get("instance").getAsString()));}
        JsonObject own(){return endpoints.get(port).getAsJsonObject();}
        long epoch(){return context.get("epoch").getAsLong();}
        JsonObject address(){JsonObject j=new JsonObject();j.addProperty("link",id.toString());j.addProperty("epoch",epoch());return j;}
        void requirePortableHeader(DataInput input)throws IOException{if(input.readInt()!=0x4d585333)throw new IOException("3DS 导出尚未使用已验证的可移植进度格式");byte[] actual=new byte[32];input.readFully(actual);if(!Arrays.equals(actual,HexFormat.of().parseHex(core.bridge())))throw new IOException("3DS 导出格式与实际核心身份不同");}
    }
    public record DsCheckpointSnapshot(String link,long frame,long epoch,boolean ending,boolean inFlight,String stage,long uploadedBytes,long totalBytes,long captureNanos,long uploadNanos,int queuedBatches,int peakQueued){}
    private static final class DsCheckpoint {
        final Session session;final long epoch,frame;final boolean ending;final JsonObject address;final List<Path> temporary=new ArrayList<>();final List<NativeProfileArchive.Snapshot> profiles=new ArrayList<>();final List<Path> states=new ArrayList<>();final CompletableFuture<Void> done=new CompletableFuture<>();
        volatile boolean cancelled;volatile String stage="capturing";volatile long uploadedBytes,totalBytes,captureNanos,uploadNanos;Path group;
        DsCheckpoint(Session s,boolean ending){session=s;epoch=s.epoch();frame=s.executed;this.ending=ending;address=s.address();address.addProperty("saveTransaction",UUID.randomUUID().toString());address.addProperty("frame",frame);}
    }
    private final ThreadPoolExecutor dsTransfers=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),task->{Thread t=new Thread(task,"playstation-ds-upload");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private volatile DsCheckpoint lastDsCheckpoint;
    public DsCheckpointSnapshot dsCheckpoint(){Session s=active;DsCheckpoint c=s==null?lastDsCheckpoint:s.latestDsCheckpoint;if(c==null)return new DsCheckpointSnapshot("",0,0,false,false,"idle",0,0,0,0,s==null?0:s.queued.get(),s==null?0:s.peakQueued);return new DsCheckpointSnapshot(c.session.id.toString(),c.frame,c.epoch,c.ending,!c.done.isDone(),c.stage,c.uploadedBytes,c.totalBytes,c.captureNanos,c.uploadNanos,c.session.queued.get(),c.session.peakQueued);}
    private final ExecutorService worker=Executors.newSingleThreadExecutor(task->{Thread t=new Thread(task,"playstation-link-worker");t.setDaemon(true);return t;});
    private final Map<UUID,Request> requests=new ConcurrentHashMap<>();
    private volatile Session active,closingSession;private final LinkNativeFilesGate nativeFiles=new LinkNativeFilesGate();private JsonObject invitation,preparing;private boolean preparedSent;
    private JsonArray players=new JsonArray();private String status="请手持已装卡的连线掌机";private boolean compatible;
    private long receivedAudio=-1,lastInputTick;private RuntimeFiles runtimeFiles;
    private static Minecraft mc(){return Minecraft.getInstance();}
    private RuntimeFiles files()throws IOException{if(runtimeFiles==null)runtimeFiles=new RuntimeFiles(mc().gameDirectory.toPath());return runtimeFiles;}
    /** Called only by the ordinary emulator background thread, before restoring any mutable native files. */
    public void awaitNativeFilesClosed()throws IOException{
        if(mc().isSameThread())throw new IOException("原生文件退出门不能在游戏主线程等待");LinkNativeFilesGate.await(nativeFiles.snapshot(),20000);
    }
    private void awaitPriorNativeFiles(Session s)throws IOException{
        CompletionStage<Void> previous=nativeFiles.snapshot();if(closingSession==s)throw new InterruptedIOException("连线已取消");LinkNativeFilesGate.await(previous,20000);if(!current(s)||closingSession==s)throw new InterruptedIOException("连线已取消");
    }
    private void startupCleanupFailure(Session s,IOException failure){if(failure.getSuppressed().length>0)s.runtimeCloseFailure=new IOException("连线启动后的资源清理未闭合",failure);}
    public boolean active(){return active!=null;}
    public boolean blocksStandalone(){return preparing!=null||active!=null;}
    public boolean ready(){Session s=active;return s!=null&&s.ready&&!s.closing;}
    public boolean hasPicture(){Session s=active;return s!=null&&s.hasPicture;}
    public String status(){return status;}
    public int port(){Session s=active;return s==null?-1:s.port;}
    public Platform platform(){Session s=active;return s==null?null:Platform.fromId(s.own().get("platform").getAsString());}
    public UUID card(){Session s=active;return s==null?null:UUID.fromString(s.own().get("instance").getAsString());}
    public Optional<MachineSaveKey> owner(){Session s=active;return s==null?Optional.empty():Optional.of(new MachineSaveKey(UUID.fromString(s.own().get("machine").getAsString()),s.own().get("hash").getAsString()));}
    public ConsoleClient.ProgressSnapshot progress(){Session s=active;if(s==null)return null;var p=s.progress.snapshot();return new ConsoleClient.ProgressSnapshot(p.card(),p.stage(),p.bytes(),p.total(),p.percent(),p.text(),p.loading(),p.error());}
    public ConsoleClient.Snapshot snapshot(){Session s=active;if(s==null)return null;LinkRuntime r=s.runtime;LinkDsRuntime ds=s.dsRuntime;Link3dsRuntime three=s.threeDsRuntime;Set<Long> pids=three!=null?Set.copyOf(three.workerPids()):ds!=null?Set.copyOf(ds.workerPids()):r==null?Set.of():Set.of(r.workerPid());return new ConsoleClient.Snapshot(status,"link/"+s.id,s.epoch(),s.port,s.ready,s.frames,s.crc,s.width,s.height,pids,s.frames,s.sequence,three!=null?three.isAlive():ds!=null?ds.isAlive():r!=null&&r.isAlive(),s.error,0,0,0,0,0,"","",0,0);}
    public JsonArray players(){return players.deepCopy();}
    public JsonObject invitation(){return invitation==null?null:invitation.deepCopy();}
    public boolean compatible(){return compatible;}
    public boolean preparing(){return preparing!=null;}
    public void open(){if(mc().player==null||mc().getConnection()==null)return;Wire.command("link_list",new JsonObject());mc().setScreen(new LinkScreen());}
    public void invite(UUID player){JsonObject j=new JsonObject();j.addProperty("player",player.toString());Wire.command("link_invite",j);}
    public void accept(){if(invitation!=null)Wire.command("link_accept",address(invitation));}
    public void decline(){if(invitation!=null)Wire.command("link_decline",address(invitation));}
    private static JsonObject address(JsonObject source){JsonObject j=new JsonObject();j.add("link",source.get("link"));j.add("epoch",source.get("epoch"));return j;}
    private static boolean same(JsonObject a,JsonObject b){return a!=null&&b!=null&&a.get("link").equals(b.get("link"))&&a.get("epoch").equals(b.get("epoch"));}
    private static boolean ends(JsonObject current,JsonObject event){return current!=null&&current.get("link").equals(event.get("link"))&&event.get("epoch").getAsLong()>=current.get("epoch").getAsLong();}
    private boolean current(Session session){return active==session&&!session.cancelled&&mc().getConnection()!=null;}
    private boolean addressed(Session s,JsonObject body){return s!=null&&same(s.context,body);}
    public boolean receive(Wire.Message message){
        JsonObject body=message.body();
        if(body.has("request")){
            UUID id;try{id=UUID.fromString(body.get("request").getAsString());}catch(RuntimeException invalid){id=null;}
            Request pending=id==null?null:requests.remove(id);
            if(pending!=null){if(current(pending.session)&&pending.epoch==pending.session.epoch()){
                if(message.operation().equals("error")||message.operation().equals("link_error")||message.operation().equals("rom_denied"))pending.result.completeExceptionally(new IOException(body.has("message")?body.get("message").getAsString():"连线请求被拒绝"));else pending.result.complete(message);
            }else pending.result.cancel(false);return true;}
        }
        if(!message.operation().startsWith("link_"))return false;
        try{Session s=active;
            switch(message.operation()){
                case "link_list" -> {players=body.getAsJsonArray("players").deepCopy();compatible=body.get("compatible").getAsBoolean();refresh();}
                case "link_invited" -> {invitation=body.deepCopy();status=body.get("host").getAsBoolean()?"邀请已发送，等待伙伴接受":"收到游戏机连线邀请";mc().setScreen(new LinkScreen());}
                case "link_prepare" -> {
                    if(invitation==null||!invitation.get("link").equals(body.get("link")))return true;
                    preparing=body.deepCopy();invitation=null;preparedSent=false;status="正在保存单机进度，等待双方准备";ConsoleClient.INSTANCE.leaveStandaloneForLink();refresh();
                }
                case "link_start" -> {
                    if(preparing==null||!preparing.get("link").equals(body.get("link")))return true;
                    Session next=new Session(body);active=next;preparing=null;preparedSent=false;receivedAudio=-1;ConsoleClient.INSTANCE.resetLinkPicture();
                    if(next.host){status="正在准备两台连线游戏机";worker.execute(()->start(next));}else{next.progress.begin(LoadProgress.Stage.SHARED,0);status="正在等待连线宿主";}
                    show();
                }
                case "link_ready" -> {if(addressed(s,body)){s.ready=true;status=s.frameFlow?"无线连线 · 你的游戏机":"串口连线 · 你的游戏机";}}
                case "link_step" -> {if(addressed(s,body)&&s.host&&!s.closing)step(s,body,message.data());}
                case "link_video" -> {
                    if(!addressed(s,body)||s.host||body.get("port").getAsInt()!=s.port||s.closing)return true;
                    long frame=body.get("frame").getAsLong();if(frame<=s.frames)return true;byte[] image=s.pictures.accept(frame,body.get("offset").getAsInt(),body.get("total").getAsInt(),message.data(),System.currentTimeMillis());
                    if(image!=null)picture(s,frame,LinkMedia.decode(image,s.width,s.height),SurfaceMetadata.read(body,s.width,s.height));
                }
                case "link_audio" -> {if(addressed(s,body)&&!s.host&&!s.closing&&body.get("port").getAsInt()==s.port){long frame=body.get("frame").getAsLong();double rate=body.get("rate").getAsDouble();if(frame>receivedAudio&&Double.isFinite(rate)&&rate>=8000&&rate<=192000){s.audio.offer(LinkMedia.pcm(message.data()),rate);receivedAudio=frame;}}}
                case "link_closing" -> {
                    if(s==null||!s.id.toString().equals(body.get("link").getAsString())||body.get("epoch").getAsLong()<=s.epoch())return true;
                    retireDs(s);cancelRequests(s);s.context=body.deepCopy();s.closing=true;ConsoleClient.INSTANCE.clearInputVisual();status="正在保存双方进度并结束连线";if(s.host)worker.execute(()->checkpoint(s,true));
                }
                case "link_end" -> {
                    boolean matches=s!=null&&ends(s.context,body)||ends(invitation,body)||ends(preparing,body);
                    if(!matches)return true;status=body.get("reason").getAsString();stop();if(mc().screen instanceof GameScreen||mc().screen instanceof LinkScreen)mc().setScreen(null);toast(status);
                }
                case "link_show" -> {if(addressed(s,body))show();}
                case "link_error" -> {if(body.has("link")&&!(addressed(s,body)||same(invitation,body)||same(preparing,body)))return true;status=body.get("message").getAsString();toast(status);refresh();}
                default -> { }
            }
        }catch(Exception failure){Session s=active;if(s!=null)failed(s,failure);else{status="连线消息无效，请重新打开列表";toast(status);}}return true;
    }
    private static void toast(String text){if(mc().player!=null)mc().player.displayClientMessage(Component.literal(text),true);}
    private void refresh(){if(mc().screen instanceof LinkScreen)mc().setScreen(new LinkScreen());}
    public void show(){Session s=active;if(s!=null&&!s.closing){ConsoleClient.INSTANCE.resumeControls();mc().setScreen(new GameScreen(ConsoleClient.INSTANCE,s.port));}}
    /** Called before ordinary held auto-start. Returns true while it owns preparation or presentation. */
    public boolean tick(){
        if(mc().player==null||mc().level==null||mc().getConnection()==null){stop();return false;}
        if(preparing!=null){if(!preparedSent&&!ConsoleClient.INSTANCE.standaloneRunning()){preparedSent=true;Wire.command("link_prepared",address(preparing));}return true;}
        Session s=active;if(s==null)return false;
        if(s.ready&&!s.closing){PortInput input=ConsoleClient.INSTANCE.sampleLinkInput(platform());if(mc().level.getGameTime()!=lastInputTick){lastInputTick=mc().level.getGameTime();JsonObject j=s.address();j.addProperty("port",s.port);j.addProperty("sequence",s.sequence++);try{Wire.command("link_input",j,InputCodec.encode(input));}catch(IOException invalid){failed(s,invalid);}}}else ConsoleClient.INSTANCE.clearInputVisual();return true;
    }
    private void start(Session s){
        try{
            if(!current(s))return;awaitPriorNativeFiles(s);ConsoleClient.INSTANCE.awaitStandaloneNativeFilesClosed();if(!current(s)||s.closing)return;
            if(s.n3ds){start3ds(s);return;}
            if(s.ds){startDs(s);return;}
            RuntimeFiles f=files();List<LinkRuntime.Endpoint> endpoints=new ArrayList<>();
            for(JsonElement value:s.endpoints){JsonObject e=value.getAsJsonObject();Path rom=rom(s,e);endpoints.add(new LinkRuntime.Endpoint(UUID.fromString(e.get("machine").getAsString()),e.get("platform").getAsString(),e.get("hash").getAsString(),rom));}
            if(!current(s))return;s.progress.begin(LoadProgress.Stage.STARTING,0);Path library=f.extract(LinkCoreIdentity.library(s.own().get("platform").getAsString()));
            LinkCoreIdentity identity=LinkCoreIdentity.bundled(s.own().get("platform").getAsString());if(!identity.bridge().equals(s.core.bridge())||!identity.single().equals(s.core.single()))throw new IOException("双方连线核心版本不同");
            Path system=f.root().resolve("firmware");Files.createDirectories(system);
            Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java");
            LinkRuntime runtime;try{runtime=LinkRuntime.open(new LinkRuntime.Config(java,List.of(f.extract("worker/runtime.jar"),f.extract("worker/jna-5.14.0.jar")),library,identity.bridge(),system,endpoints));}catch(IOException failure){startupCleanupFailure(s,failure);throw failure;}s.runtime=runtime;
            if(!current(s)){closeRuntime(s);return;}
            if(s.context.has("groupState")){byte[] bytes=state(s,s.context.getAsJsonObject("groupState"),-1);runtime.loadLinkState(bytes);}
            else{JsonArray states=s.context.getAsJsonArray("states");for(int i=0;i<2;i++){JsonObject saved=states.get(i).getAsJsonObject();if(saved.size()>0){if(!saved.get("fingerprint").getAsString().equals(identity.single()))throw new IOException("该机器单机存档版本不同，已保留原档");runtime.restoreEndpoint(i,state(s,saved,i));}}}
            s.progress.begin(LoadProgress.Stage.WAITING,0);mc().execute(()->{if(current(s)){JsonObject ready=s.address();ready.addProperty("bridge",identity.bridge());ready.addProperty("single",identity.single());ready.addProperty("fps",runtime.info().fps());Wire.command("link_ready",ready);}});
        }catch(Exception failure){failed(s,failure);}
    }
    private Path dsSpool()throws IOException{
        Path root=files().root().resolve("link-spool").toAbsolutePath().normalize();Files.createDirectories(root);if(!root.equals(root.toRealPath()))throw new IOException("无线临时进度目录无效");return root.resolve(UUID.randomUUID()+".state");
    }
    private Path dsArtifact(Session s,JsonObject metadata,String kind,int endpoint,long limit)throws Exception{
        long size=metadata.get("size").getAsLong();if(size<1||size>limit)throw new IOException("无线进度大小超出此机型范围");Path target=dsSpool();boolean complete=false;s.progress.begin(LoadProgress.Stage.RESTORING,size);
        try{try(var output=new BufferedOutputStream(Files.newOutputStream(target,StandardOpenOption.CREATE_NEW))){long offset=0;while(offset<size){List<Outbound> window=new ArrayList<>();long batch=offset;try{for(int i=0;i<8&&batch<size;i++){JsonObject j=s.address();j.addProperty("kind",kind);j.addProperty("endpoint",endpoint);j.addProperty("offset",batch);j.add("generation",metadata.get("generation"));j.add("hash",metadata.get("hash"));window.add(dispatch(s,LinkMessages.read(s.n3ds),j,new byte[0]));batch+=Math.min(24576,size-batch);}for(Outbound pending:window){Wire.Message reply=await(pending);int expected=(int)Math.min(24576,size-offset);if(!reply.operation().equals(LinkMessages.reply(s.n3ds))||reply.body().get("offset").getAsLong()!=offset||reply.data().length!=expected)throw new IOException("无线进度分块无效");output.write(reply.data());offset+=reply.data().length;s.progress.advance(reply.data().length);}}finally{window.forEach(this::cancel);}}}
            if(!Hashing.sha256(target).equals(metadata.get("hash").getAsString()))throw new IOException("无线进度校验失败");complete=true;return target;
        }finally{if(!complete)Files.deleteIfExists(target);}
    }
    private void startDs(Session s)throws Exception{
        if(!s.context.has("frameFlow")||!s.context.get("frameFlow").getAsBoolean())throw new IOException("NDS 连线需要支持进度确认的服务器");RuntimeFiles files=files();JsonArray profiles=s.context.getAsJsonArray("profiles");if(profiles==null||profiles.size()!=2)throw new IOException("无线机器文件清单无效");List<LinkDsRuntime.Endpoint> endpoints=new ArrayList<>();List<String> singleFingerprints=new ArrayList<>();LinkCoreIdentity identity=LinkCoreIdentity.bundled("nds");if(!identity.bridge().equals(s.core.bridge())||!identity.single().equals(s.core.single()))throw new IOException("双方无线核心版本不同");Path library=files.extract(LinkCoreIdentity.library("nds"));
        for(int i=0;i<2;i++){
            JsonObject endpoint=s.endpoints.get(i).getAsJsonObject();UUID machine=UUID.fromString(endpoint.get("machine").getAsString());Path rom=rom(s,endpoint);JsonObject profile=profiles.get(i).getAsJsonObject();
            if(profile.size()>0){Path archive=dsArtifact(s,profile,"profile",i,NativeProfileArchive.MAX_BYTES);try{NativeProfileArchive.restore(files.machineProfile(machine,Platform.NDS),"nds",archive,()->current(s)&&!s.closing);}finally{Files.deleteIfExists(archive);}}
            var selected=files.select(Platform.NDS,rom,new MachineSaveKey(machine,endpoint.get("hash").getAsString()));if(!Hashing.sha256(selected.launch().core()).equals(identity.single())||!selected.fingerprint().equals(LinkCoreIdentity.stateFingerprint("nds",machine)))throw new IOException("无线机型与单机工作进程身份不同");singleFingerprints.add(selected.fingerprint());LaunchConfig base=selected.launch();LaunchConfig launch=new LaunchConfig(base.javaExecutable(),base.classpath(),library,base.content(),base.systemDirectory(),base.saveDirectory(),base.options(),"nds");endpoints.add(new LinkDsRuntime.Endpoint(machine,endpoint.get("hash").getAsString(),launch));
        }
        if(!current(s)||s.closing)return;s.progress.begin(LoadProgress.Stage.STARTING,0);LinkDsRuntime runtime;try{runtime=LinkDsRuntime.open(new LinkDsRuntime.Config(identity.bridge(),endpoints));}catch(IOException failure){startupCleanupFailure(s,failure);throw failure;}s.dsRuntime=runtime;if(!current(s)){closeRuntime(s);return;}
        if(s.context.has("groupState")){Path group=dsArtifact(s,s.context.getAsJsonObject("groupState"),"group",-1,LinkDsRuntime.MAX_GROUP_BYTES);try{runtime.loadLinkStateFrom(group);}finally{Files.deleteIfExists(group);}}
        else{JsonArray states=s.context.getAsJsonArray("states");for(int i=0;i<2;i++){JsonObject saved=states.get(i).getAsJsonObject();if(saved.size()==0)continue;if(!saved.get("fingerprint").getAsString().equals(singleFingerprints.get(i)))throw new IOException("该机器的单机进度版本不同，已保留原档");Path transport=dsArtifact(s,saved,"endpoint",i,512L*1024*1024),expanded=dsSpool();try{Path selected=StateSpool.expand(transport,expanded,512L*1024*1024,()->current(s)&&!s.closing,n->{});runtime.restoreEndpointFrom(i,selected);}finally{Files.deleteIfExists(transport);Files.deleteIfExists(expanded);}}}
        s.progress.begin(LoadProgress.Stage.WAITING,0);mc().execute(()->{if(current(s)&&!s.closing){JsonObject j=s.address();j.addProperty("bridge",identity.bridge());j.addProperty("single",identity.single());j.addProperty("fps",runtime.info().fps());j.addProperty("frameFlow",1);Wire.command("link_ready",j);}});
    }
    private void start3ds(Session s)throws Exception{
        if(!s.context.has("frameFlow")||!s.context.get("frameFlow").getAsBoolean()||!s.context.has("queueCheckpoint")||s.context.get("queueCheckpoint").getAsBoolean())throw new IOException("3DS 连线需要独立的双机进度通道");
        RuntimeFiles files=files();JsonArray profiles=s.context.getAsJsonArray("profiles"),states=s.context.getAsJsonArray("states");if(profiles==null||profiles.size()!=2||states==null||states.size()!=2)throw new IOException("3DS 机器进度清单无效");
        LinkCoreIdentity identity=LinkCoreIdentity.bundled("n3ds");if(!identity.bridge().equals(s.core.bridge())||!identity.single().equals(s.core.single()))throw new IOException("双方 3DS 核心版本不同");
        Path library=files.extract(LinkCoreIdentity.library("n3ds"));List<Link3dsRuntime.Endpoint> endpoints=new ArrayList<>();List<String> fingerprints=new ArrayList<>();
        for(int i=0;i<2;i++){
            JsonObject endpoint=s.endpoints.get(i).getAsJsonObject();UUID machine=UUID.fromString(endpoint.get("machine").getAsString());Path rom=rom(s,endpoint);JsonObject profile=profiles.get(i).getAsJsonObject();if(!current(s)||s.closing)return;
            if(profile.size()>0){Path archive=dsArtifact(s,profile,"profile",i,NativeProfileArchive.MAX_BYTES);try{NativeProfileArchive.restore(files.machineProfile(machine,Platform.N3DS),"n3ds",archive,()->current(s)&&!s.closing);}finally{Files.deleteIfExists(archive);}}
            var selected=files.select(Platform.N3DS,rom,new MachineSaveKey(machine,endpoint.get("hash").getAsString()));if(!Hashing.sha256(selected.launch().core()).equals(identity.single())||!selected.fingerprint().equals(LinkCoreIdentity.stateFingerprint("n3ds",machine)))throw new IOException("3DS 单机与连线进度身份不同");
            fingerprints.add(selected.fingerprint());LaunchConfig base=selected.launch();endpoints.add(new Link3dsRuntime.Endpoint(machine,endpoint.get("hash").getAsString(),new LaunchConfig(base.javaExecutable(),base.classpath(),library,base.content(),base.systemDirectory(),base.saveDirectory(),base.options(),"n3ds")));
        }
        if(!current(s)||s.closing)return;s.progress.begin(LoadProgress.Stage.STARTING,0);var config=new Link3dsRuntime.Config(identity.bridge(),endpoints);Link3dsRuntime runtime;
        try{runtime=Link3dsRuntime.open(config);}catch(IOException failure){if(!Link3dsRuntime.filesClosedAfterStartupFailure(failure))s.runtimeCloseFailure=new IOException("3DS 启动后的原生资源尚未闭合",failure);throw failure;}
        s.threeDsRuntime=runtime;if(!current(s)||s.closing){closeRuntime(s);return;}
        for(int i=0;i<2;i++){
            var radio=runtime.nativeStatus(i);if(!radio.joined()||radio.profileStateAbi()<2)throw new IOException("3DS 无线进度恢复接口不完整");JsonObject saved=states.get(i).getAsJsonObject();if(saved.size()==0)continue;
            if(!saved.get("fingerprint").getAsString().equals(fingerprints.get(i)))throw new IOException("这台 3DS 的进度版本不同，原档已保留");
            Path transport=dsArtifact(s,saved,"endpoint",i,512L*1024*1024),expanded=dsSpool();
            try{Path selected=StateSpool.expand(transport,expanded,512L*1024*1024,()->current(s)&&!s.closing,n->{});try{runtime.restoreEndpointFrom(i,selected);}catch(IOException incompatible){if(!NativeStateBinding.mismatch(incompatible))throw incompatible;MuxiConsole.LOG.info("3DS link retained incompatible quickstate and resumed native game files machine={}",endpoints.get(i).machine());}}
            finally{Files.deleteIfExists(transport);Files.deleteIfExists(expanded);}
        }
        s.progress.begin(LoadProgress.Stage.WAITING,0);mc().execute(()->{if(current(s)&&!s.closing){JsonObject j=s.address();j.addProperty("bridge",identity.bridge());j.addProperty("single",identity.single());j.addProperty("fps",runtime.info().fps());j.addProperty("frameFlow",1);Wire.command("link_ready",j);}});
    }
    private boolean valid(DsCheckpoint c){return current(c.session)&&!c.cancelled&&c.session.epoch()==c.epoch&&(c.ending||!c.session.closing);}
    private void require(DsCheckpoint c)throws IOException{if(!valid(c))throw new InterruptedIOException("无线快照事务已退休");}
    private void retireDs(Session s){DsCheckpoint c=s.dsCheckpoint;if(c!=null)c.cancelled=true;}
    private void awaitDs(Session s)throws IOException{DsCheckpoint c=s.dsCheckpoint;if(c==null)c=s.latestDsCheckpoint;if(c==null)return;try{c.done.get(20,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("等待旧无线快照退出时中断",interrupted);}catch(ExecutionException|TimeoutException failure){throw new IOException("旧无线快照尚未退出",failure);}}
    private void finishDs(DsCheckpoint c){
        IOException failure=null;for(Path file:c.temporary)try{Files.deleteIfExists(file);}catch(IOException cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);MuxiConsole.LOG.warn("DS snapshot temporary file cleanup failed type={}",cleanup.getClass().getSimpleName());}
        synchronized(c.session){if(c.session.dsCheckpoint==c){c.session.dsCheckpoint=null;c.session.saving=false;}}lastDsCheckpoint=c;if(failure==null)c.done.complete(null);else c.done.completeExceptionally(failure);
    }
    private void dsFailure(DsCheckpoint c,Exception failure){if(valid(c)){c.stage="failed";c.session.error="无线进度未保存";mc().execute(()->{if(valid(c)){status=c.ending?"无线保存未完成，保留最近有效进度":"自动保存未完成，将在稍后重试";toast(status);if(c.ending)Wire.command("link_failed",c.address);}});MuxiConsole.LOG.warn("DS checkpoint failed link={} type={} detail={}",c.session.id,failure.getClass().getSimpleName(),TransferFailure.diagnostic(failure));}else c.stage="cancelled";}
    private void dsPieces(DsCheckpoint c,Path file,String operation,String upload)throws Exception{
        try(var input=new BufferedInputStream(Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS))){byte[] buffer=new byte[24576];long offset=0;boolean eof=false;while(!eof){require(c);List<Outbound> window=new ArrayList<>();List<Long> ends=new ArrayList<>();long acknowledged=offset;try{for(int i=0;i<8;i++){int n=input.readNBytes(buffer,0,buffer.length);if(n==0){eof=true;break;}require(c);JsonObject j=c.address.deepCopy();j.addProperty("upload",upload);j.addProperty("offset",offset);window.add(dispatch(c.session,operation,j,Arrays.copyOf(buffer,n)));offset+=n;ends.add(offset);}for(int i=0;i<window.size();i++){Wire.Message reply=await(window.get(i));require(c);long end=ends.get(i);if(!reply.operation().equals(LinkMessages.reply(c.session.n3ds))||reply.body().get("offset").getAsLong()!=end)throw new IOException("无线存档分块回执不匹配");c.uploadedBytes+=end-acknowledged;acknowledged=end;}}finally{window.forEach(this::cancel);}}if(offset!=Files.size(file))throw new IOException("无线存档在传输时改变");}
    }
    private void checkpointDs(Session s,boolean ending){
        if(!current(s)||s.dsRuntime==null)return;
        try{if(ending){retireDs(s);awaitDs(s);if(!current(s))return;JsonObject check=s.address();check.addProperty("saveTransaction",UUID.randomUUID().toString());check.addProperty("frame",s.executed);Wire.Message checked=request(s,"link_ds_final_check",check,new byte[0]);if(checked.body().get("unchanged").getAsBoolean())return;}}
        catch(Exception failure){failed(s,failure);return;}
        DsCheckpoint c; synchronized(s){if(s.dsCheckpoint!=null||!current(s))return;c=new DsCheckpoint(s,ending);s.dsCheckpoint=s.latestDsCheckpoint=c;s.saving=true;s.lastSaveAttempt=c.frame;}
        long began=System.nanoTime();boolean submitted=false;
        try{
            require(c);c.group=dsSpool();c.temporary.add(c.group);s.dsRuntime.saveLinkStateTo(c.group);
            for(int i=0;i<2;i++){require(c);UUID machine=UUID.fromString(s.endpoints.get(i).getAsJsonObject().get("machine").getAsString());Path archive=dsSpool();c.temporary.add(archive);c.profiles.add(NativeProfileArchive.pack(files().machineProfile(machine,Platform.NDS),"nds",archive,()->valid(c),n->{}));}
            require(c);c.captureNanos=System.nanoTime()-began;c.totalBytes=Files.size(c.group)+c.profiles.stream().mapToLong(NativeProfileArchive.Snapshot::size).sum();c.stage="uploading";dsTransfers.execute(()->uploadDs(c));submitted=true;
        }catch(Exception failure){dsFailure(c,failure);}finally{if(!submitted){c.captureNanos=System.nanoTime()-began;finishDs(c);}}
    }
    private void uploadDs(DsCheckpoint c){
        long began=System.nanoTime();boolean committed=false;Session s=c.session;
        try{
            require(c);for(int i=0;i<2;i++){
                var snapshot=c.profiles.get(i);JsonObject begin=c.address.deepCopy();begin.addProperty("endpoint",i);begin.addProperty("size",snapshot.size());begin.addProperty("hash",snapshot.hash());Wire.Message reply=request(s,"link_ds_profile_begin",begin,new byte[0]);require(c);
                if(!reply.body().has("committed")||!reply.body().get("committed").getAsBoolean()){String id=reply.body().get("upload").getAsString();dsPieces(c,snapshot.path(),"link_ds_profile_piece",id);JsonObject finish=c.address.deepCopy();finish.addProperty("upload",id);reply=request(s,"link_ds_profile_commit",finish,new byte[0]);require(c);}else c.totalBytes-=snapshot.size();
                if(!reply.body().getAsJsonObject("profile").get("hash").getAsString().equals(snapshot.hash()))throw new IOException("无线机器文件提交校验不同");
            }
            require(c);String hash=Hashing.sha256(c.group);JsonObject begin=c.address.deepCopy();begin.addProperty("size",Files.size(c.group));begin.addProperty("hash",hash);Wire.Message started=request(s,"link_ds_save_begin",begin,new byte[0]);require(c);String id=started.body().get("upload").getAsString();dsPieces(c,c.group,"link_ds_save_piece",id);JsonObject finish=c.address.deepCopy();finish.addProperty("upload",id);Wire.Message reply=request(s,"link_ds_save_commit",finish,new byte[0]);if(!reply.body().get("hash").getAsString().equals(hash))throw new IOException("无线连线整体进度提交校验不同");s.lastSave=Math.max(s.lastSave,c.frame);s.error="";c.stage="complete";committed=true;
        }catch(Exception failure){dsFailure(c,failure);}
        finally{
            if(!committed&&valid(c)){JsonObject abort=c.address.deepCopy();mc().execute(()->{if(valid(c))Wire.command("link_ds_abort",abort);});}c.uploadNanos=System.nanoTime()-began;finishDs(c);
        }
    }
    private void checkpoint3ds(Session s,boolean ending){
        if(!current(s)||s.threeDsRuntime==null)return;
        try{if(ending){retireDs(s);awaitDs(s);if(!current(s))return;JsonObject check=s.address();check.addProperty("saveTransaction",UUID.randomUUID().toString());check.addProperty("frame",s.executed);Wire.Message reply=request(s,"link_threeds_final_check",check,new byte[0]);if(!reply.operation().equals("link_threeds_reply"))throw new IOException("3DS 结束状态回执无效");if(reply.body().get("unchanged").getAsBoolean())return;}}
        catch(Exception failure){failed(s,failure);return;}
        DsCheckpoint c;synchronized(s){if(s.dsCheckpoint!=null||!current(s))return;c=new DsCheckpoint(s,ending);s.dsCheckpoint=s.latestDsCheckpoint=c;s.saving=true;s.lastSaveAttempt=c.frame;}
        long began=System.nanoTime();boolean submitted=false;
        try{
            // Both CPUs are paused on this worker queue. Each ordinary state and its native files
            // are made immutable before any CPU advances again; no ENet queue snapshot is claimed.
            for(int i=0;i<2;i++){require(c);Path raw=dsSpool(),compressed=dsSpool();c.temporary.add(raw);c.temporary.add(compressed);s.threeDsRuntime.exportEndpointTo(i,raw);try(var header=new DataInputStream(Files.newInputStream(raw))){s.requirePortableHeader(header);}c.states.add(StateSpool.compress(raw,compressed,512L*1024*1024,()->valid(c),n->{}));}
            for(int i=0;i<2;i++){require(c);UUID machine=UUID.fromString(s.endpoints.get(i).getAsJsonObject().get("machine").getAsString());Path archive=dsSpool();c.temporary.add(archive);c.profiles.add(NativeProfileArchive.pack(files().machineProfile(machine,Platform.N3DS),"n3ds",archive,()->valid(c),n->{}));}
            require(c);c.captureNanos=System.nanoTime()-began;c.totalBytes=c.states.stream().mapToLong(file->{try{return Files.size(file);}catch(IOException failure){throw new UncheckedIOException(failure);}}).sum()+c.profiles.stream().mapToLong(NativeProfileArchive.Snapshot::size).sum();c.stage="uploading";dsTransfers.execute(()->upload3ds(c));submitted=true;
        }catch(Exception failure){dsFailure(c,failure);}finally{if(!submitted){c.captureNanos=System.nanoTime()-began;finishDs(c);}}
    }
    private void upload3ds(DsCheckpoint c){
        long began=System.nanoTime();boolean committed=false;Session s=c.session;List<String> profiles=new ArrayList<>();
        try{
            for(int i=0;i<2;i++){
                require(c);var snapshot=c.profiles.get(i);JsonObject begin=c.address.deepCopy();begin.addProperty("endpoint",i);begin.addProperty("size",snapshot.size());begin.addProperty("hash",snapshot.hash());Wire.Message reply=request(s,"link_threeds_profile_begin",begin,new byte[0]);require(c);
                if(!reply.body().has("committed")||!reply.body().get("committed").getAsBoolean()){String id=reply.body().get("upload").getAsString();dsPieces(c,snapshot.path(),"link_threeds_profile_piece",id);JsonObject finish=c.address.deepCopy();finish.addProperty("upload",id);reply=request(s,"link_threeds_profile_commit",finish,new byte[0]);require(c);}else c.totalBytes-=snapshot.size();
                JsonObject stored=reply.body().getAsJsonObject("profile");if(!reply.operation().equals("link_threeds_reply")||!stored.get("hash").getAsString().equals(snapshot.hash())||stored.get("size").getAsLong()!=snapshot.size())throw new IOException("3DS 机器文件提交校验不同");profiles.add(stored.get("generation").getAsString());
            }
            for(int i=0;i<2;i++){
                require(c);Path state=c.states.get(i);String hash=Hashing.sha256(state);JsonObject begin=c.address.deepCopy();begin.addProperty("endpoint",i);begin.addProperty("size",Files.size(state));begin.addProperty("hash",hash);Wire.Message reply=request(s,"link_threeds_state_begin",begin,new byte[0]);require(c);String id=reply.body().get("upload").getAsString();dsPieces(c,state,"link_threeds_state_piece",id);JsonObject finish=c.address.deepCopy();finish.addProperty("upload",id);reply=request(s,"link_threeds_state_commit",finish,new byte[0]);require(c);
                JsonObject stored=reply.body().getAsJsonObject("state");if(!reply.operation().equals("link_threeds_reply")||!stored.get("hash").getAsString().equals(hash)||!stored.get("profileGeneration").getAsString().equals(profiles.get(i)))throw new IOException("3DS 进度与机器文件代际不同");
            }
            require(c);Wire.Message reply=request(s,"link_threeds_finish",c.address,new byte[0]);if(!reply.operation().equals("link_threeds_reply")||!reply.body().get("committed").getAsBoolean()||reply.body().get("frame").getAsLong()!=c.frame)throw new IOException("3DS 双机进度提交回执无效");s.lastSave=Math.max(s.lastSave,c.frame);s.error="";c.stage="complete";committed=true;
        }catch(Exception failure){dsFailure(c,failure);}
        finally{if(!committed&&valid(c)){JsonObject abort=c.address.deepCopy();mc().execute(()->{if(valid(c))Wire.command("link_threeds_abort",abort);});}c.uploadNanos=System.nanoTime()-began;finishDs(c);}
    }
    private Outbound dispatch(Session s,String operation,JsonObject body,byte[] data)throws IOException {
        if(!current(s)||body.has("epoch")&&body.get("epoch").getAsLong()!=s.epoch())throw new IOException("连线事务已取消");UUID id=UUID.randomUUID();long epoch=s.epoch();CompletableFuture<Wire.Message> result=new CompletableFuture<>();Request pending=new Request(s,epoch,result);requests.put(id,pending);JsonObject request=body.deepCopy();request.addProperty("request",id.toString());
        try{mc().execute(()->{if(current(s)&&s.epoch()==epoch&&!result.isDone()){try{Wire.command(operation,request,data);}catch(RuntimeException failure){result.completeExceptionally(failure);}}else result.cancel(false);});}catch(RuntimeException scheduling){requests.remove(id,pending);result.completeExceptionally(scheduling);}
        return new Outbound(id,pending);
    }
    private void cancel(Outbound outbound){requests.remove(outbound.id,outbound.request);outbound.request.result.cancel(false);}
    private Wire.Message await(Outbound outbound)throws Exception{try{return outbound.request.result.get(15,TimeUnit.SECONDS);}finally{cancel(outbound);}}
    private Wire.Message request(Session s,String operation,JsonObject body,byte[] data)throws Exception{return await(dispatch(s,operation,body,data));}
    private Wire.Message ticket(Session s,JsonObject endpoint)throws Exception {JsonObject j=s.address();j.add("hash",endpoint.get("hash"));Wire.Message reply=request(s,"rom_ticket",j,new byte[0]);if(reply.operation().equals("rom_ticket")&&(!reply.body().get("hash").equals(endpoint.get("hash"))||!reply.body().get("size").equals(endpoint.get("size"))))throw new IOException("云端连线卡带身份不同");return reply;}
    private static HttpMediaTransfer.Ticket mediaTicket(JsonObject j){Map<String,String> headers=new LinkedHashMap<>();for(var e:j.getAsJsonObject("headers").entrySet())headers.put(e.getKey(),e.getValue().getAsString());return new HttpMediaTransfer.Ticket(j.get("method").getAsString(),java.net.URI.create(j.get("url").getAsString()),headers,j.get("expiresAt").getAsLong());}
    private Path rom(Session s,JsonObject endpoint)throws Exception {
        String hash=endpoint.get("hash").getAsString();long size=endpoint.get("size").getAsLong();boolean[] local={false};HttpMediaTransfer.RangeReader[] reader={null};
        try{return RomTransfer.receive(files().root().resolve("roms"),hash,endpoint.get("platform").getAsString(),size,offset->{
            if(!local[0]&&reader[0]==null){Wire.Message response=ticket(s,endpoint);if(response.operation().equals("rom_transport")){local[0]=true;}else if(response.operation().equals("rom_ticket")){
                var initial=new java.util.concurrent.atomic.AtomicReference<>(mediaTicket(response.body().getAsJsonObject("downloadTicket")));
                reader[0]=new HttpMediaTransfer.RangeReader(hash,size,position->{var t=initial.getAndSet(null);if(t!=null)return t;Wire.Message refreshed=ticket(s,endpoint);if(!refreshed.operation().equals("rom_ticket"))throw new IOException("连线卡带存储已改变");return mediaTicket(refreshed.body().getAsJsonObject("downloadTicket"));},()->current(s)&&!s.closing);
            }else throw new IOException("连线卡带传输不可用");}
            if(reader[0]!=null)return reader[0].read(offset);JsonObject j=s.address();j.addProperty("hash",hash);j.addProperty("offset",offset);Wire.Message response=request(s,"rom_read",j,new byte[0]);if(!response.operation().equals("rom_chunk"))throw new IOException("连线卡带下载被拒绝");return new RomTransfer.Chunk(response.body().get("offset").getAsLong(),response.body().get("size").getAsLong(),response.body().get("hash").getAsString(),response.data());
        },()->current(s)&&!s.closing,s.progress,RomTransfer.STREAM_CHUNK_BYTES);}finally{if(reader[0]!=null)reader[0].close();}
    }
    private byte[] state(Session s,JsonObject metadata,int endpoint)throws Exception {
        long size=metadata.get("size").getAsLong();if(size<1||size>LinkStateStore.MAX_BYTES)throw new IOException("连线存档大小无效");s.progress.begin(LoadProgress.Stage.RESTORING,size);ByteArrayOutputStream output=new ByteArrayOutputStream((int)size);
        while(output.size()<size){JsonObject j=s.address();j.addProperty("kind",endpoint<0?"group":"endpoint");j.addProperty("endpoint",endpoint);j.addProperty("offset",output.size());j.add("hash",metadata.get("hash"));if(endpoint>=0)j.add("generation",metadata.get("generation"));Wire.Message response=request(s,"link_state_read",j,new byte[0]);if(!response.operation().equals("link_reply")||response.body().get("offset").getAsLong()!=output.size()||response.data().length<1||response.data().length>size-output.size())throw new IOException("连线存档分块无效");output.write(response.data());s.progress.advance(response.data().length);}
        byte[] bytes=output.toByteArray();if(!Hashing.sha256(bytes).equals(metadata.get("hash").getAsString()))throw new IOException("连线存档校验失败");return bytes;
    }
    private void step(Session s,JsonObject body,byte[] bytes)throws IOException {
        int count=body.get("count").getAsInt();if(count<1||count>3)throw new IOException("Invalid link frame count");var in=new DataInputStream(new ByteArrayInputStream(bytes));PortInput left=InputCodec.readPort(in),right=InputCodec.readPort(in);if(in.available()!=0)throw new IOException("Trailing link input");
        if(body.get("frame").getAsLong()!=s.nextStep||s.frameFlow&&body.get("tick").getAsLong()!=s.nextBatchTick++)throw new IOException("连线推进顺序已改变");s.nextStep+=count;
        int queued=s.queued.incrementAndGet();s.peakQueued=Math.max(s.peakQueued,queued);if(queued>8){s.queued.decrementAndGet();throw new IOException("连线核心未能及时处理输入");}JsonObject completion=address(body);if(s.frameFlow)completion.add("tick",body.get("tick"));
        worker.execute(()->{boolean completed=false;try{if(!current(s)||s.closing)return;LinkRuntime.Frame last=null;
            for(int i=0;i<count;i++){PortInput a=i==0?left:left.withoutMicrophone(),b=i==0?right:right.withoutMicrophone();LinkRuntime.Frame frame=s.n3ds?s.threeDsRuntime.step(a,b):s.ds?s.dsRuntime.step(a,b):s.runtime.step(left,right);s.executed++;last=frame;for(int port=0;port<2;port++){AvFrame output=frame.endpoint(port);if(port==s.port)s.audio.offer(output.stereoPcm(),output.sampleRate());else sendAudio(s,port,s.executed,output);}}
            if(last!=null){long number=s.executed;AvFrame own=last.endpoint(s.port);mc().execute(()->{if(current(s)&&!s.closing)picture(s,number,own.rgba(),own.surfaces());});sendVideo(s,1-s.port,number,last.endpoint(1-s.port));}if(!s.frameFlow&&s.executed-s.lastSaveAttempt>=300&&!s.saving)checkpoint(s,false);completion.addProperty("frame",s.executed);completed=true;
        }catch(Exception failure){failed(s,failure);}finally{s.queued.decrementAndGet();if(completed&&s.frameFlow)mc().execute(()->{if(current(s)&&addressed(s,completion)&&!s.closing)Wire.command("link_batch_done",completion);});}
            if(completed&&s.frameFlow&&current(s)&&!s.closing&&s.executed-s.lastSaveAttempt>=3600&&!s.saving)checkpoint(s,false);
        });
    }
    private void picture(Session s,long number,byte[] rgba,List<AvFrame.Surface> surfaces){if(!current(s))return;s.hasPicture=true;s.progress.begin(LoadProgress.Stage.COMPLETE,0);s.frames=Math.max(s.frames,number);CRC32 crc=new CRC32();crc.update(rgba);s.crc=Long.toHexString(crc.getValue());ConsoleClient.INSTANCE.acceptLinkPicture(new AvFrame(number,s.width,s.height,0,rgba,new short[0],48000,false,0,surfaces));}
    private void sendVideo(Session s,int port,long frame,AvFrame video)throws IOException {
        byte[] compressed=LinkMedia.encode(video.rgba(),s.width,s.height);for(int offset=0;offset<compressed.length;offset+=LinkMedia.PART){JsonObject j=s.address();j.addProperty("port",port);j.addProperty("frame",frame);j.addProperty("offset",offset);j.addProperty("total",compressed.length);SurfaceMetadata.write(j,video.surfaces());byte[] piece=Arrays.copyOfRange(compressed,offset,Math.min(offset+LinkMedia.PART,compressed.length));mc().execute(()->{if(current(s)&&!s.closing)Wire.command("link_video",j,piece);});}
    }
    private void sendAudio(Session s,int port,long number,AvFrame frame)throws IOException {if(frame.stereoPcm().length==0)return;byte[] pcm=LinkMedia.pcm(frame.stereoPcm());JsonObject j=s.address();j.addProperty("port",port);j.addProperty("frame",number);j.addProperty("rate",frame.sampleRate());mc().execute(()->{if(current(s)&&!s.closing)Wire.command("link_audio",j,pcm);});}
    private void checkpoint(Session s,boolean ending){
        if(s.n3ds){checkpoint3ds(s,ending);return;}
        if(s.ds){checkpointDs(s,ending);return;}
        if(!current(s)||s.runtime==null||s.saving)return;s.saving=true;s.lastSaveAttempt=s.executed;String upload=null;boolean committed=false;
        try{byte[] state=s.runtime.saveLinkState();JsonObject begin=s.address();begin.addProperty("size",state.length);begin.addProperty("frame",s.executed);begin.addProperty("hash",Hashing.sha256(state));Wire.Message ack=request(s,"link_save_begin",begin,new byte[0]);upload=ack.body().get("upload").getAsString();
            for(int offset=0;offset<state.length;offset+=24576){JsonObject j=s.address();j.addProperty("upload",upload);j.addProperty("offset",offset);request(s,"link_save_piece",j,Arrays.copyOfRange(state,offset,Math.min(offset+24576,state.length)));}
            JsonObject commit=s.address();commit.addProperty("upload",upload);Wire.Message complete=request(s,"link_save_commit",commit,new byte[0]);if(!complete.body().get("hash").getAsString().equals(Hashing.sha256(state)))throw new IOException("连线存档提交身份不同");s.lastSave=s.executed;committed=true;
        }catch(Exception failure){if(current(s)){s.error="连线存档未保存";mc().execute(()->{if(current(s)){status=ending?"连线保存未完成，保留最近已提交的进度":"自动保存未完成，将在稍后重试";toast(status);}});MuxiConsole.LOG.warn("Link checkpoint failed link={} type={}",s.id,failure.getClass().getSimpleName());}}
        finally{if(!committed&&upload!=null&&current(s)){JsonObject abort=s.address();abort.addProperty("upload",upload);mc().execute(()->{if(current(s))Wire.command("link_save_abort",abort);});}s.saving=false;}
    }
    public void leave(){Session s=active;if(s!=null){if(!s.closing){status="正在结束连线";Wire.command("link_leave",s.address());}return;}if(invitation!=null)decline();else if(preparing!=null)Wire.command("link_leave",address(preparing));}
    private void failed(Session s,Exception failure){mc().execute(()->{if(!current(s)||s.failed)return;s.failed=true;s.ready=false;s.closing=true;s.error="连线未能继续";status=s.error;JsonObject j=s.address();Wire.command(s.host?"link_failed":"link_leave",j);MuxiConsole.LOG.warn("Link client failure link={} type={} detail={}",s.id,failure.getClass().getSimpleName(),TransferFailure.diagnostic(failure));toast(status);});}
    private void cancelRequests(Session s){for(var entry:requests.entrySet())if(entry.getValue().session==s&&requests.remove(entry.getKey(),entry.getValue()))entry.getValue().result.cancel(false);}
    private static IOException combine(IOException previous,IOException failure){if(previous==null)return failure;if(previous!=failure)previous.addSuppressed(failure);return previous;}
    private void closeRuntime(Session s)throws IOException{
        s.audio.close();IOException failure=s.runtimeCloseFailure;try{awaitDs(s);}catch(IOException pending){failure=combine(failure,pending);}
        Link3dsRuntime three=s.threeDsRuntime;if(three!=null)try{three.close();if(three.isAlive()||!three.naturalExit())throw new IOException("3DS 原生进程关闭未完整确认");MuxiConsole.LOG.info("3DS link worker closure pids={} naturalExit={}",three.workerPids(),three.naturalExit());}catch(IOException closing){failure=combine(failure,closing);MuxiConsole.LOG.warn("3DS link worker closure failed type={}",closing.getClass().getSimpleName());}
        LinkDsRuntime ds=s.dsRuntime;if(ds!=null)try{ds.close();if(ds.isAlive()||!ds.naturalExit())throw new IOException("DS 原生进程关闭未完整确认");MuxiConsole.LOG.info("DS link worker closure pids={} naturalExit={}",ds.workerPids(),ds.naturalExit());}catch(IOException closing){failure=combine(failure,closing);MuxiConsole.LOG.warn("DS link worker closure failed type={}",closing.getClass().getSimpleName());}
        LinkRuntime r=s.runtime;if(r!=null)try{r.close();if(r.isAlive()||r.exitCode().orElse(-1)!=0||r.wasForcedShutdown()||!r.nativeTempCleaned())throw new IOException("串口原生进程关闭未完整确认");MuxiConsole.LOG.info("Link worker closure pid={} exit={} forced={} nativeTempCleaned={}",r.workerPid(),r.exitCode().orElse(-1),r.wasForcedShutdown(),r.nativeTempCleaned());}catch(IOException closing){failure=combine(failure,closing);MuxiConsole.LOG.warn("Link worker closure failed pid={}",r.workerPid());}
        if(failure!=null){s.runtimeCloseFailure=failure;throw failure;}
    }
    public synchronized void stop(){
        Session s=active;LinkNativeFilesGate.Closing closure=null;if(s!=null){closingSession=s;closure=nativeFiles.begin();}active=null;invitation=null;preparing=null;preparedSent=false;
        if(s!=null){s.cancelled=true;retireDs(s);cancelRequests(s);s.progress.cancel();s.audio.close();LinkNativeFilesGate.Closing result=closure;try{worker.execute(()->{try{closeRuntime(s);result.finish(null);}catch(Exception failure){result.finish(failure);MuxiConsole.LOG.warn("Link native file closure failed type={}",failure.getClass().getSimpleName());}});}catch(RejectedExecutionException rejected){result.finish(rejected);}}
        ConsoleClient.INSTANCE.suppressLinkAutoStart();
    }
    private LinkPlayClient(){Runtime.getRuntime().addShutdownHook(new Thread(()->{Session s=active==null?closingSession:active;stop();worker.shutdown();dsTransfers.shutdown();try{if(!worker.awaitTermination(8,TimeUnit.SECONDS)&&s!=null)try{closeRuntime(s);}catch(IOException failure){MuxiConsole.LOG.warn("Link shutdown could not close native files type={}",failure.getClass().getSimpleName());}}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}},"playstation-link-shutdown"));}
}
