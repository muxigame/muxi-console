package net.muxigame.console.domain;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class VideoOrientationTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static byte[] pixels(String letters){byte[] data=new byte[letters.length()*4];for(int i=0;i<letters.length();i++){data[i*4]=(byte)letters.charAt(i);data[i*4+1]=(byte)(i+1);data[i*4+2]=(byte)0x5a;data[i*4+3]=(byte)0xff;}return data;}
    private static void expected(int turn,String letters,int width,int height){byte[] original=pixels("ABCDEF");var result=VideoOrientation.apply(2,3,original,turn);check(result.width()==width&&result.height()==height,"Output bounds for "+turn);StringBuilder names=new StringBuilder();for(int i=0;i<6;i++){int offset=i*4;names.append((char)result.rgba()[offset]);int source="ABCDEF".indexOf(result.rgba()[offset]);check(result.rgba()[offset+1]==source+1&&result.rgba()[offset+2]==0x5a&&result.rgba()[offset+3]==(byte)0xff,"All RGBA channels travel together");}check(names.toString().equals(letters),"Public counterclockwise layout "+turn);check(Arrays.equals(original,pixels("ABCDEF")),"Input pixels remain intact");}
    public static void main(String[] args){expected(0,"ABCDEF",2,3);expected(1,"BDFACE",3,2);expected(2,"FEDCBA",2,3);expected(3,"ECAFDB",3,2);check(VideoOrientation.apply(0,0,new byte[0],3).rgba().length==0,"Empty video is allowed");System.out.println("VideoOrientationTest: "+checks+" checks passed");}
}
