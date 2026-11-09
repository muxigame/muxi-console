package net.muxigame.console.mc;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.domain.*;
import net.minecraft.server.level.ServerPlayer;

/** Large local ROM inspection/publication never runs while holding the Minecraft tick thread. */
public final class LocalMediaServer implements AutoCloseable {
    private record Job(UUID transaction,UUID writer,UUID nonce){}
    @FunctionalInterface private interface Completion<T>{void accept(T value)throws Exception;}
    private final UploadManager uploads;
    private final ThreadPoolExecutor worker;
    private final Map<UUID,Job> jobs=new HashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean expirationQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;
    public LocalMediaServer(UploadManager uploads){
        this.uploads=uploads;worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(64),task->{Thread thread=new Thread(()->{try{task.run();}finally{try{uploads.close();}catch(IOException failure){MuxiConsole.LOG.warn("Console local upload close failed",failure);}}},"playstation-local-media");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    }
    private static long now(){return System.currentTimeMillis();}
    private static void writer(ServerPlayer p,ConsoleServer.WorldState world,JsonObject body,ConsoleServer.WriterLease lease){if(!ConsoleServer.writer(p,world,body).token().equals(lease.token()))throw new DomainException("writer","请在原刻录机窗口完成操作");}
    public boolean receive(ServerPlayer p,ConsoleServer.WorldState world,Wire.Message message){
        JsonObject body=message.body();UUID actor=p.getUUID();
        switch(message.operation()){
            case "upload_begin" -> {
                UUID transaction=UUID.fromString(body.get("transaction").getAsString());var lease=ConsoleServer.writer(p,world,body);if(!ConsoleServer.hasBlank(p))throw new DomainException("blank","请将空白卡带放入刻录槽");
                var request=new UploadManager.Request(body.get("filename").getAsString(),Platform.fromId(body.get("platform").getAsString()),body.get("size").getAsLong(),body.get("hash").getAsString(),body.has("rights")&&body.get("rights").getAsBoolean());String title=body.get("title").getAsString().strip();String boundedTitle=title.substring(0,Math.min(80,title.length()));
                run(p,message,()->uploads.begin(actor,request,now()),status->{writer(p,world,body,lease);if(!ConsoleServer.hasBlank(p))throw new DomainException("blank","空白卡带已离开刻录槽");world.burns.put(actor,new ConsoleServer.UploadLease(status.id(),lease,boundedTitle,transaction));ack(p,status,transaction);},status->abort(actor,status.id()));return true;
            }
            case "upload_chunk" -> {
                var lease=ConsoleServer.requireUpload(p,world,body);writer(p,world,body,lease.writer());
                long offset=body.get("offset").getAsLong();String hash=body.get("chunkHash").getAsString();byte[] bytes=message.data();
                run(p,message,()->uploads.append(actor,lease.upload(),offset,bytes,hash,now()),status->{if(world.burns.get(actor)!=lease)throw new DomainException("stale_upload","上传事务已改变");writer(p,world,body,lease.writer());ack(p,status,lease.transaction());},ignored->{});return true;
            }
            case "upload_finish" -> {
                var lease=ConsoleServer.requireUpload(p,world,body);writer(p,world,body,lease.writer());if(!ConsoleServer.hasBlank(p))throw new DomainException("blank","空白卡带已离开刻录槽");
                run(p,message,()->uploads.finish(actor,lease.upload(),now()),rom->{if(world.burns.get(actor)!=lease)throw new DomainException("stale_upload","上传事务已改变");writer(p,world,body,lease.writer());if(!(p.containerMenu instanceof WriterMenu menu)||!menu.hasBlank())throw new DomainException("blank","空白卡带已离开刻录槽");Cartridge card=world.content.issue(rom,lease.title());menu.burn(CardData.create(card));world.burns.remove(actor,lease);JsonObject result=new JsonObject();result.addProperty("transaction",lease.transaction().toString());result.addProperty("instance",card.instanceId().toString());result.addProperty("platform",card.platform().id());result.addProperty("hash",card.sha256());result.addProperty("size",card.size());result.addProperty("title",card.title());Wire.send(p,"burned",result);},ignored->{});return true;
            }
            case "upload_abort" -> {Job job=jobs.get(actor);if(matches(job,body)&&jobs.remove(actor,job))background(()->abortActor(actor));var lease=world.burns.get(actor);if(ConsoleServer.sameUpload(lease,body)&&world.burns.remove(actor,lease))abort(actor,lease.upload());return true;}
            default -> {return false;}
        }
    }
    private static void ack(ServerPlayer player,UploadManager.Status status,UUID transaction){JsonObject result=new JsonObject();result.addProperty("transaction",transaction.toString());result.addProperty("upload",status.id().toString());result.addProperty("offset",status.offset());result.addProperty("total",status.total());Wire.send(player,"upload_ack",result);}
    private <T> void run(ServerPlayer player,Wire.Message message,Callable<T> operation,Completion<T> complete,Consumer<T> discard){
        UUID actor=player.getUUID();if(closed||jobs.containsKey(actor))throw new DomainException("limit","正在处理上一项刻录操作，请稍候");Job job=new Job(UUID.fromString(message.body().get("transaction").getAsString()),UUID.fromString(message.body().get("token").getAsString()),UUID.randomUUID());jobs.put(actor,job);
        try{worker.execute(()->{T result=null;Exception failed=null;try{result=operation.call();}catch(Exception failure){failed=failure;}T value=result;Exception failure=failed;
            player.server.execute(()->{boolean current=!closed&&job.equals(jobs.get(actor));if(current)jobs.remove(actor,job);if(!current||player.server.getPlayerList().getPlayer(actor)!=player){if(value!=null)discard.accept(value);return;}if(failure!=null){ConsoleServer.failed(player,message,failure);return;}try{complete.accept(value);}catch(Exception invalid){discard.accept(value);ConsoleServer.failed(player,message,invalid);}});
        });}catch(RejectedExecutionException busy){jobs.remove(actor,job);throw new DomainException("limit","刻录操作较多，请稍后重试");}
    }
    private static boolean matches(Job job,JsonObject body){try{return job!=null&&job.transaction.toString().equals(body.get("transaction").getAsString())&&job.writer.toString().equals(body.get("token").getAsString());}catch(RuntimeException malformed){return false;}}
    private void background(Runnable operation){if(!closed)try{worker.execute(operation);}catch(RejectedExecutionException busy){MuxiConsole.LOG.warn("Console local media queue is full");}}
    private void abortActor(UUID actor){try{uploads.abortActor(actor);}catch(IOException failure){MuxiConsole.LOG.warn("Console local writer cancellation failed",failure);}}
    public void abort(UUID actor,UUID id){background(()->{try{uploads.abort(actor,id);}catch(Exception complete){/* Exact old upload may already have committed/expired. */}});}
    public void writerClosed(UUID actor,UUID token){Job job=jobs.get(actor);if(job!=null&&job.writer.equals(token)&&jobs.remove(actor,job))background(()->abortActor(actor));}
    public void logout(UUID actor){if(jobs.remove(actor)!=null)background(()->abortActor(actor));}
    public void expire(){if(closed||!expirationQueued.compareAndSet(false,true))return;try{worker.execute(()->{try{uploads.expire(now());}catch(IOException failure){MuxiConsole.LOG.warn("Console local upload expiration failed",failure);}finally{expirationQueued.set(false);}});}catch(RejectedExecutionException busy){expirationQueued.set(false);}}
    @Override public void close(){closed=true;jobs.clear();worker.shutdownNow();try{if(worker.awaitTermination(2,TimeUnit.SECONDS))uploads.close();else MuxiConsole.LOG.warn("Console local media is still finishing its interrupted operation");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(IOException failure){MuxiConsole.LOG.warn("Console local media close failed",failure);}}
}
