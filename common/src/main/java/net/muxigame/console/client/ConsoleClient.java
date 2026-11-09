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
import net.muxigame.console.client.input.FrameInputBuffer;
import net.muxigame.console.client.input.RealtimeFrameClock;
import net.muxigame.console.client.transfer.LoadProgress;
import net.muxigame.console.client.transfer.RomTransfer;
import net.muxigame.console.client.transfer.TransferFailure;
import net.muxigame.console.client.transfer.HttpMediaTransfer;
import net.muxigame.console.client.transfer.NativeProfileArchive;
import net.muxigame.console.client.transfer.StateSpool;
import net.muxigame.console.compat.BrowserLinkClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;

/** Own client state machine: GUI, server transport and isolated worker meet only here. */
public final class ConsoleClient implements ClientActions {
    public static final ConsoleClient INSTANCE=new ConsoleClient();
    private final ScheduledExecutorService emulator=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"muxi-console-emulation");t.setDaemon(true);return t;});
    private final ExecutorService files=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"muxi-console-file-io");t.setDaemon(true);return t;});
    private final Map<UUID,CompletableFuture<Wire.Message>> downloads=new ConcurrentHashMap<>();
    private final Set<Long> ownedWorkers=ConcurrentHashMap.newKeySet();
    private final Map<Long,ProcessConsoleRuntime> liveRuntimes=new ConcurrentHashMap<>();
    private volatile IOException standaloneNativeCloseFailure;
    private volatile long normalWorkerExits,forcedWorkerExits,failedWorkerExits;
    private volatile String restoredStateHash="",savedStateHash="";private volatile long restoredStates,savedStates;
    private volatile Session active;private volatile String status="就绪",lastError="";private volatile long displayedFrames,downloadsGranted,downloadsDenied;private volatile String frameCrc="";
    private DynamicTexture texture;private ResourceLocation picture;private int pictureWidth,pictureHeight;
    private volatile List<AvFrame.Surface> pictureSurfaces=List.of();private int pictureRotation;
    private volatile PortInput.Pointer pointerInput=PortInput.Pointer.IDLE;private volatile PortInput.Motion motionInput=PortInput.Motion.IDLE;private volatile PortInput.Microphone microphoneInput=PortInput.Microphone.SILENT;private volatile int controllerDevice=1;
    private RuntimeFiles runtimeFiles;
    private final ClientUploadCoordinator uploader=new ClientUploadCoordinator(()->files().root(),files,task->mc().execute(task),()->mc().getConnection(),(op,j,data)->Wire.command(op,j,data),text->status=text,text->{lastError=text;if(mc().player!=null)mc().player.displayClientMessage(Component.literal(text),true);},e->MuxiConsole.LOG.warn("Cloud upload failed detail={}",TransferFailure.diagnostic(e)));
    private java.util.function.Supplier<PortInput> inputSource;
    private volatile PortInput visualInput = PortInput.IDLE;
    private long inputSubmissions;
    private UUID observedHandheld,attemptedHandheld,suppressedHandheld;private boolean controlsReleased;
    public record ProgressSnapshot(UUID card,String stage,long bytes,long total,int percent,String text,boolean loading,String error){}
    private static final ProgressSnapshot IDLE_PROGRESS=new ProgressSnapshot(null,"idle",0,0,-1,"",false,"");
    private volatile ProgressSnapshot terminalProgress=IDLE_PROGRESS;private volatile long terminalProgressUntil;private volatile UUID terminalMachine;
    private static final class Session {
        final JsonObject context;final String room;final long epoch,position;final int port;final Platform platform;final String hash;final long size;final UUID card,machine;final boolean shared,leader;volatile boolean hasPicture;
        volatile ProcessConsoleRuntime runtime;final PcmPlayer audio=new PcmPlayer();final java.util.concurrent.atomic.AtomicInteger queued=new java.util.concurrent.atomic.AtomicInteger();volatile boolean ready,inputPending,closeRecorded,leaving;volatile long tick=-1,sequence=0,frame=0,lastInputTick=-1,lastCheckpointFrame=0;
        final LoadProgress progress;volatile String coreFingerprint="",profileGeneration="";volatile HttpMediaTransfer.RangeReader cloudReader;long lastLoadProgress,lastSaveProgress;volatile CompletableFuture<Void> checkpoint;volatile CompletableFuture<Long> savePaused;volatile SaveUpload saveUpload;volatile String saveTransaction="",saveStage="",saveOperation="";volatile long saveBytes,saveTotal;
        volatile long receivedBatches,completedBatches,acknowledgedBatches,firstBatchNanos,totalStepNanos,totalServiceNanos,maxServiceNanos,maxQueueWaitNanos,maxAckDelayNanos,receivedEndFrame;volatile int peakQueued;long lastFlowLogNanos;
        final boolean hostClock;final FrameInputBuffer localInput=new FrameInputBuffer(64,500_000_000L);final FrameInputBuffer[] remoteInputs={new FrameInputBuffer(64,300_000_000L),new FrameInputBuffer(64,300_000_000L),new FrameInputBuffer(64,300_000_000L),new FrameInputBuffer(64,300_000_000L)};
        volatile RealtimeFrameClock clock;DeadlineWaiter deadlineWaiter;volatile boolean clockQueued;volatile int peakClockTasks,peakPictureTasks;volatile boolean clockStarted,clockHold,snapshotPause;volatile String holdToken="",snapshotHoldToken="";volatile long clockStartedAt,localSequence,remoteSequence,lastRemoteSent,lastProgressSent,pictureUploads,coalescedPictures;final java.util.concurrent.atomic.AtomicReference<AvFrame> latestPicture=new java.util.concurrent.atomic.AtomicReference<>();final java.util.concurrent.atomic.AtomicBoolean picturePending=new java.util.concurrent.atomic.AtomicBoolean();PortInput lastRemoteInput=PortInput.IDLE;volatile long snapshotFrame=-1;final List<short[]> networkSounds=new ArrayList<>();int networkSamples;double networkRate;
        Session(JsonObject j){context=j.deepCopy();room=j.get("room").getAsString();epoch=j.get("epoch").getAsLong();position=j.get("position").getAsLong();port=j.get("port").getAsInt();platform=Platform.fromId(j.get("platform").getAsString());hash=j.get("hash").getAsString();size=j.get("size").getAsLong();card=UUID.fromString(j.get("instance").getAsString());machine=UUID.fromString(j.get("machine").getAsString());if(!new MachineSaveKey(machine,j.get("content").getAsString()).contentSHA().equals(hash))throw new IllegalArgumentException("Session game identity differs");shared=platform.sharedRuntime();leader=j.get("leader").getAsBoolean();hostClock=j.has("hostClock")&&j.get("hostClock").getAsInt()==1;progress=new LoadProgress(card);}
    }
    private static final class SaveUpload {
        final Path state;final long size;final String hash,transaction,holdToken;final NativeProfileArchive.Snapshot profile;final long frame;final CompletableFuture<Void> completed;UUID id;long offset,sentOffset=-1;boolean profilePhase,committing;
        SaveUpload(Path data,NativeProfileArchive.Snapshot profile,String transaction,long frame,CompletableFuture<Void> completed)throws IOException{this(data,profile,transaction,frame,completed,"");}
        SaveUpload(Path data,NativeProfileArchive.Snapshot profile,String transaction,long frame,CompletableFuture<Void> completed,String holdToken)throws IOException{state=data;size=Files.size(data);hash=Hashing.sha256(data);this.profile=profile;this.transaction=transaction;this.frame=frame;this.completed=completed;this.holdToken=holdToken;profilePhase=profile!=null;}
        Path file(){return profilePhase?profile.path():state;}long total(){return profilePhase?profile.size():size;}String digest(){return profilePhase?profile.hash():hash;}
    }
    public record Snapshot(String status,String room,long epoch,int port,boolean ready,long frames,String frameCrc,int width,int height,Set<Long> workers,long serverTick,long sequence,boolean workerRunning,String lastError,long downloadsGranted,long downloadsDenied,long normalWorkerExits,long forcedWorkerExits,long failedWorkerExits,String restoredStateHash,String savedStateHash,long restoredStates,long savedStates){}
    public Snapshot snapshot(){if(LinkPlayClient.INSTANCE.active())return LinkPlayClient.INSTANCE.snapshot();Session s=active;return new Snapshot(status,s==null?"":s.room,s==null?0:s.epoch,s==null?-1:s.port,s!=null&&s.ready,displayedFrames,frameCrc,pictureWidth,pictureHeight,Set.copyOf(ownedWorkers),s==null?-1:s.tick,s==null?0:s.sequence,s!=null&&s.runtime!=null&&s.runtime.isAlive(),lastError,downloadsGranted,downloadsDenied,normalWorkerExits,forcedWorkerExits,failedWorkerExits,restoredStateHash,savedStateHash,restoredStates,savedStates);}
    public record FrameFlowMetrics(String room,long epoch,Platform platform,boolean enabled,long receivedBatches,long completedBatches,long acknowledgedBatches,int queued,int peakQueued,long runningNanos,long totalStepNanos,long totalServiceNanos,long maxServiceNanos,long maxQueueWaitNanos,long maxAckDelayNanos,long receivedEndFrame,long completedFrame,double nominalFps){}
    /** Actual ordinary-session counters only; this does not sample or advance the worker. */
    public Optional<FrameFlowMetrics> frameFlowMetrics(){Session s=active;return s==null||s.shared&&!s.leader?Optional.empty():Optional.of(flowMetrics(s));}
    public record RealtimeMetrics(String room,long epoch,Platform platform,boolean hostClock,boolean clockStarted,boolean leaseValid,boolean hold,long coreFrame,double coreFps,long runningNanos,long lateFrames,long presentedFrames,long pictureUploads,long coalescedPictures,int pendingCoreTasks,int peakPendingCoreTasks,int pendingPictures,int peakPendingPictures,long totalStepNanos,FrameInputBuffer.Metrics localInput,List<FrameInputBuffer.Metrics> remoteInputs,PcmPlayer.Metrics audio){}
    public Optional<RealtimeMetrics> realtimeMetrics(){Session s=active;if(s==null)return Optional.empty();RealtimeFrameClock c=s.clock;long now=System.nanoTime();return Optional.of(new RealtimeMetrics(s.room,s.epoch,s.platform,s.hostClock,s.clockStarted,c!=null&&c.leased(now),s.clockHold,s.frame,s.runtime==null?0:s.runtime.info().fps(),s.clockStartedAt==0?0:now-s.clockStartedAt,c==null?0:c.lateFrames(),displayedFrames,s.pictureUploads,s.coalescedPictures,s.clockQueued?1:0,s.peakClockTasks,s.picturePending.get()?1:0,s.peakPictureTasks,s.totalStepNanos,s.localInput.metrics(),Arrays.stream(s.remoteInputs).map(FrameInputBuffer::metrics).toList(),s.audio.metrics()));}
    private static FrameFlowMetrics flowMetrics(Session s){return new FrameFlowMetrics(s.room,s.epoch,s.platform,frameFlow(s),s.receivedBatches,s.completedBatches,s.acknowledgedBatches,s.queued.get(),s.peakQueued,s.firstBatchNanos==0?0:System.nanoTime()-s.firstBatchNanos,s.totalStepNanos,s.totalServiceNanos,s.maxServiceNanos,s.maxQueueWaitNanos,s.maxAckDelayNanos,s.receivedEndFrame,s.frame,s.runtime==null?0:s.runtime.info().fps());}
    private static boolean frameFlow(Session s){return !s.hostClock&&s.context.has("frameFlow")&&s.context.get("frameFlow").getAsBoolean();}
    public Optional<MachineSaveKey> saveOwner(){if(LinkPlayClient.INSTANCE.active())return LinkPlayClient.INSTANCE.owner();Session s=active;return s==null?Optional.empty():Optional.of(new MachineSaveKey(s.machine,s.hash));}
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static JsonObject object(){return new JsonObject();}
    private RuntimeFiles files()throws IOException{if(runtimeFiles==null)runtimeFiles=new RuntimeFiles(mc().gameDirectory.toPath());return runtimeFiles;}
    public String status(){return LinkPlayClient.INSTANCE.active()?LinkPlayClient.INSTANCE.status():status;}
    private static ProgressSnapshot progress(LoadProgress.Snapshot p){return new ProgressSnapshot(p.card(),p.stage(),p.bytes(),p.total(),p.percent(),p.text(),p.loading(),p.error());}
    public ProgressSnapshot progressSnapshot(){if(LinkPlayClient.INSTANCE.active())return LinkPlayClient.INSTANCE.progress();Session s=active;return s!=null?progress(s.progress.snapshot()):System.nanoTime()<terminalProgressUntil?terminalProgress:IDLE_PROGRESS;}
    /** A progress display cannot attach to a different cartridge or a stowed handheld. */
    public ProgressSnapshot heldProgress(UUID card){if(LinkPlayClient.INSTANCE.active())return linkHeldMatches()&&card.equals(LinkPlayClient.INSTANCE.card())?LinkPlayClient.INSTANCE.progress():IDLE_PROGRESS;Held held=heldCartridge();Session s=active;ProgressSnapshot p=progressSnapshot();boolean machine=s!=null?sameHeld(held,s):held!=null&&Objects.equals(held.machine,terminalMachine);return machine&&held.card.instanceId().equals(card)&&card.equals(p.card())?p:IDLE_PROGRESS;}
    public void selectionChanged(){uploader.selectionChanged();}
    public void receive(Wire.Message message){
        if(LinkPlayClient.INSTANCE.receive(message))return;
        if(SharedPlayClient.INSTANCE.receive(message))return;
        if(BrowserLinkClient.INSTANCE.receive(message))return;
        if(uploader.receive(message.operation(),message.body()))return;
        JsonObject b=message.body();
        switch(message.operation()){
            case "writer_open" -> {leave();status=mc().screen instanceof WriterScreen?"请选择游戏文件":"请重新打开刻录机";}
            case "device_open" -> mc().setScreen(new DeviceScreen(b,this));
            case "handheld_config" -> mc().setScreen(new HandheldModelScreen(b));
            case "device_state" -> {if(mc().screen instanceof DeviceScreen)mc().setScreen(new DeviceScreen(b,this));}
            case "error" -> {
                if(b.has("request")){UUID request=UUID.fromString(b.get("request").getAsString());CompletableFuture<Wire.Message> pending=downloads.get(request);if(pending==null)return;if(b.has("operation")&&b.get("operation").getAsString().equals("rom_read"))return;downloads.remove(request);pending.completeExceptionally(new DomainException(b.has("code")?b.get("code").getAsString():"request",b.get("message").getAsString()));return;}
                if(b.has("room")&&b.has("epoch")){Session target=active;if(target==null||!target.room.equals(b.get("room").getAsString())||target.epoch!=b.get("epoch").getAsLong())return;}
                Session saving=active;if(b.has("operation")&&(b.get("operation").getAsString().startsWith("state_")||b.get("operation").getAsString().startsWith("profile_")||b.get("operation").getAsString().equals("save_progress"))){if(saving==null||!b.has("saveTransaction")||!saving.saveTransaction.equals(b.get("saveTransaction").getAsString()))return;failCheckpoint(saving,new IOException(b.get("message").getAsString()));}
                status=b.get("message").getAsString();lastError=status;if(mc().player!=null)mc().player.displayClientMessage(Component.literal(status),true);
            }
            case "rom_chunk","rom_denied","rom_ticket","rom_transport","state_chunk","state_denied","profile_chunk","profile_denied" -> {if(message.operation().equals("rom_denied"))downloadsDenied++;else if(message.operation().equals("rom_chunk"))downloadsGranted++;UUID id=UUID.fromString(b.get("request").getAsString());CompletableFuture<Wire.Message> f=downloads.remove(id);if(f!=null)f.complete(message);}
            case "checkpoint_request" -> {Session s=active;if(s!=null&&s.room.equals(b.get("room").getAsString())&&s.epoch==b.get("epoch").getAsLong()){if(b.has("hold")&&b.get("hold").getAsBoolean()){s.holdToken=UUID.fromString(b.get("holdToken").getAsString()).toString();s.clockHold=true;}status="正在保存并同步会话…";CompletableFuture<Void> previous=s.checkpoint;if(previous==null)checkpoint(s);else previous.whenComplete((v,e)->mc().execute(()->{if(active==s)checkpoint(s);}));}}
            case "save_paused" -> {Session s=active;if(s!=null&&s.savePaused!=null&&checkpointReply(s,b)){long frame=b.get("frame").getAsLong();boolean token=s.snapshotHoldToken.isEmpty()?(!b.has("holdToken")||b.get("holdToken").getAsString().isEmpty()):b.has("holdToken")&&s.snapshotHoldToken.equals(b.get("holdToken").getAsString());if(frame>=0&&(!s.hostClock||token))s.savePaused.complete(frame);}}
            case "state_ack","profile_ack" -> saveAcknowledged(b,message.operation().equals("profile_ack"));
            case "state_committed","profile_committed" -> saveCommitted(b,message.operation().equals("profile_committed"));
            case "session_start" -> start(b);
            case "clock_start","clock_lease","clock_input","clock_resume" -> clockMessage(message);
            case "input_ack" -> {Session s=active;if(s!=null&&s.room.equals(b.get("room").getAsString())&&s.epoch==b.get("epoch").getAsLong()){s.sequence=b.get("nextSequence").getAsLong();s.inputPending=false;if(!b.get("accepted").getAsBoolean())s.lastInputTick=b.get("nextTick").getAsLong()-1;}}
            case "session_end" -> {Session s=active;if(b.has("room")&&(s==null||!s.room.equals(b.get("room").getAsString())||b.has("epoch")&&s.epoch!=b.get("epoch").getAsLong()))return;status=b.get("reason").getAsString();stop();if(mc().screen instanceof GameScreen)mc().setScreen(null);}
            case "frames" -> frames(b,message.data());
            default -> { }
        }
    }
    public void upload(JsonObject context,Path file,Platform selected,boolean rights){uploader.upload(context,file,List.of(),selected,rights);}
    public void upload(JsonObject context,Path file,List<Path> dependencies,Platform selected,boolean rights){uploader.upload(context,file,dependencies,selected,rights);}
    public void cancelWriter(JsonObject context){uploader.cancelWriter(context);}
    private static HttpMediaTransfer.Ticket mediaTicket(JsonObject ticket){try{Map<String,String> headers=new LinkedHashMap<>();for(var header:ticket.getAsJsonObject("headers").entrySet())headers.put(header.getKey(),header.getValue().getAsString());return new HttpMediaTransfer.Ticket(ticket.get("method").getAsString(),java.net.URI.create(ticket.get("url").getAsString()),headers,ticket.get("expiresAt").getAsLong());}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid cloud media ticket");}}
    public CompletableFuture<Optional<byte[]>> probeDownload(String hash,String room,long epoch){
        UUID request=UUID.randomUUID();CompletableFuture<Wire.Message> result=new CompletableFuture<>();downloads.put(request,result);JsonObject j=object();j.addProperty("request",request.toString());j.addProperty("hash",hash);j.addProperty("offset",0);if(room!=null&&!room.isEmpty()){j.addProperty("room",room);j.addProperty("epoch",epoch);}mc().execute(()->Wire.command("rom_read",j));
        return result.orTimeout(15,TimeUnit.SECONDS).thenApply(m->m.operation().equals("rom_denied")?Optional.<byte[]>empty():Optional.of(m.data())).whenComplete((v,e)->downloads.remove(request));
    }
    private boolean loading(Session s){return active==s&&!s.leaving;}
    private Wire.Message readPiece(Session s,String operation,JsonObject request)throws Exception{
        for(int attempt=0;attempt<3;attempt++){
            RomTransfer.requireActive(()->loading(s));UUID id=UUID.randomUUID();var result=new CompletableFuture<Wire.Message>();downloads.put(id,result);JsonObject piece=request.deepCopy();piece.addProperty("request",id.toString());
            mc().execute(()->{if(loading(s)&&!result.isDone())Wire.command(operation,piece);else result.cancel(false);});
            try{return result.get(15,TimeUnit.SECONDS);}catch(TimeoutException timeout){if(attempt==2)throw timeout;}finally{downloads.remove(id);result.cancel(false);}
        }throw new IOException("卡带传输未完成");
    }
    private static String cacheExtension(Platform platform){return platform.extensions().stream().sorted().findFirst().orElseThrow();}
    private static String cacheExtension(Session session)throws IOException{if(!session.context.has("extension"))return cacheExtension(session.platform);String hint=session.context.get("extension").getAsString();if(!hint.matches("[a-z0-9]+")||!session.platform.acceptsExtension(hint))throw new IOException("卡带原始格式与当前机型不匹配");return hint;}
    private static boolean sharedPublishing(Session s){return s.shared&&s.leader&&(!s.context.has("sharedPeers")||s.context.get("sharedPeers").getAsInt()>0);}
    private Wire.Message romTicket(Session s)throws Exception{JsonObject request=s.context.deepCopy();request.addProperty("hash",s.hash);Wire.Message reply=readPiece(s,"rom_ticket",request);if(reply.operation().equals("rom_ticket")&&(!reply.body().get("hash").getAsString().equals(s.hash)||reply.body().get("size").getAsLong()!=s.size))throw new IOException("云端卡带身份与当前卡带不符");return reply;}
    private RomTransfer.Chunk localRomPiece(Session s,long offset)throws Exception{
        JsonObject j=object();j.addProperty("room",s.room);j.addProperty("epoch",s.epoch);j.addProperty("hash",s.hash);j.addProperty("offset",offset);Wire.Message reply=readPiece(s,"rom_read",j);
        if(reply.operation().equals("rom_denied"))throw new DomainException(reply.body().has("code")?reply.body().get("code").getAsString():"permission",reply.body().has("message")?reply.body().get("message").getAsString():"服务器拒绝提供此卡带内容");
        if(!reply.operation().equals("rom_chunk"))throw new IOException("卡带下载格式无效");return new RomTransfer.Chunk(reply.body().get("offset").getAsLong(),reply.body().get("size").getAsLong(),reply.body().get("hash").getAsString(),reply.data());
    }
    private Path rom(Session s)throws Exception{
        boolean tickets=s.context.has("romTickets")&&s.context.get("romTickets").getAsBoolean();boolean[] local={!tickets};
        try{return RomTransfer.receive(files().root().resolve("roms"),s.hash,cacheExtension(s),s.size,offset->{
            if(local[0])return localRomPiece(s,offset);
            if(s.cloudReader==null){Wire.Message first=romTicket(s);if(first.operation().equals("rom_transport")&&first.body().get("transport").getAsString().equals("local")){local[0]=true;return localRomPiece(s,offset);}if(!first.operation().equals("rom_ticket"))throw new IOException("卡带传输凭证无效");
                java.util.concurrent.atomic.AtomicReference<HttpMediaTransfer.Ticket> initial=new java.util.concurrent.atomic.AtomicReference<>(mediaTicket(first.body().getAsJsonObject("downloadTicket")));
                s.cloudReader=new HttpMediaTransfer.RangeReader(s.hash,s.size,position->{HttpMediaTransfer.Ticket ticket=initial.getAndSet(null);if(ticket!=null)return ticket;Wire.Message refreshed=romTicket(s);if(!refreshed.operation().equals("rom_ticket"))throw new IOException("卡带存储已改变，请重新拿起");return mediaTicket(refreshed.body().getAsJsonObject("downloadTicket"));},()->loading(s));
            }return s.cloudReader.read(offset);
        },()->loading(s),s.progress,tickets?RomTransfer.STREAM_CHUNK_BYTES:RomTransfer.CHUNK_BYTES);}finally{if(s.cloudReader!=null){s.cloudReader.close();s.cloudReader=null;}}
    }
    private void start(JsonObject context){
        Session existing=active;boolean open=!context.has("open")||context.get("open").getAsBoolean();
        if(existing!=null&&existing.room.equals(context.get("room").getAsString())&&existing.epoch==context.get("epoch").getAsLong()){
            if(open){controlsReleased=false;suppressedHandheld=null;showGame(existing);}return;
        }
        boolean sameRoom=existing!=null&&existing.room.equals(context.get("room").getAsString());
        stop();Session s=new Session(context);active=s;lastError="";terminalProgress=IDLE_PROGRESS;terminalProgressUntil=0;terminalMachine=null;if(!sameRoom)controlsReleased=false;status="正在准备 "+s.platform.id().toUpperCase(Locale.ROOT)+"…";
        boolean held=context.has("presentation")&&context.get("presentation").getAsString().equals("held");
        if(held)controlsReleased=true;
        if(open&&!held){controlsReleased=false;suppressedHandheld=null;showGame(s);}
        restoredStateHash="";if(s.shared&&!s.leader){s.progress.begin(LoadProgress.Stage.SHARED,0);status="正在连接主机画面与控制席…";SharedPlayClient.INSTANCE.start(context);return;}
        emulator.execute(()->{try{
            if(s.context.has("saveError"))throw new IOException(s.context.get("saveError").getAsString());
            Path rom=rom(s);RomTransfer.requireActive(()->loading(s));LinkPlayClient.INSTANCE.awaitNativeFilesClosed();RomTransfer.requireActive(()->loading(s));requireStandaloneNativeFilesClosed();restoreProfile(s);s.progress.begin(LoadProgress.Stage.STARTING,0);RuntimeFiles.Selected config=files().select(s.platform,rom,new MachineSaveKey(s.machine,s.hash));s.coreFingerprint=config.fingerprint();RomTransfer.requireActive(()->loading(s));ProcessConsoleRuntime runtime;
            try{runtime=ProcessConsoleRuntime.open(config.launch());}catch(IOException|RuntimeException startup){if(hasCleanupFailure(startup))standaloneClosureFailed(startup);throw startup;}
            s.runtime=runtime;ownedWorkers.add(runtime.workerPid());liveRuntimes.put(runtime.workerPid(),runtime);
            if(!loading(s)){close(s);return;}
            if(s.context.has("state")){
                JsonObject saved=s.context.getAsJsonObject("state");if(!saved.get("machine").getAsString().equals(s.machine.toString())||!saved.get("content").getAsString().equals(s.hash))throw new IOException("存档不属于当前机器和游戏");
                boolean profileMatches=!nativeFiles(s)||!s.profileGeneration.isEmpty()&&saved.has("profileGeneration")&&s.profileGeneration.equals(saved.get("profileGeneration").getAsString());
                if(!profileMatches){MuxiConsole.LOG.info("Safe native-files boot: quickstate profile generation differs platform={}",s.platform.id());mc().execute(()->{if(active==s&&mc().player!=null)mc().player.displayClientMessage(Component.literal("机器文件已更新，已使用游戏内存档启动"),true);});}
                else if(!saved.get("fingerprint").getAsString().equals(config.fingerprint())&&s.platform==Platform.N3DS&&nativeFiles(s)){
                    MuxiConsole.LOG.info("Safe native-files boot: preserved incompatible quickstate platform={} machine={} previousFingerprint={} currentFingerprint={}",s.platform.id(),s.machine,saved.get("fingerprint").getAsString(),config.fingerprint());
                    mc().execute(()->{if(active==s&&mc().player!=null)mc().player.displayClientMessage(Component.literal("模拟器已更新，原即时存档已保留，现使用本机游戏内存档启动"),true);});
                }else{
                    if(!saved.get("fingerprint").getAsString().equals(config.fingerprint()))throw new IOException("存档使用了不同版本的模拟器核心，请恢复原核心后再读取");Path restored=downloadArtifact(s,saved,"state",stateLimit(s));
                    Path expanded=null;try{RomTransfer.requireActive(()->loading(s));s.progress.begin(LoadProgress.Stage.RESTORING,0);expanded=StateSpool.expand(restored,newSpool(),stateLimit(s),()->loading(s),bytes->{});try{runtime.loadStateFrom(expanded);if(loading(s)){restoredStateHash=saved.get("hash").getAsString();restoredStates++;}}catch(IOException mismatch){if(!NativeStateBinding.mismatch(mismatch))throw mismatch;MuxiConsole.LOG.info("Safe native-files boot: quickstate native format or directory binding differs platform={}",s.platform.id());mc().execute(()->{if(active==s&&mc().player!=null)mc().player.displayClientMessage(Component.literal("即时存档格式或运行目录已变化，已使用本机游戏内存档启动"),true);});}}finally{if(expanded!=null&&!expanded.equals(restored))Files.deleteIfExists(expanded);Files.deleteIfExists(restored);}
                }
            }
            if(!loading(s)){close(s);return;}s.ready=true;s.progress.begin(LoadProgress.Stage.WAITING,0);JsonObject j=s.context.deepCopy();j.addProperty("fingerprint",config.fingerprint());j.addProperty("fps",runtime.info().fps());if(s.hostClock){j.remove("frameFlow");j.addProperty("hostClock",1);}else if(frameFlow(s))j.addProperty("frameFlow",1);if(!s.profileGeneration.isEmpty())j.addProperty("profileGeneration",s.profileGeneration);mc().execute(()->{if(loading(s)){status="等待其他控制器准备…";Wire.command("ready",j);}});
        }catch(Exception e){failed(s,e);}});
    }
    private void frames(JsonObject b,byte[] inputBytes){
        Session s=active;if(s==null||!s.ready||!s.room.equals(b.get("room").getAsString())||s.epoch!=b.get("epoch").getAsLong())return;
        if(s.hostClock)return;
        s.tick=b.get("tick").getAsLong();if(s.shared&&!s.leader)return;long enqueued=System.nanoTime();if(s.firstBatchNanos==0)s.firstBatchNanos=enqueued;s.receivedBatches++;int queued=s.queued.incrementAndGet();s.peakQueued=Math.max(s.peakQueued,queued);
        if(queued>120){MuxiConsole.LOG.warn("Console frame queue overflow metrics={}",flowMetrics(s));failed(s,new IOException("模拟器跟不上服务器，会话已停止"));return;}
        if(enqueued-s.lastFlowLogNanos>TimeUnit.SECONDS.toNanos(5)){s.lastFlowLogNanos=enqueued;MuxiConsole.LOG.info("Console frame flow metrics={}",flowMetrics(s));}
        JsonObject packet=b.deepCopy();s.receivedEndFrame=packet.get("frame").getAsLong()+packet.get("count").getAsInt();emulator.execute(()->{long serviceStart=System.nanoTime();try{
            if(active!=s)return;long first=packet.get("frame").getAsLong();if(first!=s.frame)throw new IOException("模拟帧序号不连续");int count=packet.get("count").getAsInt();if(count<0||count>6)throw new IOException("模拟帧批次无效");
            s.maxQueueWaitNanos=Math.max(s.maxQueueWaitNanos,serviceStart-enqueued);
            InputFrame input;if(inputBytes.length>0)input=InputCodec.decodeFrame(inputBytes);else{List<PortInput> ports=new ArrayList<>();for(JsonElement row:packet.getAsJsonArray("ports")){JsonArray a=row.getAsJsonArray();ports.add(new PortInput(a.get(0).getAsInt(),a.get(1).getAsShort(),a.get(2).getAsShort(),a.get(3).getAsShort(),a.get(4).getAsShort()));}input=new InputFrame(ports);}
            AvFrame result=null;java.util.List<short[]> sounds=new ArrayList<>();double soundRate=0;int soundSamples=0;
            for(int i=0;i<count;i++){long stepStart=System.nanoTime();result=s.runtime.step(i==0?input:input.withoutMicrophone());s.totalStepNanos+=System.nanoTime()-stepStart;s.audio.offer(result.stereoPcm(),result.sampleRate());if(sharedPublishing(s)||BrowserLinkClient.INSTANCE.publishing()){if(soundRate!=0&&soundRate!=result.sampleRate())throw new IOException("模拟器在一批帧内改变了音频格式");soundRate=result.sampleRate();sounds.add(result.stereoPcm());soundSamples+=result.stereoPcm().length;}s.frame++;}
            if(active==s&&(sharedPublishing(s)||BrowserLinkClient.INSTANCE.publishing())&&soundSamples>0){if(soundSamples>2_000_000)throw new IOException("主机音频批次过大");short[] sound=new short[soundSamples];int offset=0;for(short[] piece:sounds){System.arraycopy(piece,0,sound,offset,piece.length);offset+=piece.length;}if(sharedPublishing(s))SharedPlayClient.INSTANCE.audio(s.frame,sound,soundRate);if(BrowserLinkClient.INSTANCE.publishing())BrowserLinkClient.INSTANCE.audio(s.frame,sound,soundRate);}
            if(result!=null){AvFrame f=result;CRC32 crc=new CRC32();crc.update(f.rgba());String digest=Long.toHexString(crc.getValue());long number=s.frame;
                long checkpointFrames=nativeFiles(s)?Math.max(300,Math.round(s.runtime.info().fps()*60)):300;
                if(s.leader&&number-s.lastCheckpointFrame>=checkpointFrames){s.lastCheckpointFrame=number;mc().execute(()->{if(active==s)checkpoint(s);});}
                if(!s.shared&&number%120<count){JsonObject check=s.context.deepCopy();check.addProperty("frame",number);check.addProperty("crc",digest);mc().execute(()->{if(active==s)Wire.command("frame_crc",check);});}
                mc().execute(()->{if(active==s){displayedFrames=number;frameCrc=digest;updatePicture(f);status="玩家 "+(s.port+1)+" · "+s.platform.id().toUpperCase(Locale.ROOT);}});
            }
            long elapsed=System.nanoTime()-serviceStart;s.completedBatches++;s.totalServiceNanos+=elapsed;s.maxServiceNanos=Math.max(s.maxServiceNanos,elapsed);
            if(frameFlow(s)){long completed=s.frame,tick=packet.get("tick").getAsLong(),finished=System.nanoTime();mc().execute(()->{if(active==s&&s.ready){JsonObject done=object();done.addProperty("room",s.room);done.addProperty("epoch",s.epoch);done.addProperty("tick",tick);done.addProperty("frame",completed);Wire.command("frame_done",done);s.acknowledgedBatches++;s.maxAckDelayNanos=Math.max(s.maxAckDelayNanos,System.nanoTime()-finished);}});}
        }catch(Exception e){failed(s,e);}finally{s.queued.decrementAndGet();}});
    }
    private static boolean clockAddress(Session s,JsonObject b){return s!=null&&s.hostClock&&b.has("room")&&s.room.equals(b.get("room").getAsString())&&b.has("epoch")&&s.epoch==b.get("epoch").getAsLong();}
    private void clockMessage(Wire.Message message){
        Session s=active;JsonObject b=message.body();if(!clockAddress(s,b)||!s.leader||!s.ready)return;
        try{
            if(message.operation().equals("clock_resume")){if(s.clockHold&&s.holdToken.equals(b.get("holdToken").getAsString())){s.clockHold=false;s.holdToken="";emulator.execute(()->{if(active==s&&s.clock!=null){s.clock.reanchor(System.nanoTime());scheduleClock(s);}});}return;}
            if(message.operation().equals("clock_input")){
                int port=b.get("port").getAsInt();if(port<0||port>=4||port==s.port||b.get("inputVersion").getAsInt()!=InputCodec.VERSION)throw new IOException("远端输入接口无效");
                s.remoteInputs[port].publish(b.get("sequence").getAsLong(),InputCodec.decode(message.data()),System.nanoTime());return;
            }
            if(b.get("port").getAsInt()!=s.port)throw new IOException("本地主机时钟权限无效");
            long lease=b.get("leaseMillis").getAsLong();boolean start=message.operation().equals("clock_start");InputFrame initial=message.data().length==0?InputFrame.idle():InputCodec.decodeFrame(message.data());
            double fps=start?b.get("fps").getAsDouble():s.runtime.info().fps();long frame=start?b.get("frame").getAsLong():-1;
            emulator.execute(()->{try{if(active!=s||!s.ready)return;long now=System.nanoTime();if(start){if(s.clockStarted)return;if(frame!=s.frame||frame!=0||Math.abs(fps-s.runtime.info().fps())>.001)throw new IOException("本地时钟初始帧或速率无效");s.deadlineWaiter=DeadlineWaiter.create();s.clock=new RealtimeFrameClock(fps);s.clockStarted=true;s.clockStartedAt=now;for(int p=0;p<4;p++)if(p!=s.port)s.remoteInputs[p].publish(0,initial.port(p),now);}if(s.clock==null)return;boolean expired=!s.clock.leased(now);s.clock.renew(now,lease);if(expired&&!start){s.localInput.clear();for(var remote:s.remoteInputs)remote.clear();}scheduleClock(s);}catch(Exception failure){failed(s,failure);}});
        }catch(Exception failure){failed(s,failure);}
    }
    /** One FIFO frame task, with a session-owned high-resolution wait; no scheduled-executor coarse timer. */
    private void scheduleClock(Session s){
        if(active!=s||!s.ready||!s.clockStarted||s.clockHold||s.snapshotPause||s.clockQueued)return;
        long now=System.nanoTime(),wait=s.clock.delay(now);if(wait<0){s.localInput.clear();for(var remote:s.remoteInputs)remote.clear();return;}
        s.clockQueued=true;s.peakClockTasks=Math.max(s.peakClockTasks,1);long deadline=now+wait;
        emulator.execute(()->{try{if(active==s&&!s.clockHold&&!s.snapshotPause)s.deadlineWaiter.awaitUntil(deadline);s.clockQueued=false;stepClock(s);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();s.clockQueued=false;failed(s,new IOException("本地游戏时钟等待被中断",interrupted));}catch(Exception failure){s.clockQueued=false;failed(s,failure);}});
    }
    private void stepClock(Session s){
        if(active!=s||!s.ready||s.clockHold||s.snapshotPause)return;long now=System.nanoTime();if(!s.clock.leased(now)){s.localInput.clear();for(var remote:s.remoteInputs)remote.clear();return;}
        try{
            List<PortInput> ports=new ArrayList<>(4);for(int p=0;p<4;p++)ports.add((p==s.port?s.localInput:s.remoteInputs[p]).take(now,s.frame+1));
            long began=System.nanoTime();AvFrame frame=s.runtime.step(new InputFrame(ports));s.totalStepNanos+=System.nanoTime()-began;s.frame++;s.completedBatches++;short[] pcm=frame.stereoPcm();s.audio.offer(pcm,frame.sampleRate());
            if(sharedPublishing(s)||BrowserLinkClient.INSTANCE.publishing()){if(!s.networkSounds.isEmpty()&&s.networkRate!=frame.sampleRate())throw new IOException("核心在音频批次中改变采样率");s.networkRate=frame.sampleRate();s.networkSounds.add(pcm);s.networkSamples+=pcm.length;if(s.networkSounds.size()>=3){short[] sound=new short[s.networkSamples];int at=0;for(short[] piece:s.networkSounds){System.arraycopy(piece,0,sound,at,piece.length);at+=piece.length;}s.networkSounds.clear();s.networkSamples=0;if(sharedPublishing(s))SharedPlayClient.INSTANCE.audio(s.frame,sound,s.networkRate);if(BrowserLinkClient.INSTANCE.publishing())BrowserLinkClient.INSTANCE.audio(s.frame,sound,s.networkRate);}}
            offerPicture(s,frame);long checkpointFrames=nativeFiles(s)?Math.max(300,Math.round(s.runtime.info().fps()*60)):300;
            if(s.frame-s.lastCheckpointFrame>=checkpointFrames&&s.checkpoint==null){s.lastCheckpointFrame=s.frame;mc().execute(()->{if(active==s&&s.checkpoint==null)checkpoint(s);});}
            long finished=System.nanoTime();if(finished-s.lastProgressSent>=250_000_000L){s.lastProgressSent=finished;long completed=s.frame;mc().execute(()->{if(active==s&&s.ready){JsonObject progress=s.context.deepCopy();progress.addProperty("frame",completed);Wire.command("clock_progress",progress);}});}
            s.clock.completed(finished);scheduleClock(s);
        }catch(Exception failure){failed(s,failure);}
    }
    private void offerPicture(Session s,AvFrame frame){
        if(s.latestPicture.getAndSet(frame)!=null)s.coalescedPictures++;
        if(s.picturePending.compareAndSet(false,true)){s.peakPictureTasks=Math.max(s.peakPictureTasks,1);mc().execute(()->drawLatest(s));}
    }
    private void drawLatest(Session s){
        try{AvFrame frame=s.latestPicture.getAndSet(null);if(active==s&&frame!=null){CRC32 crc=new CRC32();crc.update(frame.rgba());displayedFrames=frame.number()+1;frameCrc=Long.toHexString(crc.getValue());updatePicture(frame);s.pictureUploads++;status="玩家 "+(s.port+1)+" · "+s.platform.id().toUpperCase(Locale.ROOT);}}
        finally{s.picturePending.set(false);if(active==s&&s.latestPicture.get()!=null)emulator.execute(()->{if(active==s&&s.latestPicture.get()!=null&&s.picturePending.compareAndSet(false,true))mc().execute(()->drawLatest(s));});}
    }
    private void showGame(Session s){
        if(mc().screen==null||mc().screen instanceof GameScreen||mc().screen instanceof DeviceScreen||mc().screen instanceof DockScreen){
            // The game view is not a container. Retire its menu before returning to ordinary inventory input.
            if(mc().screen instanceof DockScreen&&mc().player!=null&&mc().player.containerMenu instanceof DockMenu)mc().player.closeContainer();
            mc().setScreen(new GameScreen(this,s.port));
        }
    }
    private record Held(net.minecraft.world.InteractionHand hand,Cartridge card,UUID machine){}
    private static boolean sameHeld(Held held,Session session){return held!=null&&session.card.equals(held.card.instanceId())&&session.machine.equals(held.machine);}
    private Held heldCartridge(){
        if(mc().player==null||!mc().player.isAlive()||mc().player.isSpectator())return null;
        Held first=null;Session s=active;
        for(var hand:net.minecraft.world.InteractionHand.values()){var item=mc().player.getItemInHand(hand);if(!item.is(ConsoleContent.HANDHELD.get())||HardwareCatalog.model(item)==0)continue;var card=CardData.read(item);if(card.isEmpty())continue;Held held=new Held(hand,card.get(),MachineData.read(item).orElse(null));if(s!=null&&s.room.startsWith("handheld/")&&sameHeld(held,s))return held;if(first==null)first=held;}return first;
    }
    public boolean handheldSession(){if(LinkPlayClient.INSTANCE.active())return true;Session s=active;return s!=null&&s.room.startsWith("handheld/");}
    public boolean heldControlsReleased(){return controlsReleased;}
    public boolean heldWorldPlaying(){if(LinkPlayClient.INSTANCE.active())return LinkPlayClient.INSTANCE.ready()&&linkHeldMatches();Session s=active;Held held=heldCartridge();return s!=null&&s.ready&&!s.leaving&&s.room.startsWith("handheld/")&&sameHeld(held,s);}
    /** Exiting fullscreen returns input to the world without giving up the controller seat. */
    public void releaseControls(){net.muxigame.console.client.input.NativeControls.clear();controlsReleased=true;inputSource=null;clearInputVisual();Session s=active;if(s!=null)s.localInput.clear();submitInput(neutralInput());}
    public void resumeControls(){controlsReleased=false;}
    public record InputFeedback(String room,Platform platform,UUID card,boolean focused,boolean controlsReleased,PortInput input,long sentCount){}
    public InputFeedback inputFeedback(){if(LinkPlayClient.INSTANCE.active())return new InputFeedback(LinkPlayClient.INSTANCE.snapshot().room(),LinkPlayClient.INSTANCE.platform(),LinkPlayClient.INSTANCE.card(),mc().screen instanceof GameScreen,controlsReleased,localInputVisual(),inputSubmissions);Session s=active;return new InputFeedback(s==null?"":s.room,s==null?null:s.platform,s==null?null:s.card,mc().screen instanceof GameScreen,controlsReleased,localInputVisual(),inputSubmissions);}
    /** Read-only level state from the same local sample submitted to the current session. */
    public PortInput localInputVisual(){if(LinkPlayClient.INSTANCE.active())return LinkPlayClient.INSTANCE.ready()&&!controlsReleased&&mc().screen instanceof GameScreen&&(inputSource!=null||mc().isWindowActive())?visualInput:PortInput.IDLE;Session s=active;return s!=null&&s.ready&&!s.leaving&&!controlsReleased&&mc().screen instanceof GameScreen&&(inputSource!=null||mc().isWindowActive())?visualInput:PortInput.IDLE;}
    public PortInput heldInputVisual(UUID card){if(LinkPlayClient.INSTANCE.active())return card.equals(LinkPlayClient.INSTANCE.card())?localInputVisual():PortInput.IDLE;Session s=active;return s!=null&&s.card.equals(card)?localInputVisual():PortInput.IDLE;}
    public PortInput deviceInputVisual(long position){Session s=active;return s!=null&&s.position==position?localInputVisual():PortInput.IDLE;}
    public int visualPort(){if(LinkPlayClient.INSTANCE.active())return LinkPlayClient.INSTANCE.ready()&&!controlsReleased&&mc().screen instanceof GameScreen?0:-1;Session s=active;return s!=null&&s.ready&&!s.leaving&&!controlsReleased&&mc().screen instanceof GameScreen?s.port:-1;}
    public void clearInputVisual(){visualInput=PortInput.IDLE;clearPointer();motionInput=PortInput.Motion.IDLE;microphoneInput=PortInput.Microphone.SILENT;Session s=active;if(s!=null)s.localInput.clear();}
    public List<AvFrame.Surface> surfaces(){if(LinkPlayClient.INSTANCE.active())return LinkPlayClient.INSTANCE.hasPicture()?pictureSurfaces:List.of();Session s=active;return s!=null&&s.ready&&s.hasPicture?pictureSurfaces:List.of();}
    public void clearPointer(){pointerInput=PortInput.Pointer.IDLE;}
    public void pointer(String surface,double x,double y,boolean pressed){
        Session s=active;boolean ready=LinkPlayClient.INSTANCE.active()?LinkPlayClient.INSTANCE.ready():s!=null&&s.ready&&!s.leaving;
        if(!ready||controlsReleased||!(mc().screen instanceof GameScreen)||!Double.isFinite(x)||!Double.isFinite(y)||x<0||x>1||y<0||y>1||surfaces().stream().noneMatch(p->p.id().equals(surface)&&p.touchable())){clearPointer();return;}
        double u=x,v=y;if(pictureRotation==1){u=1-y;v=x;}else if(pictureRotation==2){u=1-x;v=1-y;}else if(pictureRotation==3){u=y;v=1-x;}pointerInput=new PortInput.Pointer(surface,(short)Math.round(u*65534-32767),(short)Math.round(v*65534-32767),pressed,true);sampleControls();
    }
    public void motion(PortInput.Motion motion){motionInput=Objects.requireNonNull(motion);}
    public void microphone(PortInput.Microphone microphone){microphoneInput=Objects.requireNonNull(microphone);}
    public void controllerDevice(int device){new PortInput(0,(short)0,(short)0,(short)0,(short)0,device,PortInput.Pointer.IDLE,PortInput.Motion.IDLE,PortInput.Microphone.SILENT);controllerDevice=device;}
    private PortInput neutralInput(){return new PortInput(0,(short)0,(short)0,(short)0,(short)0,controllerDevice,PortInput.Pointer.IDLE,PortInput.Motion.IDLE,PortInput.Microphone.SILENT);}

    public void openModelFullscreen(){if(LinkPlayClient.INSTANCE.active()){controlsReleased=false;LinkPlayClient.INSTANCE.show();return;}Session s=active;if(s!=null&&!s.leaving){controlsReleased=false;mc().setScreen(new GameScreen(this,s.port));}}
    public void tick(){
        if(mc().player==null||mc().level==null||mc().getConnection()==null){observedHandheld=null;attemptedHandheld=null;suppressedHandheld=null;uploader.disconnected();if(active!=null)stop();return;}
        uploader.tick();
        if(LinkPlayClient.INSTANCE.tick())return;
        Held held=heldCartridge();UUID selected=held==null?null:held.machine==null?held.card.instanceId():held.machine;
        if(!Objects.equals(observedHandheld,selected)){observedHandheld=selected;attemptedHandheld=null;suppressedHandheld=null;}
        Session s=active;
        if(s!=null&&s.room.startsWith("handheld/")&&!sameHeld(held,s)){
            submitInput(PortInput.IDLE);leave();if(mc().screen instanceof GameScreen)mc().setScreen(null);return;
        }
        if(s==null){
            if(held!=null&&mc().screen==null&&!mc().player.isShiftKeyDown()&&!selected.equals(attemptedHandheld)&&!selected.equals(suppressedHandheld)){
                attemptedHandheld=selected;JsonObject request=object();request.addProperty("hand",held.hand.name());request.addProperty("presentation","held");Wire.command("handheld_start",request);
            }return;
        }
        var loading=s.progress.snapshot();long time=System.nanoTime();if(s.context.has("loadProgress")&&s.context.get("loadProgress").getAsBoolean()&&!s.leaving&&loading.loading()&&time-s.lastLoadProgress>=TimeUnit.SECONDS.toNanos(1)){s.lastLoadProgress=time;JsonObject report=object();report.addProperty("room",s.room);report.addProperty("epoch",s.epoch);report.addProperty("stage",loading.stage());report.addProperty("bytes",loading.bytes());report.addProperty("total",loading.total());Wire.command("load_progress",report);}
        if(s.checkpoint!=null&&!s.saveTransaction.isEmpty()&&time-s.lastSaveProgress>=TimeUnit.SECONDS.toNanos(1))saveProgress(s,time);
        if(!s.ready){clearInputVisual();return;}
        sampleControls();
    }
    /** Called by real Screen callbacks and render polling, always on the Minecraft thread. */
    public void sampleControls(){
        if(!mc().isSameThread())return;Session s=active;if(s==null||!s.ready||LinkPlayClient.INSTANCE.active())return;
        PortInput input=neutralInput();if(net.muxigame.console.client.input.HeldInputPolicy.captures(mc().screen instanceof GameScreen,controlsReleased,s.leaving))input=capturedInput(s.platform);else s.localInput.clear();
        visualInput=input;submitInput(input);
    }
    /** Normal frontend input entry point; all controller ownership and sequence checks remain on the server. */
    public void submitInput(PortInput input){
        if(LinkPlayClient.INSTANCE.active()){visualInput=input;return;}Session s=active;if(s==null||!s.ready)return;
        if(s.hostClock){
            long now=System.nanoTime();inputSubmissions++;
            if(s.leader)s.localInput.publish(++s.localSequence,input,now);
            else{
                boolean edge=input.buttons()!=s.lastRemoteInput.buttons()||input.device()!=s.lastRemoteInput.device()||input.pointer().pressed()!=s.lastRemoteInput.pointer().pressed()||input.pointer().valid()!=s.lastRemoteInput.pointer().valid();
                if(edge||!input.equals(s.lastRemoteInput)&&now-s.lastRemoteSent>=33_000_000L||now-s.lastRemoteSent>=100_000_000L){
                    try{JsonObject j=s.context.deepCopy();j.addProperty("sequence",++s.remoteSequence);j.addProperty("inputVersion",InputCodec.VERSION);Wire.command("clock_input",j,InputCodec.encode(input));s.lastRemoteInput=input;s.lastRemoteSent=now;}
                    catch(IOException failure){failed(s,failure);}
                }
            }
            if(input.microphone()==microphoneInput)microphoneInput=microphoneInput.withoutSamples();return;
        }
        if(s.inputPending)return;long target=Math.max(s.lastInputTick+1,Math.max(0,s.tick+2));if(target>s.tick+12)return;JsonObject j=s.context.deepCopy();j.addProperty("sequence",s.sequence);j.addProperty("tick",target);j.addProperty("buttons",input.buttons());JsonArray a=new JsonArray();a.add(input.leftX());a.add(input.leftY());a.add(input.rightX());a.add(input.rightY());j.add("axes",a);
        try{byte[] bytes=InputCodec.encode(input);j.addProperty("inputVersion",InputCodec.VERSION);s.inputPending=true;s.lastInputTick=target;inputSubmissions++;Wire.command("input",j,bytes);if(input.microphone()==microphoneInput)microphoneInput=microphoneInput.withoutSamples();}catch(IOException invalid){failed(s,invalid);}
    }
    /** Optional frontend integration; null returns to this mod's physical keyboard/gamepad sampler. */
    public void setInputSource(java.util.function.Supplier<PortInput> source){inputSource=source;clearInputVisual();}
    public void device(JsonObject context,String action,int port){JsonObject j=context.deepCopy();j.addProperty("action",action);j.addProperty("port",port);Wire.command("device_action",j);}
    /** Opening inventory access saves a held machine once; an existing dock game keeps its seat. */
    public void prepareDockMenu(){if(handheldSession()||LinkPlayClient.INSTANCE.blocksStandalone()||LinkPlayClient.INSTANCE.invitation()!=null)leave();}
    public void leave(){if(LinkPlayClient.INSTANCE.blocksStandalone()||LinkPlayClient.INSTANCE.invitation()!=null){LinkPlayClient.INSTANCE.leave();return;}leaveStandaloneForLink();}
    public void leaveStandaloneForLink(){net.muxigame.console.client.input.NativeControls.clear();clearInputVisual();Session s=active;if(s!=null&&s.room.startsWith("handheld/"))suppressedHandheld=s.machine;if(s!=null&&mc().getConnection()!=null&&s.ready){if(!s.leaving){s.leaving=true;inputSource=null;status="正在保存并离开…";Wire.command("leave_request",s.context.deepCopy());}return;}if(s!=null&&mc().getConnection()!=null)Wire.command("leave",object());stop();}
    public void stop(){net.muxigame.console.client.input.NativeControls.clear();Session s=active;active=null;inputSource=null;clearInputVisual();displayedFrames=0;frameCrc="";SharedPlayClient.INSTANCE.stop();BrowserLinkClient.INSTANCE.stop();downloads.values().forEach(f->f.cancel(false));downloads.clear();if(s!=null){if(s.cloudReader!=null)s.cloudReader.close();s.progress.cancel();terminalMachine=s.machine;terminalProgress=progress(s.progress.snapshot());terminalProgressUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);if(s.checkpoint!=null)s.checkpoint.completeExceptionally(new IOException("会话已停止"));SaveUpload saving=s.saveUpload;abortCheckpoint(s);s.saveUpload=null;s.checkpoint=null;s.saveTransaction="";if(saving!=null)discardSave(saving);emulator.execute(()->close(s));}}
    private static boolean nativeFiles(Session s){return s.context.has("nativeFiles")&&s.context.get("nativeFiles").getAsBoolean();}
    private static long stateLimit(Session s){return switch(s.platform){case NDS->128L*1024*1024;case N3DS,WII->512L*1024*1024;default->32L*1024*1024;};}
    private Path newSpool()throws IOException{Path directory=files().root().resolve("state-spool");Files.createDirectories(directory);if(!directory.toRealPath().equals(directory.toAbsolutePath().normalize()))throw new IOException("存档临时目录不可使用链接");return directory.resolve("checkpoint-"+UUID.randomUUID()+".bin");}
    private Path downloadArtifact(Session s,JsonObject metadata,String kind,long limit)throws Exception{
        long size=metadata.get("size").getAsLong();if(size<1||size>limit)throw new IOException("存档大小超出当前机型范围");Path target=newSpool();boolean complete=false;s.progress.begin(LoadProgress.Stage.RESTORING,size);
        try{
            var digest=Hashing.sha256Digest();
            try(var output=new BufferedOutputStream(Files.newOutputStream(target,StandardOpenOption.CREATE_NEW))){for(long offset=0;offset<size;){RomTransfer.requireActive(()->loading(s));JsonObject request=s.context.deepCopy();request.addProperty("generation",metadata.get("generation").getAsString());request.addProperty("offset",offset);Wire.Message response=readPiece(s,kind+"_read",request);
                RomTransfer.requireActive(()->loading(s));if(response.operation().equals(kind+"_denied"))throw new IOException("服务器拒绝或无法提供机器存档");if(!response.operation().equals(kind+"_chunk")||response.body().get("offset").getAsLong()!=offset||response.data().length<1||response.data().length>24576||response.data().length>size-offset)throw new IOException("机器存档分块格式无效");output.write(response.data());digest.update(response.data());offset+=response.data().length;s.progress.advance(response.data().length);
            }}
            if(!HexFormat.of().formatHex(digest.digest()).equals(metadata.get("hash").getAsString()))throw new IOException("服务器机器存档校验失败");complete=true;return target;
        }finally{if(!complete)Files.deleteIfExists(target);}
    }
    private void restoreProfile(Session s)throws Exception{
        if(!nativeFiles(s)||!s.context.has("profile"))return;JsonObject metadata=s.context.getAsJsonObject("profile");
        if(!metadata.get("machine").getAsString().equals(s.machine.toString())||!metadata.get("platform").getAsString().equals(s.platform.id()))throw new IOException("机器文件不属于当前设备");
        Path downloaded=downloadArtifact(s,metadata,"profile",NativeProfileArchive.MAX_BYTES);
        try{RomTransfer.requireActive(()->loading(s));NativeProfileArchive.restore(files().machineProfile(s.machine,s.platform),s.platform.id(),downloaded,()->loading(s));s.profileGeneration=metadata.get("generation").getAsString();}finally{Files.deleteIfExists(downloaded);}
    }
    private CompletableFuture<Void> checkpoint(Session s){
        if(active!=s||!s.ready||!s.leader)return CompletableFuture.completedFuture(null);if(s.checkpoint!=null)return s.checkpoint;
        CompletableFuture<Void> complete=new CompletableFuture<>();String transaction=UUID.randomUUID().toString();s.checkpoint=complete;s.saveTransaction=transaction;s.saveStage="serializing";s.saveOperation="waiting_pause";s.saveBytes=s.saveTotal=0;s.savePaused=new CompletableFuture<>();s.snapshotFrame=-1;s.snapshotHoldToken="";
        if(s.hostClock){checkpointRealtime(s,complete,transaction);return complete;}
        s.savePaused.orTimeout(15,TimeUnit.SECONDS).whenComplete((frame,failure)->mc().execute(()->{if(active!=s||s.checkpoint!=complete)return;if(failure!=null)failCheckpoint(s,new IOException("等待保存暂停回执超时",failure));else prepareCheckpoint(s,complete,transaction,frame);}));
        saveProgress(s,System.nanoTime());
        return complete;
    }
    private void checkpointRealtime(Session s,CompletableFuture<Void> complete,String transaction){
        s.snapshotPause=true;
        emulator.execute(()->{Path raw=null,profile=null;try{
            if(active!=s||s.checkpoint!=complete)throw new IOException("会话已改变");long captured=s.frame;s.snapshotFrame=captured;String capturedHold=s.clockHold?s.holdToken:"";s.snapshotHoldToken=capturedHold;
            mc().execute(()->{if(active==s&&s.checkpoint==complete)saveProgress(s,System.nanoTime());});
            s.saveOperation="serialize_core";raw=newSpool();s.runtime.saveStateTo(raw);long size=Files.size(raw);if(size>stateLimit(s))throw new IOException("状态超过此机型存档上限");
            NativeProfileArchive.Snapshot archive=null;if(nativeFiles(s)){s.saveOperation="archive_native_files";profile=newSpool();archive=NativeProfileArchive.pack(files().machineProfile(s.machine,s.platform),s.platform.id(),profile,()->active==s&&s.checkpoint==complete,bytes->s.saveBytes=bytes);}
            Path capturedState=raw;NativeProfileArchive.Snapshot capturedProfile=archive;raw=null;profile=null;
            files.execute(()->{Path transport=capturedState;try{
                if(active!=s||s.checkpoint!=complete)throw new IOException("会话已改变");s.saveOperation="compress_core";
                Path compressed=StateSpool.compress(capturedState,newSpool(),stateLimit(s),()->active==s&&s.checkpoint==complete,bytes->s.saveBytes=bytes);if(!compressed.equals(capturedState)){Files.delete(capturedState);transport=compressed;}
                SaveUpload save=new SaveUpload(transport,capturedProfile,transaction,captured,complete,capturedHold);transport=null;
                s.savePaused.orTimeout(15,TimeUnit.SECONDS).whenComplete((ack,failure)->mc().execute(()->{if(active!=s||s.checkpoint!=complete){discardSave(save);return;}if(failure!=null||ack!=captured){discardSave(save);failCheckpoint(s,new IOException("保存快照授权回执不匹配",failure));return;}s.saveUpload=save;beginSave(s,save);}));
            }catch(Exception failure){deleteSpool(transport);if(capturedProfile!=null)deleteSpool(capturedProfile.path());mc().execute(()->{if(s.checkpoint==complete)failCheckpoint(s,failure);});}});
        }catch(Exception failure){mc().execute(()->{if(s.checkpoint==complete)failCheckpoint(s,failure);});}
        finally{deleteSpool(raw);deleteSpool(profile);s.snapshotPause=false;if(active==s&&s.clock!=null){s.clock.resumeAfterSnapshot(System.nanoTime());scheduleClock(s);}}});
    }
    private void prepareCheckpoint(Session s,CompletableFuture<Void> complete,String transaction,long pausedFrame){
        emulator.execute(()->{Path state=null,profile=null;try{
            if(active!=s||s.checkpoint!=complete)throw new IOException("会话已改变");if(s.frame!=pausedFrame)throw new IOException("保存暂停帧与本地模拟帧不一致");s.saveOperation="serialize_core";state=newSpool();s.runtime.saveStateTo(state);if(Files.size(state)>stateLimit(s))throw new IOException("状态超过此机型存档上限");
            s.saveOperation="compress_core";long rawSize=Files.size(state);Path transport=StateSpool.compress(state,newSpool(),stateLimit(s),()->active==s&&s.checkpoint==complete,bytes->s.saveBytes=bytes);if(!transport.equals(state)){Files.delete(state);state=transport;}
            NativeProfileArchive.Snapshot archive=null;if(nativeFiles(s)){s.saveOperation="archive_native_files";profile=newSpool();archive=NativeProfileArchive.pack(files().machineProfile(s.machine,s.platform),s.platform.id(),profile,()->active==s&&s.checkpoint==complete,bytes->s.saveBytes=rawSize+bytes);}
            s.saveOperation="hash_checkpoint";
            SaveUpload save=new SaveUpload(state,archive,transaction,s.frame,complete);state=null;profile=null;
            mc().execute(()->{if(active!=s||s.checkpoint!=complete){discardSave(save);complete.completeExceptionally(new IOException("会话已改变"));return;}s.saveUpload=save;beginSave(s,save);});
        }catch(Exception failure){mc().execute(()->{if(s.checkpoint==complete)failCheckpoint(s,failure);else complete.completeExceptionally(failure);});}finally{deleteSpool(state);deleteSpool(profile);}});
    }
    private static boolean checkpointReply(Session s,JsonObject body){try{return body.has("room")&&s.room.equals(body.get("room").getAsString())&&body.has("epoch")&&s.epoch==body.get("epoch").getAsLong()&&body.has("machine")&&s.machine.toString().equals(body.get("machine").getAsString())&&body.has("content")&&s.hash.equals(body.get("content").getAsString())&&body.has("saveTransaction")&&s.saveTransaction.equals(body.get("saveTransaction").getAsString());}catch(RuntimeException invalid){return false;}}
    private void saveProgress(Session s,long now){if(s.hostClock&&s.snapshotFrame<0)return;s.lastSaveProgress=now;JsonObject report=s.context.deepCopy();report.addProperty("saveTransaction",s.saveTransaction);report.addProperty("stage",s.saveStage);report.addProperty("bytes",s.saveBytes);report.addProperty("total",s.saveTotal);if(s.hostClock&&s.snapshotFrame>=0){report.addProperty("snapshotFrame",s.snapshotFrame);if(!s.snapshotHoldToken.isEmpty())report.addProperty("holdToken",s.snapshotHoldToken);}Wire.command("save_progress",report);}
    private static JsonObject saveContext(Session s,SaveUpload save){JsonObject request=s.context.deepCopy();request.addProperty("saveTransaction",save.transaction);if(!save.holdToken.isEmpty())request.addProperty("holdToken",save.holdToken);if(save.id!=null)request.addProperty("upload",save.id.toString());return request;}
    private void beginSave(Session s,SaveUpload save){
        save.id=null;save.offset=0;save.sentOffset=-1;save.committing=false;s.saveStage=save.profilePhase?"profile":"state";s.saveOperation=save.profilePhase?"begin_profile":"begin_state";s.saveBytes=0;s.saveTotal=save.total();
        JsonObject begin=saveContext(s,save);begin.addProperty("frame",save.frame);begin.addProperty("size",save.total());begin.addProperty("hash",save.digest());
        if(save.profilePhase){if(!s.profileGeneration.isEmpty())begin.addProperty("previous",s.profileGeneration);}
        else if(!s.profileGeneration.isEmpty())begin.addProperty("profileGeneration",s.profileGeneration);
        Wire.command(save.profilePhase?"profile_begin":"state_begin",begin);
    }
    private static boolean saveReply(Session s,SaveUpload save,JsonObject body,boolean profile){
        try{return s!=null&&save!=null&&save.profilePhase==profile&&body.has("room")&&s.room.equals(body.get("room").getAsString())&&body.has("epoch")&&s.epoch==body.get("epoch").getAsLong()&&body.has("machine")&&s.machine.toString().equals(body.get("machine").getAsString())&&body.has("saveTransaction")&&save.transaction.equals(body.get("saveTransaction").getAsString())&&(profile?body.has("platform")&&s.platform.id().equals(body.get("platform").getAsString()):body.has("content")&&s.hash.equals(body.get("content").getAsString()))&&(save.id==null||body.has("upload")&&save.id.toString().equals(body.get("upload").getAsString()));}catch(RuntimeException invalid){return false;}
    }
    private void saveAcknowledged(JsonObject body,boolean profile){
        Session s=active;SaveUpload save=s==null?null:s.saveUpload;if(!saveReply(s,save,body,profile))return;
        try{
            UUID id=UUID.fromString(body.get("upload").getAsString());long offset=body.get("offset").getAsLong();if(body.get("total").getAsLong()!=save.total()||offset<save.offset||offset>save.total())throw new IOException("存档回执范围无效");
            if(save.id==null)save.id=id;save.offset=offset;s.saveBytes=offset;sendStatePiece(s,save);
        }catch(Exception failure){failCheckpoint(s,failure);}
    }
    private void saveCommitted(JsonObject body,boolean profile){
        Session s=active;SaveUpload save=s==null?null:s.saveUpload;if(!saveReply(s,save,body,profile))return;
        if(save.id==null?!(profile&&body.has("unchanged")&&body.get("unchanged").getAsBoolean()&&!body.has("upload")):!save.committing)return;
        try{
            if(!save.digest().equals(body.get("hash").getAsString()))throw new IOException("已提交机器存档校验不符");
            if(profile){if(body.get("size").getAsLong()!=save.profile.size())throw new IOException("机器文件提交大小不符");s.profileGeneration=UUID.fromString(body.get("generation").getAsString()).toString();save.profilePhase=false;beginSave(s,save);}
            else{savedStateHash=save.hash;savedStates++;s.saveUpload=null;s.checkpoint=null;s.saveTransaction="";discardSave(save);save.completed.complete(null);}
        }catch(Exception failure){failCheckpoint(s,failure);}
    }
    private void sendStatePiece(Session s,SaveUpload save){
        if(active!=s||s.saveUpload!=save||save.id==null||save.offset<0||save.offset>save.total()||save.committing||save.sentOffset==save.offset)return;
        JsonObject request=saveContext(s,save);String kind=save.profilePhase?"profile":"state";
        if(save.offset==save.total()){s.saveOperation="commit_"+kind;save.committing=true;Wire.command(kind+"_commit",request);return;}
        s.saveOperation="read_"+kind+"_chunk";
        long offset=save.offset;boolean phase=save.profilePhase;Path path=save.file();save.sentOffset=offset;
        files.execute(()->{try(var channel=Files.newByteChannel(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            channel.position(offset);java.nio.ByteBuffer buffer=java.nio.ByteBuffer.allocate((int)Math.min(24576,save.total()-offset));while(buffer.hasRemaining()){if(channel.read(buffer)<0)throw new EOFException("机器存档临时文件被截断");}byte[] piece=buffer.array();request.addProperty("offset",offset);
            mc().execute(()->{if(active==s&&s.saveUpload==save&&save.profilePhase==phase&&save.offset==offset)Wire.command(kind+"_piece",request,piece);});
        }catch(Exception failure){mc().execute(()->{if(s.saveUpload==save)failCheckpoint(s,failure);});}});
    }
    private void failCheckpoint(Session s,Exception failure){
        SaveUpload save=s.saveUpload;CompletableFuture<Void> completed=s.checkpoint;String transaction=s.saveTransaction,stage=s.saveStage,operation=s.saveOperation;abortCheckpoint(s);s.saveUpload=null;s.checkpoint=null;s.saveTransaction="";s.leaving=false;
        if(save!=null)discardSave(save);
        status="存档未保存："+TransferFailure.playerMessage(failure,"saving");MuxiConsole.LOG.warn("Checkpoint failed platform={} room={} epoch={} transaction={} stage={} operation={} filesystemReason={} detail={}",s.platform.id(),s.room,s.epoch,transaction,stage,operation,TransferFailure.filesystemReason(failure),TransferFailure.diagnostic(failure));if(completed!=null)completed.completeExceptionally(failure);
    }
    private void abortCheckpoint(Session s){if(mc().getConnection()==null||s.saveTransaction.isEmpty())return;SaveUpload save=s.saveUpload;JsonObject request=save==null?s.context.deepCopy():saveContext(s,save);request.addProperty("saveTransaction",s.saveTransaction);Wire.command(save!=null&&save.profilePhase?"profile_abort":"state_abort",request);}
    private static void deleteSpool(Path path){if(path!=null)try{Files.deleteIfExists(path);}catch(IOException failure){MuxiConsole.LOG.debug("Local checkpoint cleanup failed",failure);}}
    private void discardSave(SaveUpload save){files.execute(()->{deleteSpool(save.state);if(save.profile!=null)deleteSpool(save.profile.path());});}
    private void standaloneClosureFailed(Throwable failure){if(standaloneNativeCloseFailure==null)standaloneNativeCloseFailure=new IOException("上次普通游戏的原生文件未能确认正常关闭，请先重启客户端",failure);}
    private static boolean hasCleanupFailure(Throwable failure){for(int n=0;failure!=null&&n<16;n++,failure=failure.getCause())if(failure.getSuppressed().length>0)return true;return false;}
    private void close(Session s){if(s.closeRecorded)return;s.audio.close();if(s.deadlineWaiter!=null){try{s.deadlineWaiter.close();}catch(IOException failure){standaloneClosureFailed(failure);MuxiConsole.LOG.warn("Realtime clock cleanup failed detail={}",TransferFailure.diagnostic(failure));}s.deadlineWaiter=null;}ProcessConsoleRuntime r=s.runtime;if(r==null)return;boolean closedNormally=false;try{r.close();closedNormally=true;}catch(IOException|RuntimeException|LinkageError e){standaloneClosureFailed(e);MuxiConsole.LOG.warn("Worker close failed detail={}",TransferFailure.diagnostic(e));}finally{s.closeRecorded=true;boolean natural=closedNormally&&!r.wasForcedShutdown()&&!r.isAlive()&&r.exitCode().orElse(-1)==0&&r.nativeTemporaryFilesCleaned();if(!natural)standaloneClosureFailed(new IOException("普通工作进程未完成自然退出及原生文件清理"));if(r.wasForcedShutdown())forcedWorkerExits++;else if(natural)normalWorkerExits++;else failedWorkerExits++;MuxiConsole.LOG.info("Worker closure pid={} exit={} forced={} nativeTempCleaned={}",r.workerPid(),r.exitCode().orElse(-1),r.wasForcedShutdown(),r.nativeTemporaryFilesCleaned());if(!r.isAlive()){ownedWorkers.remove(r.workerPid());liveRuntimes.remove(r.workerPid());}}}
    private void failed(Session s,Exception failure){
        mc().execute(()->{if(active!=s)return;var progress=s.progress.snapshot();status=TransferFailure.playerMessage(failure,progress.stage());lastError=status;MuxiConsole.LOG.warn("Console failure code={} stage={} platform={} core={} fingerprint={} bytes={}/{} detail={}",TransferFailure.code(failure,progress.stage()),progress.stage(),s.platform.id(),RuntimeFiles.core(s.platform),s.coreFingerprint,progress.bytes(),progress.total(),TransferFailure.diagnostic(failure));s.progress.fail(status);JsonObject j=s.context.deepCopy();j.addProperty("reason",status);Wire.command("runtime_failed",j);stop();});
    }
    private void updatePicture(AvFrame frame){updatePicture(frame,true);}
    private void updatePicture(AvFrame frame,boolean publish){
        VideoOrientation.Image oriented=VideoOrientation.apply(frame.width(),frame.height(),frame.rgba(),frame.rotation());if(oriented.width()<1||oriented.height()<1)return;
        double aspect=frame.displayAspect();if((frame.rotation()&1)!=0)aspect=1/aspect;oriented=VideoLayout.display(oriented,aspect);int w=oriented.width(),h=oriented.height();
        if(texture==null||pictureWidth!=w||pictureHeight!=h){if(texture!=null)mc().getTextureManager().release(picture);NativeImage image=new NativeImage(w,h,false);texture=new DynamicTexture(image);picture=mc().getTextureManager().register("muxi-console-game",texture);pictureWidth=w;pictureHeight=h;}
        NativeImage image=texture.getPixels();byte[] rgba=oriented.rgba();for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=(y*w+x)*4;int pixel=(rgba[i+3]&255)<<24|(rgba[i+2]&255)<<16|(rgba[i+1]&255)<<8|(rgba[i]&255);image.setPixelRGBA(x,y,pixel);}texture.upload();pictureRotation=frame.rotation();pictureSurfaces=surfaceLayout(frame,w,h);Session current=active;if(current!=null){current.hasPicture=true;current.progress.begin(LoadProgress.Stage.COMPLETE,0);}
        if(publish)BrowserLinkClient.INSTANCE.frame(displayedFrames,w,h,oriented.rgba());Session publisher=publish?active:null;if(publisher!=null&&sharedPublishing(publisher))SharedPlayClient.INSTANCE.frame(displayedFrames,w,h,oriented.rgba());
    }
    private static List<AvFrame.Surface> surfaceLayout(AvFrame frame,int width,int height){List<AvFrame.Surface> result=new ArrayList<>();int rw=(frame.rotation()&1)==0?frame.width():frame.height(),rh=(frame.rotation()&1)==0?frame.height():frame.width();for(var p:frame.surfaces()){int x=p.x(),y=p.y(),w=p.width(),h=p.height();if(frame.rotation()==1){x=p.y();y=frame.width()-p.x()-p.width();w=p.height();h=p.width();}else if(frame.rotation()==2){x=frame.width()-p.x()-p.width();y=frame.height()-p.y()-p.height();}else if(frame.rotation()==3){x=frame.height()-p.y()-p.height();y=p.x();w=p.height();h=p.width();}int x0=(int)((long)x*width/rw),y0=(int)((long)y*height/rh),x1=(int)((long)(x+w)*width/rw),y1=(int)((long)(y+h)*height/rh);if(x1>x0&&y1>y0)result.add(new AvFrame.Surface(p.id(),x0,y0,x1-x0,y1-y0,(frame.rotation()&1)==0?p.nativeWidth():p.nativeHeight(),(frame.rotation()&1)==0?p.nativeHeight():p.nativeWidth(),p.touchable()));}return List.copyOf(result);}
    /** Link preparation waits for the server's ordinary session_end after a committed single-machine save. */
    public boolean standaloneRunning(){return active!=null;}
    private void requireStandaloneNativeFilesClosed()throws IOException{
        if(standaloneNativeCloseFailure!=null)throw new IOException("上次普通游戏的原生文件尚未安全关闭",standaloneNativeCloseFailure);
        for(ProcessConsoleRuntime runtime:liveRuntimes.values())if(runtime.isAlive()||runtime.exitCode().orElse(-1)!=0||runtime.wasForcedShutdown()||!runtime.nativeTemporaryFilesCleaned())throw new IOException("普通游戏仍持有机器文件，不能覆盖原生进度");
    }
    /** Background-only FIFO barrier: active=null alone never proves an old native writer has exited. */
    public void awaitStandaloneNativeFilesClosed()throws IOException{
        if(mc().isSameThread())throw new IOException("普通游戏文件关闭门不能在游戏主线程等待");
        CompletableFuture<Void> complete=new CompletableFuture<>();
        try{emulator.execute(()->{try{if(active!=null)throw new IOException("普通游戏会话仍在运行");requireStandaloneNativeFilesClosed();complete.complete(null);}catch(Exception failure){complete.completeExceptionally(failure);}});}
        catch(RejectedExecutionException stopped){throw new IOException("普通游戏退出队列已停止",stopped);}
        try{complete.get(20,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("等待普通游戏关闭时中断",interrupted);}catch(ExecutionException failed){throw new IOException("普通游戏原生文件未能安全交接",failed.getCause());}catch(TimeoutException timeout){throw new IOException("普通游戏仍在关闭原生文件",timeout);}
    }
    private boolean linkHeldMatches(){var owner=LinkPlayClient.INSTANCE.owner();if(owner.isEmpty()||mc().player==null)return false;for(var hand:net.minecraft.world.InteractionHand.values()){var item=mc().player.getItemInHand(hand);var card=CardData.read(item);if(item.is(ConsoleContent.HANDHELD.get())&&MachineData.read(item).filter(owner.get().machineUUID()::equals).isPresent()&&card.isPresent()&&card.get().sha256().equals(owner.get().contentSHA())&&card.get().instanceId().equals(LinkPlayClient.INSTANCE.card()))return true;}return false;}
    public PortInput sampleLinkInput(Platform platform){
        PortInput input=neutralInput();
        if(net.muxigame.console.client.input.HeldInputPolicy.captures(mc().screen instanceof GameScreen,controlsReleased,!LinkPlayClient.INSTANCE.ready())){
            input=capturedInput(platform);
        }
        visualInput=input;inputSubmissions++;return input;
    }
    private PortInput capturedInput(Platform platform){
        PortInput input;
        if(inputSource!=null)input=Objects.requireNonNullElse(inputSource.get(),PortInput.IDLE);
        else if(mc().isWindowActive())input=net.muxigame.console.client.input.NativeControls.sample(platform);
        else{((GameScreen)mc().screen).clearInputKeys();Session s=active;if(s!=null)s.localInput.clear();return neutralInput();}
        // Screen/API extras and the selected input provider share one captured
        // sample. No extras are merged when the real focus/provider gate fails.
        controllerDevice=input.device();return new PortInput(input.buttons(),input.leftX(),input.leftY(),input.rightX(),input.rightY(),controllerDevice,pointerInput.valid()?pointerInput:input.pointer(),motionInput.valid()?motionInput:input.motion(),microphoneInput.connected()?microphoneInput:input.microphone());
    }
    public void resetLinkPicture(){displayedFrames=0;frameCrc="";pictureSurfaces=List.of();clearInputVisual();}
    public void acceptLinkPicture(AvFrame frame){if(!LinkPlayClient.INSTANCE.active())return;displayedFrames=frame.number();CRC32 crc=new CRC32();crc.update(frame.rgba());frameCrc=Long.toHexString(crc.getValue());updatePicture(frame,false);}
    public void suppressLinkAutoStart(){if(mc().isSameThread()){Held held=heldCartridge();if(held!=null){suppressedHandheld=held.machine==null?held.card.instanceId():held.machine;attemptedHandheld=suppressedHandheld;}}}
    public SharedPlayClient.Lease sharedLease(){Session s=active;return s!=null&&s.shared?new SharedPlayClient.Lease(s.room,s.epoch,s.port,s.leader):null;}
    private Session sharedGuest(String room,long epoch){Session s=active;return s!=null&&s.shared&&!s.leader&&s.room.equals(room)&&s.epoch==epoch?s:null;}
    public void sharedReady(String room,long epoch){Session s=sharedGuest(room,epoch);if(s!=null){s.ready=true;status="控制席已连接，等待主机画面…";}}
    public void sharedPicture(String room,long epoch,long frame,int width,int height,byte[] rgba){Session s=sharedGuest(room,epoch);if(s==null||!s.ready||frame<=displayedFrames)return;displayedFrames=frame;CRC32 crc=new CRC32();crc.update(rgba);frameCrc=Long.toHexString(crc.getValue());updatePicture(new AvFrame(frame,width,height,0,rgba,new short[0],48000,false));status="玩家 "+(s.port+1)+" · "+s.platform.id().toUpperCase(Locale.ROOT)+" · 主机画面";}
    public void sharedPicture(String room,long epoch,long frame,int width,int height,byte[] rgba,List<AvFrame.Surface> surfaces){Session s=sharedGuest(room,epoch);if(s==null||!s.ready||frame<=displayedFrames)return;displayedFrames=frame;CRC32 crc=new CRC32();crc.update(rgba);frameCrc=Long.toHexString(crc.getValue());updatePicture(new AvFrame(frame,width,height,0,rgba,new short[0],48000,false,0,surfaces));status="玩家 "+(s.port+1)+" · "+s.platform.id().toUpperCase(Locale.ROOT)+" · 主机画面";}
    public void sharedAudio(String room,long epoch,long frame,short[] pcm){Session s=sharedGuest(room,epoch);if(s!=null&&s.ready)s.audio.offer(pcm,48000);}
    public ResourceLocation picture(){if(LinkPlayClient.INSTANCE.active())return LinkPlayClient.INSTANCE.hasPicture()?picture:null;return active!=null&&active.ready&&active.hasPicture?picture:null;}
    public long screenConsole(){Session s=active;return s==null?Long.MIN_VALUE:s.position;}
    public void renderPicture(GuiGraphics graphics,int x,int y,int width,int height){
        graphics.fill(x,y,x+width,y+height,0xff101719);ResourceLocation p=picture();if(p==null)return;
        double scale=Math.min(width/(double)pictureWidth,height/(double)pictureHeight);int w=(int)(pictureWidth*scale),h=(int)(pictureHeight*scale);
        graphics.blit(p,x+(width-w)/2,y+(height-h)/2,w,h,0,0,pictureWidth,pictureHeight,pictureWidth,pictureHeight);
    }
    private ConsoleClient(){Runtime.getRuntime().addShutdownHook(new Thread(()->{stop();emulator.shutdown();try{emulator.awaitTermination(8,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}for(ProcessConsoleRuntime r:liveRuntimes.values())try{r.close();}catch(IOException e){MuxiConsole.LOG.warn("Shutdown worker close failed",e);}},"muxi-console-shutdown"));}
}
