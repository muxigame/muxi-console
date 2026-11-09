package net.muxigame.console.runtime;

import java.io.*;
import java.util.*;

/** Bounded independent controller payload shared by MC and subprocess/link transports. */
public final class InputCodec {
    public static final int VERSION=1,MAX_BYTES=24576;
    private static final int PORT=0x4d584950,FRAME=0x4d584946;
    private InputCodec(){}
    private interface Writer{void write(DataOutputStream out)throws IOException;}
    private static byte[] encode(int magic,Writer writer)throws IOException{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(magic);out.writeByte(VERSION);writer.write(out);}if(bytes.size()>MAX_BYTES)throw new IOException("Input payload exceeds packet budget");return bytes.toByteArray();}
    private static DataInputStream input(byte[] bytes,int magic)throws IOException{if(bytes==null||bytes.length>MAX_BYTES)throw new IOException("Input payload exceeds packet budget");var in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=magic||in.readUnsignedByte()!=VERSION)throw new IOException("Invalid input payload version");return in;}
    private static void end(DataInputStream in)throws IOException{if(in.available()!=0)throw new IOException("Trailing input payload bytes");}
    private static boolean bool(DataInputStream in)throws IOException{int value=in.readUnsignedByte();if(value>1)throw new IOException("Invalid input boolean");return value!=0;}
    public static byte[] encode(PortInput port)throws IOException{return encode(PORT,out->writePort(out,port));}
    public static PortInput decode(byte[] bytes)throws IOException{var in=input(bytes,PORT);PortInput port=readPort(in);end(in);return port;}
    public static byte[] encodeSingle(PortInput port)throws IOException{return encode(port);}
    public static PortInput decodeSingle(byte[] bytes)throws IOException{return decode(bytes);}
    public static byte[] encodeFrame(InputFrame frame)throws IOException{return encode(FRAME,out->{out.writeByte(frame.ports().size());for(PortInput port:frame.ports())writePort(out,port);});}
    public static InputFrame decodeFrame(byte[] bytes)throws IOException{var in=input(bytes,FRAME);int count=in.readUnsignedByte();if(count>InputFrame.MAX_PORTS)throw new IOException("Too many input ports");List<PortInput> ports=new ArrayList<>();for(int i=0;i<count;i++)ports.add(readPort(in));end(in);return new InputFrame(ports);}
    public static void writePort(DataOutputStream out,PortInput p)throws IOException{
        out.writeShort(p.buttons());out.writeShort(p.leftX());out.writeShort(p.leftY());out.writeShort(p.rightX());out.writeShort(p.rightY());out.writeShort(p.device());
        var pointer=p.pointer();out.writeByte(switch(pointer.surface()){case "top"->1;case "bottom"->2;default->0;});out.writeShort(pointer.x());out.writeShort(pointer.y());out.writeByte((pointer.valid()?1:0)|(pointer.pressed()?2:0));
        var motion=p.motion();out.writeFloat(motion.accelerationX());out.writeFloat(motion.accelerationY());out.writeFloat(motion.accelerationZ());out.writeFloat(motion.gyroX());out.writeFloat(motion.gyroY());out.writeFloat(motion.gyroZ());out.writeLong(motion.timestampNanos());out.writeBoolean(motion.valid());
        var mic=p.microphone();short[] samples=mic.samples();out.writeBoolean(mic.connected());out.writeInt(mic.sampleRate());out.writeShort(samples.length);for(short sample:samples)out.writeShort(sample);
    }
    public static PortInput readPort(DataInputStream in)throws IOException{
        try{int buttons=in.readUnsignedShort();short lx=in.readShort(),ly=in.readShort(),rx=in.readShort(),ry=in.readShort();int device=in.readUnsignedShort();int surface=in.readUnsignedByte();if(surface>2)throw new IOException("Invalid pointer surface");short x=in.readShort(),y=in.readShort();int flags=in.readUnsignedByte();if((flags&~3)!=0)throw new IOException("Invalid pointer flags");var pointer=new PortInput.Pointer(surface==0?"primary":surface==1?"top":"bottom",x,y,(flags&2)!=0,(flags&1)!=0);
            var motion=new PortInput.Motion(in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readLong(),bool(in));boolean connected=bool(in);int rate=in.readInt(),count=in.readUnsignedShort();if(count>PortInput.Microphone.MAX_SAMPLES||count*2>in.available())throw new IOException("Invalid microphone sample count");short[] samples=new short[count];for(int i=0;i<count;i++)samples[i]=in.readShort();return new PortInput(buttons,lx,ly,rx,ry,device,pointer,motion,new PortInput.Microphone(connected,rate,samples));
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid controller payload",invalid);}
    }
}
