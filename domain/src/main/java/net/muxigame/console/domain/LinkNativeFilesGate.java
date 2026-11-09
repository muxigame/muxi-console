package net.muxigame.console.domain;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A later session cannot erase a failed or unfinished native filesystem owner. No process claims are made here. */
public final class LinkNativeFilesGate {
    private volatile CompletableFuture<Void> latest=CompletableFuture.completedFuture(null);
    public final class Closing {
        private final CompletableFuture<Void> previous,result;private final AtomicBoolean finished=new AtomicBoolean();
        private Closing(CompletableFuture<Void> previous,CompletableFuture<Void> result){this.previous=previous;this.result=result;}
        /** Caller supplies success only after all owned native processes and spool discards have really closed. */
        public void finish(Throwable failure){
            if(!finished.compareAndSet(false,true))throw new IllegalStateException("Native closure was already reported");
            if(failure!=null){result.completeExceptionally(failure);return;}
            previous.whenComplete((ignored,oldFailure)->{if(oldFailure==null)result.complete(null);else result.completeExceptionally(oldFailure);});
        }
    }
    /** Publish before the client clears its active owner, so an ordinary launch sees this exact generation. */
    public synchronized Closing begin(){CompletableFuture<Void> previous=latest,next=new CompletableFuture<>();latest=next;return new Closing(previous,next);}
    public CompletionStage<Void> snapshot(){return latest.minimalCompletionStage();}
    public static void await(CompletionStage<Void> snapshot,int timeoutMillis)throws IOException{
        if(timeoutMillis<1||timeoutMillis>20000)throw new IllegalArgumentException("Native closure wait exceeds its bound");
        try{snapshot.toCompletableFuture().get(timeoutMillis,TimeUnit.MILLISECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("等待上次连线关闭时中断",interrupted);}
        catch(ExecutionException failed){Throwable cause=failed.getCause();while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();throw new IOException("上次连线的原生文件尚未完整关闭",cause);}
        catch(TimeoutException timeout){throw new IOException("上次连线仍在关闭原生文件",timeout);}
    }
}
