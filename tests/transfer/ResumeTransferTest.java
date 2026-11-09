package net.muxigame.console.tests.transfer;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.muxigame.console.client.transfer.*;
import net.muxigame.console.domain.Hashing;
import net.muxigame.console.domain.DomainException;

public final class ResumeTransferTest {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static void rejected(Attempt action)throws Exception{try{action.run();throw new AssertionError("Expected failure");}catch(IOException expected){checks++;}}
    @FunctionalInterface private interface Attempt{void run()throws Exception;}
    private static RomTransfer.Chunk chunk(byte[] data,String hash,long offset){return new RomTransfer.Chunk(offset,data.length,hash,Arrays.copyOfRange(data,(int)offset,Math.min(data.length,(int)offset+RomTransfer.CHUNK_BYTES)));}
    public static void main(String[] args)throws Exception{
        Path base=Path.of(args[0]).resolve("resume-transfer");Files.createDirectories(base);Path cache=Files.createTempDirectory(base,"case-").toAbsolutePath();
        byte[] data=new byte[8*RomTransfer.CHUNK_BYTES+71];new Random(294).nextBytes(data);String hash=Hashing.sha256(data);UUID card=UUID.randomUUID();
        LoadProgress interrupted=new LoadProgress(card);List<Long> offsets=new ArrayList<>();
        rejected(()->RomTransfer.receive(cache,hash,"gba",data.length,o->{offsets.add(o);return chunk(data,hash,o);},()->interrupted.snapshot().bytes()<3L*RomTransfer.CHUNK_BYTES,interrupted));
        check(offsets.equals(List.of(0L,(long)RomTransfer.CHUNK_BYTES,2L*RomTransfer.CHUNK_BYTES)),"cancel sends no next request");
        check(!Files.exists(cache.resolve(hash+".gba")),"partial never appears as a ROM");
        check(Files.size(cache.resolve(hash+".part"))==3L*RomTransfer.CHUNK_BYTES,"complete received prefix survives normal cancellation");
        // A process can die after writing data but before atomically committing metadata.
        Files.write(cache.resolve(hash+".part"),new byte[]{0,1,2},StandardOpenOption.APPEND);
        List<Long> resumed=new ArrayList<>();LoadProgress second=new LoadProgress(UUID.randomUUID());
        Path complete=RomTransfer.receive(cache,hash,"gba",data.length,o->{resumed.add(o);check(second.snapshot().bytes()==o,"resumed progress starts at the actual committed offset");return chunk(data,hash,o);},()->true,second);
        check(resumed.getFirst()==3L*RomTransfer.CHUNK_BYTES,"another card/session resumes content identity instead of UUID");
        check(Hashing.sha256(complete).equals(hash)&&!Files.exists(cache.resolve(hash+".part"))&&!Files.exists(cache.resolve(hash+".meta")),"full verification atomically publishes and retires journal");
        Path other=RomTransfer.receive(cache,hash,"agb",data.length,o->{throw new AssertionError("Extension cache reuse made a request");},()->true,new LoadProgress(card));
        check(Hashing.sha256(other).equals(hash),"same content cached with another extension is reused without network");
        Path local=Files.createTempDirectory(base,"local-").toAbsolutePath(),source=local.resolve("original.gba");Files.write(source,data);
        Path author=RomTransfer.cacheLocal(local.resolve("cache"),hash,"gba",data.length,source,()->true);
        check(Hashing.sha256(author).equals(hash),"author's real local bytes seed verified shared cache");
        RomTransfer.receive(local.resolve("cache"),hash,"gba",data.length,o->{throw new AssertionError("Author re-downloaded own upload");},()->true,new LoadProgress(card));checks++;
        Path broken=Files.createTempDirectory(base,"broken-").toAbsolutePath();LoadProgress stop=new LoadProgress(card);
        rejected(()->RomTransfer.receive(broken,hash,"gba",data.length,o->chunk(data,hash,o),()->stop.snapshot().bytes()<RomTransfer.CHUNK_BYTES,stop));
        byte[] prefix=Files.readAllBytes(broken.resolve(hash+".part"));prefix[0]^=1;Files.write(broken.resolve(hash+".part"),prefix);
        List<Long> repair=new ArrayList<>();Path repaired=RomTransfer.receive(broken,hash,"gba",data.length,o->{repair.add(o);return chunk(data,hash,o);},()->true,new LoadProgress(card));
        check(repair.getFirst()==RomTransfer.CHUNK_BYTES&&Collections.frequency(repair,0L)==1,"corrupted old prefix permits exactly one complete repair");
        check(Hashing.sha256(repaired).equals(hash),"corrupted prefix never becomes published content");
        Path wrongMeta=Files.createTempDirectory(base,"wrong-meta-").toAbsolutePath();Files.write(wrongMeta.resolve(hash+".part"),data);Files.writeString(wrongMeta.resolve(hash+".meta"),"MXROM1\n"+hash+"\n"+(data.length+1)+"\n1\n");AtomicLong first=new AtomicLong(-1);
        RomTransfer.receive(wrongMeta,hash,"gba",data.length,o->{first.compareAndSet(-1,o);return chunk(data,hash,o);},()->true,new LoadProgress(card));check(first.get()==0,"size/identity journal mismatch restarts safely");
        Path timeout=Files.createTempDirectory(base,"network-").toAbsolutePath();AtomicInteger read=new AtomicInteger();
        try{RomTransfer.receive(timeout,hash,"gba",data.length,o->{if(read.incrementAndGet()==3)throw new TimeoutException();return chunk(data,hash,o);},()->true,new LoadProgress(card));throw new AssertionError("Missing timeout");}catch(TimeoutException expected){checks++;}
        first.set(-1);RomTransfer.receive(timeout,hash,"gba",data.length,o->{first.compareAndSet(-1,o);return chunk(data,hash,o);},()->true,new LoadProgress(card));check(first.get()==2L*RomTransfer.CHUNK_BYTES,"timeout retains committed prefix");
        String failure=TransferFailure.playerMessage(new ExecutionException(new TimeoutException()),"downloading");check(failure.contains("rom_timeout")&&failure.contains("保留"),"wrapped timeout remains actionable");
        check(TransferFailure.playerMessage(new IOException("选择的机型无效"),"starting").equals("选择的机型无效"),"specific Chinese failure survives async handling");
        String diagnostic=TransferFailure.diagnostic(new IOException("C:\\Users\\player\\private\\game.gba https://example.invalid/private?token=secret"));
        check(!diagnostic.contains("player")&&!diagnostic.contains("token=")&&!diagnostic.contains("game.gba"),"diagnostics omit local ROM paths and remote credentials");
        check(TransferFailure.code(new ExecutionException(new DomainException("permission","需要持卡")),"downloading").equals("downloading_permission"),"server error code survives its async wrapper");
        Path locked=Files.createTempDirectory(base,"locked-").toAbsolutePath();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean waitingActive=new AtomicBoolean(true);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Future<Path> owner=pool.submit(()->RomTransfer.receive(locked,hash,"gba",data.length,o->{if(o==0){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IOException("test release timeout");}return chunk(data,hash,o);},()->true,new LoadProgress(card)));
            check(entered.await(5,TimeUnit.SECONDS),"first transfer owns its content lock");
            Future<Path> waiter=pool.submit(()->RomTransfer.receive(locked,hash,"gba",data.length,o->{throw new AssertionError("Cancelled lock waiter downloaded bytes");},waitingActive::get,new LoadProgress(card)));
            waitingActive.set(false);try{waiter.get(5,TimeUnit.SECONDS);throw new AssertionError("Cancelled waiter continued");}catch(ExecutionException expected){check(expected.getCause() instanceof IOException,"cache lock wait remains cancellable");}
            release.countDown();check(Hashing.sha256(owner.get(5,TimeUnit.SECONDS)).equals(hash),"cancelling another waiter does not damage the active transfer");
        }finally{release.countDown();pool.shutdownNow();}
        System.out.println("ResumeTransfer checks="+checks+"; persistent actual bytes, crash tail, author reuse, repaired hash, safe diagnostics");
    }
}
