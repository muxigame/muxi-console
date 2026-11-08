package net.muxigame.console.tests.hardware;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.muxigame.console.client.transfer.*;
import net.muxigame.console.domain.Hashing;

/** Real temporary-file transfers: cache verification, malformed chunks, denial and cancellation. No Minecraft or ROM distribution. */
public final class RomTransferTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejected(Attempt attempt,String label)throws Exception{try{attempt.run();throw new AssertionError(label);}catch(IOException expected){checks++;}}
    @FunctionalInterface private interface Attempt{void run()throws Exception;}
    private static RomTransfer.Reader reader(byte[] bytes,String hash,LoadProgress progress,AtomicInteger calls){return offset->{
        calls.incrementAndGet();check(progress.snapshot().stage().equals("downloading")&&progress.snapshot().bytes()==offset,"progress counts only bytes already written before the next read");
        return new RomTransfer.Chunk(offset,bytes.length,hash,Arrays.copyOfRange(bytes,(int)offset,Math.min(bytes.length,(int)offset+RomTransfer.CHUNK_BYTES)));
    };}
    private static void noTemporary(Path cache)throws Exception{try(var entries=Files.list(cache)){check(entries.noneMatch(p->p.getFileName().toString().startsWith("receive-")),"every failed transfer removes only its own receive temporary file");}}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]).resolve("rom-transfer");Files.createDirectories(root);Path cache=Files.createTempDirectory(root,"case-").toAbsolutePath();
        byte[] bytes=new byte[2*1024*1024+17];new Random(37).nextBytes(bytes);String hash=Hashing.sha256(bytes);UUID card=UUID.randomUUID();LoadProgress progress=new LoadProgress(card);AtomicInteger calls=new AtomicInteger();
        Path target=RomTransfer.receive(cache,hash,"gba",bytes.length,reader(bytes,hash,progress,calls),()->true,progress);
        check(calls.get()==(bytes.length+24575)/24576,"bounded sequential chunks include the final short remainder");check(Files.size(target)==bytes.length&&Hashing.sha256(target).equals(hash),"download publishes the complete exact file only after verification");
        var verified=progress.snapshot();check(verified.stage().equals("verifying")&&verified.bytes()==bytes.length&&verified.total()==bytes.length&&verified.percent()==100,"verification has its own actual byte count");noTemporary(cache);
        progress=new LoadProgress(card);LoadProgress cached=progress;Path hit=RomTransfer.receive(cache,hash,"gba",bytes.length,offset->{throw new AssertionError("Valid cache made a network request");},()->true,cached);
        check(hit.equals(target)&&cached.snapshot().stage().equals("cache_check")&&cached.snapshot().percent()==100,"cache hit streams full hash without fetching any chunk");
        byte[] corrupt=bytes.clone();corrupt[13]^=1;Files.write(target,corrupt);LoadProgress repair=new LoadProgress(card);calls.set(0);RomTransfer.receive(cache,hash,"gba",bytes.length,reader(bytes,hash,repair,calls),()->true,repair);
        check(calls.get()>0&&Hashing.sha256(target).equals(hash),"same-size corrupt cache is never trusted and is replaced by verified content");
        Files.write(target,new byte[7]);LoadProgress shortCache=new LoadProgress(card);RomTransfer.receive(cache,hash,"gba",bytes.length,reader(bytes,hash,shortCache,new AtomicInteger()),()->true,shortCache);check(Hashing.sha256(target).equals(hash),"wrong-size cache is replaced through the normal chunk path");
        for(int type=0;type<7;type++){
            Path bad=Files.createTempDirectory(root,"bad-").toAbsolutePath();LoadProgress p=new LoadProgress(card);final int kind=type;
            rejected(()->RomTransfer.receive(bad,hash,"gba",bytes.length,offset->switch(kind){
                case 0->new RomTransfer.Chunk(offset+1,bytes.length,hash,new byte[1]);case 1->new RomTransfer.Chunk(offset,bytes.length+1,hash,new byte[1]);
                case 2->new RomTransfer.Chunk(offset,bytes.length,"0".repeat(64),new byte[1]);case 3->new RomTransfer.Chunk(offset,bytes.length,hash,new byte[0]);
                case 4->new RomTransfer.Chunk(offset,bytes.length,hash,new byte[24577]);case 5->null;
                default->throw new IOException("server permission denied");},()->true,p),"bad/denied chunk must reject");
            check(!Files.exists(bad.resolve(hash+".gba")),"bad metadata or denial cannot publish a cache entry");noTemporary(bad);
        }
        Path mismatch=Files.createTempDirectory(root,"hash-").toAbsolutePath();LoadProgress wrong=new LoadProgress(card);
        rejected(()->RomTransfer.receive(mismatch,hash,"gba",bytes.length,reader(corrupt,hash,wrong,new AtomicInteger()),()->true,wrong),"final identity mismatch must reject");noTemporary(mismatch);check(!Files.exists(mismatch.resolve(hash+".gba")),"full bad body never becomes a cache hit");
        Path cancelled=Files.createTempDirectory(root,"cancel-").toAbsolutePath();LoadProgress cancel=new LoadProgress(card);AtomicInteger reads=new AtomicInteger();
        rejected(()->RomTransfer.receive(cancelled,hash,"gba",bytes.length,reader(bytes,hash,cancel,reads),()->cancel.snapshot().bytes()<24576,cancel),"cancellation before second request must reject");
        check(reads.get()==1&&cancel.snapshot().bytes()==24576,"cancellation prevents further reads after actual first write");noTemporary(cancelled);
        AtomicInteger cacheReads=new AtomicInteger();LoadProgress cancelCache=new LoadProgress(card);
        rejected(()->RomTransfer.receive(cache,hash,"gba",bytes.length,offset->{cacheReads.incrementAndGet();throw new AssertionError("Cache cancellation fetched content");},()->cancelCache.snapshot().bytes()<32768,cancelCache),"cache verification can cancel between streaming buffers");
        check(cacheReads.get()==0&&Hashing.sha256(target).equals(hash),"cancelled hash check leaves the original cache untouched");
        LoadProgress state=new LoadProgress(card);check(state.snapshot().percent()==-1&&state.snapshot().loading(),"unmeasured preparation has no invented percentage");
        state.begin(LoadProgress.Stage.DOWNLOADING,1000);state.advance(70);check(state.snapshot().percent()==7&&state.snapshot().text().equals("正在加载卡带 7%"),"download label derives exactly from real counted work");
        state.begin(LoadProgress.Stage.STARTING,0);check(state.snapshot().percent()==-1&&state.snapshot().text().equals("正在启动游戏…"),"core startup is a stage rather than guessed completion");
        state.begin(LoadProgress.Stage.RESTORING,400);state.advance(100);check(state.snapshot().percent()==25,"save download has its own bounded progress");
        state.begin(LoadProgress.Stage.WAITING,0);check(state.snapshot().loading(),"ready worker keeps loading until actual picture");state.begin(LoadProgress.Stage.COMPLETE,0);state.begin(LoadProgress.Stage.STARTING,0);check(!state.snapshot().loading()&&state.snapshot().stage().equals("complete"),"first-frame completion cannot regress from late work");
        LoadProgress failure=new LoadProgress(card);failure.fail("permission denied");failure.cancel();failure.begin(LoadProgress.Stage.DOWNLOADING,1);check(!failure.snapshot().loading()&&failure.snapshot().error().equals("permission denied"),"error survives cancellation and cannot be revived by late callbacks");
        LoadProgress aborted=new LoadProgress(card);aborted.begin(LoadProgress.Stage.DOWNLOADING,100);aborted.advance(7);aborted.cancel();aborted.advance(1);check(aborted.snapshot().stage().equals("cancelled")&&aborted.snapshot().bytes()==7&&!aborted.snapshot().loading(),"cancelled transfer is terminal and ignores late progress");
        LoadProgress large=new LoadProgress(card);large.begin(LoadProgress.Stage.DOWNLOADING,Long.MAX_VALUE);large.advance(Long.MAX_VALUE-1);check(large.snapshot().percent()<100,"near-limit rounding never claims 100 before final byte");large.advance(1);check(large.snapshot().percent()==100,"exact final byte reaches 100");
        System.out.println("RomTransfer checks="+checks+"; real file/cache/chunk/identity/cancellation progress");
    }
}
