package net.muxigame.console.tests.audio;

import java.util.*;
import net.muxigame.console.client.audio.StereoResampler;

public final class StereoResamplerTest {
    private static int checks;
    private static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    public static void main(String[] ignored){
        for(int rate:new int[]{8000,32768,44100,48000,96000,192000}){
            short[] pcm=new short[rate*2];for(int i=0;i<pcm.length;i+=2){pcm[i]=1000;pcm[i+1]=-2000;}
            short[] continuous=new StereoResampler(48000).convert(pcm,rate);check(continuous.length==96000,"One second has exact output duration at "+rate);
            for(int i=0;i<continuous.length;i+=2)if(continuous[i]!=1000||continuous[i+1]!=-2000)throw new AssertionError("Channel/DC error");checks++;
            StereoResampler partitioned=new StereoResampler(48000);short[] actual=new short[continuous.length];int count=0;
            for(int from=0;from<pcm.length;){int to=Math.min(pcm.length,from+514);short[] result=partitioned.convert(Arrays.copyOfRange(pcm,from,to),rate);System.arraycopy(result,0,actual,count,result.length);count+=result.length;from=to;}
            check(count==continuous.length&&Arrays.equals(actual,continuous),"Frame boundaries preserve phase at "+rate);
        }
        short[] alternating=new short[80];for(int at=0;at<alternating.length;at+=2){alternating[at]=(short)(at%4==0?16000:-16000);alternating[at+1]=1000;}
        short[] low=new StereoResampler(48000).convert(alternating,192000);check(low.length==20,"4x decimation duration");check(low[0]==0&&low[1]==1000,"Area filter rejects alternating Nyquist signal, preserves other channel");
        for(double rate:new double[]{Double.NaN,Double.POSITIVE_INFINITY,0,7999,192001}){try{new StereoResampler(48000).convert(new short[2],rate);throw new AssertionError("Invalid rate accepted");}catch(IllegalArgumentException expected){checks++;}}
        try{new StereoResampler(48000).convert(new short[1],48000);throw new AssertionError("Odd stereo buffer accepted");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("Stereo resampler: "+checks+" checks passed");
    }
}
