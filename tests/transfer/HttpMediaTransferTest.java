package net.muxigame.console.tests.transfer;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.muxigame.console.client.transfer.*;
import net.muxigame.console.domain.Hashing;

/** Real loopback HTTP bytes; no cloud credentials, Minecraft, server proxy or external network. */
public final class HttpMediaTransferTest {
    private static int checks;
    private static void check(boolean yes,String label){checks++;if(!yes)throw new AssertionError(label);}
    private static HttpMediaTransfer.Ticket ticket(URI uri,String method){return new HttpMediaTransfer.Ticket(method,uri,Map.of(),System.currentTimeMillis()+60000);}
    public static void main(String[] args)throws Exception{
        byte[] data=new byte[2*1024*1024+19];new Random(724).nextBytes(data);String hash=Hashing.sha256(data);
        AtomicInteger gets=new AtomicInteger(),badGets=new AtomicInteger();List<Long> ranges=Collections.synchronizedList(new ArrayList<>());AtomicLong uploaded=new AtomicLong();AtomicReference<String> uploadedHash=new AtomicReference<>();CountDownLatch stalled=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService handlers=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"test-cloud-http");t.setDaemon(true);return t;});HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(handlers);
        server.createContext("/rom",exchange->{try{String range=exchange.getRequestHeaders().getFirst("Range");long offset=Long.parseLong(range.substring(6,range.length()-1));ranges.add(offset);int attempt=gets.incrementAndGet();exchange.getResponseHeaders().add("Content-Range","bytes "+offset+"-"+(data.length-1)+"/"+data.length);exchange.sendResponseHeaders(206,data.length-offset);try(OutputStream out=exchange.getResponseBody()){int count=attempt==1?65536:data.length-(int)offset;out.write(data,(int)offset,count);}}catch(IOException expectedTruncation){}finally{exchange.close();}});
        server.createContext("/expired",exchange->{exchange.sendResponseHeaders(403,-1);exchange.close();});
        server.createContext("/bad-range",exchange->{badGets.incrementAndGet();exchange.getResponseHeaders().add("Content-Range","bytes 1-"+(data.length-1)+"/"+data.length);exchange.sendResponseHeaders(206,-1);exchange.close();});
        server.createContext("/stall",exchange->{try{exchange.getResponseHeaders().add("Content-Range","bytes 0-"+(data.length-1)+"/"+data.length);exchange.sendResponseHeaders(206,data.length);exchange.getResponseBody().flush();stalled.countDown();release.await(5,TimeUnit.SECONDS);}catch(Exception stopped){}finally{exchange.close();}});
        server.createContext("/upload",exchange->{try{var digest=Hashing.sha256Digest();byte[] block=new byte[32768];try(InputStream in=exchange.getRequestBody()){for(int n;(n=in.read(block))!=-1;){uploaded.addAndGet(n);digest.update(block,0,n);}}uploadedHash.set(HexFormat.of().formatHex(digest.digest()));exchange.sendResponseHeaders(201,-1);}finally{exchange.close();}});
        server.createContext("/exists",exchange->{exchange.sendResponseHeaders(412,-1);exchange.close();});server.start();
        Path root=Path.of(args[0]).resolve("http-media");Files.createDirectories(root);root=Files.createTempDirectory(root,"case-").toAbsolutePath();URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        try{
            AtomicInteger tickets=new AtomicInteger();LoadProgress progress=new LoadProgress(UUID.randomUUID());
            try(var reader=new HttpMediaTransfer.RangeReader(hash,data.length,offset->{tickets.incrementAndGet();return ticket(base.resolve("/rom?Signature=fixture-private"),"GET");},()->true)){
                Path complete=RomTransfer.receive(root.resolve("range"),hash,"gba",data.length,reader,()->true,progress,RomTransfer.STREAM_CHUNK_BYTES);check(Hashing.sha256(complete).equals(hash),"truncated HTTP stream resumes exact bytes and passes whole SHA");
            }
            check(gets.get()==2&&ranges.getFirst()==0&&ranges.get(1)>0,"one Range stream per connection; reconnect starts at committed offset");check(tickets.get()==2,"broken stream requests fresh authorization");check(progress.snapshot().bytes()==data.length,"progress is actual fully verified work");
            AtomicInteger renewed=new AtomicInteger();try(var reader=new HttpMediaTransfer.RangeReader(hash,data.length,offset->ticket(base.resolve(renewed.incrementAndGet()==1?"/expired":"/rom"),"GET"),()->true)){
                Path complete=RomTransfer.receive(root.resolve("renew"),hash,"gba",data.length,reader,()->true,new LoadProgress(UUID.randomUUID()),RomTransfer.STREAM_CHUNK_BYTES);check(Hashing.sha256(complete).equals(hash)&&renewed.get()==2,"403 renews once rather than using the expired URL again");
            }
            try(var reader=new HttpMediaTransfer.RangeReader(hash,data.length,offset->ticket(base.resolve("/bad-range"),"GET"),()->true)){
                try{RomTransfer.receive(root.resolve("bad"),hash,"gba",data.length,reader,()->true,new LoadProgress(UUID.randomUUID()),RomTransfer.STREAM_CHUNK_BYTES);throw new AssertionError("Wrong range accepted");}catch(IOException expected){checks++;}
            }
            check(badGets.get()==3&&!Files.exists(root.resolve("bad").resolve(hash+".gba")),"malformed ranges retry only a bounded count and never publish content");
            AtomicBoolean active=new AtomicBoolean(true);ExecutorService receive=Executors.newSingleThreadExecutor();
            try(var reader=new HttpMediaTransfer.RangeReader(hash,data.length,offset->ticket(base.resolve("/stall"),"GET"),active::get)){
                Path cancel=root.resolve("cancel");Future<Path> job=receive.submit(()->RomTransfer.receive(cancel,hash,"gba",data.length,reader,active::get,new LoadProgress(UUID.randomUUID()),RomTransfer.STREAM_CHUNK_BYTES));check(stalled.await(5,TimeUnit.SECONDS),"actual HTTP headers reached a deliberately stalled body");active.set(false);reader.close();
                try{job.get(5,TimeUnit.SECONDS);throw new AssertionError("Cancelled HTTP continued");}catch(ExecutionException expected){check(expected.getCause() instanceof IOException,"cancellation closes an in-flight HTTP body without waiting its network timeout");}
                check(!Files.exists(cancel.resolve(hash+".gba")),"cancelled cloud response does not become a cache hit");
            }finally{release.countDown();receive.shutdownNow();}
            Path local=root.resolve("author.gba");Files.write(local,data);AtomicLong sent=new AtomicLong();int status=HttpMediaTransfer.put(ticket(base.resolve("/upload?Signature=fixture-private"),"PUT"),local,data.length,()->true,sent::set);
            check(status==201&&uploaded.get()==data.length&&uploadedHash.get().equals(hash),"direct streaming PUT reaches object endpoint with exact bytes");check(sent.get()==data.length,"upload progress counts actual body publisher bytes");
            check(HttpMediaTransfer.put(ticket(base.resolve("/exists"),"PUT"),local,data.length,()->true,n->{})==412,"existing object conflict returns to server HEAD validation without overwrite retry");
            check(!ticket(base.resolve("/rom?Signature=fixture-private"),"GET").toString().contains("fixture-private"),"ticket string representation never exposes signatures or headers");
            System.out.println("HttpMediaTransfer checks="+checks+"; actual Range/reconnect/expiry/cancel/direct PUT/conflict, no external network");
        }finally{release.countDown();server.stop(0);handlers.shutdownNow();}
    }
}
