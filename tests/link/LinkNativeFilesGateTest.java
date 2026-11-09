package net.muxigame.console.tests.link;

import java.io.IOException;
import java.util.concurrent.*;
import net.muxigame.console.domain.LinkNativeFilesGate;

/** Real completion stages model an ordinary launch racing two retired link filesystem owners. */
public final class LinkNativeFilesGateTest {
    private static int checks;
    private static void check(boolean good,String label){checks++;if(!good)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        var gate=new LinkNativeFilesGate();LinkNativeFilesGate.await(gate.snapshot(),1);check(gate.snapshot().toCompletableFuture().isDone(),"no prior link has no filesystem delay");
        var first=gate.begin();var observed=gate.snapshot();check(!observed.toCompletableFuture().isDone(),"closing is published while native owners are still live");
        var external=observed.toCompletableFuture();external.complete(null);check(!gate.snapshot().toCompletableFuture().isDone(),"a caller cannot forge success by completing its returned future copy");
        try{LinkNativeFilesGate.await(gate.snapshot(),1);throw new AssertionError("Live native owner admitted an ordinary restore");}catch(IOException expected){check(expected.getCause()instanceof TimeoutException,"wait is bounded while the real close is pending");}
        var second=gate.begin();second.finish(null);check(!gate.snapshot().toCompletableFuture().isDone(),"a later successful close cannot hide an unfinished earlier native owner");first.finish(null);LinkNativeFilesGate.await(gate.snapshot(),1);check(gate.snapshot().toCompletableFuture().isDone(),"both exact generations must close before ordinary restore proceeds");
        var failed=gate.begin();IOException original=new IOException("exact owned native process did not signal");failed.finish(original);var later=gate.begin();later.finish(null);
        try{LinkNativeFilesGate.await(gate.snapshot(),1);throw new AssertionError("A later session erased an unconfirmed close");}catch(IOException expected){check(expected.getCause()==original,"a prior native failure remains the actual cause across later sessions");}
        try{later.finish(null);throw new AssertionError("Duplicate completion changed ownership");}catch(IllegalStateException expected){checks++;}
        try{LinkNativeFilesGate.await(gate.snapshot(),20001);throw new AssertionError("Unbounded wait accepted");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("LinkNativeFilesGateTest "+checks+" checks passed");
    }
}
