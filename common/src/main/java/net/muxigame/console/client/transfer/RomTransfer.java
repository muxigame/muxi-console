package net.muxigame.console.client.transfer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.muxigame.console.domain.Hashing;

/** A content-addressed, resumable disk transfer. Only a full SHA-256 check publishes a ROM. */
public final class RomTransfer {
    public static final int CHUNK_BYTES=24576;
    public static final int STREAM_CHUNK_BYTES=1024*1024;
    public record Chunk(long offset,long size,String hash,byte[] data){}
    @FunctionalInterface public interface Reader {Chunk read(long offset)throws Exception;}
    private RomTransfer(){}
    public static void requireActive(BooleanSupplier active)throws IOException{if(!active.getAsBoolean())throw new IOException("卡带加载已取消");}

    /** Cache the author's real local file before issuing the upload; never requires a server download. */
    public static Path cacheLocal(Path cache,String hash,String extension,long size,Path source,BooleanSupplier active)throws Exception{
        String identity=Hashing.normalizeSha256(hash);
        try(FileChannel input=FileChannel.open(source,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            if(input.size()!=size)throw new IOException("选择的文件已改变");
            return receive(cache,identity,extension,size,offset->{
                input.position(offset);ByteBuffer buffer=ByteBuffer.allocate((int)Math.min(STREAM_CHUNK_BYTES,size-offset));
                while(buffer.hasRemaining()){if(input.read(buffer)<0)throw new IOException("选择的文件已改变");}
                return new Chunk(offset,size,identity,buffer.array());
            },active,new LoadProgress(UUID.randomUUID()),STREAM_CHUNK_BYTES);
        }
    }

    public static Path receive(Path cache,String hash,String extension,long size,Reader reader,BooleanSupplier active,LoadProgress progress)throws Exception{
        return receive(cache,hash,extension,size,reader,active,progress,CHUNK_BYTES);
    }
    /** Transport-specific bounded chunks; packet transport remains limited to CHUNK_BYTES. */
    public static Path receive(Path cache,String hash,String extension,long size,Reader reader,BooleanSupplier active,LoadProgress progress,int maximumChunkBytes)throws Exception{
        Objects.requireNonNull(progress);hash=Hashing.normalizeSha256(hash);
        if(maximumChunkBytes<1||maximumChunkBytes>STREAM_CHUNK_BYTES)throw new IOException("卡带传输块大小无效");
        if(size<1||!extension.matches("[a-z0-9]+")||extension.matches("part|meta|lock"))throw new IOException("卡带大小或格式无效");
        requireActive(active);Files.createDirectories(cache);
        if(Files.isSymbolicLink(cache)||!cache.toRealPath().equals(cache.toAbsolutePath().normalize()))throw new IOException("卡带缓存路径不安全");
        Path target=cache.resolve(hash+"."+extension),partial=cache.resolve(hash+".part"),metadata=cache.resolve(hash+".meta"),lock=cache.resolve(hash+".lock");
        for(Path file:new Path[]{target,partial,metadata,lock})safeFile(file);
        try(FileChannel channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock held=lock(channel,active)){
            requireActive(active);
            if(validSize(target,size)&&verify(target,size,hash,LoadProgress.Stage.CACHE_CHECK,active,progress))return target;
            // Old versions selected an unordered platform extension. Reuse those exact bytes locally.
            try(var entries=Files.newDirectoryStream(cache,hash+".*")){
                for(Path candidate:entries){
                    String suffix=candidate.getFileName().toString().substring(hash.length()+1);
                    if(candidate.equals(target)||!suffix.matches("[a-z0-9]+")||suffix.matches("part|meta|lock")||!validSize(candidate,size))continue;
                    if(!verify(candidate,size,hash,LoadProgress.Stage.CACHE_CHECK,active,progress))continue;
                    Path copy=Files.createTempFile(cache,"receive-",".tmp");
                    try{if(copyValidated(candidate,copy,size,hash,active,progress)){requireActive(active);publish(copy,target,active);return target;}}
                    finally{Files.deleteIfExists(copy);}
                }
            }
            long offset=resume(partial,metadata,hash,size);
            boolean resumed=offset>0;
            for(int attempt=0;attempt<2;attempt++){
                progress.begin(LoadProgress.Stage.DOWNLOADING,size);if(offset>0)progress.advance(offset);
                try(FileChannel output=FileChannel.open(partial,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                    output.truncate(offset);output.position(offset);
                    while(offset<size){
                        requireActive(active);Chunk chunk=reader.read(offset);requireActive(active);
                        if(chunk==null||chunk.offset()!=offset||chunk.size()!=size||!hash.equals(chunk.hash())||chunk.data()==null||chunk.data().length<1||chunk.data().length>maximumChunkBytes||chunk.data().length>size-offset)throw new IOException("卡带下载格式无效");
                        ByteBuffer data=ByteBuffer.wrap(chunk.data());while(data.hasRemaining())output.write(data);
                        output.force(false);offset+=chunk.data().length;
                        commit(metadata,hash,size,offset,active);progress.advance(chunk.data().length);
                    }
                }
                if(verify(partial,size,hash,LoadProgress.Stage.VERIFYING,active,progress)){
                    requireActive(active);publish(partial,target,active);Files.deleteIfExists(metadata);return target;
                }
                // A partial file is untrusted across restarts. Repair a damaged old prefix once.
                Files.deleteIfExists(partial);Files.deleteIfExists(metadata);
                if(!resumed||attempt==1)throw new IOException("卡带下载校验失败，请重新获取原文件");
                resumed=false;offset=0;
            }
            throw new IOException("卡带下载未完成");
        }
    }
    private static FileLock lock(FileChannel channel,BooleanSupplier active)throws Exception{
        for(;;){requireActive(active);try{FileLock lock=channel.tryLock();if(lock!=null)return lock;}catch(OverlappingFileLockException busy){}Thread.sleep(10);}
    }
    private static void safeFile(Path file)throws IOException{
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)&&(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file)))throw new IOException("卡带缓存文件路径不安全");
    }
    private static boolean validSize(Path file,long size)throws IOException{return Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(file)&&Files.size(file)==size;}
    private static long resume(Path partial,Path metadata,String hash,long size)throws IOException{
        if(Files.isRegularFile(partial,LinkOption.NOFOLLOW_LINKS)&&Files.isRegularFile(metadata,LinkOption.NOFOLLOW_LINKS)&&Files.size(metadata)<256){
            try{String[] fields=Files.readString(metadata,StandardCharsets.US_ASCII).split("\\n");long total=Long.parseLong(fields[2]),offset=Long.parseLong(fields[3]);
                if(fields.length==4&&fields[0].equals("MXROM1")&&fields[1].equals(hash)&&total==size&&offset>=0&&offset<=size&&offset<=Files.size(partial))return offset;
            }catch(NumberFormatException|IndexOutOfBoundsException invalid){}
        }
        Files.deleteIfExists(partial);Files.deleteIfExists(metadata);return 0;
    }
    private static void commit(Path metadata,String hash,long size,long offset,BooleanSupplier active)throws IOException{
        Path temporary=Files.createTempFile(metadata.getParent(),"receive-",".tmp");
        try{Files.writeString(temporary,"MXROM1\n"+hash+"\n"+size+"\n"+offset+"\n",StandardCharsets.US_ASCII);publish(temporary,metadata,active);}finally{Files.deleteIfExists(temporary);}
    }
    private static void publish(Path from,Path to,BooleanSupplier active)throws IOException{
        // Windows readers (including scanners) can briefly withhold FILE_SHARE_DELETE.
        // Keep the verified source/journal intact; never hide a permanent denial or cancel.
        long deadline=System.nanoTime()+1_500_000_000L;
        for(;;){
            requireActive(active);
            try{
                safeFile(from);safeFile(to);
                try{Files.move(from,to,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
                catch(AtomicMoveNotSupportedException unsupported){Files.move(from,to,StandardCopyOption.REPLACE_EXISTING);}
                return;
            }catch(FileSystemException denied){
                boolean windows=System.getProperty("os.name","").startsWith("Windows");
                if(!windows||denied instanceof NoSuchFileException||denied instanceof NotDirectoryException||denied instanceof DirectoryNotEmptyException||denied instanceof FileAlreadyExistsException||!Files.isRegularFile(from,LinkOption.NOFOLLOW_LINKS)||System.nanoTime()>=deadline)throw denied;
                requireActive(active);
                try{Thread.sleep(25);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("卡带加载已取消");}
            }
        }
    }
    private static boolean copyValidated(Path source,Path target,long expected,String hash,BooleanSupplier active,LoadProgress progress)throws IOException{
        progress.begin(LoadProgress.Stage.CACHE_CHECK,expected);var digest=Hashing.sha256Digest();long count=0;
        try(var input=Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS);var output=Files.newOutputStream(target,StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS)){
            byte[] buffer=new byte[32768];for(int n;(n=input.read(buffer))!=-1;){requireActive(active);count+=n;if(count>expected)return false;digest.update(buffer,0,n);output.write(buffer,0,n);progress.advance(n);}
        }
        requireActive(active);return count==expected&&Files.size(target)==expected&&HexFormat.of().formatHex(digest.digest()).equals(hash);
    }
    private static boolean verify(Path file,long expected,String hash,LoadProgress.Stage stage,BooleanSupplier active,LoadProgress progress)throws IOException{
        progress.begin(stage,expected);var digest=Hashing.sha256Digest();long bytes=0;
        try(InputStream input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){
            byte[] buffer=new byte[32768];for(int count;(count=input.read(buffer))!=-1;){
                requireActive(active);if(count>expected-bytes)return false;digest.update(buffer,0,count);bytes+=count;progress.advance(count);
            }
        }
        requireActive(active);return bytes==expected&&Files.size(file)==expected&&HexFormat.of().formatHex(digest.digest()).equals(hash);
    }
}
