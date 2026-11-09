package net.muxigame.console.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.domain.*;
import net.muxigame.console.domain.storage.ObjectStorage;
import net.minecraft.server.level.ServerPlayer;

/** Optional direct object transfer. All authority and inventory mutations stay on the Minecraft thread. */
public final class CloudMediaServer implements AutoCloseable {
    private final ObjectStorage storage;
    private final CloudUploads uploads;
    private final ThreadPoolExecutor worker;
    private record Job(UUID nonce,UUID transaction,UUID writer,java.util.concurrent.atomic.AtomicBoolean cancelled){}
    private final Map<UUID,Job> jobs=new HashMap<>();
    private final Map<UUID,ObjectStorage.Multipart.Upload> multipart=new HashMap<>();
    private final Map<UUID,TreeMap<Integer,ObjectStorage.Multipart.Part>> receipts=new HashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean expirationQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;
    private record Begun(UploadManager.Status status,ObjectStorage.Ticket ticket,ObjectStorage.Multipart.Upload multipart){}
    @FunctionalInterface private interface Completion<T>{void accept(T result)throws Exception;}

    public CloudMediaServer(ContentStore content,ObjectStorage storage){
        this.storage=Objects.requireNonNull(storage);uploads=new CloudUploads(content,storage);
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(64),task->{Thread t=new Thread(task,"playstation-object-storage");t.setDaemon(false);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    private static long now(){return System.currentTimeMillis();}
    private static ConsoleServer.WriterLease writer(ServerPlayer player,ConsoleServer.WorldState world,JsonObject body){return ConsoleServer.writer(player,world,body);}
    private static void sameWriter(ServerPlayer player,ConsoleServer.WorldState world,JsonObject body,ConsoleServer.WriterLease original){
        if(!writer(player,world,body).token().equals(original.token()))throw new DomainException("writer","请在原刻录机窗口完成操作");
    }
    private static ConsoleServer.UploadLease burn(ServerPlayer player,ConsoleServer.WorldState world,JsonObject body){
        var lease=ConsoleServer.requireUpload(player,world,body);
        sameWriter(player,world,body,lease.writer());return lease;
    }
    public boolean receive(ServerPlayer player,ConsoleServer.WorldState world,Wire.Message message)throws IOException{
        JsonObject body=message.body();UUID actor=player.getUUID();
        switch(message.operation()){
            case "upload_begin" -> {
                UUID transaction=UUID.fromString(body.get("transaction").getAsString());
                var lease=writer(player,world,body);if(!ConsoleServer.hasBlank(player))throw new DomainException("blank","请将空白卡带放入刻录槽");
                var request=new UploadManager.Request(body.get("filename").getAsString(),Platform.fromId(body.get("platform").getAsString()),body.get("size").getAsLong(),body.get("hash").getAsString(),body.has("rights")&&body.get("rights").getAsBoolean());
                String title=body.get("title").getAsString().strip();String boundedTitle=title.substring(0,Math.min(80,title.length()));
                run(player,message,()->{var status=uploads.begin(actor,request,now());try{var plan=uploads.multipartPlan(actor,status.id(),now()).orElse(null);return new Begun(status,status.offset()<status.total()&&plan==null?uploads.uploadTicket(actor,status.id(),now()):null,plan);}catch(Exception failure){uploads.abort(actor,status.id());throw failure;}},begun->{
                    sameWriter(player,world,body,lease);if(!ConsoleServer.hasBlank(player))throw new DomainException("blank","空白卡带已离开刻录槽");
                    world.burns.put(actor,new ConsoleServer.UploadLease(begun.status.id(),lease,boundedTitle,transaction));if(begun.multipart!=null){multipart.put(begun.status.id(),begun.multipart);receipts.put(begun.status.id(),new TreeMap<>());}ack(player,begun,transaction);
                },begun->abort(actor,begun.status.id()));return true;
            }
            case "upload_finish" -> {
                var lease=burn(player,world,body);if(!ConsoleServer.hasBlank(player))throw new DomainException("blank","空白卡带已离开刻录槽");
                run(player,message,()->uploads.finish(actor,lease.upload(),now()),rom->{
                    if(world.burns.get(actor)!=lease)throw new DomainException("upload","上传会话已改变");
                    sameWriter(player,world,body,lease.writer());if(!(player.containerMenu instanceof WriterMenu menu)||!menu.hasBlank())throw new DomainException("blank","空白卡带已离开刻录槽");
                    Cartridge card=world.content.issue(rom,lease.title());menu.burn(CardData.create(card));world.burns.remove(actor,lease);clearMultipart(lease.upload());
                    JsonObject result=new JsonObject();result.addProperty("transaction",lease.transaction().toString());result.addProperty("instance",card.instanceId().toString());result.addProperty("platform",card.platform().id());result.addProperty("hash",card.sha256());result.addProperty("size",card.size());result.addProperty("title",card.title());Wire.send(player,"burned",result);
                },ignored->{});return true;
            }
            case "upload_ticket" -> {
                var lease=burn(player,world,body);run(player,message,()->uploads.uploadTicket(actor,lease.upload(),now()),ticket->{
                    if(world.burns.get(actor)!=lease)throw new DomainException("upload","上传会话已改变");sameWriter(player,world,body,lease.writer());
                    JsonObject reply=new JsonObject();reply.addProperty("transaction",lease.transaction().toString());reply.addProperty("upload",lease.upload().toString());reply.add("uploadTicket",ticket(ticket));Wire.send(player,"upload_ticket",reply);
                },ignored->{});return true;
            }
            case "upload_part_ticket" -> {
                var lease=burn(player,world,body);int number=body.get("part").getAsInt();plan(lease.upload()).partBytes(number);
                run(player,message,()->uploads.partTicket(actor,lease.upload(),number,now()),ticket->{current(player,world,body,lease);JsonObject reply=identity(lease);reply.addProperty("part",number);reply.add("uploadTicket",ticket(ticket));Wire.send(player,"upload_part_ticket",reply);},ignored->{});return true;
            }
            case "upload_part_receipt" -> {
                var lease=burn(player,world,body);var plan=plan(lease.upload());int number=body.get("number").getAsInt();long size=body.get("size").getAsLong();if(size!=plan.partBytes(number))throw new DomainException("multipart","分片长度与上传计划不同");var part=new ObjectStorage.Multipart.Part(number,size,body.get("etag").getAsString());
                var saved=receipts.computeIfAbsent(lease.upload(),ignored->new TreeMap<>());var old=saved.putIfAbsent(number,part);if(old!=null&&!old.equals(part))throw new DomainException("multipart","同一分片的回执发生冲突");JsonObject reply=identity(lease);reply.addProperty("number",number);Wire.send(player,"upload_part_received",reply);return true;
            }
            case "upload_parts" -> {
                var lease=burn(player,world,body);var plan=plan(lease.upload());int from=body.has("from")?body.get("from").getAsInt():1,max=body.has("max")?body.get("max").getAsInt():32;if(from<1||from>plan.partCount()||max<1||max>32)throw new DomainException("multipart","分片列表范围无效");
                run(player,message,()->uploads.parts(actor,lease.upload(),now()),parts->{current(player,world,body,lease);JsonObject reply=identity(lease);JsonArray list=new JsonArray();int next=0;for(var part:parts)if(part.number()>=from){if(list.size()==max){next=part.number();break;}list.add(part(part));}reply.add("parts",list);reply.addProperty("next",next);Wire.send(player,"upload_parts",reply);},ignored->{});return true;
            }
            case "upload_complete" -> {
                var lease=burn(player,world,body);var plan=plan(lease.upload());var saved=receipts.get(lease.upload());if(saved==null||saved.size()!=plan.partCount())throw new DomainException("incomplete","尚未收到全部分片回执");List<ObjectStorage.Multipart.Part> confirmed=List.copyOf(saved.values());
                run(player,message,()->uploads.complete(actor,lease.upload(),confirmed,now()),status->{current(player,world,body,lease);ack(player,new Begun(status,null,null),lease.transaction());},ignored->{});return true;
            }
            case "upload_touch" -> {
                var lease=burn(player,world,body);if(!jobs.containsKey(actor))background(()->{try{uploads.touch(actor,lease.upload(),now());}catch(Exception expired){/* Finish/refresh reports an expired upload with its correlated request. */}});return true;
            }
            case "upload_abort" -> {Job job=jobs.get(actor);if(matches(job,body)){job.cancelled.set(true);jobs.remove(actor,job);background(()->abortActor(actor));}var lease=world.burns.get(actor);if(ConsoleServer.sameUpload(lease,body)&&world.burns.remove(actor,lease))abort(actor,lease.upload());return true;}
            case "upload_chunk" -> throw new DomainException("transport","此上传使用云端直传，请重新打开刻录机");
            case "rom_ticket" -> {
                String hash=body.get("hash").getAsString();if(!hash.matches("[0-9a-f]{64}")||!ConsoleServer.mayDownload(player,hash,body))throw new DomainException("permission","需要持有相应卡带或当前控制席权限");
                if(!world.content.isRemote(hash))return false;
                JsonObject reply=new JsonObject();reply.addProperty("request",body.get("request").getAsString());reply.addProperty("hash",hash);reply.addProperty("size",world.content.size(hash));reply.add("downloadTicket",ticket(storage.download(hash)));Wire.send(player,"rom_ticket",reply);return true;
            }
            case "rom_read" -> {
                String hash=body.get("hash").getAsString();if(world.content.isRemote(hash))throw new DomainException("transport","此卡带使用云端直传，请重新加载卡带");return false;
            }
            default -> {return false;}
        }
    }
    private static void ack(ServerPlayer player,Begun begun,UUID transaction){
        JsonObject reply=new JsonObject();reply.addProperty("transaction",transaction.toString());reply.addProperty("upload",begun.status.id().toString());reply.addProperty("offset",begun.status.offset());reply.addProperty("total",begun.status.total());if(begun.ticket!=null)reply.add("uploadTicket",ticket(begun.ticket));if(begun.multipart!=null)reply.add("multipart",planJson(begun.multipart));Wire.send(player,"upload_ack",reply);
    }
    private static JsonObject identity(ConsoleServer.UploadLease lease){JsonObject result=new JsonObject();result.addProperty("transaction",lease.transaction().toString());result.addProperty("upload",lease.upload().toString());return result;}
    private static void current(ServerPlayer p,ConsoleServer.WorldState world,JsonObject body,ConsoleServer.UploadLease lease){if(world.burns.get(p.getUUID())!=lease)throw new DomainException("stale_upload","上传事务已改变");sameWriter(p,world,body,lease.writer());}
    private ObjectStorage.Multipart.Upload plan(UUID upload){var plan=multipart.get(upload);if(plan==null)throw new DomainException("multipart","当前上传不是分片传输");return plan;}
    private static JsonObject planJson(ObjectStorage.Multipart.Upload plan){JsonObject value=new JsonObject();value.addProperty("id",plan.id());value.addProperty("hash",plan.hash());value.addProperty("size",plan.size());value.addProperty("platform",plan.platform());value.addProperty("partSize",plan.partSize());value.addProperty("partCount",plan.partCount());return value;}
    private static JsonObject part(ObjectStorage.Multipart.Part part){JsonObject value=new JsonObject();value.addProperty("number",part.number());value.addProperty("size",part.size());value.addProperty("etag",part.etag());return value;}
    private void clearMultipart(UUID id){multipart.remove(id);receipts.remove(id);}
    private static JsonObject ticket(ObjectStorage.Ticket ticket){
        JsonObject reply=new JsonObject();reply.addProperty("method",ticket.method());reply.addProperty("url",ticket.url().toASCIIString());reply.addProperty("expiresAt",ticket.expiresAtMillis());JsonObject headers=new JsonObject();
        ticket.headers().forEach((key,value)->{if(!Set.of("host","content-length","connection","expect","upgrade").contains(key.toLowerCase(Locale.ROOT)))headers.addProperty(key,value);});reply.add("headers",headers);return reply;
    }
    private <T> void run(ServerPlayer player,Wire.Message message,Callable<T> operation,Completion<T> completion,Consumer<T> discard){
        UUID actor=player.getUUID();if(closed||jobs.containsKey(actor))throw new DomainException("limit","云端正在处理上一项操作，请稍候");Job job=new Job(UUID.randomUUID(),UUID.fromString(message.body().get("transaction").getAsString()),UUID.fromString(message.body().get("token").getAsString()),new java.util.concurrent.atomic.AtomicBoolean());jobs.put(actor,job);
        try{worker.execute(()->{
            if(closed||job.cancelled.get())return;T result=null;Exception failure=null;try{result=operation.call();}catch(Exception error){failure=error;}T value=result;Exception error=failure;
            player.server.execute(()->{
                boolean current=!closed&&job.equals(jobs.get(actor));if(current)jobs.remove(actor,job);
                if(!current||player.server.getPlayerList().getPlayer(actor)!=player){if(value!=null)discard.accept(value);return;}
                if(error!=null){ConsoleServer.failed(player,message,error);return;}
                try{completion.accept(value);}catch(Exception invalid){discard.accept(value);ConsoleServer.failed(player,message,invalid);}
            });
        });}catch(RejectedExecutionException busy){jobs.remove(actor,job);throw new DomainException("limit","云端操作较多，请稍后重试");}
    }
    private void background(Runnable task){if(!closed)try{worker.execute(task);}catch(RejectedExecutionException busy){MuxiConsole.LOG.warn("Console cloud operation queue is full");}}
    public void abort(UUID actor,UUID id){clearMultipart(id);background(()->{try{uploads.abort(actor,id);}catch(DomainException ended){if(!Set.of("upload","expired").contains(ended.code()))MuxiConsole.LOG.warn("Console cloud upload abort failed",ended);}catch(IOException failed){MuxiConsole.LOG.warn("Console cloud multipart cleanup requires retry",failed);}});}
    private static boolean matches(Job job,JsonObject body){try{return job!=null&&job.transaction.toString().equals(body.get("transaction").getAsString())&&job.writer.toString().equals(body.get("token").getAsString());}catch(RuntimeException invalid){return false;}}
    private void abortActor(UUID actor){try{uploads.abortActor(actor);}catch(IOException failure){MuxiConsole.LOG.warn("Console cloud canceled writer cleanup failed",failure);}}
    public void writerClosed(UUID actor,UUID token){Job job=jobs.get(actor);if(job!=null&&job.writer.equals(token)&&jobs.remove(actor,job)){job.cancelled.set(true);background(()->abortActor(actor));}}
    public void logout(UUID actor){Job job=jobs.remove(actor);if(job!=null){job.cancelled.set(true);background(()->abortActor(actor));}}
    public void expire(){if(closed||!expirationQueued.compareAndSet(false,true))return;try{worker.execute(()->{try{uploads.expire(now());}catch(IOException failure){MuxiConsole.LOG.warn("Console cloud upload expiration failed",failure);}finally{expirationQueued.set(false);}});}catch(RejectedExecutionException busy){expirationQueued.set(false);}}
    @Override public void close(){
        if(closed)return;closed=true;jobs.values().forEach(job->job.cancelled.set(true));jobs.clear();multipart.clear();receipts.clear();
        worker.getQueue().clear();worker.execute(()->{try{uploads.close();}catch(IOException failure){MuxiConsole.LOG.warn("Console cloud multipart cleanup failed; incomplete-upload lifecycle cleanup may still be required",failure);}});worker.shutdown();
        try{
            if(!worker.awaitTermination(20,TimeUnit.SECONDS)){
                // A provider outage or a long CompleteMultipart request must not hold server shutdown.
                // Java's control HTTP calls cancel their request and retain the interrupt on this path.
                worker.shutdownNow();
                if(!worker.awaitTermination(2,TimeUnit.SECONDS))MuxiConsole.LOG.warn("Console cloud operation did not stop after interruption");
                MuxiConsole.LOG.warn("Console cloud cleanup exceeded shutdown grace; incomplete-upload lifecycle cleanup may be required");
            }
        }catch(InterruptedException interrupted){worker.shutdownNow();Thread.currentThread().interrupt();}
    }
}
