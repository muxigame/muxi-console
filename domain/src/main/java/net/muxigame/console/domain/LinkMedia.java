package net.muxigame.console.domain;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Lossless, bounded transport for one endpoint. No video or audio from its partner is combined. */
public final class LinkMedia {
    public static final int WIDTH=160, HEIGHT=144, RGBA_BYTES=WIDTH*HEIGHT*4, PART=24_576, MAX_COMPRESSED=RGBA_BYTES+1024;
    /** Media follows completed CPU work; queued but unexecuted batches cannot age a legal picture out. */
    public static boolean frameWithinWindow(long frame,long issuedFrame,long completedFrame){
        return frame>=0&&completedFrame>=0&&completedFrame<=issuedFrame&&frame<=issuedFrame&&frame>=Math.max(0,completedFrame-12);
    }
    public static byte[] encode(byte[] rgba)throws IOException {
        return encode(rgba,WIDTH,HEIGHT);
    }
    private static int pixels(int width,int height)throws IOException{if(width==160&&height==144||width==240&&height==160||width==256&&height==384||width==400&&height==480)return width*height*4;throw new IOException("Unsupported link picture geometry");}
    public static byte[] encode(byte[] rgba,int width,int height)throws IOException {
        int size=pixels(width,height);if(rgba==null||rgba.length!=size)throw new IOException("Invalid link picture");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DeflaterOutputStream output=new DeflaterOutputStream(bytes)){output.write(rgba);}
        byte[] result=bytes.toByteArray();if(result.length>size+1024)throw new IOException("Link picture exceeds bounds");return result;
    }
    public static byte[] decode(byte[] bytes)throws IOException {
        return decode(bytes,WIDTH,HEIGHT);
    }
    public static byte[] decode(byte[] bytes,int width,int height)throws IOException {
        int size=pixels(width,height);if(bytes==null||bytes.length<1||bytes.length>size+1024)throw new IOException("Invalid compressed link picture");
        Inflater inflater=new Inflater();
        try{inflater.setInput(bytes);byte[] output=new byte[size];int n=0;
            while(n<output.length){int count=inflater.inflate(output,n,output.length-n);if(count==0)break;n+=count;}
            if(n!=output.length||inflater.inflate(new byte[1])!=0||!inflater.finished()||inflater.getRemaining()!=0)throw new IOException("Invalid link picture bounds");return output;
        }catch(DataFormatException invalid){throw new IOException("Invalid link picture",invalid);}finally{inflater.end();}
    }
    public static byte[] pcm(short[] samples)throws IOException {
        if(samples==null||(samples.length&1)!=0||samples.length>8192)throw new IOException("Invalid link audio");
        byte[] bytes=new byte[samples.length*2];for(int i=0;i<samples.length;i++){bytes[i*2]=(byte)samples[i];bytes[i*2+1]=(byte)(samples[i]>>>8);}return bytes;
    }
    public static short[] pcm(byte[] bytes)throws IOException {
        if(bytes==null||bytes.length%4!=0||bytes.length>16384)throw new IOException("Invalid link audio");
        short[] samples=new short[bytes.length/2];for(int i=0;i<samples.length;i++)samples[i]=(short)((bytes[i*2]&255)|(bytes[i*2+1]&255)<<8);return samples;
    }
    public static final class Assembler {
        private final int width,height,max;
        public Assembler(){width=WIDTH;height=HEIGHT;max=MAX_COMPRESSED;}
        public Assembler(int width,int height)throws IOException{this.width=width;this.height=height;max=pixels(width,height)+1024;}
        private long frame=-1,started;private byte[] data;private int next;
        public byte[] accept(long number,int offset,int total,byte[] piece,long now)throws IOException {
            if(number<0||total<1||total>max||offset<0||offset>total||piece==null||piece.length<1||piece.length>PART||piece.length>total-offset)throw new IOException("Invalid link fragment");
            if(number<frame)return null;
            if(number>frame){if(offset!=0)return null;frame=number;started=now;data=new byte[total];next=0;}
            if(data==null||now-started>2000||offset!=next||data.length!=total)return null;
            System.arraycopy(piece,0,data,offset,piece.length);next+=piece.length;
            if(next!=total)return null;byte[] completed=data;data=null;decode(completed,width,height);return completed;
        }
        public void clear(){frame=-1;data=null;next=0;}
    }
    private LinkMedia(){}
}
