package net.muxigame.console.client.transfer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import net.muxigame.console.domain.Hashing;
import net.muxigame.console.domain.storage.ObjectStorage.Multipart;

/** One verified, bounded local file handle; independent streaming UploadPart calls with real receipts. */
public final class MultipartMediaTransfer {
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final long IDLE_NANOS=TimeUnit.SECONDS.toNanos(30);
    private MultipartMediaTransfer() { }

    public static final class VerifiedFile implements AutoCloseable {
        private final Path path;
        private final FileChannel channel;
        private final BasicFileAttributes original;
        private final String hash;
        private volatile boolean closed;
        private boolean inUse;
        private CompletableFuture<?> pending;
        private VerifiedFile(Path path,FileChannel channel,BasicFileAttributes original,String hash){this.path=path;this.channel=channel;this.original=original;this.hash=hash;}
        public long size(){return original.size();}
        public String hash(){return hash;}
        private void check()throws IOException{
            if(closed||!channel.isOpen())throw new IOException("Multipart upload was cancelled");
            BasicFileAttributes current=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!current.isRegularFile()||current.isSymbolicLink()||channel.size()!=size()||current.size()!=size()
                    ||!current.lastModifiedTime().equals(original.lastModifiedTime())||!java.util.Objects.equals(current.fileKey(),original.fileKey()))
                throw new IOException("Multipart source file changed");
        }
        private synchronized void begin()throws IOException{check();if(inUse)throw new IOException("Another part already uses this verified file");inUse=true;}
        private synchronized void future(CompletableFuture<?> task)throws IOException{if(closed){task.cancel(true);throw new IOException("Multipart upload was cancelled");}pending=task;}
        private synchronized void end(){pending=null;inUse=false;}
        @Override public synchronized void close()throws IOException{closed=true;if(pending!=null)pending.cancel(true);channel.close();}
        @Override public String toString(){return "VerifiedMultipartFile[size="+size()+", path=<redacted>]";}
    }

    /** Hashes once in 32KiB buffers and retains this exact file handle for all subsequent ranges. */
    public static VerifiedFile verify(Path file,long size,String sha256,BooleanSupplier active)throws IOException{
        String hash=Hashing.normalizeSha256(sha256);if(size<1)throw new IOException("Invalid multipart file size");
        FileChannel channel=null;
        try{
            require(active);Path path=file.toAbsolutePath().normalize();BasicFileAttributes attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attributes.isRegularFile()||attributes.isSymbolicLink()||attributes.size()!=size)throw new IOException("Multipart source is not the expected regular file");
            channel=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
            var digest=Hashing.sha256Digest();ByteBuffer buffer=ByteBuffer.allocate(32768);long offset=0;
            while(offset<size){require(active);buffer.clear();buffer.limit((int)Math.min(buffer.capacity(),size-offset));int count=channel.read(buffer,offset);if(count<1)throw new IOException("Multipart source ended early");digest.update(buffer.array(),0,count);offset+=count;}
            if(!java.util.HexFormat.of().formatHex(digest.digest()).equals(hash))throw new IOException("Multipart source hash differs from the selected file");
            VerifiedFile source=new VerifiedFile(path,channel,attributes,hash);source.check();require(active);return source;
        }catch(IOException|RuntimeException failure){if(channel!=null)try{channel.close();}catch(IOException ignored){}throw new IOException("Multipart source could not be verified");}
    }

    /** Progress is the current part's actual HTTP-body bytes, reset to zero for a fresh subscription. */
    public static Multipart.Part putPart(VerifiedFile source,Multipart.Upload plan,int number,HttpMediaTransfer.Ticket ticket,
                                         BooleanSupplier active,LongConsumer progress)throws Exception{
        require(active);if(!source.hash().equals(plan.hash())||source.size()!=plan.size())throw new IOException("Multipart plan differs from the verified source");
        long length=plan.partBytes(number);validateTicket(ticket,plan,number,length);source.begin();CompletableFuture<HttpResponse<Void>> future=null;
        try{
            AtomicLong sent=new AtomicLong();
            HttpRequest.BodyPublisher body=range(source.channel,plan.offset(number),length,()->!source.closed&&active.getAsBoolean(),value->{sent.set(value);progress.accept(value);});
            HttpRequest.Builder builder=HttpRequest.newBuilder(ticket.url()).timeout(Duration.ofMinutes(15));
            for(var header:ticket.headers().entrySet())if(!Set.of("host","content-length").contains(header.getKey().toLowerCase(java.util.Locale.ROOT)))builder.header(header.getKey(),header.getValue());
            future=HTTP.sendAsync(builder.PUT(body).build(),HttpResponse.BodyHandlers.discarding());source.future(future);
            long deadline=System.nanoTime()+IDLE_NANOS,last=0;HttpResponse<Void> response;
            for(;;){require(active);source.check();try{response=future.get(200,TimeUnit.MILLISECONDS);break;}catch(TimeoutException waiting){long bytes=sent.get();if(bytes!=last){last=bytes;deadline=System.nanoTime()+IDLE_NANOS;}if(System.nanoTime()-deadline>=0)throw new IOException("Multipart upload part timed out");}}
            require(active);source.check();if(response.statusCode()!=200)throw new IOException("Multipart upload part returned HTTP "+response.statusCode());
            var tags=response.headers().allValues("etag");if(tags.size()!=1)throw new IOException("Cloud did not return one multipart ETag");
            if(sent.get()!=length)throw new IOException("Multipart HTTP body was not fully sent");
            return new Multipart.Part(number,length,tags.getFirst());
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Multipart upload was interrupted");}
        catch(java.util.concurrent.ExecutionException|java.util.concurrent.CancellationException|IllegalArgumentException failure){throw new IOException("Multipart upload part failed");}
        finally{if(future!=null)future.cancel(true);source.end();}
    }

    /** Known-length publisher with absolute long offsets; at most the HTTP reader's 32KiB window is filled. */
    static HttpRequest.BodyPublisher range(FileChannel channel,long offset,long length,BooleanSupplier active,LongConsumer progress)throws IOException{
        if(offset<0||length<1||offset>channel.size()||length>channel.size()-offset)throw new IOException("Multipart source range is outside the file");
        var stream=HttpRequest.BodyPublishers.ofInputStream(()->new InputStream(){
            private long read;private boolean closed;
            {progress.accept(0);}
            @Override public int read()throws IOException{byte[] single=new byte[1];return read(single,0,1)<0?-1:single[0]&255;}
            @Override public int read(byte[] bytes,int from,int amount)throws IOException{
                java.util.Objects.checkFromIndexSize(from,amount,bytes.length);if(amount==0)return 0;require(active);if(closed)throw new IOException("Multipart part reader is closed");if(read==length)return -1;
                int count=channel.read(ByteBuffer.wrap(bytes,from,(int)Math.min(Math.min(amount,32768),length-read)),offset+read);
                if(count<1)throw new IOException("Multipart source ended early");read+=count;progress.accept(read);return count;
            }
            @Override public void close(){closed=true;}
        });
        return HttpRequest.BodyPublishers.fromPublisher(stream,length);
    }
    private static void validateTicket(HttpMediaTransfer.Ticket ticket,Multipart.Upload plan,int number,long length)throws IOException{
        try{
            URI uri=ticket.url();String host=uri.getHost();boolean local=host.equals("localhost")||host.equals("127.0.0.1")||host.equals("[::1]")||host.equals("::1");
            if(!ticket.method().equals("PUT")||!uri.getScheme().equals("https")&&!(uri.getScheme().equals("http")&&local)||uri.getUserInfo()!=null||uri.getFragment()!=null||ticket.expiresAt()<=System.currentTimeMillis())throw new IllegalArgumentException();
            Map<String,String> query=new HashMap<>();if(uri.getRawQuery()==null)throw new IllegalArgumentException();for(String piece:uri.getRawQuery().split("&")){String[] pair=piece.split("=",2);String key=URLDecoder.decode(pair[0],StandardCharsets.UTF_8),value=pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"";if(query.put(key,value)!=null)throw new IllegalArgumentException();}
            if(!plan.id().equals(query.get("uploadId"))||!Integer.toString(number).equals(query.get("partNumber")))throw new IllegalArgumentException();
            for(var header:ticket.headers().entrySet()){
                String name=header.getKey().toLowerCase(java.util.Locale.ROOT),value=header.getValue();if(!name.matches("[a-z0-9-]{1,80}")||value.length()>16384||value.chars().anyMatch(c->c<32||c>126)||Set.of("connection","transfer-encoding","upgrade","expect","proxy-authorization").contains(name))throw new IllegalArgumentException();
                if(name.equals("content-length")&&!Long.toString(length).equals(value))throw new IllegalArgumentException();
            }
        }catch(RuntimeException invalid){throw new IOException("Multipart upload ticket is invalid or expired");}
    }
    private static void require(BooleanSupplier active)throws IOException{if(!active.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("Multipart upload was cancelled");}
}
