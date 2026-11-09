import net.muxigame.console.runtime.*;
import java.io.*;
import java.nio.*;
import java.util.*;
public final class ExpansionInputTest{
 static int checks;static void check(boolean yes){checks++;if(!yes)throw new AssertionError("check "+checks);}
 static void bad(Throwing body)throws Exception{try{body.run();throw new AssertionError("invalid input admitted");}catch(IOException|IllegalArgumentException expected){checks++;}}
 interface Throwing{void run()throws Exception;}
 public static void main(String[]args)throws Exception{
  check(new PortInput(5).equals(new PortInput(5,(short)0,(short)0,(short)0,(short)0)));check(new PortInput(0).motion().accelerationZ()==-1);
  short[] pcm=new short[2400];for(int i=0;i<pcm.length;i++)pcm[i]=(short)(i-1200);
  PortInput pad=new PortInput(0xffff,(short)-32767,(short)32767,(short)12,(short)-12,2305,new PortInput.Pointer("bottom",(short)-12345,(short)12345,true,true),new PortInput.Motion(0.1f,0.2f,-0.98f,1,2,3,123456,true),new PortInput.Microphone(true,48000,pcm));
  byte[] one=InputCodec.encode(pad);check(InputCodec.decode(one).equals(pad));check(InputCodec.decodeSingle(one).equals(pad));
  InputFrame frame=new InputFrame(List.of(pad,pad,pad,pad));byte[] four=InputCodec.encodeFrame(frame);check(four.length<24576);check(InputCodec.decodeFrame(four).equals(frame));check(InputCodec.decodeFrame(InputCodec.encodeFrame(frame.withoutMicrophone())).port(0).microphone().samples().length==0);check(frame.withoutMicrophone().port(0).pointer().equals(pad.pointer()));
  pcm[0]=321;check(pad.microphone().samples()[0]!=321);short[] escaped=pad.microphone().samples();escaped[1]=999;check(pad.microphone().samples()[1]!=999);
  for(int end=0;end<one.length;end+=127){byte[] trunc=Arrays.copyOf(one,end);bad(()->InputCodec.decode(trunc));}
  bad(()->InputCodec.decode(Arrays.copyOf(one,one.length+1)));bad(()->InputCodec.decode(new byte[24577]));
  byte[] nan=one.clone();ByteBuffer.wrap(nan).putInt(23,Float.floatToIntBits(Float.NaN));bad(()->InputCodec.decode(nan));
  byte[] bool=one.clone();bool[55]=2;bad(()->InputCodec.decode(bool));
  byte[] flags=one.clone();flags[22]=7;bad(()->InputCodec.decode(flags));
  bad(()->new PortInput.Pointer("bottom",(short)0,(short)0,true,false));bad(()->new PortInput.Motion(Float.POSITIVE_INFINITY,0,-1,0,0,0,0,true));bad(()->new PortInput.Microphone(true,48000,new short[2401]));bad(()->new PortInput(0,(short)0,(short)0,(short)0,(short)0,1536,PortInput.Pointer.IDLE,PortInput.Motion.IDLE,PortInput.Microphone.SILENT));
  System.out.println("ExpansionInputTest: "+checks+" checks passed");
 }
}
