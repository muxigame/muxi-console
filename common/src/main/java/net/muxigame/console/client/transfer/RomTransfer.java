package net.muxigame.console.client.transfer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.muxigame.console.domain.Hashing;

/** Sequential bounded chunks to disk, followed by a complete streaming identity check. */
public final class RomTransfer {
    public static final int CHUNK_BYTES=24576;
    public record Chunk(long offset,long size,String hash,byte[] data){}
    @FunctionalInterface public interface Reader {Chunk read(long offset)throws Exception;}
    private RomTransfer(){}
    public static void requireActive(BooleanSupplier active)throws IOException{if(!active.getAsBoolean())throw new IOException("卡带加载已取消");}
    public static Path receive(Path cache,String hash,String extension,long size,Reader reader,BooleanSupplier active,LoadProgress progress)throws Exception{
        Objects.requireNonNull(progress);Hashing.normalizeSha256(hash);
        if(size<1||!extension.matches("[a-z0-9]+"))throw new IOException("卡带大小或格式无效");
        requireActive(active);Files.createDirectories(cache);
        if(Files.isSymbolicLink(cache)||!cache.toRealPath().equals(cache.toAbsolutePath().normalize()))throw new IOException("卡带缓存路径不安全");
        Path target=cache.resolve(hash+"."+extension);
        if(Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(target)&&Files.size(target)==size){
            if(verify(target,size,hash,LoadProgress.Stage.CACHE_CHECK,active,progress))return target;
        }
        Path temporary=Files.createTempFile(cache,"receive-",".tmp");
        try{
            progress.begin(LoadProgress.Stage.DOWNLOADING,size);
            try(var output=Files.newOutputStream(temporary)){
                for(long offset=0;offset<size;){
                    requireActive(active);Chunk chunk=reader.read(offset);requireActive(active);
                    if(chunk==null||chunk.offset()!=offset||chunk.size()!=size||!hash.equals(chunk.hash())||chunk.data()==null||chunk.data().length<1||chunk.data().length>CHUNK_BYTES||chunk.data().length>size-offset)throw new IOException("卡带下载格式无效");
                    output.write(chunk.data());offset+=chunk.data().length;progress.advance(chunk.data().length);
                }
            }
            if(!verify(temporary,size,hash,LoadProgress.Stage.VERIFYING,active,progress))throw new IOException("卡带下载校验失败");
            requireActive(active);Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);return target;
        }finally{Files.deleteIfExists(temporary);}
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
