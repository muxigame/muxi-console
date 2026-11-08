package net.muxigame.consoleqa;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.function.Supplier;

/** QA-only at-most-once dispatch with a retained immutable reply until atomic replacement succeeds. */
final class ReplyQueue {
    private long executed=-1,written=-1,pendingId=-1,executions,attempts,retryFailures;
    private byte[] pending;
    boolean busy(){return pending!=null;}
    long lastExecuted(){return executed;}
    boolean execute(long id,Supplier<byte[]> action){
        if(busy()||id<=executed)return false;
        executed=id;pendingId=id;executions++;
        byte[] result=Objects.requireNonNull(action.get(),"A completed callback must have a reply");
        if(result.length==0||result.length>1024*1024)throw new IllegalArgumentException("QA reply outside the metadata budget");
        pending=result.clone();return true;
    }
    void flush(Path directory,String role)throws IOException{
        if(!busy())return;
        if(!role.matches("server|host|guest|client[0-9]+"))throw new IllegalArgumentException("Owned role only");
        attempts++;Path temporary=directory.resolve("reply-"+role+".tmp"),target=directory.resolve("reply-"+role+".json");
        try{
            Files.createDirectories(directory);Files.write(temporary,pending);
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            written=pendingId;pendingId=-1;pending=null;
        }catch(IOException failure){retryFailures++;throw failure;}
    }
    State state(){return new State(executed,written,pendingId,executions,attempts,retryFailures);}
    record State(long executedId,long writtenId,long pendingId,long executions,long writeAttempts,long retryFailures){}
}
