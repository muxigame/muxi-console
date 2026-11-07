package net.muxigame.console.client.audio;

import java.util.Arrays;

/** Streaming area resampling to a device-friendly rate; fractional samples survive frame boundaries. */
public final class StereoResampler {
    private final double outputRate;
    private double inputRate,filled,left,right;
    public StereoResampler(double outputRate){if(!Double.isFinite(outputRate)||outputRate<8000||outputRate>192000)throw new IllegalArgumentException("Output rate");this.outputRate=outputRate;}
    public short[] convert(short[] stereo,double rate){
        if(!Double.isFinite(rate)||rate<8000||rate>192000||stereo.length%2!=0||stereo.length>2_000_000)throw new IllegalArgumentException("PCM format");
        if(rate!=inputRate){inputRate=rate;filled=left=right=0;}
        double span=outputRate/rate;long maximum=(long)Math.ceil(stereo.length/2.0*span)+2;
        if(maximum>6_000_002)throw new IllegalArgumentException("PCM output too large");short[] result=new short[(int)maximum*2];int count=0;
        for(int at=0;at<stereo.length;at+=2){double remaining=span;while(remaining>1e-12){double weight=Math.min(remaining,1-filled);left+=stereo[at]*weight;right+=stereo[at+1]*weight;filled+=weight;remaining-=weight;
            if(filled>=1-1e-10){result[count++]=(short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,Math.round(left)));result[count++]=(short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,Math.round(right)));filled=left=right=0;}
        }}return Arrays.copyOf(result,count);
    }
}
