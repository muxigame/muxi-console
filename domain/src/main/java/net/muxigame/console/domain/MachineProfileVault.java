package net.muxigame.console.domain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** A machine's native filesystem is shared across its games; it never uses a cartridge/ROM namespace. */
public final class MachineProfileVault implements AutoCloseable {
    public record Key(UUID machine,Platform platform){public Key{Objects.requireNonNull(machine);Objects.requireNonNull(platform);}}
    public record Snapshot(Key key,MachineStateVault.Snapshot stored){
        public UUID generation(){return stored.generationID();}public long size(){return stored.size();}public String hash(){return stored.hash();}public long frame(){return stored.frame();}
    }
    private record Pending(Key key,UUID id,UUID previous){}
    private final MachineStateVault files;
    private final Map<UUID,Pending> pending=new HashMap<>();
    public static final StateVault.Limits LIMITS=new StateVault.Limits(8L*1024*1024*1024,512L*1024*1024,24576,180000,60000);
    public MachineProfileVault(Path root)throws IOException{files=new MachineStateVault(root,LIMITS);}
    private static String digest(String text){return Hashing.sha256(text.getBytes(StandardCharsets.UTF_8));}
    private static MachineSaveKey storageKey(Key key){return new MachineSaveKey(key.machine,digest("native-profile-v1\n"+key.platform.id()));}
    private static String format(Key key){return digest("native-profile-archive-v1\n"+key.platform.id());}
    public synchronized Optional<Snapshot> latest(Key key)throws IOException{return files.latest(storageKey(key)).map(value->new Snapshot(key,value));}
    public synchronized StateVault.Status begin(UUID actor,Key key,UUID previous,long frame,long size,String hash,long now)throws IOException{
        Optional<Snapshot> current=latest(key);if(!Objects.equals(current.map(Snapshot::generation).orElse(null),previous))throw new DomainException("profile_stale","The machine filesystem changed; reload its latest profile");
        StateVault.Status status=files.begin(actor,storageKey(key),format(key),frame,size,hash,now);pending.put(actor,new Pending(key,status.uploadId(),previous));return status;
    }
    public synchronized StateVault.Status append(UUID actor,UUID id,long offset,byte[] bytes,long now)throws IOException{require(actor,id);return files.append(actor,id,offset,bytes,now);}
    public synchronized Snapshot commit(UUID actor,UUID id,long now)throws IOException{
        Pending upload=require(actor,id);try{
            if(!Objects.equals(latest(upload.key).map(Snapshot::generation).orElse(null),upload.previous))throw new DomainException("profile_stale","The machine filesystem changed during upload");
            return new Snapshot(upload.key,files.commit(actor,id,now));
        }finally{pending.remove(actor,upload);files.abort(actor,id);}
    }
    public synchronized byte[] read(Key key,UUID generation,long offset,int limit)throws IOException{return files.read(storageKey(key),generation,offset,limit);}
    public synchronized void abort(UUID actor,UUID id)throws IOException{Pending upload=pending.get(actor);if(upload!=null&&upload.id.equals(id)){files.abort(actor,id);pending.remove(actor,upload);}}
    public synchronized void expire(long now)throws IOException{files.expire(now);}
    private Pending require(UUID actor,UUID id){Pending upload=pending.get(actor);if(upload==null||!upload.id.equals(id))throw new DomainException("profile_session","Machine filesystem transaction is unavailable");return upload;}
    @Override public synchronized void close()throws IOException{pending.clear();files.close();}
}
