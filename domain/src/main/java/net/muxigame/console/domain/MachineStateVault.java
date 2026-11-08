package net.muxigame.console.domain;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Machine/content namespace over the existing atomic vault. Old states/cards are never opened. */
public final class MachineStateVault implements AutoCloseable {
    public record Snapshot(MachineSaveKey key,StateVault.Snapshot checkpoint) {
        public UUID machineUUID(){return key.machineUUID();}
        public String contentSHA(){return key.contentSHA();}
        public String fingerprint(){return checkpoint.fingerprint();}
        public long frame(){return checkpoint.frame();}
        public String hash(){return checkpoint.hash();}
        public long size(){return checkpoint.size();}
        public UUID generationID(){return checkpoint.generationID();}
    }
    private static final class Pending {
        final MachineSaveKey key;final UUID id;long progressed;
        Pending(MachineSaveKey key,UUID id,long now){this.key=key;this.id=id;progressed=now;}
    }
    private final Path root;
    private final StateVault.Limits limits;
    private final Map<MachineSaveKey,StateVault> stores=new HashMap<>();
    private final Map<UUID,Pending> uploads=new HashMap<>();
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    public MachineStateVault(Path root)throws IOException{this(root,StateVault.DEFAULT_LIMITS);}
    public MachineStateVault(Path root,StateVault.Limits limits)throws IOException{
        this.root=Objects.requireNonNull(root).toAbsolutePath().normalize();this.limits=Objects.requireNonNull(limits);directory(this.root);
        FileChannel acquiredChannel=FileChannel.open(this.root.resolve("machines.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock acquired=null;
        try{
            try{acquired=acquiredChannel.tryLock();}catch(OverlappingFileLockException busy){throw new IOException("Machine checkpoint vault is already open",busy);}
            if(acquired==null)throw new IOException("Machine checkpoint vault is already open");
            try(var machines=Files.newDirectoryStream(this.root)){
                for(Path machine:machines){if(machine.getFileName().toString().equals("machines.lock"))continue;directory(machine);UUID identity=canonicalUUID(machine.getFileName().toString());
                    try(var games=Files.newDirectoryStream(machine)){for(Path game:games){String hash=game.getFileName().toString();MachineSaveKey key;try{key=new MachineSaveKey(identity,hash);}catch(DomainException invalid){throw new IOException("Unexpected game save directory",invalid);}directory(game);stores.put(key,new StateVault(game,limits,namespace(key)));}}
                }
            }
        }catch(IOException|RuntimeException failure){for(StateVault store:stores.values())try{store.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}if(acquired!=null)try{acquired.release();}catch(IOException cleanup){failure.addSuppressed(cleanup);}try{acquiredChannel.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}
        channel=acquiredChannel;lock=acquired;
    }
    public synchronized StateVault.Status begin(UUID actor,MachineSaveKey key,String fingerprint,long frame,long size,String hash,long now)throws IOException{
        active();Objects.requireNonNull(key);expire(now);
        if(actor==null)throw new DomainException("save_identity","Checkpoint actor is required");
        if(uploads.containsKey(actor)||uploads.values().stream().anyMatch(p->p.key.equals(key)))throw new DomainException("save_active","Machine/game checkpoint is already uploading");
        if(size<1||size>limits.maxStateBytes())throw new DomainException("save_size","Checkpoint bounds are invalid");
        long charge=Math.addExact(size,1024L);
        if(charge>limits.quotaBytes()-chargedBytes())throw new DomainException("save_quota","Machine checkpoint storage quota is exhausted");
        StateVault store=stores.get(key);if(store==null){Path machine=root.resolve(key.machineUUID().toString());directory(machine);Path game=machine.resolve(key.contentSHA());directory(game);store=new StateVault(game,limits,namespace(key));stores.put(key,store);}
        StateVault.Status status=store.begin(actor,key.machineUUID(),fingerprint,frame,size,hash,now);uploads.put(actor,new Pending(key,status.uploadId(),now));return status;
    }
    public synchronized StateVault.Status append(UUID actor,UUID id,long offset,byte[] bytes,long now)throws IOException{
        active();Pending pending=pending(actor,id);StateVault.Status status=stores.get(pending.key).append(actor,id,offset,bytes,now);pending.progressed=now;return status;
    }
    public synchronized Snapshot commit(UUID actor,UUID id,long now)throws IOException{
        active();Pending pending=pending(actor,id);
        try{return new Snapshot(pending.key,stores.get(pending.key).commit(actor,id,now));}
        finally{uploads.remove(actor);stores.get(pending.key).abort(actor,id);}
    }
    public synchronized void abort(UUID actor,UUID id)throws IOException{
        active();Pending pending=uploads.get(actor);if(pending==null)return;if(!pending.id.equals(id))throw new DomainException("save_session","Checkpoint upload identity differs");
        stores.get(pending.key).abort(actor,id);uploads.remove(actor);
    }
    public synchronized Optional<Snapshot> latest(MachineSaveKey key)throws IOException{
        active();StateVault store=stores.get(Objects.requireNonNull(key));return store==null?Optional.empty():store.latest(key.machineUUID()).map(s->new Snapshot(key,s));
    }
    public synchronized byte[] read(MachineSaveKey key,UUID generation,long offset,int limit)throws IOException{
        active();StateVault store=stores.get(Objects.requireNonNull(key));if(store==null)throw new DomainException("save_missing","Machine/game checkpoint is absent");return store.read(key.machineUUID(),generation,offset,limit);
    }
    public synchronized void expire(long now)throws IOException{
        active();if(now<0)throw new DomainException("save_time","Checkpoint time must not be negative");
        for(StateVault store:stores.values())store.expire(now);
        uploads.entrySet().removeIf(e->now>e.getValue().progressed&&now-e.getValue().progressed>limits.uploadTimeoutMillis());
    }
    public synchronized long chargedBytes()throws IOException{active();long total=0;for(StateVault store:stores.values())total=Math.addExact(total,store.chargedBytes());return total;}
    private Pending pending(UUID actor,UUID id){Pending value=uploads.get(actor);if(value==null||!value.id.equals(id))throw new DomainException("save_session","Checkpoint upload identity differs");return value;}
    private static String namespace(MachineSaveKey key){return key.machineUUID()+":"+key.contentSHA();}
    private void active()throws IOException{if(closed)throw new IOException("Machine checkpoint vault is closed");}
    private static UUID canonicalUUID(String text)throws IOException{try{UUID id=UUID.fromString(text);if(!id.toString().equals(text))throw new IllegalArgumentException();return id;}catch(IllegalArgumentException invalid){throw new IOException("Unexpected machine save directory",invalid);}}
    private static void directory(Path path)throws IOException{if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Save path is not a direct directory");Files.createDirectories(path);}
    @Override public synchronized void close()throws IOException{
        if(closed)return;IOException failure=null;
        for(StateVault store:stores.values())try{store.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}closed=true;uploads.clear();
        try{lock.release();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}try{channel.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}if(failure!=null)throw failure;
    }
}
