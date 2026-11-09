package net.muxigame.console.client.transfer;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** Direct object-store bytes. Tickets are ephemeral and never form part of a room context or log. */
public final class HttpMediaTransfer {
    public record Ticket(String method,URI url,Map<String,String> headers,long expiresAt){
        public Ticket{headers=Map.copyOf(headers);if(!Set.of("GET","PUT").contains(method)||url==null||!Set.of("http","https").contains(url.getScheme())||url.getHost()==null||url.getUserInfo()!=null||url.getFragment()!=null||headers.size()>32)throw new IllegalArgumentException("Invalid media ticket");}
        @Override public String toString(){return "MediaTicket["+method+", expiresAt="+expiresAt+"]";}
    }
    @FunctionalInterface public interface Tickets {Ticket get(long offset)throws Exception;}
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final ExecutorService READS=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"muxi-console-cloud-read");t.setDaemon(true);return t;});
    private static final long IDLE_NANOS=TimeUnit.SECONDS.toNanos(30);
    private HttpMediaTransfer(){}
    private static HttpRequest.Builder request(Ticket ticket,String method)throws IOException{
        if(!method.equals(ticket.method()))throw new IOException("云端传输凭证操作不符");
        try{HttpRequest.Builder request=HttpRequest.newBuilder(ticket.url()).timeout(Duration.ofSeconds(30));
            for(var header:ticket.headers().entrySet())request.header(header.getKey(),header.getValue());return request;
        }catch(IllegalArgumentException invalid){throw new IOException("云端传输凭证格式无效");}
    }
    private static <T>T await(Future<T> future,BooleanSupplier active,LongSupplier progress)throws Exception{
        long deadline=System.nanoTime()+IDLE_NANOS,last=progress.getAsLong();
        try{for(;;){RomTransfer.requireActive(active);try{return future.get(200,TimeUnit.MILLISECONDS);}catch(TimeoutException waiting){long bytes=progress.getAsLong();if(bytes!=last){last=bytes;deadline=System.nanoTime()+IDLE_NANOS;}if(System.nanoTime()-deadline>=0)throw new TimeoutException("Cloud transfer idle timeout");}}}
        catch(Exception failure){future.cancel(true);if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw failure;}
    }
    /** One streaming Range response per connection, with fresh authorization at each reconnection. */
    public static final class RangeReader implements RomTransfer.Reader,AutoCloseable {
        private final String hash;private final long size;private final Tickets tickets;private final BooleanSupplier active;
        private volatile boolean closed;private volatile InputStream input;private volatile Future<?> pending;private long next;
        public RangeReader(String hash,long size,Tickets tickets,BooleanSupplier active){this.hash=hash;this.size=size;this.tickets=tickets;this.active=active;}
        private boolean live(){return !closed&&active.getAsBoolean();}
        private void drop(){InputStream old=input;input=null;if(old!=null)try{old.close();}catch(IOException ignored){}}
        private void open(long offset)throws Exception{
            RomTransfer.requireActive(this::live);Ticket ticket=tickets.get(offset);RomTransfer.requireActive(this::live);
            HttpRequest call=request(ticket,"GET").header("Range","bytes="+offset+"-").GET().build();
            CompletableFuture<HttpResponse<InputStream>> future=HTTP.sendAsync(call,HttpResponse.BodyHandlers.ofInputStream());pending=future;
            HttpResponse<InputStream> response;
            try{response=await(future,this::live,()->0);}finally{if(pending==future)pending=null;}
            InputStream body=response.body();input=body;
            if(!live()){drop();throw new IOException("卡带加载已取消");}
            int status=response.statusCode();long remaining=size-offset;
            if(status!=206&&!(offset==0&&status==200)){drop();throw new IOException("云端卡带读取失败 [http_"+status+"]");}
            if(status==206){String range=response.headers().firstValue("Content-Range").orElse("");String expected="bytes "+offset+"-"+(size-1)+"/"+size;if(!range.equals(expected)){drop();throw new IOException("云端卡带分段范围无效");}}
            long length=response.headers().firstValueAsLong("Content-Length").orElse(-1);if(length!=-1&&length!=remaining){drop();throw new IOException("云端卡带大小与卡带不符");}next=offset;
        }
        @Override public RomTransfer.Chunk read(long offset)throws Exception{
            Exception last=null;
            for(int attempt=0;attempt<3;attempt++){
                RomTransfer.requireActive(this::live);
                try{if(input==null||next!=offset){drop();open(offset);}InputStream stream=input;byte[] buffer=new byte[(int)Math.min(RomTransfer.STREAM_CHUNK_BYTES,size-offset)];
                    Future<Integer> read=READS.submit(()->stream.read(buffer));pending=read;int count;
                    try{count=await(read,this::live,()->0);}finally{if(pending==read)pending=null;}
                    RomTransfer.requireActive(this::live);if(count<1)throw new EOFException("Cloud content ended early");next=offset+count;return new RomTransfer.Chunk(offset,size,hash,Arrays.copyOf(buffer,count));
                }catch(Exception failure){last=failure;drop();RomTransfer.requireActive(this::live);if(attempt==2)throw new IOException("云端卡带读取中断；已保留已下载部分",failure);}
            }throw last;
        }
        @Override public void close(){closed=true;Future<?> task=pending;if(task!=null)task.cancel(true);drop();}
    }
    /** PUT is always the complete verified local file. Conflicts must be checked by server HEAD. */
    public static int put(Ticket ticket,Path file,long size,BooleanSupplier active,LongConsumer progress)throws Exception{
        RomTransfer.requireActive(active);if(Files.size(file)!=size||Files.isSymbolicLink(file))throw new IOException("上传文件已改变");
        AtomicLong sent=new AtomicLong();HttpRequest.BodyPublisher source=HttpRequest.BodyPublishers.ofFile(file);
        HttpRequest.BodyPublisher counted=new HttpRequest.BodyPublisher(){
            public long contentLength(){return source.contentLength();}
            public void subscribe(Flow.Subscriber<? super ByteBuffer> downstream){source.subscribe(new Flow.Subscriber<ByteBuffer>(){
                public void onSubscribe(Flow.Subscription subscription){downstream.onSubscribe(subscription);}
                public void onNext(ByteBuffer buffer){progress.accept(sent.updateAndGet(previous->Math.min(size,previous+buffer.remaining())));downstream.onNext(buffer);}
                public void onError(Throwable error){downstream.onError(error);}public void onComplete(){downstream.onComplete();}
            });}
        };
        CompletableFuture<HttpResponse<Void>> future=HTTP.sendAsync(request(ticket,"PUT").timeout(Duration.ofHours(1)).PUT(counted).build(),HttpResponse.BodyHandlers.discarding());
        try{int status=await(future,active,sent::get).statusCode();RomTransfer.requireActive(active);return status;}finally{future.cancel(true);}
    }
}
