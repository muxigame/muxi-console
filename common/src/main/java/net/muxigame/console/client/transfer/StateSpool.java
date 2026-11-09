package net.muxigame.console.client.transfer;
import java.io.*;
import java.nio.file.*;
import java.util.function.*;
import java.util.zip.*;

/** Optional transport compression; the emulator's original MXS1 bytes remain untouched. */
public final class StateSpool{
    private static final int MAGIC=0x4d585a31;
    public static final long THRESHOLD=8L*1024*1024;
    private StateSpool(){}
    private static void active(BooleanSupplier running)throws IOException{if(Thread.currentThread().isInterrupted()||!running.getAsBoolean())throw new InterruptedIOException("Checkpoint compression cancelled");}
    public static Path compress(Path raw,Path target,long limit,BooleanSupplier running,LongConsumer progress)throws IOException{
        long size=Files.size(raw);if(size<1||size>limit)throw new IOException("Core state exceeds platform budget");if(size<=THRESHOLD)return raw;
        boolean created=false,complete=false;
        try{
            try(var destination=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)))){
                created=true;destination.writeInt(MAGIC);destination.writeLong(size);
                try(var gzip=new GZIPOutputStream(destination,65536);var input=Files.newInputStream(raw,LinkOption.NOFOLLOW_LINKS)){
                    byte[] block=new byte[65536];long count=0;for(int n;(n=input.read(block))!=-1;){active(running);count+=n;if(count>size)throw new IOException("Core state changed during compression");gzip.write(block,0,n);progress.accept(count);}if(count!=size)throw new IOException("Core state was truncated during compression");
                }
            }
            active(running);if(Files.size(target)>=size-size/20){Files.delete(target);complete=true;return raw;}complete=true;return target;
        }finally{if(created&&!complete)Files.deleteIfExists(target);}
    }
    public static Path expand(Path transport,Path target,long limit,BooleanSupplier running,LongConsumer progress)throws IOException{
        if(Files.size(transport)<4||Files.size(transport)>limit)throw new IOException("Saved transport size exceeds platform budget");
        boolean created=false,complete=false;
        try(var input=new DataInputStream(new BufferedInputStream(Files.newInputStream(transport,LinkOption.NOFOLLOW_LINKS)))){
            if(input.readInt()!=MAGIC)return transport;long size=input.readLong();if(size<16||size>limit)throw new IOException("Expanded state exceeds platform budget");
            try(var gzip=new GZIPInputStream(input,65536);var output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)){
                created=true;byte[] block=new byte[65536];long count=0;for(int n;(n=gzip.read(block))!=-1;){active(running);count+=n;if(count>size)throw new IOException("Expanded state exceeds declared size");output.write(block,0,n);progress.accept(count);}if(count!=size)throw new IOException("Expanded state is truncated");
            }
            complete=true;return target;
        }finally{if(created&&!complete)Files.deleteIfExists(target);}
    }
}
