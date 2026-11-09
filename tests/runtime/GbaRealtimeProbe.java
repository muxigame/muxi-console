package net.muxigame.console.tests.runtime;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import javax.imageio.ImageIO;
import com.sun.jna.*;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.win32.StdCallLibrary;
import net.muxigame.console.runtime.*;

/** Paced ordinary-core baseline, reading guest pixels and real PCM, never serializing per frame. */
public final class GbaRealtimeProbe {
    private record Guest(int frame,int downs,int ups,int keys,int lastDownFrame,int beat){}
    private static final int SIGNATURE=0xA65C;
    private static final class Tap {
        final int id,desiredMillis;final long on,off;boolean sampled;long firstSample,ack;int expectedCount;
        Tap(int id,long on,int millis){this.id=id;this.on=on;off=on+millis*1_000_000L;desiredMillis=millis;}
    }
    private static final class Sound {
        final double rate,threshold;long samples,nonzero,crossings;int previousLeft,previousRight,maxLeft,maxRight;
        final List<Long> onsets=new ArrayList<>();
        Sound(double rate,int rightPeak){this.rate=rate;threshold=Math.max(64,rightPeak*.35);}
        void accept(short[] pcm){
            for(int p=0;p<pcm.length;p+=2){int left=pcm[p],right=pcm[p+1];if(left!=0||right!=0)nonzero++;
                if(previousLeft<=0&&left>0)crossings++;previousLeft=left;maxLeft=Math.max(maxLeft,Math.abs(left));maxRight=Math.max(maxRight,Math.abs(right));
                // The PSG DAC has a real DC decay when disabled. Detect AC
                // transitions, rather than inventing ticks from that tail.
                if(Math.abs(right-previousRight)>threshold&&(onsets.isEmpty()||samples-onsets.get(onsets.size()-1)>rate*.25))onsets.add(samples);
                previousRight=right;
                samples++;
            }
        }
    }
    private static final List<Map<String,Object>> CHECKS=new ArrayList<>();
    private static void check(boolean value,String label){CHECKS.add(Map.of("passed",value,"label",label));if(!value)throw new AssertionError(label);}
    private static Guest decode(AvFrame frame)throws IOException{
        if(frame.width()!=240||frame.height()!=160)throw new IOException("GBA source geometry differs");
        int[] words=new int[7];for(int row=0;row<7;row++)for(int bit=0;bit<16;bit++){
            int p=((17+row*16)*240+19+bit*8)*4,r=frame.rgba()[p]&255,g=frame.rgba()[p+1]&255,b=frame.rgba()[p+2]&255;
            if(r>220&&g>220&&b>220)words[row]|=1<<bit;else if(r>30||g>30||b>30)throw new IOException("Guest barcode is not black/white");
        }
        if(words[6]!=SIGNATURE)throw new IOException("Actual guest diagnostic signature not present");
        return new Guest(words[0],words[1],words[2],words[3],words[4],words[5]);
    }
    private static void picture(AvFrame frame,Path path)throws IOException{
        BufferedImage image=new BufferedImage(frame.width(),frame.height(),BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<frame.height();y++)for(int x=0;x<frame.width();x++){int p=(y*frame.width()+x)*4;image.setRGB(x,y,(frame.rgba()[p]&255)<<16|(frame.rgba()[p+1]&255)<<8|(frame.rgba()[p+2]&255));}
        ImageIO.write(image,"PNG",path.toFile());
    }
    private static Map<String,Object> distribution(List<Long> nanos){
        if(nanos.isEmpty())return Map.of("count",0);List<Long> sorted=new ArrayList<>(nanos);Collections.sort(sorted);
        Map<String,Object> out=new LinkedHashMap<>();out.put("count",sorted.size());for(double q:new double[]{.5,.95,.99,1})out.put(q==1?"maxMs":"p"+(int)(q*100)+"Ms",sorted.get(Math.min(sorted.size()-1,(int)Math.ceil(sorted.size()*q)-1))/1_000_000.0);return out;
    }
    private interface TimerKernel extends StdCallLibrary {
        Pointer CreateWaitableTimerExW(Pointer security,Pointer name,int flags,int access);
        boolean SetWaitableTimer(Pointer timer,LongByReference due,int period,Pointer callback,Pointer argument,boolean resume);
        int WaitForSingleObject(Pointer handle,int milliseconds);
        boolean CloseHandle(Pointer handle);
    }
    /** An owned high-resolution timer; never changes the system timer period. */
    private static final class Pacer implements AutoCloseable {
        final TimerKernel api;final Pointer timer;
        Pacer()throws IOException{
            if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")){api=null;timer=null;return;}
            api=Native.load("kernel32",TimerKernel.class);timer=api.CreateWaitableTimerExW(Pointer.NULL,Pointer.NULL,2,0x1F0003);
            if(timer==null)throw new IOException("Owned high-resolution timer unavailable, error="+Native.getLastError());
        }
        String kind(){return timer==null?"LockSupport-non-Windows":"owned-Windows-high-resolution-waitable-timer";}
        void until(long deadline)throws IOException{
            while(true){long left=deadline-System.nanoTime();if(left<=0)return;
                if(timer==null)LockSupport.parkNanos(left);
                else if(!api.SetWaitableTimer(timer,new LongByReference(-Math.max(1,left/100)),0,Pointer.NULL,Pointer.NULL,false)||api.WaitForSingleObject(timer,5000)!=0)throw new IOException("Owned pacing timer failed");
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Paced baseline interrupted");
            }
        }
        public void close()throws IOException{if(timer!=null&&!api.CloseHandle(timer))throw new IOException("Owned pacing timer did not close");}
    }
    private static String json(Object value){
        if(value==null)return"null";if(value instanceof Boolean||value instanceof Number)return value.toString();
        if(value instanceof Map<?,?> map){StringJoiner join=new StringJoiner(",","{","}");for(var e:map.entrySet())join.add(json(e.getKey().toString())+":"+json(e.getValue()));return join.toString();}
        if(value instanceof Iterable<?> list){StringJoiner join=new StringJoiner(",","[","]");for(Object entry:list)join.add(json(entry));return join.toString();}
        String s=value.toString();StringBuilder out=new StringBuilder("\"");for(char c:s.toCharArray()){if(c=='"'||c=='\\')out.append('\\').append(c);else if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);}return out.append('"').toString();
    }
    public static void main(String[] args)throws Exception{
        Path java=Path.of(args[0]),core=Path.of(args[2]),rom=Path.of(args[3]),out=Path.of(args[4]);int seconds=Integer.parseInt(args[5]);
        if(seconds<3||seconds>120)throw new IllegalArgumentException("Bounded 3..120 second source measurement required");
        List<Path> cp=Arrays.stream(args[1].split(File.pathSeparator)).map(Path::of).toList();Map<String,Object> report=new LinkedHashMap<>();report.put("schema",1);report.put("checks",CHECKS);report.put("measurementSeconds",seconds);
        report.put("scope","Ordinary actual mGBA worker paced in wall time. Frame-sampled software input and generated PCM only; not Minecraft, OS-input, physical screen or speaker latency.");report.put("minecraftStarted",false);report.put("audioDevicePlayed",false);report.put("actualLineMetrics",null);report.put("audioUnderrunMeasured",false);report.put("perFrameSaveStateCalls",0);
        ProcessConsoleRuntime runtime=null;Throwable failure=null;
        try{
            runtime=ProcessConsoleRuntime.open(new LaunchConfig(java,cp,core,rom,out.resolve("empty-system"),out.resolve("native-save"),Map.of(),"gba"));report.put("workerPid",runtime.workerPid());
            double fps=runtime.info().fps(),rate=runtime.info().sampleRate();check(Double.isFinite(fps)&&fps>59&&fps<61,"Actual ordinary mGBA native frame rate is valid");
            AvFrame current=null;int peakRight=0;for(int i=0;i<90;i++){current=runtime.step(InputFrame.idle());for(int p=1;p<current.stereoPcm().length;p+=2)peakRight=Math.max(peakRight,Math.abs((int)current.stereoPcm()[p]));}
            picture(current,out.resolve("native-warmup.png"));Guest guest=decode(current);check(guest.frame>60&&guest.downs==0&&guest.ups==0,"Actual VBlank barcode is live and starts without injected input");check(peakRight>100,"Original hardware PSG metronome emits actual right-channel PCM");
            rate=current.sampleRate();check(Double.isFinite(rate)&&rate>=8000&&rate<=192000,"Actual PCM rate is recorded instead of assuming 48 kHz");Sound sound=new Sound(rate,peakRight);
            List<Tap> taps=new ArrayList<>();for(int id=0;;id++){long on=500_000_000L+id*173_000_000L+(id*37L%47)*1_000_000L;if(on+30_000_000L>seconds*1_000_000_000L)break;taps.add(new Tap(id,on,10+(id%3)*10));}
            List<Long> steps=new ArrayList<>(),cadence=new ArrayList<>(),late=new ArrayList<>(),observedLatency=new ArrayList<>(),scheduledLatency=new ArrayList<>();Deque<Tap> pending=new ArrayDeque<>();int deliveredDown=0,deliveredUp=0,lastMask=0,lastObserved=guest.downs,previousGuest=guest.frame;long start=System.nanoTime(),previousReturn=0;int measured=0,index=0;
            try(var trace=Files.newBufferedWriter(out.resolve("frames.csv"));var pacer=new Pacer()){
                report.put("pacer",pacer.kind());report.put("systemTimerPeriodChanged",false);
                trace.write("step,elapsedNs,startLateNs,stepNs,mask,guestFrame,guestDowns,guestUps,lastDownGuestFrame,beat,pcmFrameStart,pcmFrames\n");
                for(int step=0;step<(int)Math.floor(seconds*fps);step++){
                    long deadline=start+Math.round(step*1_000_000_000.0/fps);pacer.until(deadline);long before=System.nanoTime(),elapsed=before-start;
                    while(index<taps.size()&&elapsed>=taps.get(index).off)index++;Tap tap=index<taps.size()&&elapsed>=taps.get(index).on?taps.get(index):null;int mask=tap==null?0:1<<8;
                    if(mask!=0&&lastMask==0){deliveredDown++;tap.sampled=true;tap.firstSample=before;tap.expectedCount=deliveredDown;pending.addLast(tap);}else if(mask==0&&lastMask!=0)deliveredUp++;lastMask=mask;
                    current=runtime.step(new InputFrame(List.of(new PortInput(mask))));long returned=System.nanoTime();steps.add(returned-before);late.add(Math.max(0,before-deadline));if(previousReturn!=0)cadence.add(returned-previousReturn);previousReturn=returned;
                    guest=decode(current);checkStep(guest.frame==previousGuest+1,"Guest VBlank counter skipped or repeated during paced stepping");previousGuest=guest.frame;
                    if(guest.downs>lastObserved){checkStep(guest.downs==lastObserved+1&&!pending.isEmpty(),"Guest edge count has an unexplained jump");Tap ack=pending.removeFirst();checkStep(guest.downs==ack.expectedCount,"Guest edge receipt differs from the actual delivered native mask");ack.ack=returned;observedLatency.add(returned-ack.firstSample);scheduledLatency.add(returned-start-ack.on);lastObserved=guest.downs;}
                    checkStep(current.sampleRate()==rate,"Actual core PCM format changed mid measurement");long pcmStart=sound.samples;sound.accept(current.stereoPcm());
                    trace.write(step+","+elapsed+","+Math.max(0,before-deadline)+","+(returned-before)+","+mask+","+guest.frame+","+guest.downs+","+guest.ups+","+guest.lastDownFrame+","+guest.beat+","+pcmStart+","+(current.stereoPcm().length/2)+"\n");measured++;
                }
            }
            long ended=System.nanoTime();for(int i=0;i<3;i++){current=runtime.step(InputFrame.idle());guest=decode(current);if(guest.downs>lastObserved){Tap ack=pending.removeFirst();ack.ack=System.nanoTime();observedLatency.add(ack.ack-ack.firstSample);scheduledLatency.add(ack.ack-start-ack.on);lastObserved=guest.downs;}}
            if(lastMask!=0)deliveredUp++;picture(current,out.resolve("native-final.png"));double elapsed=(ended-start)/1e9,achieved=measured/elapsed;report.put("nativeFps",fps);report.put("actualFps",achieved);report.put("elapsedSeconds",elapsed);report.put("measuredFrames",measured);report.put("stepService",distribution(steps));report.put("sourceFrameReturnCadence",distribution(cadence));report.put("pacedDeadlineLateness",distribution(late));report.put("deliveredNativeMaskToGuestPixel",distribution(observedLatency));report.put("scheduledSoftwarePulseToGuestPixel",distribution(scheduledLatency));
            check(Math.abs(achieved/fps-1)<.03,"Wall-paced ordinary worker remains within three percent of its actual native FPS");check(guest.downs==deliveredDown&&guest.ups==deliveredUp&&pending.isEmpty(),"Every actually delivered native down/up edge is visible once in real guest pixels");
            List<Map<String,Object>> widths=new ArrayList<>();for(int width:new int[]{10,20,30}){List<Tap> group=taps.stream().filter(t->t.desiredMillis==width).toList();widths.add(Map.of("desiredMillis",width,"scheduled",group.size(),"intersectedNativeFrameSample",group.stream().filter(t->t.sampled).count(),"acknowledgedByGuestPixels",group.stream().filter(t->t.ack!=0).count(),"notSampled",group.stream().filter(t->!t.sampled).count()));}report.put("softwarePulseWidths",widths);report.put("pulsePhasePlan","500ms + id*173ms + (id*37 mod 47)ms; widths rotate 10/20/30ms, avoiding the 31-native-frame resonance of a plain 519ms group");report.put("shortPulseCaveat","A pulse wholly between native frame samples is reported as notSampled, not falsely credited or called core input loss. MC callback edge preservation requires its own subsequent test.");
            List<Long> periods=new ArrayList<>(),errors=new ArrayList<>();double expectedSamples=30/fps*rate;for(int i=1;i<sound.onsets.size();i++){long delta=sound.onsets.get(i)-sound.onsets.get(i-1);periods.add(Math.round(delta/rate*1e9));errors.add(Math.round(Math.abs(delta-expectedSamples)/rate*1e9));}
            Map<String,Object> audio=new LinkedHashMap<>();audio.put("sourceSampleRate",rate);audio.put("sourceStereoFrames",sound.samples);audio.put("sourceNonzeroStereoFrames",sound.nonzero);audio.put("pilotPositiveCrossingHz",sound.crossings/(sound.samples/rate));audio.put("metronomeOnsets",sound.onsets);audio.put("metronomeExpectedPeriodMs",30/fps*1000);audio.put("metronomePeriods",distribution(periods));audio.put("metronomePeriodError",distribution(errors));audio.put("maxAbsLeft",sound.maxLeft);audio.put("maxAbsRight",sound.maxRight);audio.put("onsetThreshold",sound.threshold);audio.put("onsetDetector","right-channel first difference with 250ms debounce; not DC level");audio.put("actualDeviceOutput",false);audio.put("noLineUnderrunClaim",true);report.put("audio",audio);
            check(sound.nonzero>1000&&Math.abs(sound.crossings/(sound.samples/rate)-512)<60,"Actual generated left pilot remains near the original 512 Hz");check(sound.onsets.size()>=Math.floor(seconds*fps/30)-2&&sound.onsets.size()<=Math.ceil(seconds*fps/30)+2,"Actual right-channel ticks have the expected count");check(!errors.isEmpty()&&errors.stream().mapToLong(Long::longValue).max().orElseThrow()<5_000_000,"Generated PCM metronome intervals have less than five milliseconds of source jitter");
        }catch(Throwable problem){failure=problem;report.put("failureType",problem.getClass().getSimpleName());report.put("failure",problem instanceof AssertionError?problem.getMessage():"Actual native baseline failed; see original native log");}
        finally{
            if(runtime!=null){try{runtime.close();boolean normal=!runtime.isAlive()&&runtime.exitCode().orElse(-1)==0&&!runtime.wasForcedShutdown()&&runtime.nativeTemporaryFilesCleaned()&&!Files.exists(runtime.nativeTemporaryDirectory());report.put("workerClosure",Map.of("pid",runtime.workerPid(),"normalExit",normal,"exitCode",runtime.exitCode().orElse(-1),"forced",runtime.wasForcedShutdown(),"nativeTemporaryFilesCleaned",runtime.nativeTemporaryFilesCleaned(),"actualOwnedProcessSignalRequired",true));if(!normal)throw new IOException("Actual worker closure was not clean");}catch(Throwable closing){if(failure==null)failure=closing;report.put("cleanupFailureType",closing.getClass().getSimpleName());}}
            report.put("complete",failure==null);Files.writeString(out.resolve("result.json"),json(report)+"\n");
        }
        System.out.println(json(Map.of("complete",failure==null,"checks",CHECKS.size(),"result","result.json","audioDevicePlayed",false)));
        if(failure!=null)throw new IOException("Paced baseline failed; original JSON/log retained",failure);
    }
    private static void checkStep(boolean value,String label)throws IOException{if(!value)throw new IOException(label);}
}
