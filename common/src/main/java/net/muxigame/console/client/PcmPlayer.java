package net.muxigame.console.client;

import javax.sound.sampled.*;
import net.muxigame.console.client.audio.StereoResampler;
import net.muxigame.console.client.audio.PcmSampleQueue;

/** Nonblocking producer, duration-bounded PCM ring and an independent JavaSound consumer. */
public final class PcmPlayer implements AutoCloseable {
    private static final int RATE=48000,BUFFER_BYTES=RATE*4*60/1000;
    private final PcmSampleQueue queue=new PcmSampleQueue(RATE*120/1000);
    private final StereoResampler resampler=new StereoResampler(RATE);
    private volatile boolean closed,opened,outputAvailable,started;private volatile SourceDataLine line;private Thread thread;
    private volatile long writtenFrames,playedFrames,underruns,startupDiscardedFrames,startupTrimmedFrames,deviceOpenNanos,deviceStartNanos;private volatile int actualBufferBytes,availableBytes;private volatile String errorCode="";
    public record Metrics(boolean opened,boolean outputAvailable,int outputRate,int requestedBufferBytes,int actualBufferBytes,int availableBytes,long offeredFrames,long writtenFrames,long playedFrames,int queuedFrames,long droppedFrames,long underruns,String errorCode,boolean started,long startupDiscardedFrames,long startupTrimmedFrames,long deviceOpenNanos,long deviceStartNanos){}
    public Metrics metrics(){return new Metrics(opened,outputAvailable,RATE,BUFFER_BYTES,actualBufferBytes,availableBytes,queue.offeredFrames(),writtenFrames,playedFrames,queue.frames(),queue.droppedFrames(),underruns,errorCode,started,startupDiscardedFrames,startupTrimmedFrames,deviceOpenNanos,deviceStartNanos);}
    public synchronized void offer(short[] pcm,double rate){
        if(closed||pcm.length==0||!Double.isFinite(rate)||rate<8000||rate>192000)return;
        short[] output=resampler.convert(pcm,rate);if(output.length==0)return;queue.offer(output);
        if(thread==null){thread=new Thread(this::run,"muxi-console-audio");thread.setDaemon(true);thread.start();}
    }
    private void run(){
        try{
            long opening=System.nanoTime();AudioFormat format=new AudioFormat(RATE,16,2,true,false);SourceDataLine device=AudioSystem.getSourceDataLine(format);line=device;
            device.open(format,BUFFER_BYTES);deviceOpenNanos=System.nanoTime()-opening;actualBufferBytes=device.getBufferSize();opened=outputAvailable=true;
            device.addLineListener(event->{if(!closed&&event.getType()==LineEvent.Type.STOP&&device.isRunning()&&writtenFrames>0)underruns++;});
            // Opening a Windows output line can take longer than the bounded producer ring.
            // Keep the newest 40 ms once, count all discarded startup PCM, then prime the
            // stopped device with real audio before playback begins. Never trim steady audio.
            int primingFrames=Math.max(1,Math.min(RATE*40/1000,actualBufferBytes/4));startupTrimmedFrames=queue.discardTo(primingFrames);
            while(!closed&&queue.frames()<primingFrames)Thread.sleep(1);
            while(!closed){
                short[] samples=queue.poll(started?RATE/100:primingFrames);if(samples.length==0){availableBytes=device.available();playedFrames=device.getLongFramePosition();Thread.sleep(1);continue;}
                byte[] bytes=new byte[samples.length*2];for(int i=0;i<samples.length;i++){short v=(short)(samples[i]*.35);bytes[i*2]=(byte)v;bytes[i*2+1]=(byte)(v>>>8);}
                int at=0;while(!closed&&at<bytes.length){int written=device.write(bytes,at,bytes.length-at);if(written<0||written%4!=0)throw new IllegalStateException("PCM device returned an invalid frame count");if(written==0){Thread.sleep(1);continue;}at+=written;writtenFrames+=written/4;}
                if(!started&&!closed){long starting=System.nanoTime();device.start();deviceStartNanos=System.nanoTime()-starting;startupTrimmedFrames+=queue.discardTo(RATE*20/1000);startupDiscardedFrames=queue.droppedFrames();started=true;}availableBytes=device.available();playedFrames=device.getLongFramePosition();
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(Exception unavailable){errorCode=unavailable.getClass().getSimpleName();outputAvailable=false;}
        finally{SourceDataLine device=line;if(device!=null)device.close();}
    }
    public void close(){closed=true;queue.clear();SourceDataLine device=line;if(device!=null){device.stop();device.flush();device.close();}if(thread!=null)thread.interrupt();}
}
