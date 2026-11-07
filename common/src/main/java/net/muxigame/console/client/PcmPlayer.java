package net.muxigame.console.client;

import java.util.concurrent.*;
import javax.sound.sampled.*;

/** Bounded PCM playback; an unavailable output device cannot stall emulation or Minecraft. */
final class PcmPlayer implements AutoCloseable {
    private record Sound(short[] samples,double rate){}
    private final BlockingQueue<Sound> queue=new ArrayBlockingQueue<>(4);
    private volatile boolean closed;private volatile SourceDataLine line;private Thread thread;
    synchronized void offer(short[] pcm,double rate){if(closed||pcm.length==0||rate<8000||rate>192000)return;if(thread==null){thread=new Thread(this::run,"muxi-console-audio");thread.setDaemon(true);thread.start();}if(!queue.offer(new Sound(pcm.clone(),rate))){queue.poll();queue.offer(new Sound(pcm.clone(),rate));}}
    private void run(){try{double last=0;while(!closed){Sound s=queue.poll(1,TimeUnit.SECONDS);if(s==null)continue;if(line==null||last!=s.rate){if(line!=null)line.close();AudioFormat format=new AudioFormat((float)s.rate,16,2,true,false);line=AudioSystem.getSourceDataLine(format);line.open(format,Math.max(4096,(int)s.rate/5*4));line.start();last=s.rate;}byte[] bytes=new byte[s.samples.length*2];for(int i=0;i<s.samples.length;i++){short v=(short)(s.samples[i]*.35);bytes[i*2]=(byte)v;bytes[i*2+1]=(byte)(v>>>8);}if(!closed)line.write(bytes,0,bytes.length);}}catch(Exception unavailable){/* Optional audio device; video and input remain usable. */}finally{SourceDataLine l=line;if(l!=null)l.close();}}
    public void close(){closed=true;queue.clear();SourceDataLine l=line;if(l!=null){l.stop();l.flush();l.close();}if(thread!=null)thread.interrupt();}
}
