package net.muxigame.console.client;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import net.muxigame.console.client.audio.StereoResampler;
import net.muxigame.console.compat.BrowserFrameCodec;
import net.muxigame.console.mc.SharedPlayServer;
import net.muxigame.console.mc.Wire;
import net.minecraft.client.Minecraft;

/** One host worker executes hardware cores. Other occupied seats receive pixels/PCM and still submit inputs. */
public final class SharedPlayClient {
    public static final SharedPlayClient INSTANCE=new SharedPlayClient();
    public record Lease(String room,long epoch,int port,boolean leader){public Lease{if(room==null||room.isBlank()||room.length()>256||epoch<0||port<0||port>3)throw new IllegalArgumentException("Invalid shared play lease");}}
    public record Snapshot(long sentVideo,long receivedVideo,long sentAudio,long receivedAudio,long droppedVideo,long droppedAudio,long lastVideo,long lastAudio,String lastError,long sentVideoFrame,long sentAudioFrame,String sentVideoCrc,String receivedVideoCrc,String sentAudioCrc,String receivedAudioCrc){}
    /** Pure audio assembler: one partial batch at a time, bounded memory, exact little-endian stereo pairs. */
    public static final class AudioAssembler {
        private long frame=-1,started;private byte[] batch;private int next,parts,total;
        public short[] accept(long incoming,int part,int count,int length,byte[] data,long now)throws IOException{
            try{SharedPlayServer.Gate.audioBounds(part,count,length,data.length);}catch(IllegalArgumentException invalid){throw new IOException(invalid.getMessage(),invalid);}
            if(incoming<0||incoming<frame)return null;
            if(incoming>frame){if(part!=0)return null;frame=incoming;started=now;batch=new byte[length];next=0;parts=count;total=length;}
            if(batch==null||now-started>1_000_000_000L||part!=next||count!=parts||length!=total)return null;
            System.arraycopy(data,0,batch,part*SharedPlayServer.AUDIO_PART_BYTES,data.length);next++;if(next!=parts)return null;short[] pcm=decode(batch);batch=null;return pcm;
        }
        public void clear(){frame=-1;batch=null;next=parts=total=0;}
    }
    public static byte[] encode(short[] stereo){if(stereo==null||stereo.length%2!=0||stereo.length*2L>SharedPlayServer.AUDIO_MAX_BYTES)throw new IllegalArgumentException("Invalid shared PCM");byte[] data=new byte[stereo.length*2];for(int i=0;i<stereo.length;i++){data[i*2]=(byte)stereo[i];data[i*2+1]=(byte)(stereo[i]>>>8);}return data;}
    public static short[] decode(byte[] data){if(data==null||data.length%4!=0||data.length>SharedPlayServer.AUDIO_MAX_BYTES)throw new IllegalArgumentException("Invalid shared PCM");short[] samples=new short[data.length/2];for(int i=0;i<samples.length;i++)samples[i]=(short)((data[i*2]&255)|(data[i*2+1]&255)<<8);return samples;}
    private final ExecutorService encoder=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"muxi-console-shared-encoder");t.setDaemon(true);return t;});
    private final AtomicBoolean videoQueued=new AtomicBoolean(),audioQueued=new AtomicBoolean();private final AudioAssembler assembler=new AudioAssembler();
    private volatile long generation,sentVideo,receivedVideo,sentAudio,receivedAudio,droppedVideo,droppedAudio;private volatile String lastError="";private volatile Lease startedLease;
    private long sentVideoFrame=-1,sentAudioFrame=-1;private String sentVideoCrc="",receivedVideoCrc="",sentAudioCrc="",receivedAudioCrc="";
    private long nextVideo,lastVideo=-1,lastAudio=-1;private StereoResampler resampler=new StereoResampler(48000);
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static Lease lease(){return ConsoleClient.INSTANCE.sharedLease();}
    private static boolean same(Lease a,Lease b){return a!=null&&a.equals(b);}
    private static boolean addressed(JsonObject j,Lease lease){return lease!=null&&!lease.leader&&lease.room.equals(j.get("room").getAsString())&&lease.epoch==j.get("epoch").getAsLong()&&lease.port==j.get("port").getAsInt();}
    private static JsonObject packet(Lease lease,long frame){JsonObject j=new JsonObject();j.addProperty("room",lease.room);j.addProperty("epoch",lease.epoch);j.addProperty("port",lease.port);if(frame>=0)j.addProperty("frame",frame);return j;}
    private static String crc(byte[] data){CRC32 crc=new CRC32();crc.update(data);return Long.toHexString(crc.getValue());}
    public synchronized Snapshot snapshot(){return new Snapshot(sentVideo,receivedVideo,sentAudio,receivedAudio,droppedVideo,droppedAudio,lastVideo,lastAudio,lastError,sentVideoFrame,sentAudioFrame,sentVideoCrc,receivedVideoCrc,sentAudioCrc,receivedAudioCrc);}
    public synchronized void start(JsonObject context){Lease current=lease();if(current==null){stop();return;}if(same(startedLease,current))return;stop();startedLease=current;if(!current.leader&&mc().getConnection()!=null)Wire.command("shared_attach",packet(current,-1));}
    public synchronized void stop(){generation++;startedLease=null;nextVideo=0;lastVideo=lastAudio=sentVideoFrame=sentAudioFrame=-1;sentVideoCrc=receivedVideoCrc=sentAudioCrc=receivedAudioCrc="";assembler.clear();resampler=new StereoResampler(48000);}
    public void frame(long number,int width,int height,byte[] rgba){Lease current=lease();if(current==null||!current.leader||number<0)return;if(rgba==null||width<1||height<1||(long)width*height>BrowserFrameCodec.MAX_SOURCE_PIXELS||(long)width*height*4!=rgba.length){droppedVideo++;lastError="共享画面尺寸无效";return;}long selected; synchronized(this){long now=System.nanoTime();if(now<nextVideo||!videoQueued.compareAndSet(false,true)){droppedVideo++;return;}nextVideo=now+50_000_000L;selected=generation;}
        byte[] pixels=rgba.clone();encoder.execute(()->{try{byte[] png=BrowserFrameCodec.encode(width,height,pixels,512);mc().execute(()->{if(selected!=generation||!same(current,lease())||mc().getConnection()==null)return;Wire.command("shared_frame",packet(current,number),png);sentVideo++;sentVideoFrame=number;sentVideoCrc=crc(png);});}catch(IOException|RuntimeException bad){lastError="共享画面编码失败："+bad.getMessage();}finally{videoQueued.set(false);}});
    }
    public void audio(long number,short[] pcm,double rate){Lease current=lease();if(current==null||!current.leader||number<0||pcm==null||pcm.length==0)return;
        if(!Double.isFinite(rate)||rate<8000||rate>192000||pcm.length%2!=0||pcm.length/(2*rate)>.5){droppedAudio++;lastError="共享音频批次无效";return;}if(!audioQueued.compareAndSet(false,true)){droppedAudio++;return;}long selected=generation;short[] source=pcm.clone();StereoResampler converter; synchronized(this){converter=resampler;}
        encoder.execute(()->{try{short[] normalized=converter.convert(source,rate);if(normalized.length==0)return;byte[] data=encode(normalized);int parts=(data.length+SharedPlayServer.AUDIO_PART_BYTES-1)/SharedPlayServer.AUDIO_PART_BYTES;mc().execute(()->{if(selected!=generation||!same(current,lease())||mc().getConnection()==null)return;for(int part=0;part<parts;part++){int from=part*SharedPlayServer.AUDIO_PART_BYTES;byte[] piece=Arrays.copyOfRange(data,from,Math.min(data.length,from+SharedPlayServer.AUDIO_PART_BYTES));JsonObject j=packet(current,number);j.addProperty("part",part);j.addProperty("parts",parts);j.addProperty("totalBytes",data.length);j.addProperty("rate",48000);Wire.command("shared_audio",j,piece);}sentAudio++;sentAudioFrame=number;sentAudioCrc=crc(data);});}catch(RuntimeException bad){lastError="共享音频编码失败："+bad.getMessage();}finally{audioQueued.set(false);}});
    }
    public synchronized boolean receive(Wire.Message message){if(!message.operation().startsWith("shared_"))return false;Lease current=lease();JsonObject j=message.body();try{if(!addressed(j,current))return true;
        switch(message.operation()){
            case "shared_ready" -> ConsoleClient.INSTANCE.sharedReady(current.room,current.epoch);
            case "shared_frame" -> {long frame=j.get("frame").getAsLong();if(frame<0||frame<=lastVideo)return true;BrowserFrameCodec.Image image=BrowserFrameCodec.decode(message.data());if(j.get("width").getAsInt()!=image.width()||j.get("height").getAsInt()!=image.height())throw new IOException("Shared image dimensions differ");lastVideo=frame;receivedVideo++;receivedVideoCrc=crc(message.data());ConsoleClient.INSTANCE.sharedPicture(current.room,current.epoch,frame,image.width(),image.height(),image.rgba());}
            case "shared_audio" -> {long frame=j.get("frame").getAsLong();if(frame<0||frame<=lastAudio)return true;if(j.get("rate").getAsInt()!=48000)throw new IOException("Shared audio rate");short[] pcm=assembler.accept(frame,j.get("part").getAsInt(),j.get("parts").getAsInt(),j.get("totalBytes").getAsInt(),message.data(),System.nanoTime());if(pcm!=null){lastAudio=frame;receivedAudio++;receivedAudioCrc=crc(encode(pcm));ConsoleClient.INSTANCE.sharedAudio(current.room,current.epoch,frame,pcm);}}
            default -> throw new IOException("Unknown shared event");
        }
    }catch(Exception bad){lastError="共享游戏数据无效："+Objects.toString(bad.getMessage(),bad.getClass().getSimpleName());assembler.clear();}return true;}
    private SharedPlayClient(){}
}
