package net.muxigame.console.domain.storage;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** A stalled real control HTTP request must be interruptible during normal server shutdown. */
public final class StorageCancellationTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        verify("HEAD");verify("POST");
        System.out.println("StorageCancellationTest: "+checks+" checks passed; real loopback HEAD/POST interruption, no object GET");
    }
    private static void verify(String method)throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicReference<String> actualMethod=new AtomicReference<>();AtomicInteger requests=new AtomicInteger();
        AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean interrupted=new AtomicBoolean();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService service=Executors.newSingleThreadExecutor();server.setExecutor(service);
        server.createContext("/",exchange->{
            requests.incrementAndGet();actualMethod.set(exchange.getRequestMethod());entered.countDown();
            try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException stop){Thread.currentThread().interrupt();}
            finally{exchange.close();}
        });server.start();
        var config=new StorageConfig(StorageConfig.Provider.S3,
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"test-bucket","test-region","original-diagnostics",
                "dummy-id","dummy-secret","",true,60);
        var storage=new CloudObjectStorage(config,Clock.systemUTC());
        Thread caller=new Thread(()->{
            try{if(method.equals("HEAD"))storage.head("a".repeat(64));else storage.multipart().orElseThrow().start("a".repeat(64),5_000_000_001L,"wii");}
            catch(Throwable error){failure.set(error);interrupted.set(Thread.currentThread().isInterrupted());}
        },"storage-cancellation-fixture");
        try{
            caller.start();check(entered.await(5,TimeUnit.SECONDS),method+" reached the real server before cancellation");
            long start=System.nanoTime();caller.interrupt();caller.join(2000);
            check(!caller.isAlive()&&System.nanoTime()-start<TimeUnit.SECONDS.toNanos(2),method+" leaves a stalled provider without waiting its request timeout");
            check(failure.get() instanceof IOException,method+" reports interruption as a storage failure");
            check(interrupted.get(),method+" preserves the worker interrupt for shutdown");
            check(requests.get()==1&&method.equals(actualMethod.get()),method+" cancellation never retries or downloads object bytes");
        }finally{
            caller.interrupt();release.countDown();caller.join(2000);server.stop(0);service.shutdownNow();
            check(service.awaitTermination(2,TimeUnit.SECONDS),method+" fixture server closes normally");
            if(caller.isAlive())throw new AssertionError("Fixture caller remained alive");
        }
    }
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
}
