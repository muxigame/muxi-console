package net.muxigame.consoleqa;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.muxigame.console.client.ConsoleClient;
import net.muxigame.console.client.input.NativeControls;
import net.muxigame.console.client.ui.GameScreen;
import net.muxigame.console.client.ui.WriterScreen;
import net.muxigame.console.domain.Hashing;
import net.muxigame.console.domain.Platform;
import net.muxigame.console.mc.Wire;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Normal main-thread GUI callbacks and actual rendered guest pixels; never a synthetic input mask. */
final class LatencyActions {
    private static final Gson JSON=new Gson();
    private static LatencyTimer timer;
    private static boolean installed;private static Run active;private static int leaseDelayMillis,saveDelayMillis;private static long delayedLeases;private static final List<Delayed> delayed=new ArrayList<>();private static final ArrayDeque<JsonObject> events=new ArrayDeque<>();
    private static final class Delayed {final Wire.Message message;final Consumer<Wire.Message> receiver;final JsonObject event;boolean delivered;Delayed(Wire.Message message,Consumer<Wire.Message> receiver,JsonObject event){this.message=message;this.receiver=receiver;this.event=event;}}
    private static final class Tap {
        final int id,desiredMillis;final long due;long down,up,downSequence=-1,upSequence=-1,applied,appliedFrame=-1,upApplied,observed,observedGuestFrame=-1;
        Tap(int id,long due,int width){this.id=id;this.due=due;desiredMillis=width;}
    }
    private static final class Run {
        final String room;final long epoch,start,duration;final int key,baselineDowns,baselineUps;final GameScreen screen;final boolean barcode;
        final List<Tap> taps=new ArrayList<>();final List<Long> presentationIntervals=new ArrayList<>(),renderIntervals=new ArrayList<>();
        final ConsoleClient.RealtimeMetrics before;long renderCount,lastRender,lastPicture,distinctPictures,firstGuestFrame,lastGuestFrame,finished;int next;boolean complete;String failure="";
        Run(ConsoleClient.RealtimeMetrics before,GameScreen screen,int key,int seconds,int pulses,int[] guest){this.before=before;this.screen=screen;this.key=key;room=before.room();epoch=before.epoch();start=System.nanoTime();duration=seconds*1_000_000_000L;barcode=guest!=null;baselineDowns=barcode?guest[1]:0;baselineUps=barcode?guest[2]:0;firstGuestFrame=lastGuestFrame=barcode?guest[0]:0;
            long spacing=(duration-3_000_000_000L)/Math.max(1,pulses);for(int id=0;id<pulses;id++)taps.add(new Tap(id,start+1_000_000_000L+id*spacing+(id*37L%47)*1_000_000L,10+(id%3)*10));}
    }
    private static void install(){if(!installed){NeoForge.EVENT_BUS.addListener(LatencyActions::render);installed=true;}}
    private static LatencyTimer timer(){if(timer==null)try{timer=new LatencyTimer();}catch(IOException error){throw new IllegalStateException(error);}return timer;}
    static JsonObject command(Minecraft client,JsonObject command,QaOwner owner){try{
        install();switch(command.get("action").getAsString()){
            case "latency_choose"->{
                if(!(client.screen instanceof WriterScreen writer))throw new IOException("Open the actual writer first");String hash=owner.pin("latency.sha256");if(!hash.matches("[a-f0-9]{64}"))throw new IOException("Original realtime ROM must be pinned");
                byte[] body;try(InputStream input=LatencyActions.class.getResourceAsStream("/diagnostics/original-realtime.gba")){if(input==null)throw new IOException("Missing first-party realtime fixture");body=input.readNBytes(65537);}if(body.length!=65536||!Hashing.sha256(body).equals(hash))throw new IOException("Original realtime ROM identity differs");
                Path directory=owner.validatedRoot().resolve(System.getProperty("qa.local.role")).resolve("console-qa");Files.createDirectories(directory);Path file=directory.resolve("original-realtime.gba");if(Files.exists(file)){if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!Hashing.sha256(file).equals(hash))throw new IOException("Existing authored fixture differs");}else Files.write(file,body,StandardOpenOption.CREATE_NEW);writer.onFilesDrop(List.of(file));
            }
            case "latency_begin","latency_remote_taps"->{
                if(active!=null&&!active.complete)throw new IOException("A measurement is already active");var metrics=ConsoleClient.INSTANCE.realtimeMetrics().orElseThrow();var state=ConsoleClient.INSTANCE.snapshot();
                int seconds=command.get("seconds").getAsInt(),pulses=command.get("pulses").getAsInt();if(seconds<6||seconds>120||pulses<3||pulses>256||pulses>(seconds-3)*8)throw new IOException("Bounded realtime schedule required");
                boolean remote=command.get("action").getAsString().equals("latency_remote_taps");
                if(!(client.screen instanceof GameScreen screen)||!metrics.hostClock()||!state.ready()||metrics.hold()||(!remote&&(metrics.platform()!=Platform.GBA||!metrics.clockStarted()||!metrics.leaseValid()||!state.workerRunning()||state.workers().size()!=1))||(remote&&(metrics.platform()!=Platform.NES||state.workerRunning()||!state.workers().isEmpty())))throw new IOException("Actual ordinary GBA host or core-free NES remote seat required");
                int[] guest=remote?null:pixels(client);if(!remote&&guest==null)throw new IOException("Actual first-party guest barcode is absent");int key=NativeControls.key(metrics.platform(),8).getKey().getValue();if(key<32||key==256)throw new IOException("A normal bound keyboard A key is required");
                Platform platform=metrics.platform();ConsoleClient.INSTANCE.setInputSource(()->NativeControls.sample(platform));screen.clearInputKeys();active=new Run(metrics,screen,key,seconds,pulses,guest);
            }
            case "latency_delay_leases"->{int ms=command.get("milliseconds").getAsInt();if(ms<0||ms>1000)throw new IOException("Only bounded lease delivery delay is allowed");var m=ConsoleClient.INSTANCE.realtimeMetrics().orElseThrow();if(!m.hostClock()||!m.clockStarted()||!m.leaseValid())throw new IOException("An authorized clock lease is required");leaseDelayMillis=ms;}
            case "latency_delay_saves"->{int ms=command.get("milliseconds").getAsInt();if(ms<0||ms>1000||ConsoleClient.INSTANCE.realtimeMetrics().isEmpty())throw new IOException("Only bounded authorized save-ACK delivery delay is allowed");saveDelayMillis=ms;}
            case "latency_stop"->{if(active!=null&&!active.complete)finish(client,active,"Stopped before the scheduled measurement completed");leaseDelayMillis=saveDelayMillis=0;flushDelayed();if(timer!=null){timer.close();timer=null;}}
            case "latency_observe"->{}
            default->throw new IOException("Known latency action required");
        }return observe(client);
    }catch(Exception failure){throw new IllegalStateException(failure);}}
    /** Hook before the real receiver. Uniform delay retains every exact packet and original authorization. */
    static boolean delay(Wire.Message message,Consumer<Wire.Message> receiver){
        JsonObject event=null;if(Set.of("session_start","clock_start","clock_resume","checkpoint_request","save_paused","state_ack","state_committed","profile_committed","session_end","error").contains(message.operation())){event=new JsonObject();event.addProperty("operation",message.operation());event.addProperty("receivedAtNanos",System.nanoTime());JsonObject body=new JsonObject();for(String key:List.of("room","epoch","frame","hold","holdToken","saveTransaction","machine","content","generation","profileGeneration","hash","size","operation","code"))if(message.body().has(key))body.add(key,message.body().get(key).deepCopy());event.add("body",body);var atArrival=ConsoleClient.INSTANCE.realtimeMetrics();if(atArrival.isPresent())eventMetrics(event,atArrival.get(),"AtArrival");events.add(event);while(events.size()>128)events.removeFirst();}
        int delay=message.operation().equals("clock_lease")?leaseDelayMillis:message.operation().equals("save_paused")?saveDelayMillis:0;
        if(delay==0)return false;
        var state=ConsoleClient.INSTANCE.realtimeMetrics();if(state.isEmpty()||!message.body().has("room")||!state.get().room().equals(message.body().get("room").getAsString())||state.get().epoch()!=message.body().get("epoch").getAsLong())return false;
        if(delayed.size()>=32)throw new IllegalStateException("QA delayed-response window exceeded");Delayed pending=new Delayed(new Wire.Message(message.operation(),message.body().deepCopy(),message.data().clone()),receiver,event);delayed.add(pending);if(message.operation().equals("clock_lease"))delayedLeases++;
        timer().afterMillis(delay,()->Minecraft.getInstance().execute(()->deliver(pending)));return true;
    }
    private static void eventMetrics(JsonObject event,ConsoleClient.RealtimeMetrics m,String suffix){event.addProperty("coreFrame"+suffix,m.coreFrame());event.addProperty("hold"+suffix,m.hold());}
    private static void deliver(Delayed pending){if(pending.delivered)return;pending.delivered=true;delayed.remove(pending);if(pending.event!=null){pending.event.addProperty("deliveredAtNanos",System.nanoTime());ConsoleClient.INSTANCE.realtimeMetrics().ifPresent(m->eventMetrics(pending.event,m,"AtDelivery"));}pending.receiver.accept(pending.message);}
    private static void flushDelayed(){for(Delayed pending:List.copyOf(delayed))deliver(pending);}
    private static boolean live(Minecraft client,Run r){var state=ConsoleClient.INSTANCE.realtimeMetrics();return state.isPresent()&&r.room.equals(state.get().room())&&r.epoch==state.get().epoch()&&client.screen==r.screen;}
    private static void release(Minecraft client,Run r,Tap tap){if(tap.up!=0)return;if(live(client,r)){tap.up=System.nanoTime();r.screen.keyReleased(r.key,0,0);tap.upSequence=ConsoleClient.INSTANCE.realtimeMetrics().orElseThrow().localInput().sequence();}else finish(client,r,"Screen/session changed during a real key callback");}
    private static void finish(Minecraft client,Run r,String reason){if(r.complete)return;for(Tap tap:r.taps)if(tap.down!=0&&tap.up==0&&client.screen==r.screen){tap.up=System.nanoTime();r.screen.keyReleased(r.key,0,0);}r.screen.clearInputKeys();ConsoleClient.INSTANCE.setInputSource(null);r.finished=System.nanoTime();r.failure=reason;r.complete=true;}
    private static void render(RenderFrameEvent.Post ignored){Minecraft client=Minecraft.getInstance();Run r=active;if(r==null||r.complete)return;
        try{
            if(!live(client,r)){finish(client,r,"Original session or full-screen presentation changed");return;}long now=System.nanoTime();r.renderCount++;if(r.lastRender!=0&&r.renderIntervals.size()<32768)r.renderIntervals.add(now-r.lastRender);r.lastRender=now;
            var m=ConsoleClient.INSTANCE.realtimeMetrics().orElseThrow();int[] guest=pixels(client);
            if(guest!=null&&guest[0]!=r.lastGuestFrame){if(r.lastPicture!=0&&r.presentationIntervals.size()<32768)r.presentationIntervals.add(now-r.lastPicture);r.lastPicture=now;r.lastGuestFrame=guest[0];r.distinctPictures++;for(Tap tap:r.taps)if(tap.down!=0&&tap.observed==0&&guest[1]-r.baselineDowns>=tap.id+1){tap.observed=now;tap.observedGuestFrame=guest[0];}}
            for(var applied:m.localInput().applied())for(Tap tap:r.taps){if(tap.downSequence==applied.sequence()){tap.applied=applied.appliedAtNanos();tap.appliedFrame=applied.frame();}if(tap.upSequence==applied.sequence())tap.upApplied=applied.appliedAtNanos();}
            if(r.next<r.taps.size()&&now>=r.taps.get(r.next).due&&(r.next==0||r.taps.get(r.next-1).up!=0)){Tap tap=r.taps.get(r.next++);tap.down=System.nanoTime();r.screen.keyPressed(r.key,0,0);tap.downSequence=ConsoleClient.INSTANCE.realtimeMetrics().orElseThrow().localInput().sequence();timer().afterMillis(tap.desiredMillis,()->client.execute(()->{if(!r.complete)release(client,r,tap);}));}
            if(now-r.start>=r.duration){if(r.barcode&&guest==null)finish(client,r,"Guest diagnostic disappeared");else if(r.barcode&&((guest[1]-r.baselineDowns&65535)!=r.taps.size()||(guest[2]-r.baselineUps&65535)!=r.taps.size()))finish(client,r,"Actual guest down/up count differs from scheduled normal callbacks");else if(r.next!=r.taps.size()||r.taps.stream().anyMatch(t->t.up==0))finish(client,r,"Scheduled callback sequence is incomplete");else finish(client,r,"");}
        }catch(Exception failure){finish(client,r,"QA render observation failed: "+failure.getClass().getSimpleName());}
    }
    private static int[] pixels(Minecraft client){
        var location=ConsoleClient.INSTANCE.picture();if(location==null)return null;var texture=client.getTextureManager().getTexture(location);if(!(texture instanceof DynamicTexture dynamic))return null;var image=dynamic.getPixels();if(image==null||image.getWidth()!=240||image.getHeight()!=160)return null;
        int[] words=new int[7];for(int row=0;row<7;row++)for(int bit=0;bit<16;bit++){int pixel=image.getPixelRGBA(19+bit*8,17+row*16),r=pixel&255,g=pixel>>>8&255,b=pixel>>>16&255;if(r>220&&g>220&&b>220)words[row]|=1<<bit;else if(r>30||g>30||b>30)return null;}return words[6]==0xA65C?words:null;
    }
    private static JsonObject distribution(List<Long> nanos){JsonObject out=new JsonObject();out.addProperty("count",nanos.size());if(nanos.isEmpty())return out;var sorted=new ArrayList<>(nanos);Collections.sort(sorted);for(double q:new double[]{.5,.95,.99,1})out.addProperty(q==1?"maxMs":"p"+(int)(q*100)+"Ms",sorted.get(Math.min(sorted.size()-1,(int)Math.ceil(sorted.size()*q)-1))/1e6);return out;}
    static JsonObject observe(Minecraft client){JsonObject out=new JsonObject();out.addProperty("physicalOSInput",false);out.addProperty("controlledFrontendCallbacks",true);out.addProperty("inputDeadlineTimer","owned-Windows-high-resolution-waitable-timer");out.addProperty("timerFailure",timer==null?"":timer.failure());out.addProperty("leaseDelayMillis",leaseDelayMillis);out.addProperty("delayedLeases",delayedLeases);out.addProperty("pendingDelayedLeases",delayed.size());out.add("events",JSON.toJsonTree(events));ConsoleClient.INSTANCE.realtimeMetrics().ifPresent(m->out.add("metrics",JSON.toJsonTree(m)));int[] guest=pixels(client);if(guest!=null)out.add("guest",JSON.toJsonTree(guest));Run r=active;if(r==null){out.addProperty("active",false);return out;}
        out.addProperty("active",!r.complete);out.addProperty("complete",r.complete);out.addProperty("failure",r.failure);out.addProperty("room",r.room);out.addProperty("epoch",r.epoch);out.addProperty("elapsedNanos",(r.complete?r.finished:System.nanoTime())-r.start);out.addProperty("renderEvents",r.renderCount);out.addProperty("distinctPresentedGuestFrames",r.distinctPictures);out.addProperty("baselineDowns",r.baselineDowns);out.addProperty("baselineUps",r.baselineUps);out.add("before",JSON.toJsonTree(r.before));
        JsonArray taps=new JsonArray();List<Long> applied=new ArrayList<>(),shown=new ArrayList<>(),held=new ArrayList<>();for(Tap t:r.taps){JsonObject tap=new JsonObject();tap.addProperty("id",t.id);tap.addProperty("desiredMillis",t.desiredMillis);tap.addProperty("dueNanos",t.due);tap.addProperty("downNanos",t.down);tap.addProperty("upNanos",t.up);tap.addProperty("downSequence",t.downSequence);tap.addProperty("upSequence",t.upSequence);tap.addProperty("appliedNanos",t.applied);tap.addProperty("appliedFrame",t.appliedFrame);tap.addProperty("upAppliedNanos",t.upApplied);tap.addProperty("firstObservedNanos",t.observed);tap.addProperty("observedGuestFrame",t.observedGuestFrame);taps.add(tap);if(t.applied!=0&&t.down!=0)applied.add(t.applied-t.down);if(t.observed!=0&&t.down!=0)shown.add(t.observed-t.down);if(t.up!=0&&t.down!=0)held.add(t.up-t.down);}out.add("taps",taps);out.add("callbackToApplied",distribution(applied));out.add("callbackToPresentedGuestPixels",distribution(shown));out.add("actualCallbackHoldDuration",distribution(held));out.add("presentationCadence",distribution(r.presentationIntervals));out.add("renderCadence",distribution(r.renderIntervals));return out;
    }
    private LatencyActions(){}
}
