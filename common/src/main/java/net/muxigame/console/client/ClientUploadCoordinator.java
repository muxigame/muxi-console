package net.muxigame.console.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.muxigame.console.client.transfer.HttpMediaTransfer;
import net.muxigame.console.client.transfer.MultipartMediaTransfer;
import net.muxigame.console.client.transfer.RomTransfer;
import net.muxigame.console.domain.ArcadeBundle;
import net.muxigame.console.domain.Hashing;
import net.muxigame.console.domain.Platform;
import net.muxigame.console.domain.RomDescriptor;
import net.muxigame.console.domain.RomInspector;
import net.muxigame.console.domain.storage.ObjectStorage.Multipart;
import net.muxigame.console.mc.UserErrors;

/** Writer-specific transfer ownership, isolated from emulation and Link presentation state. */
public final class ClientUploadCoordinator {
    @FunctionalInterface public interface CacheRoot { Path get() throws IOException; }
    @FunctionalInterface public interface Sender { void send(String operation,JsonObject body,byte[] data); }
    private final CacheRoot root;
    private final Executor io,client;
    private final Supplier<Object> connection;
    private final Sender sender;
    private final Consumer<String> status,error;
    private final Consumer<Exception> diagnostic;
    private volatile Preparation preparing;
    private volatile Upload upload;
    private long generation;
    private record Preparation(long generation,Object connection,JsonObject writer) { }
    private static final class Upload {
        final Object connection;
        final JsonObject writer;
        final Path file,temporary;
        final RomDescriptor descriptor;
        UUID id;
        long offset,lastTouch,sentOffset=-1;
        volatile long sent;
        volatile boolean cloud,finishSent,worker;
        volatile CompletableFuture<HttpMediaTransfer.Ticket> ticket;
        volatile int ticketPart;
        volatile CompletableFuture<List<Multipart.Part>> parts;
        volatile int partsFrom;
        volatile CompletableFuture<Integer> receiptAck;
        volatile int receiptNumber;
        volatile MultipartMediaTransfer.VerifiedFile verified;
        Multipart.Upload multipart;
        Upload(Preparation prepared,Path file,RomDescriptor descriptor,Path temporary){
            connection=prepared.connection;writer=prepared.writer.deepCopy();writer.addProperty("transaction",UUID.randomUUID().toString());
            this.file=file;this.descriptor=descriptor;this.temporary=temporary;
        }
    }
    public ClientUploadCoordinator(CacheRoot root,Executor io,Executor client,Supplier<Object> connection,Sender sender,Consumer<String> status,Consumer<String> error){
        this(root,io,client,connection,sender,status,error,ignored->{});
    }
    public ClientUploadCoordinator(CacheRoot root,Executor io,Executor client,Supplier<Object> connection,Sender sender,Consumer<String> status,Consumer<String> error,Consumer<Exception> diagnostic){
        this.root=Objects.requireNonNull(root);this.io=Objects.requireNonNull(io);this.client=Objects.requireNonNull(client);this.connection=Objects.requireNonNull(connection);this.sender=Objects.requireNonNull(sender);this.status=Objects.requireNonNull(status);this.error=Objects.requireNonNull(error);this.diagnostic=Objects.requireNonNull(diagnostic);
    }
    public boolean busy(){return preparing!=null||upload!=null;}
    public void selectionChanged(){if(!busy())status.accept("选择文件并确认授权后，即可上传刻录");}
    /** Call on the client thread. The original writer token, position and connection never change. */
    public void upload(JsonObject context,Path file,List<Path> dependencies,Platform selected,boolean rights){
        if(!rights){status.accept("请先确认拥有上传和使用所需授权");return;}
        if(busy()){status.accept("已有上传进行中");return;}
        Object socket=connection.get();if(socket==null){status.accept("请先连接服务器");return;}
        if(!context.has("token")||!context.has("position"))throw new IllegalArgumentException("Actual writer lease required");
        Preparation p=new Preparation(++generation,socket,context.deepCopy());preparing=p;status.accept("正在检查文件…");List<Path> chosen=List.copyOf(dependencies);
        io.execute(()->{Path temporary=null;try{
            require(p);Path content=file;Platform platform=selected;boolean zip=file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
            if(zip&&(selected==null||selected==Platform.ARCADE)){
                Path preparation=root.get().resolve("upload-preparation");Files.createDirectories(preparation);
                if(Files.isSymbolicLink(preparation)||!preparation.toRealPath().equals(preparation.toAbsolutePath().normalize()))throw new IOException("媒体准备目录不安全");
                temporary=Files.createTempDirectory(preparation,"mxarc-");String name=file.getFileName().toString().toLowerCase(Locale.ROOT);content=temporary.resolve(name.substring(0,name.length()-4)+".mxarc");ArcadeBundle.pack(file,chosen,content);platform=Platform.ARCADE;
            }else if(!chosen.isEmpty())throw new IOException("父集和BIOS依赖只能添加到街机driver.zip");
            require(p);RomDescriptor descriptor=new RomInspector().inspect(content,platform,()->live(p));require(p);
            Path cached=RomTransfer.cacheLocal(root.get().resolve("roms"),descriptor.sha256(),extension(descriptor),descriptor.size(),content,()->live(p));
            Upload u=new Upload(p,cached,descriptor,temporary);client.execute(()->{
                if(!live(p)){cleanup(u);return;}preparing=null;upload=u;
                JsonObject begin=context(u);begin.addProperty("filename",descriptor.filename());begin.addProperty("title",descriptor.filename().replaceFirst("\\.[^.]+$",""));begin.addProperty("platform",descriptor.platform().id());begin.addProperty("hash",descriptor.sha256());begin.addProperty("size",descriptor.size());begin.addProperty("rights",true);
                status.accept("上传中 0%");send(u,"upload_begin",begin);
            });
        }catch(Exception failure){deletePrepared(temporary);client.execute(()->{if(!live(p))return;preparing=null;diagnostic.accept(failure);status.accept(UserErrors.describe(failure));});}});
    }
    public void upload(JsonObject context,Path file,Platform selected,boolean rights){upload(context,file,List.of(),selected,rights);}
    public void cancelWriter(JsonObject writer){
        Preparation p=preparing;if(p!=null&&sameWriter(p.writer,writer)){generation++;preparing=null;}
        Upload u=upload;if(u!=null&&sameWriter(u.writer,writer)){if(live(u))send(u,"upload_abort",context(u));upload=null;cleanup(u);}
    }
    public void disconnected(){generation++;preparing=null;Upload u=upload;upload=null;cleanup(u);}
    /** Called once each client tick, independent of whether an emulator or Link session is active. */
    public void tick(){
        Preparation p=preparing;if(p!=null&&p.connection!=connection.get()){disconnected();return;}
        Upload u=upload;if(u==null)return;if(!live(u)){disconnected();return;}
        if(u.cloud&&!u.finishSent){status.accept("上传中 "+Math.min(100,u.sent*100/u.descriptor.size())+"%");long now=System.nanoTime();if(now-u.lastTouch>=TimeUnit.SECONDS.toNanos(5)){u.lastTouch=now;send(u,"upload_touch",context(u));}}
    }
    /** Stale upload replies are consumed here as well; they must not become errors in a newer session. */
    public boolean receive(String operation,JsonObject body){
        boolean uploadError=operation.equals("error")&&body.has("operation")&&body.get("operation").getAsString().startsWith("upload_");
        if(!operation.startsWith("upload_")&&!operation.equals("burned")&&!uploadError)return false;
        Upload u=upload;if(!reply(u,body)||!live(u))return true;
        try{
            if(uploadError){fail(u,body.get("message").getAsString(),false,null);return true;}
            switch(operation){
                case "burned"->{status.accept("卡带已刻录："+body.get("title").getAsString());upload=null;cleanup(u);}
                case "upload_ack"->{
                    UUID id=UUID.fromString(body.get("upload").getAsString());if(u.id!=null&&!u.id.equals(id))return true;u.id=id;
                    long offset=body.get("offset").getAsLong();if(offset<u.offset||offset>u.descriptor.size()||body.has("total")&&body.get("total").getAsLong()!=u.descriptor.size())throw new IOException("云端上传进度不符");u.offset=offset;
                    if(u.offset==u.descriptor.size())piece(u);
                    else if(body.has("multipart")){
                        JsonObject p=body.getAsJsonObject("multipart");Multipart.Upload plan=new Multipart.Upload(p.get("id").getAsString(),p.get("hash").getAsString(),p.get("size").getAsLong(),p.get("platform").getAsString(),p.get("partSize").getAsInt());
                        if(!plan.hash().equals(u.descriptor.sha256())||plan.size()!=u.descriptor.size()||!plan.platform().equals(u.descriptor.platform().id())||p.has("partCount")&&p.get("partCount").getAsInt()!=plan.partCount()||u.multipart!=null&&!u.multipart.equals(plan))throw new IOException("分段计划与已选文件不符");
                        u.multipart=plan;multipart(u);
                    }else if(body.has("uploadTicket"))single(u,ticket(body.getAsJsonObject("uploadTicket")));
                    else if(!u.cloud)piece(u);
                }
                case "upload_ticket","upload_part_ticket"->{
                    if(u.ticket!=null&&(operation.equals("upload_ticket")&&u.ticketPart==0||operation.equals("upload_part_ticket")&&body.get("part").getAsInt()==u.ticketPart))u.ticket.complete(ticket(body.getAsJsonObject("uploadTicket")));
                }
                case "upload_parts"->{
                    if(u.parts!=null){var result=parseParts(u,body.getAsJsonArray("parts"));if(result.isEmpty()||result.getFirst().number()>=u.partsFrom)u.parts.complete(result);}
                }
                case "upload_part_received"->{if(u.receiptAck!=null&&body.get("number").getAsInt()==u.receiptNumber)u.receiptAck.complete(u.receiptNumber);}
                default->{ }
            }
        }catch(Exception invalid){fail(u,"云端上传回执无效，请重新打开刻录机",true,invalid);}
        return true;
    }
    private void single(Upload u,HttpMediaTransfer.Ticket initial){
        if(!live(u)||u.worker)return;u.worker=true;u.cloud=true;
        io.execute(()->{try{HttpMediaTransfer.Ticket ticket=initial;for(int attempt=0;attempt<3;attempt++){
            require(u);u.sent=0;int result;
            try{result=HttpMediaTransfer.put(ticket,u.file,u.descriptor.size(),()->live(u),bytes->u.sent=bytes);}catch(Exception network){require(u);if(attempt==2)throw network;result=503;}
            if(result>=200&&result<300||result==409||result==412){client.execute(()->{if(live(u)){u.offset=u.descriptor.size();piece(u);}});return;}
            if(result!=401&&result!=403&&result!=408&&result!=429&&result<500||attempt==2)throw new IOException("Cloud upload failed");ticket=requestTicket(u,0);
        }}catch(Exception failure){client.execute(()->fail(u,"云端上传未完成，请重试 [cloud_upload]",true,failure));}finally{u.worker=false;}});
    }
    private void multipart(Upload u){
        if(!live(u)||u.worker)return;u.worker=true;u.cloud=true;
        io.execute(()->{try(var source=MultipartMediaTransfer.verify(u.file,u.descriptor.size(),u.descriptor.sha256(),()->live(u))){
            u.verified=source;require(u);long confirmed=0;
            for(int number=1;number<=u.multipart.partCount();number++){
                Multipart.Part receipt=null;final long base=confirmed;
                for(int attempt=0;attempt<3;attempt++){
                    require(u);try{receipt=MultipartMediaTransfer.putPart(source,u.multipart,number,requestTicket(u,number),()->live(u),bytes->u.sent=base+bytes);break;}
                    catch(Exception failure){require(u);if(attempt==2)throw failure;
                        // A PUT may have completed even if its HTTP receipt was lost. Provider ListParts is authoritative metadata.
                        for(Multipart.Part part:requestParts(u,number))if(part.number()==number){receipt=part;break;}if(receipt!=null)break;
                    }
                }
                if(receipt==null)throw new IOException("Multipart receipt missing");submitReceipt(u,receipt);confirmed+=receipt.size();u.sent=confirmed;
            }
            client.execute(()->{if(live(u)){status.accept("正在合并并确认卡带…");send(u,"upload_complete",context(u));}});
        }catch(Exception failure){client.execute(()->fail(u,"云端分段上传未完成，请重试 [multipart_upload]",true,failure));}
        finally{u.verified=null;u.worker=false;}});
    }
    private HttpMediaTransfer.Ticket requestTicket(Upload u,int part)throws Exception{
        require(u);var future=new CompletableFuture<HttpMediaTransfer.Ticket>();u.ticketPart=part;u.ticket=future;
        client.execute(()->{if(!live(u)){future.cancel(false);return;}JsonObject request=context(u);if(part>0)request.addProperty("part",part);send(u,part>0?"upload_part_ticket":"upload_ticket",request);});
        try{return future.get(30,TimeUnit.SECONDS);}finally{if(u.ticket==future)u.ticket=null;future.cancel(false);}
    }
    private List<Multipart.Part> requestParts(Upload u,int from)throws Exception{
        require(u);var future=new CompletableFuture<List<Multipart.Part>>();u.partsFrom=from;u.parts=future;
        client.execute(()->{if(!live(u)){future.cancel(false);return;}JsonObject request=context(u);request.addProperty("from",from);request.addProperty("max",32);send(u,"upload_parts",request);});
        try{return future.get(30,TimeUnit.SECONDS);}finally{if(u.parts==future)u.parts=null;future.cancel(false);}
    }
    private void submitReceipt(Upload u,Multipart.Part receipt)throws Exception{
        require(u);var future=new CompletableFuture<Integer>();u.receiptNumber=receipt.number();u.receiptAck=future;
        client.execute(()->{if(!live(u)){future.cancel(false);return;}JsonObject request=context(u);request.addProperty("number",receipt.number());request.addProperty("size",receipt.size());request.addProperty("etag",receipt.etag());send(u,"upload_part_receipt",request);});
        try{if(future.get(30,TimeUnit.SECONDS)!=receipt.number())throw new IOException("Multipart receipt acknowledgement is out of order");}
        finally{if(u.receiptAck==future)u.receiptAck=null;future.cancel(false);}
    }
    private static List<Multipart.Part> parseParts(Upload u,JsonArray rows)throws IOException{
        if(u.multipart==null||rows.size()>32)throw new IOException("Invalid multipart receipt page");var result=new ArrayList<Multipart.Part>();int previous=0;
        for(var value:rows){JsonObject p=value.getAsJsonObject();var part=new Multipart.Part(p.get("number").getAsInt(),p.get("size").getAsLong(),p.get("etag").getAsString());if(part.number()<=previous||part.number()>u.multipart.partCount()||part.size()!=u.multipart.partBytes(part.number()))throw new IOException("Invalid multipart receipt range");previous=part.number();result.add(part);}return result;
    }
    private void piece(Upload u){
        if(!live(u)||u.finishSent)return;if(u.offset==u.descriptor.size()){u.finishSent=true;status.accept("正在确认刻录…");send(u,"upload_finish",context(u));return;}
        long offset=u.offset;if(u.sentOffset==offset)return;u.sentOffset=offset;io.execute(()->{try(var in=Files.newByteChannel(u.file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            require(u);if(in.size()!=u.descriptor.size())throw new IOException("选择的文件已改变");in.position(offset);ByteBuffer buffer=ByteBuffer.allocate((int)Math.min(RomTransfer.CHUNK_BYTES,u.descriptor.size()-offset));while(buffer.hasRemaining())if(in.read(buffer)<0)throw new IOException("选择的文件提前结束");
            byte[] bytes=buffer.array();JsonObject request=context(u);request.addProperty("offset",offset);request.addProperty("chunkHash",Hashing.sha256(bytes));client.execute(()->{if(live(u)&&u.offset==offset){status.accept("上传中 "+offset*100/u.descriptor.size()+"%");sender.send("upload_chunk",request,bytes);}});
        }catch(Exception failure){client.execute(()->fail(u,UserErrors.describe(failure),true,failure));}});
    }
    private void fail(Upload u,String message,boolean abort,Exception failure){
        if(!live(u))return;if(abort)send(u,"upload_abort",context(u));upload=null;cleanup(u);if(failure!=null)diagnostic.accept(failure);status.accept(message);error.accept(message);
    }
    private void cleanup(Upload u){
        if(u==null)return;if(u.ticket!=null)u.ticket.cancel(false);if(u.parts!=null)u.parts.cancel(false);if(u.receiptAck!=null)u.receiptAck.cancel(false);
        var source=u.verified;if(source!=null)try{source.close();}catch(IOException ignored){}
        if(u.temporary!=null)io.execute(()->deletePrepared(u.temporary));
    }
    private void deletePrepared(Path directory){
        if(directory==null)return;try{Path allowed=root.get().resolve("upload-preparation").toAbsolutePath().normalize(),resolved=directory.toAbsolutePath().normalize();if(!allowed.equals(resolved.getParent())||!resolved.getFileName().toString().startsWith("mxarc-")||Files.isSymbolicLink(resolved))throw new IOException("Invalid media preparation cleanup");try(var entries=Files.list(resolved)){for(Path entry:entries.toList()){if(!Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(entry))throw new IOException("Unexpected media preparation entry");Files.delete(entry);}}Files.delete(resolved);}catch(IOException failure){diagnostic.accept(failure);}
    }
    private boolean live(Preparation p){return preparing==p&&generation==p.generation&&p.connection!=null&&connection.get()==p.connection;}
    private boolean live(Upload u){return u!=null&&upload==u&&u.connection!=null&&connection.get()==u.connection;}
    private void require(Preparation p)throws IOException{if(!live(p))throw new IOException("上传已取消");}
    private void require(Upload u)throws IOException{if(!live(u))throw new IOException("上传已取消");}
    private void send(Upload u,String operation,JsonObject body){if(live(u))sender.send(operation,body,new byte[0]);}
    private static boolean sameWriter(JsonObject a,JsonObject b){return a!=null&&b!=null&&a.has("token")&&b.has("token")&&a.get("token").equals(b.get("token"))&&a.has("position")&&b.has("position")&&a.get("position").equals(b.get("position"));}
    private static boolean reply(Upload u,JsonObject body){return u!=null&&body.has("transaction")&&u.writer.get("transaction").equals(body.get("transaction"))&&(!body.has("upload")||u.id==null||u.id.toString().equals(body.get("upload").getAsString()));}
    private static JsonObject context(Upload u){JsonObject request=u.writer.deepCopy();if(u.id!=null)request.addProperty("upload",u.id.toString());return request;}
    private static String extension(RomDescriptor descriptor){String name=descriptor.filename();return name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);}
    private static HttpMediaTransfer.Ticket ticket(JsonObject value){Map<String,String> headers=new LinkedHashMap<>();for(var header:value.getAsJsonObject("headers").entrySet())headers.put(header.getKey(),header.getValue().getAsString());return new HttpMediaTransfer.Ticket(value.get("method").getAsString(),URI.create(value.get("url").getAsString()),headers,value.get("expiresAt").getAsLong());}
}
