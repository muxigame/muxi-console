package net.muxigame.console.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.muxigame.console.domain.Hashing;
import net.muxigame.console.domain.Platform;
import net.muxigame.console.domain.storage.ObjectStorage.Multipart;

/** Real preparation/cache/body bytes; explicit test transport substitutes only the server control replies. */
public final class ClientUploadCoordinatorTest {
    private static int checks;
    private static final UUID ID=new UUID(0,50);
    private record Command(String operation,JsonObject body,byte[] bytes) { }
    public static void main(String[] args)throws Exception{
        Path output=Path.of(args.length==0?"build/upload-coordinator-tests":args[0]);Files.createDirectories(output);Path root=Files.createTempDirectory(output,"coordinator-");
        try{local(root);lifecycle(root);cloud(root);System.out.println("Client upload coordinator: "+checks+" checks passed; local/chunk/direct PUT/multipart/lease/generation, no Minecraft or cloud");}
        finally{try(var files=Files.walk(root)){for(Path p:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    private static Path file(Path root,String name,long size)throws Exception{Path path=root.resolve(name);byte[] block=new byte[32768];for(int i=0;i<block.length;i++)block[i]=(byte)(i*17+3);try(var out=Files.newOutputStream(path)){for(long at=0;at<size;at+=block.length)out.write(block,0,(int)Math.min(block.length,size-at));}return path;}
    private static JsonObject writer(int token){JsonObject j=new JsonObject();j.addProperty("token",new UUID(0,token).toString());j.addProperty("position",123456L);return j;}
    private static JsonObject reply(Command command){JsonObject j=new JsonObject();j.add("transaction",command.body.get("transaction"));j.addProperty("upload",ID.toString());return j;}
    private static JsonObject ack(Command begin,long offset){JsonObject j=reply(begin);j.addProperty("offset",offset);j.add("total",begin.body.get("size"));return j;}
    private static void local(Path root)throws Exception{
        Path rom=file(root,"original.sfc",32768);
        try(var h=new Harness(root.resolve("local-cache"))){
            h.call(()->h.u.upload(writer(1),rom,Platform.SNES,false));check(!h.u.busy()&&h.commands.isEmpty(),"rights required before preparation or server side effect");
            h.call(()->h.u.upload(writer(1),rom,Platform.SNES,true));Command begin=h.next("upload_begin");
            check(begin.body.get("rights").getAsBoolean()&&begin.body.get("hash").getAsString().equals(Hashing.sha256(rom)),"normal inspector hash and confirmed rights preserved");
            try(var cached=Files.list(root.resolve("local-cache/roms"))){check(cached.filter(p->p.getFileName().toString().startsWith(begin.body.get("hash").getAsString())).anyMatch(p->{try{return Files.size(p)==32768;}catch(Exception missing){return false;}}),"author's content is already verified in cache before upload_begin");}
            JsonObject stale=reply(begin);stale.addProperty("transaction",UUID.randomUUID().toString());stale.addProperty("operation","upload_begin");stale.addProperty("message","Old error");h.receive("error",stale);check(h.u.busy()&&h.errors.isEmpty(),"stale transaction error cannot cancel current upload");
            h.receive("upload_ack",ack(begin,0));Command first=h.next("upload_chunk");eq(24576,first.bytes.length,"local transport retains existing bounded chunks");eq(Hashing.sha256(first.bytes),first.body.get("chunkHash").getAsString(),"actual local chunk SHA");
            h.receive("upload_ack",ack(begin,0));check(h.commands.poll(150,TimeUnit.MILLISECONDS)==null,"duplicate zero-offset ACK cannot enqueue the same local chunk twice");
            h.receive("upload_ack",ack(begin,24576));Command last=h.next("upload_chunk");eq(8192,last.bytes.length,"final local chunk exact remaining bytes");
            h.receive("upload_ack",ack(begin,32768));Command finish=h.next("upload_finish");eq(begin.body.get("transaction"),finish.body.get("transaction"),"finish retains exact writer transaction");
            JsonObject burned=reply(begin);burned.addProperty("title","Original");h.receive("burned",burned);check(!h.u.busy()&&h.errors.isEmpty(),"only correlated burned ends local upload successfully");
        }
    }
    private static void lifecycle(Path root)throws Exception{
        Path rom=root.resolve("original.sfc");
        try(var h=new Harness(root.resolve("lifecycle-cache"))){
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);h.io.submit(()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});check(entered.await(5,TimeUnit.SECONDS),"preparation queue held at an explicit test barrier");
            h.call(()->h.u.upload(writer(1),rom,Platform.SNES,true));h.call(()->h.u.cancelWriter(writer(1)));h.call(()->h.u.upload(writer(2),rom,Platform.SNES,true));release.countDown();Command begin=h.next("upload_begin");eq(writer(2).get("token"),begin.body.get("token"),"cancelled old preparation cannot upload after a new writer opens");
            h.call(()->h.u.cancelWriter(writer(1)));check(h.u.busy()&&h.commands.isEmpty(),"closing old menu cannot cancel newer writer");
            h.receive("upload_ack",ack(begin,0));h.next("upload_chunk");h.call(()->h.u.cancelWriter(writer(2)));Command abort=h.next("upload_abort");eq(ID.toString(),abort.body.get("upload").getAsString(),"cancel aborts exact assigned server upload");check(!h.u.busy(),"menu cancellation releases client ownership");
            h.call(()->h.u.upload(writer(3),rom,Platform.SNES,true));Command next=h.next("upload_begin");h.socket.set(new Object());h.call(h.u::tick);check(!h.u.busy()&&h.commands.isEmpty(),"connection replacement cancels locally and never sends old lease to new connection");
            h.receive("upload_ack",ack(next,32768));check(h.commands.isEmpty(),"old connection ACK cannot burn on replacement connection");
            h.call(()->h.u.upload(writer(4),rom,Platform.SNES,true));Command current=h.next("upload_begin");JsonObject error=reply(next);error.addProperty("operation","upload_finish");error.addProperty("message","Stale failure");h.receive("error",error);check(h.u.busy()&&h.errors.isEmpty(),"old connection transaction failure cannot kill newer transaction");
            h.call(h.u::disconnected);h.receive("upload_ack",ack(current,0));check(!h.u.busy()&&h.commands.isEmpty(),"disconnect consumes stale replies without replaying inventory/upload");
        }
    }
    private static void cloud(Path root)throws Exception{
        var bytes=new AtomicLong();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var receipts=new java.util.concurrent.ConcurrentHashMap<Integer,String>();
        server.createContext("/",exchange->{try{long count=0;byte[] buffer=new byte[32768];try(var input=exchange.getRequestBody()){for(int n;(n=input.read(buffer))!=-1;)count+=n;}bytes.addAndGet(count);String query=exchange.getRequestURI().getRawQuery();int number=query!=null&&query.contains("partNumber=2")?2:1;String etag="\"part-"+number+"\"";receipts.put(number,etag);if(!exchange.getRequestURI().getPath().equals("/lose-etag"))exchange.getResponseHeaders().add("ETag",etag);exchange.sendResponseHeaders(200,-1);}finally{exchange.close();}});server.start();URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        try{
            try(var h=new Harness(root.resolve("single-cache"))){
                h.call(()->h.u.upload(writer(1),root.resolve("original.sfc"),Platform.SNES,true));Command begin=h.next("upload_begin");JsonObject ack=ack(begin,0);ack.add("uploadTicket",ticket(base.resolve("/single"),false,0));h.receive("upload_ack",ack);h.next("upload_finish");eq(32768L,bytes.get(),"extracted coordinator still sends one direct complete verified-file PUT");JsonObject burned=reply(begin);burned.addProperty("title","Single");h.receive("burned",burned);
            }
            Path rom=file(root,"synthetic.iso",(long)Multipart.PART_BYTES+257);try(var channel=java.nio.channels.FileChannel.open(rom,java.nio.file.StandardOpenOption.WRITE)){channel.position(0x18);channel.write(java.nio.ByteBuffer.wrap(new byte[]{0x5d,0x1c,(byte)0x9e,(byte)0xa3}));}long size=Files.size(rom);String hash=Hashing.sha256(rom);var plan=new Multipart.Upload("provider-id",hash,size,"wii",Multipart.PART_BYTES);
            try(var h=new Harness(root.resolve("multipart-cache"))){
                h.call(()->h.u.upload(writer(1),rom,Platform.WII,true));Command begin=h.next("upload_begin");JsonObject ack=ack(begin,0);ack.add("multipart",plan(plan));h.receive("upload_ack",ack);
                Command first=h.next("upload_part_ticket");eq(1,first.body.get("part").getAsInt(),"multipart requests only current part ticket");JsonObject firstTicket=reply(begin);firstTicket.addProperty("part",1);firstTicket.add("uploadTicket",ticket(base.resolve("/part?uploadId=provider-id&partNumber=1"),true,Multipart.PART_BYTES));h.receive("upload_part_ticket",firstTicket);
                Command r1=h.next("upload_part_receipt");eq(1,r1.body.get("number").getAsInt(),"first actual ETag is sent in its own bounded receipt");eq((long)Multipart.PART_BYTES,r1.body.get("size").getAsLong(),"first receipt size is real body size");JsonObject a1=reply(begin);a1.addProperty("number",1);h.receive("upload_part_received",a1);
                Command second=h.next("upload_part_ticket");eq(2,second.body.get("part").getAsInt(),"next ticket waits first receipt acknowledgement");JsonObject secondTicket=reply(begin);secondTicket.addProperty("part",2);secondTicket.add("uploadTicket",ticket(base.resolve("/lose-etag?uploadId=provider-id&partNumber=2"),true,257));h.receive("upload_part_ticket",secondTicket);
                Command list=h.next("upload_parts");eq(2,list.body.get("from").getAsInt(),"lost ETag queries only bounded cloud receipt page from failed part");check(list.body.get("max").getAsInt()<=32,"receipt recovery cannot exceed unchanged wire metadata budget");
                JsonObject listed=reply(begin);JsonArray parts=new JsonArray();JsonObject part=new JsonObject();part.addProperty("number",2);part.addProperty("size",257);part.addProperty("etag",receipts.get(2));parts.add(part);listed.add("parts",parts);listed.addProperty("next",0);h.receive("upload_parts",listed);
                Command r2=h.next("upload_part_receipt");eq(receipts.get(2),r2.body.get("etag").getAsString(),"lost HTTP response is recovered from real provider part metadata");JsonObject a2=reply(begin);a2.addProperty("number",2);h.receive("upload_part_received",a2);
                Command complete=h.next("upload_complete");check(!complete.body.has("parts")&&complete.body.toString().length()<1024,"final complete is a small command, never 576 receipts/URLs");
                JsonObject merged=ack(begin,size);merged.add("multipart",plan(plan));h.receive("upload_ack",merged);h.next("upload_finish");check(h.commands.poll(150,TimeUnit.MILLISECONDS)==null,"merged ACK with repeated plan does not restart upload");
                JsonObject burned=reply(begin);burned.addProperty("title","Multipart");h.receive("burned",burned);check(!h.u.busy()&&h.errors.isEmpty(),"multipart finishes only after server merge confirmation and burn");eq(32768L+size,bytes.get(),"lost receipt recovery does not redundantly upload successful part");
            }
            try(var h=new Harness(root.resolve("cancel-multipart-cache"))){
                h.call(()->h.u.upload(writer(1),rom,Platform.WII,true));Command begin=h.next("upload_begin");JsonObject ack=ack(begin,0);ack.add("multipart",plan(plan));h.receive("upload_ack",ack);h.next("upload_part_ticket");long before=bytes.get();h.call(()->h.u.cancelWriter(writer(1)));h.next("upload_abort");h.io.submit(()->{}).get(5,TimeUnit.SECONDS);check(!h.u.busy()&&bytes.get()==before,"cancelling a pending part ticket closes verified file/future and sends no later body");
            }
        }finally{server.stop(0);}
    }
    private static JsonObject plan(Multipart.Upload p){JsonObject j=new JsonObject();j.addProperty("id",p.id());j.addProperty("hash",p.hash());j.addProperty("size",p.size());j.addProperty("platform",p.platform());j.addProperty("partSize",p.partSize());j.addProperty("partCount",p.partCount());return j;}
    private static JsonObject ticket(URI uri,boolean length,long size){JsonObject j=new JsonObject();j.addProperty("method","PUT");j.addProperty("url",uri.toString());j.addProperty("expiresAt",System.currentTimeMillis()+60000);JsonObject headers=new JsonObject();if(length)headers.addProperty("content-length",size);j.add("headers",headers);return j;}
    private static final class Harness implements AutoCloseable{
        final java.util.concurrent.ExecutorService io=Executors.newSingleThreadExecutor(),client=Executors.newSingleThreadExecutor();
        final AtomicReference<Object> socket=new AtomicReference<>(new Object());final BlockingQueue<Command> commands=new LinkedBlockingQueue<>();final List<String> errors=new java.util.concurrent.CopyOnWriteArrayList<>();final ClientUploadCoordinator u;
        Harness(Path root){u=new ClientUploadCoordinator(()->root,io,client,socket::get,(op,j,b)->{if(j.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16384)throw new AssertionError("Wire metadata overflow");commands.add(new Command(op,j.deepCopy(),b.clone()));},ignored->{},errors::add);}
        void call(Runnable action)throws Exception{client.submit(action).get(10,TimeUnit.SECONDS);}
        void receive(String operation,JsonObject body)throws Exception{call(()->check(u.receive(operation,body),"upload operation consumed by coordinator"));}
        Command next(String operation)throws Exception{Command value=commands.poll(10,TimeUnit.SECONDS);if(value==null||!value.operation.equals(operation))throw new AssertionError("Expected "+operation+", got "+(value==null?"timeout":value.operation));return value;}
        public void close()throws Exception{call(u::disconnected);io.shutdown();client.shutdown();if(!io.awaitTermination(10,TimeUnit.SECONDS))io.shutdownNow();if(!client.awaitTermination(10,TimeUnit.SECONDS))client.shutdownNow();}
    }
    private static void eq(Object expected,Object actual,String why){check(java.util.Objects.equals(expected,actual),why+" expected="+expected+" actual="+actual);}
    private static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
}
