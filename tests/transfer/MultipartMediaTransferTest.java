package net.muxigame.console.client.transfer;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.muxigame.console.domain.Hashing;
import net.muxigame.console.domain.storage.ObjectStorage.Multipart;

/** Real small streaming PUTs, cancellation, and a sparse >5GB offset. No cloud or large-file transfer. */
public final class MultipartMediaTransferTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        Path output=Path.of(args.length==0?"build/multipart-transfer-tests":args[0]);Files.createDirectories(output);
        Path root=Files.createTempDirectory(output,"multipart-");
        var handlers=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"multipart-test-http");t.setDaemon(true);return t;});
        var client=Executors.newSingleThreadExecutor();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(handlers);
        AtomicInteger requests=new AtomicInteger(),gets=new AtomicInteger(),redirectTarget=new AtomicInteger();AtomicLong bytes=new AtomicLong();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var hashes=new java.util.concurrent.ConcurrentHashMap<String,String>();
        server.createContext("/",exchange->{try{
            requests.incrementAndGet();if(exchange.getRequestMethod().equals("GET"))gets.incrementAndGet();
            String path=exchange.getRequestURI().getPath();
            if(path.equals("/stall")){entered.countDown();try{release.await(8,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}return;}
            if(path.equals("/redirect-target")){redirectTarget.incrementAndGet();exchange.sendResponseHeaders(200,-1);return;}
            if(path.equals("/redirect")){exchange.getResponseHeaders().add("Location","/redirect-target");exchange.sendResponseHeaders(302,-1);return;}
            var digest=Hashing.sha256Digest();long count=0;byte[] block=new byte[32768];try(var input=exchange.getRequestBody()){for(int n;(n=input.read(block))!=-1;){count+=n;digest.update(block,0,n);}}
            if(!Long.toString(count).equals(exchange.getRequestHeaders().getFirst("Content-Length")))throw new IOException("Expected known-length HTTP body");
            hashes.put(exchange.getRequestURI().getRawQuery(),java.util.HexFormat.of().formatHex(digest.digest()));bytes.addAndGet(count);
            if(!path.equals("/missing-etag"))exchange.getResponseHeaders().add("ETag","\"actual-cloud-receipt\"");exchange.sendResponseHeaders(200,-1);
        }catch(IOException cancelled){/* Cancellation can close the request before the server body completes. */}finally{exchange.close();}});server.start();
        URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        try{
            Path file=root.resolve("original.bin");long size=(long)Multipart.PART_BYTES+257;
            try(var stream=Files.newOutputStream(file)){byte[] block=new byte[32768];for(int i=0;i<block.length;i++)block[i]=(byte)(i*31+7);for(long at=0;at<size;at+=block.length)stream.write(block,0,(int)Math.min(block.length,size-at));}
            String hash=Hashing.sha256(file);var plan=new Multipart.Upload("test-id",hash,size,"wii",Multipart.PART_BYTES);
            fails(()->MultipartMediaTransfer.verify(file,size,"0".repeat(64),()->true),"wrong whole-file SHA rejected before upload");eq(0,requests.get(),"no HTTP before verified local bytes");
            try(var source=MultipartMediaTransfer.verify(file,size,hash,()->true)){
                check(!source.toString().contains(file.toString()),"verified-file toString hides local path");
                for(int part=1;part<=2;part++){
                    final int number=part;AtomicLong progress=new AtomicLong(-1);long before=bytes.get();
                    var receipt=MultipartMediaTransfer.putPart(source,plan,part,ticket(base,"/part",plan,part),()->true,value->{long previous=progress.getAndSet(value);if(value<previous||value>plan.partBytes(number))throw new AssertionError("Invalid actual body progress");});
                    eq(plan.partBytes(part),bytes.get()-before,"real HTTP receives exactly this range");eq(plan.partBytes(part),progress.get(),"actual publisher reaches exact part length");eq("\"actual-cloud-receipt\"",receipt.etag(),"real ETag is retained verbatim");
                    String query="uploadId=test-id&partNumber="+part;eq(rangeHash(file,plan.offset(part),plan.partBytes(part)),hashes.get(query),"range bytes match original file independently");
                }
                int before=requests.get();fails(()->MultipartMediaTransfer.putPart(source,plan,1,ticket(base,"/part",plan,2),()->true,n->{}),"wrong ticket part identity rejected");eq(before,requests.get(),"invalid part ticket sends no HTTP");
                fails(()->MultipartMediaTransfer.putPart(source,plan,2,ticket(base,"/missing-etag",plan,2),()->true,n->{}),"missing cloud ETag cannot produce a receipt");
                fails(()->MultipartMediaTransfer.putPart(source,plan,2,ticket(base,"/redirect",plan,2),()->true,n->{}),"redirect is rejected");eq(0,redirectTarget.get(),"signed tickets never redirect to another destination");
            }
            var cancelled=MultipartMediaTransfer.verify(file,size,hash,()->true);
            var job=client.submit(()->MultipartMediaTransfer.putPart(cancelled,plan,1,ticket(base,"/stall",plan,1),()->true,n->{}));
            check(entered.await(5,TimeUnit.SECONDS),"actual streaming PUT reaches deliberately stalled endpoint");cancelled.close();
            try{job.get(5,TimeUnit.SECONDS);throw new AssertionError("Cancelled multipart PUT succeeded");}catch(ExecutionException stopped){check(stopped.getCause() instanceof IOException,"close cancels HTTP future and file reader promptly");}finally{release.countDown();}
            fails(()->MultipartMediaTransfer.putPart(cancelled,plan,2,ticket(base,"/part",plan,2),()->true,n->{}),"closed verified file cannot upload a later part");
            try(var source=MultipartMediaTransfer.verify(file,size,hash,()->true)){Files.write(file,new byte[]{1});fails(()->MultipartMediaTransfer.putPart(source,plan,1,ticket(base,"/part",plan,1),()->true,n->{}),"changed verified source is refused before body publication");}
            // Sparse offset exercise uses the same range publisher; it does not pretend to hash a 9GiB ROM.
            Path sparse=root.resolve("logical-9gib.bin");long end=9L*1024*1024*1024;
            try(var channel=FileChannel.open(sparse,StandardOpenOption.CREATE_NEW,StandardOpenOption.SPARSE,StandardOpenOption.READ,StandardOpenOption.WRITE)){
                channel.write(ByteBuffer.wrap(new byte[]{42}),end-1);long start=end-257;AtomicLong progress=new AtomicLong();
                var body=MultipartMediaTransfer.range(channel,start,257,()->true,progress::set);eq(257L,body.contentLength(),"sparse range has exact known length");
                var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(base.resolve("/sparse?fixture=logical")).PUT(body).build(),HttpResponse.BodyHandlers.discarding());
                eq(200,response.statusCode(),"actual HTTP streams only 257 bytes from >9GB offset");eq(rangeHash(sparse,start,257),hashes.get("fixture=logical"),"sparse long offset is not truncated");eq(257L,progress.get(),"sparse logical file does not inflate progress to whole size");
            }
            eq(0,gets.get(),"multipart client makes no object GET");check(bytes.get()<2L*Multipart.PART_BYTES,"test transferred only bounded small fixtures, not the 9GiB logical file");
            System.out.println("Multipart client transfer: "+checks+" checks passed; real HTTP/range/ETag/cancel/sparse offset, no cloud");
        }finally{release.countDown();server.stop(0);handlers.shutdownNow();client.shutdownNow();try(var paths=Files.walk(root)){for(Path p:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    private static HttpMediaTransfer.Ticket ticket(URI base,String path,Multipart.Upload plan,int number){return new HttpMediaTransfer.Ticket("PUT",base.resolve(path+"?uploadId="+plan.id()+"&partNumber="+number),Map.of("content-length",Long.toString(plan.partBytes(number))),System.currentTimeMillis()+60000);}
    private static String rangeHash(Path file,long offset,long length)throws IOException{var digest=Hashing.sha256Digest();try(var channel=FileChannel.open(file,StandardOpenOption.READ)){ByteBuffer block=ByteBuffer.allocate(32768);for(long read=0;read<length;){block.clear();block.limit((int)Math.min(block.capacity(),length-read));int n=channel.read(block,offset+read);if(n<1)throw new IOException();digest.update(block.array(),0,n);read+=n;}}return java.util.HexFormat.of().formatHex(digest.digest());}
    @FunctionalInterface private interface Action{void run()throws Exception;}
    private static void fails(Action action,String label)throws Exception{try{action.run();throw new AssertionError(label);}catch(IOException|IllegalArgumentException expected){checks++;}}
    private static void eq(Object expected,Object actual,String label){check(java.util.Objects.equals(expected,actual),label+" expected="+expected+" actual="+actual);}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
}
