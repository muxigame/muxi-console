package net.muxigame.console.tests.stream;

import java.io.IOException;
import java.util.*;
import net.muxigame.console.client.SharedPlayClient;
import net.muxigame.console.client.SharedPlayClient.AudioAssembler;
import net.muxigame.console.mc.SharedPlayServer;
import net.muxigame.console.mc.SharedPlayServer.Gate;

/** Pure wire boundaries and actual PCM reassembly; real MC seat authority remains a native integration test. */
public final class SharedStreamChecks {
    private static int checks;
    private static void check(boolean result,String label){checks++;if(!result)throw new AssertionError(label);}
    @FunctionalInterface private interface Action{void run()throws Exception;}
    private static void reject(Action action,String label)throws Exception{try{action.run();throw new AssertionError("Accepted "+label);}catch(IllegalArgumentException|IOException expected){checks++;}}
    private static byte[] part(byte[] bytes,int index){int from=index*SharedPlayServer.AUDIO_PART_BYTES;return Arrays.copyOfRange(bytes,from,Math.min(bytes.length,from+SharedPlayServer.AUDIO_PART_BYTES));}
    public static void main(String[] ignored)throws Exception{
        Gate video=new Gate(3,0);check(video.video(0,0)&&video.video(1,0),"two-frame burst");check(!video.video(2,0),"third frame without time is dropped");check(video.video(2,50_000_000L),"one token at 20Hz");check(!video.video(2,1_000_000_000L),"duplicate frame cannot replay");check(!video.video(1,1_000_000_000L),"older frame cannot replay");check(!video.video(-1,1_000_000_000L),"negative frame rejected");check(new Gate(4,0).video(0,0),"new epoch restarts source sequence");
        Gate burst=new Gate(1,0);int accepted=0;for(int i=0;i<1000;i++)if(burst.video(i,i*1_000_000L))accepted++;check(accepted<=22&&accepted>=20,"one-second frame flood remains rate bounded");
        for(int samples:new int[]{0,2,4,4800,12288,30000,49152}){short[] pcm=new short[samples];Random random=new Random(samples);for(int i=0;i<pcm.length;i++)pcm[i]=(short)random.nextInt();byte[] bytes=SharedPlayClient.encode(pcm);check(bytes.length==pcm.length*2,"S16 byte count");check(Arrays.equals(SharedPlayClient.decode(bytes),pcm),"all signed PCM values and stereo channel order round-trip");}
        short[] extremes={Short.MIN_VALUE,Short.MAX_VALUE,-1,0};byte[] endian=SharedPlayClient.encode(extremes);check(Arrays.equals(endian,new byte[]{0,(byte)128,(byte)255,127,(byte)255,(byte)255,0,0}),"specified little-endian wire order");
        reject(()->SharedPlayClient.encode(new short[1]),"odd stereo source");reject(()->SharedPlayClient.encode(new short[49154]),"audio batch memory bound");reject(()->SharedPlayClient.decode(new byte[3]),"partial stereo pair");
        int max=SharedPlayServer.AUDIO_MAX_BYTES;byte[] full=new byte[max];new Random(52).nextBytes(full);AudioAssembler assembler=new AudioAssembler();Gate audio=new Gate(1,0);short[] restored=null;
        for(int i=0;i<4;i++){byte[] data=part(full,i);check(audio.audio(7,i,4,max,data.length,0),"bounded ordered source PCM part "+i);restored=assembler.accept(7,i,4,max,data,0);check(i==3?restored!=null:restored==null,"audio offered only after complete batch "+i);}
        check(Arrays.equals(SharedPlayClient.encode(restored),full),"four network chunks reconstruct exact source PCM");check(!audio.audio(7,0,4,max,24576,0),"completed batch cannot replay");check(assembler.accept(7,0,4,max,part(full,0),50_000_000L)==null,"client cannot replay completed audio");check(!audio.audio(8,0,1,9600,9600,0),"audio byte budget exhausted");check(audio.audio(8,0,1,9600,9600,50_000_000L),"48k stereo 50ms budget replenished");
        assembler.clear();check(assembler.accept(20,1,4,max,part(full,1),0)==null,"out-of-order initial part ignored");check(assembler.accept(20,0,4,max,part(full,0),0)==null,"partial batch accepted");check(assembler.accept(20,0,4,max,part(full,0),0)==null,"duplicate partial ignored");check(assembler.accept(19,0,1,4,new byte[4],0)==null,"old partial cannot replace newer frame");
        check(assembler.accept(21,0,1,4,new byte[]{1,2,3,4},0)!=null,"new source batch replaces incomplete previous batch");assembler.clear();assembler.accept(30,0,4,max,part(full,0),0);check(assembler.accept(30,1,4,max,part(full,1),1_000_000_001L)==null,"expired partial cannot be played");
        for(int[] p:new int[][]{{0,0,4,4},{0,5,max+4,24576},{1,1,4,4},{0,1,3,3},{0,2,4,4},{0,1,4,8},{0,4,max,4}})reject(()->Gate.audioBounds(p[0],p[1],p[2],p[3]),"invalid part metadata "+Arrays.toString(p));
        reject(()->assembler.accept(40,0,4,max,new byte[24572],0),"short non-final part");
        var lease=new SharedPlayClient.Lease("test-room",4,1,false);check(lease.port()==1&&!lease.leader(),"remote controller lease retains its assigned port");reject(()->new SharedPlayClient.Lease("",1,1,false),"empty room");reject(()->new SharedPlayClient.Lease("test",-1,1,false),"negative epoch");reject(()->new SharedPlayClient.Lease("test",1,4,false),"invalid controller seat");
        System.out.println("SharedStreamChecks: "+checks+" passed");
    }
}
