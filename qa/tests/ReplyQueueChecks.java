package net.muxigame.consoleqa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic filesystem fault, not a timing-dependent claim that Windows never contends. */
public final class ReplyQueueChecks {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]).toAbsolutePath().normalize(),directory=root.resolve("channel");
        Files.createDirectories(directory);Path target=directory.resolve("reply-host.json");
        Files.createDirectory(target);Path blocker=target.resolve("ordinary-blocker");Files.writeString(blocker,"force replacement failure");
        AtomicInteger actions=new AtomicInteger();ReplyQueue queue=new ReplyQueue();
        byte[] first="{\"id\":1,\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        check(queue.execute(1,()->{actions.incrementAndGet();return first;}));
        try{queue.flush(directory,"host");throw new AssertionError("Expected the deliberate filesystem failure");}catch(java.io.IOException expected){}
        check(queue.busy());check(actions.get()==1);check(queue.state().pendingId()==1);check(queue.state().writtenId()==-1);check(queue.state().retryFailures()==1);
        check(!queue.execute(1,()->{actions.incrementAndGet();return first;}));
        check(!queue.execute(2,()->{actions.incrementAndGet();return first;}));check(actions.get()==1);
        Files.delete(blocker);Files.delete(target);queue.flush(directory,"host");
        check(!queue.busy());check(queue.state().writtenId()==1);check(queue.state().writeAttempts()==2);check(Files.readString(target).equals(new String(first,StandardCharsets.UTF_8)));
        check(!queue.execute(1,()->{actions.incrementAndGet();return first;}));
        byte[] second="{\"id\":2,\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        check(queue.execute(2,()->{actions.incrementAndGet();return second;}));queue.flush(directory,"host");
        check(actions.get()==2);check(queue.state().executions()==2);check(queue.state().writtenId()==2);check(Files.readString(target).equals(new String(second,StandardCharsets.UTF_8)));
        Files.delete(target);Files.delete(directory);System.out.println("ReplyQueue checks="+checks+"; replacement failure preserved one execution and retried the same reply");
    }
}
