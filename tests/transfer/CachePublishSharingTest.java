package net.muxigame.console.tests.transfer;

import com.sun.nio.file.ExtendedOpenOption;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.muxigame.console.client.transfer.*;
import net.muxigame.console.domain.Hashing;

/** Actual Windows deny-delete handles exercise journal and verified-ROM replacement. */
public final class CachePublishSharingTest {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static final byte[] DATA=new byte[1024];
    private static final String HASH;
    static{new Random(9321).nextBytes(DATA);HASH=Hashing.sha256(DATA);}
    private static Path prepare(Path base,String name,boolean metadata)throws Exception{
        Path dir=Files.createTempDirectory(base,name).toAbsolutePath();
        if(metadata){Files.write(dir.resolve(HASH+".part"),new byte[0]);Files.writeString(dir.resolve(HASH+".meta"),"MXROM1\n"+HASH+"\n"+DATA.length+"\n0\n");}
        else Files.write(dir.resolve(HASH+".gba"),new byte[]{0});
        return dir;
    }
    private static RomTransfer.Reader reader(CountDownLatch entered,AtomicInteger reads){return offset->{reads.incrementAndGet();entered.countDown();return new RomTransfer.Chunk(offset,DATA.length,HASH,DATA);};}
    private static void blocked(Path path)throws Exception{
        Path source=Files.createTempFile(path.getParent(),"probe-",".tmp");
        try{try{Files.move(source,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);throw new AssertionError("Windows handle did not deny replacement");}catch(FileSystemException actual){checks++;}}
        finally{Files.deleteIfExists(source);}
    }
    public static void main(String[]args)throws Exception{
        if(!System.getProperty("os.name","").startsWith("Windows")){System.out.println("CachePublishSharing Windows-only actual handle coverage skipped");return;}
        Path base=Path.of(args[0]).resolve("cache-publish-sharing");Files.createDirectories(base);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try{
            for(boolean metadata:new boolean[]{true,false}){
                Path dir=prepare(base,metadata?"journal-":"rom-",metadata),target=dir.resolve(HASH+(metadata?".meta":".gba"));CountDownLatch entered=new CountDownLatch(1);AtomicInteger reads=new AtomicInteger();Future<Path> transfer;
                try(FileChannel held=FileChannel.open(target,StandardOpenOption.READ,ExtendedOpenOption.NOSHARE_DELETE)){
                    blocked(target);transfer=pool.submit(()->RomTransfer.receive(dir,HASH,"gba",DATA.length,reader(entered,reads),()->true,new LoadProgress(UUID.randomUUID())));
                    check(entered.await(2,TimeUnit.SECONDS),"real transfer reaches blocked replacement");Thread.sleep(150);check(!transfer.isDone(),"deny-delete handle actually holds publication");
                }
                Path complete=transfer.get(3,TimeUnit.SECONDS);check(Hashing.sha256(complete).equals(HASH)&&reads.get()==1,"release publishes verified bytes without another network request");
                check(!Files.exists(dir.resolve(HASH+".part"))&&!Files.exists(dir.resolve(HASH+".meta")),"successful retry retires transfer journal");
            }
            Path permanent=prepare(base,"permanent-",true);AtomicInteger requests=new AtomicInteger();long began=System.nanoTime();
            try(FileChannel held=FileChannel.open(permanent.resolve(HASH+".meta"),StandardOpenOption.READ,ExtendedOpenOption.NOSHARE_DELETE)){
                try{RomTransfer.receive(permanent,HASH,"gba",DATA.length,reader(new CountDownLatch(0),requests),()->true,new LoadProgress(UUID.randomUUID()));throw new AssertionError("Permanent denial was ignored");}
                catch(FileSystemException expected){check(requests.get()==1,"permanent denial never replays a ROM request");}
                check((System.nanoTime()-began)/1_000_000<3_000,"permanent denial fails within a fixed short budget");
                check(!Files.exists(permanent.resolve(HASH+".gba"))&&Files.readString(permanent.resolve(HASH+".meta")).endsWith("\n0\n"),"failed replacement preserves last committed journal and hides partial ROM");
            }
            AtomicLong first=new AtomicLong(-1);Path recovered=RomTransfer.receive(permanent,HASH,"gba",DATA.length,o->{first.set(o);return new RomTransfer.Chunk(o,DATA.length,HASH,DATA);},()->true,new LoadProgress(UUID.randomUUID()));
            check(first.get()==0&&Hashing.sha256(recovered).equals(HASH),"next authorized transfer resumes only the committed offset after permanent failure");
            Path cancelled=prepare(base,"cancelled-",true);AtomicBoolean active=new AtomicBoolean(true);CountDownLatch entered=new CountDownLatch(1);AtomicInteger cancelReads=new AtomicInteger();
            try(FileChannel held=FileChannel.open(cancelled.resolve(HASH+".meta"),StandardOpenOption.READ,ExtendedOpenOption.NOSHARE_DELETE)){
                Future<Path> transfer=pool.submit(()->RomTransfer.receive(cancelled,HASH,"gba",DATA.length,reader(entered,cancelReads),active::get,new LoadProgress(UUID.randomUUID())));
                check(entered.await(2,TimeUnit.SECONDS),"cancelled transfer reached actual held file");Thread.sleep(75);long cancelAt=System.nanoTime();active.set(false);
                try{transfer.get(500,TimeUnit.MILLISECONDS);throw new AssertionError("Cancelled publication continued");}catch(ExecutionException expected){check(expected.getCause() instanceof IOException&&expected.getCause().getMessage().contains("取消"),"waiting replacement observes the real cancellation predicate");}
                check((System.nanoTime()-cancelAt)/1_000_000<500&&cancelReads.get()==1&&!Files.exists(cancelled.resolve(HASH+".gba")),"cancel closes promptly without publication or extra network reads");
            }
        }finally{pool.shutdownNow();check(pool.awaitTermination(3,TimeUnit.SECONDS),"test executor closes normally");}
        System.out.println("CachePublishSharing checks="+checks+"; actual NOSHARE_DELETE journal/ROM handles, bounded denial, cancellation and recovery");
    }
}
