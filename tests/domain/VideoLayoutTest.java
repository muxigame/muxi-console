package net.muxigame.console.tests.domain;

import net.muxigame.console.domain.*;
import java.util.*;

public final class VideoLayoutTest {
    private static int checks;
    private static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    public static void main(String[] ignored){
        byte[] original=new byte[1552*240*4];for(int y=0;y<240;y++)for(int x=0;x<1552;x++){int at=(y*1552+x)*4;original[at]=(byte)(y<120?255:0);original[at+2]=(byte)(y>=120?255:0);original[at+3]=(byte)255;}
        var input=new VideoOrientation.Image(1552,240,original);var display=VideoLayout.display(input,4.0/3);check(display.width()==320&&display.height()==240,"MAME wide sample buffer uses core 4:3 display shape");
        for(int x:new int[]{0,80,160,319}){int top=x*4,bottom=((display.height()-1)*display.width()+x)*4;check((display.rgba()[top]&255)==255&&(display.rgba()[top+2]&255)==0,"Full top region preserves orientation/color");check((display.rgba()[bottom]&255)==0&&(display.rgba()[bottom+2]&255)==255,"Full bottom region preserved");}
        check(original.length==1552*240*4&&(original[0]&255)==255,"Source buffer unchanged");
        check(VideoLayout.display(input,1552.0/240)==input,"Square-pixel images avoid resampling");
        var portrait=VideoLayout.display(VideoOrientation.apply(1552,240,original,1),3.0/4);check(portrait.width()==1164&&portrait.height()==1552,"Quarter turn inverts physical display ratio");
        float[] fit=VideoLayout.fit(0,0,12,9,1.5);check(Math.abs(fit[0])<.001&&Math.abs(fit[2]-12)<.001&&Math.abs(fit[1]-.5)<.001&&Math.abs(fit[3]-8.5)<.001,"GBA full picture letterboxes in a 4:3 aperture");
        float[] gb=VideoLayout.fit(0,0,12,9,10.0/9);check(Math.abs(gb[0]-1)<.001&&Math.abs(gb[2]-11)<.001&&gb[1]==0&&gb[3]==9,"Game Boy picture avoids horizontal stretch");
        check(VideoLayout.display(new VideoOrientation.Image(0,0,new byte[0]),4.0/3).rgba().length==0,"Empty startup picture stays empty");
        for(double invalid:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY})try{VideoLayout.fit(0,0,12,9,invalid);throw new AssertionError("Invalid ratio accepted");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("Video layout: "+checks+" checks passed");
    }
}
