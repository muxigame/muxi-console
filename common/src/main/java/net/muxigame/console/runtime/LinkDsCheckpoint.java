package net.muxigame.console.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;

/** One paused DS endpoint: complete Console MXS1, frontend MpState and already delivered transport bytes. */
final class LinkDsCheckpoint {
    static final long MAX_CORE=128L*1024*1024,MAX_FILE=160L*1024*1024;
    static String save(Path spool,long frame,LibretroCore core,LinkDsNativeState mp,LinkDsNetwork.Connection network)throws IOException{
        String name="endpoint-"+UUID.randomUUID()+".dse1";Path target=spool.resolve(name),single=spool.resolve("endpoint-"+UUID.randomUUID()+".mxs1");boolean complete=false;
        try{core.saveStateTo(single);long length=Files.size(single);if(length<16||length>MAX_CORE)throw new IOException("DS Console checkpoint exceeds its group bound");byte[] nativeQueue=mp.save(),transport=network.snapshotIncoming();
            try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)))){out.writeInt(0x44534531);out.writeShort(1);out.writeLong(frame);out.writeLong(length);Files.copy(single,out);out.writeInt(nativeQueue.length);out.write(nativeQueue);out.writeInt(transport.length);out.write(transport);}complete=true;return name;
        }finally{Files.deleteIfExists(single);if(!complete)Files.deleteIfExists(target);}
    }
    static long restore(Path spool,Path source,LibretroCore core,LinkDsNativeState mp,LinkDsNetwork.Connection network)throws IOException{
        if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)||!source.equals(source.toRealPath())||Files.size(source)>MAX_FILE)throw new IOException("Invalid DS endpoint checkpoint file");Path single=spool.resolve("endpoint-"+UUID.randomUUID()+".mxs1");
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS)))){
            if(in.readInt()!=0x44534531||in.readUnsignedShort()!=1)throw new IOException("Invalid DS endpoint checkpoint format");long frame=in.readLong(),length=in.readLong();if(frame<0||length<16||length>MAX_CORE||length>Files.size(source)-30)throw new IOException("Invalid DS checkpoint frame or Console length");
            try(var out=Files.newOutputStream(single,StandardOpenOption.CREATE_NEW)){copy(in,out,length);}
            byte[] nativeQueue=part(in,16*1024*1024),transport=part(in,9*1024*1024);if(in.read()!=-1)throw new IOException("Trailing DS endpoint checkpoint bytes");
            // The runtime closes both fresh workers if any stage rejects; no partial pair becomes active.
            core.loadStateFrom(single);mp.restore(nativeQueue);network.restoreIncoming(transport);return frame;
        }finally{Files.deleteIfExists(single);}
    }
    private static byte[] part(DataInputStream in,int max)throws IOException{int length=in.readInt();if(length<1||length>max)throw new IOException("DS queue checkpoint exceeds bounds");byte[] bytes=in.readNBytes(length);if(bytes.length!=length)throw new EOFException();return bytes;}
    static void copy(InputStream in,OutputStream out,long size)throws IOException{byte[] buffer=new byte[65536];for(long left=size;left>0;){int n=in.read(buffer,0,(int)Math.min(buffer.length,left));if(n<0)throw new EOFException();out.write(buffer,0,n);left-=n;}}
}
