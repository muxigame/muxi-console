package net.muxigame.console.domain;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.util.*;

/** Streaming DS wireless barriers. DNL1 never uses or enlarges the small GB/GBA cable vault. */
public final class LinkDsGroupVault implements AutoCloseable {
    public static final long MAX_BYTES=320L*1024*1024+4096, QUOTA_BYTES=8L*1024*1024*1024;
    private static final long IDLE=180000, ABSOLUTE=2*60*60*1000L, MAX_ENDPOINT=160L*1024*1024, MAX_SINGLE=128L*1024*1024;
    private static final int CHUNK=24576, HEADER=4096, MAGIC=0x4d584447;
    public record Key(List<MachineSaveKey> pair,String actualBridgeSHA) {
        public Key {
            if(pair==null||pair.size()!=2||pair.stream().anyMatch(Objects::isNull)||pair.get(0).machineUUID().equals(pair.get(1).machineUUID()))throw new IllegalArgumentException("DS group requires two distinct machines");
            pair=pair.stream().sorted(Comparator.comparing(k->k.machineUUID().toString())).toList();
            actualBridgeSHA=Hashing.normalizeSha256(actualBridgeSHA);
        }
    }
    public record Barrier(MachineSaveKey key,String singleSHA,UUID singleGeneration,long singleFrame,UUID profileGeneration,String profileSHA) {
        public Barrier {
            Objects.requireNonNull(key);singleSHA=Hashing.normalizeSha256(singleSHA);Objects.requireNonNull(singleGeneration);if(singleFrame<0)throw new IllegalArgumentException("Invalid DS single checkpoint frame");
            if(profileGeneration==null?profileSHA!=null:profileSHA==null)throw new IllegalArgumentException("Incomplete DS profile barrier");
            if(profileSHA!=null)profileSHA=Hashing.normalizeSha256(profileSHA);
        }
    }
    public record Snapshot(UUID generation,long frame,long size,String hash,Key key,List<Barrier> barriers) {
        public Snapshot {barriers=List.copyOf(barriers);}
    }
    /** Caller may only read these staging paths. No endpoint becomes visible before its normal vault commit. */
    public record Export(MachineSaveKey key,Path path,long size,String hash) {}
    public record Staged(UUID uploadId,Key key,long frame,long size,String hash,List<Export> endpoints) {
        public Staged {endpoints=List.copyOf(endpoints);}
    }
    private static final class Pending {
        final UUID actor,id;final Key key;final long frame,total,started;final String hash;final Path file;
        long offset,progress;Staged staged;
        Pending(UUID actor,Key key,long frame,long total,String hash,Path file,UUID id,long now){this.actor=actor;this.key=key;this.frame=frame;this.total=total;this.hash=hash;this.file=file;this.id=id;started=progress=now;}
        StateVault.Status status(){return new StateVault.Status(id,offset,total);}
    }
    private record Stored(Snapshot snapshot,int prefix) {}
    private record Completed(UUID actor,Snapshot snapshot,long at) {}
    private final Path incoming;
    private final StateVault store;
    private final Map<UUID,Pending> pending=new HashMap<>();
    private final Map<UUID,Pending> cleanup=new LinkedHashMap<>();
    private final Map<UUID,Completed> completed=new LinkedHashMap<>();
    private final Map<UUID,Stored> metadata=new LinkedHashMap<>(16,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<UUID,Stored> eldest){return size()>256;}
    };
    private boolean closed;

    public LinkDsGroupVault(Path root)throws IOException {
        root=root.toAbsolutePath().normalize();directory(root);incoming=root.resolve("wire-incoming");directory(incoming);
        store=new StateVault(root.resolve("committed"),new StateVault.Limits(QUOTA_BYTES,MAX_BYTES+HEADER,CHUNK,IDLE,60000));
        try(var entries=Files.newDirectoryStream(incoming)) {
            for(Path file:entries){ordinary(file);if(!file.getFileName().toString().matches("[a-f0-9-]{36}\\.(part|single-[01]|record)"))throw new IOException("Unknown DS staging file");Files.delete(file);}
        }catch(IOException|RuntimeException failure){try{store.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    public synchronized StateVault.Status begin(UUID actor,Key key,long frame,long size,String hash,long now)throws IOException {
        active();Objects.requireNonNull(actor);Objects.requireNonNull(key);time(now);hash=Hashing.normalizeSha256(hash);expire(now);
        if(frame<1||size<1||size>MAX_BYTES)throw new DomainException("link_save_size","DS group checkpoint bounds are invalid");
        if(pending.containsKey(actor)||pending.size()>=16||cleanup.size()>=128||completed.size()+pending.size()>=2048||pending.values().stream().anyMatch(p->p.key.equals(key)))throw new DomainException("link_save_active","DS group checkpoint is already uploading or waiting for bounded cleanup");
        long reserved=0;for(Pending p:pending.values())reserved=Math.addExact(reserved,charge(p.total));for(Pending p:cleanup.values())reserved=Math.addExact(reserved,charge(p.total));
        if(charge(size)>QUOTA_BYTES-store.chargedBytes()-reserved)throw new DomainException("link_save_quota","DS group checkpoint quota is exhausted");
        UUID id=UUID.randomUUID();Path file=incoming.resolve(id+".part");
        try(var ignored=FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){}
        Pending p=new Pending(actor,key,frame,size,hash,file,id,now);pending.put(actor,p);return p.status();
    }
    public synchronized StateVault.Status append(UUID actor,UUID id,long offset,byte[] bytes,long now)throws IOException {
        Pending p=upload(actor,id,now);Objects.requireNonNull(bytes);
        if(p.staged!=null||offset!=p.offset||bytes.length<1||bytes.length>CHUNK||bytes.length>p.total-offset)throw new DomainException("link_save_chunk","DS group chunk is stale, oversized or out of order");
        ordinary(p.file);try(var file=FileChannel.open(p.file,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            if(file.size()!=p.offset)throw new IOException("DS staging length changed");file.position(offset);ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())file.write(buffer);
        }
        p.offset+=bytes.length;p.progress=now;return p.status();
    }
    public synchronized Staged finish(UUID actor,UUID id,long now)throws IOException {
        Pending p=upload(actor,id,now);if(p.staged!=null)return p.staged;
        if(p.offset!=p.total)throw new DomainException("link_save_incomplete","DS group upload is incomplete");
        try {
            ordinary(p.file);if(Files.size(p.file)!=p.total||!Hashing.sha256(p.file).equals(p.hash))throw new IOException("DS group checksum differs");
            List<Export> exports=parse(p);p.staged=new Staged(id,p.key,p.frame,p.total,p.hash,exports);p.progress=now;return p.staged;
        }catch(IOException|RuntimeException failure){remove(p);throw failure;}
    }
    public synchronized Snapshot commit(UUID actor,UUID id,List<Barrier> barriers,MachineStateVault states,MachineProfileVault profiles,long now)throws IOException {
        active();time(now);Completed prior=completed.get(id);if(prior!=null){if(!prior.actor.equals(actor)||!prior.snapshot.barriers.equals(ordered(prior.snapshot.key,barriers)))throw new DomainException("link_save_session","Committed DS transaction identity differs");return prior.snapshot;}
        Pending p=upload(actor,id,now);if(p.staged==null)throw new DomainException("link_save_incomplete","DS group must be validated before commit");
        List<Barrier> ordered=ordered(p.key,barriers);UUID inner=null;Path record=incoming.resolve(id+".record");
        try {
            for(int i=0;i<2;i++)if(!ordered.get(i).singleSHA.equals(p.staged.endpoints.get(i).hash))throw new IOException("DS group does not match its extracted endpoint");
            requireBarriers(p.key,ordered,states,profiles);
            byte[] header=header(p,ordered);ordinary(p.file);
            try(var out=new BufferedOutputStream(Files.newOutputStream(record,StandardOpenOption.CREATE_NEW));var in=Files.newInputStream(p.file,LinkOption.NOFOLLOW_LINKS)) {
                new DataOutputStream(out).writeInt(header.length);out.write(header);copy(in,out,p.total);if(in.read()!=-1)throw new IOException("DS staged body grew");
            }
            if(!Hashing.sha256(p.file).equals(p.hash))throw new IOException("DS staged body changed");
            StateVault.Status status=store.begin(actor,identity(p.key),fingerprint(p.key),p.frame,Files.size(record),Hashing.sha256(record),now);inner=status.uploadId();
            try(var in=Files.newInputStream(record,LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes=new byte[CHUNK];long offset=0;for(int n;(n=in.read(bytes))!=-1;){if(n==0)continue;store.append(actor,inner,offset,Arrays.copyOf(bytes,n),now);offset+=n;}
            }
            // Hashing, force and generation preparation run outside the two global machine monitors.
            StateVault.Snapshot saved=store.commitGuarded(actor,inner,now,publication->{
                synchronized(states){synchronized(profiles){requireBarriers(p.key,ordered,states,profiles);publication.publish();}}
            });
            Snapshot result=new Snapshot(saved.generationID(),p.frame,p.total,p.hash,p.key,ordered);metadata.put(result.generation,new Stored(result,header.length+4));completed.put(id,new Completed(actor,result,now));return result;
        }finally {
            try{if(inner!=null&&!completed.containsKey(id))store.abort(actor,inner);}finally{remove(p);}
        }
    }
    public synchronized Optional<Snapshot> latest(Key key,MachineStateVault states,MachineProfileVault profiles)throws IOException {
        active();Optional<StateVault.Snapshot> current=store.latest(identity(key));if(current.isEmpty())return Optional.empty();Stored stored=stored(key,current.get().generationID());
        synchronized(states){synchronized(profiles){return matches(key,stored.snapshot.barriers,states,profiles)?Optional.of(stored.snapshot):Optional.empty();}}
    }
    public synchronized byte[] read(Key key,UUID generation,long offset,int limit)throws IOException {
        active();Stored saved=stored(key,generation);
        if(offset<0||offset>saved.snapshot.size||limit<1||limit>CHUNK)throw new DomainException("link_save_read","DS group read bounds are invalid");
        if(offset==saved.snapshot.size)return store.read(identity(key),generation,saved.prefix+offset,1);return store.read(identity(key),generation,saved.prefix+offset,(int)Math.min(limit,saved.snapshot.size-offset));
    }
    public synchronized void abort(UUID actor,UUID id)throws IOException {
        active();Completed done=completed.get(id);if(done!=null){if(!done.actor.equals(actor))throw new DomainException("link_save_session","Committed DS transaction actor differs");return;}Pending p=pending.get(actor);if(p==null)return;if(!p.id.equals(id))throw new DomainException("link_save_session","DS upload identity differs");remove(p);
    }
    public synchronized void expire(long now)throws IOException {
        active();time(now);completed.entrySet().removeIf(e->elapsed(now,e.getValue().at,300000));for(Pending p:List.copyOf(pending.values()))if(elapsed(now,p.progress,IDLE)||elapsed(now,p.started,ABSOLUTE))remove(p);for(Pending p:List.copyOf(cleanup.values()))tryCleanup(p);store.expire(now);
    }
    public synchronized int cleanupPending(){return cleanup.size();}
    private Pending upload(UUID actor,UUID id,long now)throws IOException {
        active();time(now);Pending p=pending.get(actor);if(p==null||!p.id.equals(id))throw new DomainException("link_save_session","DS upload identity differs");
        if(elapsed(now,p.progress,IDLE)||elapsed(now,p.started,ABSOLUTE)){remove(p);throw new DomainException("link_save_expired","DS upload expired");}return p;
    }
    private List<Export> parse(Pending p)throws IOException {
        List<Export> exports=new ArrayList<>();try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(p.file,LinkOption.NOFOLLOW_LINKS)))) {
            if(in.readInt()!=0x444e4c31||in.readUnsignedShort()!=1||!in.readUTF().equals("melondsds")||!in.readUTF().equals(p.key.actualBridgeSHA)||in.readLong()!=p.frame)throw new IOException("DS group backend or frame differs");
            for(int port=0;port<2;port++){
                MachineSaveKey key=p.key.pair.get(port);
                if(!in.readUTF().equals(key.machineUUID().toString())||!in.readUTF().equals(key.contentSHA())||!in.readUTF().equals("nds"))throw new IOException("DS endpoint identity differs");
                long length=in.readLong();String hash=in.readUTF();if(length<30||length>MAX_ENDPOINT||!hash.matches("[a-f0-9]{64}"))throw new IOException("Invalid DS endpoint size");
                var digest=Hashing.sha256Digest();var bounded=new BoundedInput(in,length);var endpoint=new DataInputStream(new DigestInputStream(bounded,digest));
                if(endpoint.readInt()!=0x44534531||endpoint.readUnsignedShort()!=1||endpoint.readLong()!=p.frame)throw new IOException("DS endpoint frame differs");
                long size=endpoint.readLong();if(size<16||size>MAX_SINGLE||size>length-30)throw new IOException("Invalid DS single-machine size");
                Path single=incoming.resolve(p.id+".single-"+port);var singleDigest=Hashing.sha256Digest();
                try(var out=new BufferedOutputStream(Files.newOutputStream(single,StandardOpenOption.CREATE_NEW))) {
                    var singleInput=new DataInputStream(new DigestInputStream(new BoundedInput(endpoint,size),singleDigest));single(singleInput,size,out);
                }
                nativeQueue(endpoint);transport(endpoint,port);
                if(endpoint.read()!=-1||bounded.left!=0||!HexFormat.of().formatHex(digest.digest()).equals(hash))throw new IOException("DS endpoint checksum or trailing bytes differ");
                exports.add(new Export(key,single,size,HexFormat.of().formatHex(singleDigest.digest())));
            }
            if(in.read()!=-1)throw new IOException("Trailing DS group bytes");return List.copyOf(exports);
        }
    }
    private static void single(DataInputStream in,long size,OutputStream output)throws IOException {
        DataOutputStream out=new DataOutputStream(output);int magic=in.readInt();out.writeInt(magic);if(magic!=0x4d585331)throw new IOException("DS endpoint is not MXS1");long used=4;
        for(int field=0;field<3;field++){int n=in.readInt();out.writeInt(n);used+=4;int max=field==0?128*1024*1024:24*1024*1024;if(n<0||field==0&&n==0||n>max||n>size-used)throw new IOException("Invalid DS MXS1 field size");copy(in,out,n);used+=n;}
        if(used!=size||in.read()!=-1)throw new IOException("Trailing DS MXS1 bytes");
    }
    private static DataInputStream part(DataInputStream in,int min,int max)throws IOException {
        int size=in.readInt();if(size<min||size>max)throw new IOException("Invalid DS queue size");return new DataInputStream(new BoundedInput(in,size));
    }
    private static void nativeQueue(DataInputStream in)throws IOException {
        DataInputStream part=part(in,18,16*1024*1024);
        if(part.readInt()!=0x444d5031||part.readUnsignedShort()!=1||part.readUnsignedByte()>1)throw new IOException("Invalid DS native queue header");
        long timeouts=Integer.toUnsignedLong(part.readInt());if(timeouts>1000000||part.readUnsignedByte()>1||part.readUnsignedShort()>1)throw new IOException("Invalid DS native queue state");
        int count=part.readInt();if(count<0||count>256)throw new IOException("DS native queue count exceeds bounds");
        for(int i=0;i<count;i++){part.readLong();part.readUnsignedByte();if(part.readUnsignedByte()>2)throw new IOException("Invalid DS packet type");int length=part.readInt();if(length<0||length>65526)throw new IOException("Invalid DS native packet size");copy(part,OutputStream.nullOutputStream(),length);}
        if(part.read()!=-1)throw new IOException("Trailing DS native queue bytes");
    }
    private static void transport(DataInputStream in,int port)throws IOException {
        DataInputStream part=part(in,8,9*1024*1024);if(part.readInt()!=0x44515431)throw new IOException("Invalid DS transport queue");int count=part.readInt();if(count<0||count>128)throw new IOException("DS transport queue count exceeds bounds");
        for(int i=0;i<count;i++){if(part.readUnsignedShort()!=1-port)throw new IOException("DS transport sender differs");int n=part.readInt();if(n<1||n>65536)throw new IOException("DS transport packet exceeds bounds");copy(part,OutputStream.nullOutputStream(),n);}if(part.read()!=-1)throw new IOException("Trailing DS transport queue bytes");
    }
    private byte[] header(Pending p,List<Barrier> barriers)throws IOException {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){
            out.writeInt(MAGIC);out.writeShort(2);out.writeUTF(p.key.actualBridgeSHA);out.writeLong(p.frame);out.writeLong(p.total);out.writeUTF(p.hash);
            for(Barrier b:barriers){out.writeUTF(b.key.machineUUID().toString());out.writeUTF(b.key.contentSHA());out.writeUTF(b.singleSHA);out.writeUTF(b.singleGeneration.toString());out.writeLong(b.singleFrame);out.writeBoolean(b.profileGeneration!=null);if(b.profileGeneration!=null){out.writeUTF(b.profileGeneration.toString());out.writeUTF(b.profileSHA);}}
        }if(bytes.size()>HEADER-4)throw new IOException("DS envelope metadata exceeds bounds");return bytes.toByteArray();
    }
    private Stored stored(Key key,UUID generation)throws IOException {
        Stored cached=metadata.get(generation);if(cached!=null){if(!cached.snapshot.key.equals(key))throw new IOException("DS group key differs");return cached;}
        byte[] prefix=store.read(identity(key),generation,0,HEADER);var input=new DataInputStream(new ByteArrayInputStream(prefix));int n=input.readInt();if(n<1||n>HEADER-4||n>input.available())throw new IOException("Invalid DS envelope header");
        var in=new DataInputStream(new ByteArrayInputStream(input.readNBytes(n)));if(in.readInt()!=MAGIC||in.readUnsignedShort()!=2||!in.readUTF().equals(key.actualBridgeSHA))throw new IOException("DS stored bridge differs");long frame=in.readLong(),size=in.readLong();String hash=in.readUTF();
        if(frame<1||size<1||size>MAX_BYTES||!hash.matches("[a-f0-9]{64}"))throw new IOException("Invalid DS stored bounds");List<Barrier> barriers=new ArrayList<>();
        try{for(int i=0;i<2;i++){MachineSaveKey k=new MachineSaveKey(UUID.fromString(in.readUTF()),in.readUTF());String single=in.readUTF();UUID generationID=UUID.fromString(in.readUTF());long singleFrame=in.readLong();boolean profile=in.readBoolean();barriers.add(new Barrier(k,single,generationID,singleFrame,profile?UUID.fromString(in.readUTF()):null,profile?in.readUTF():null));}}catch(IllegalArgumentException invalid){throw new IOException("Invalid DS envelope identity");}
        if(in.read()!=-1)throw new IOException("Trailing DS envelope metadata");barriers=ordered(key,barriers);Stored result=new Stored(new Snapshot(generation,frame,size,hash,key,barriers),n+4);metadata.put(generation,result);return result;
    }
    private static List<Barrier> ordered(Key key,List<Barrier> barriers)throws IOException {
        if(barriers==null||barriers.size()!=2||barriers.stream().anyMatch(Objects::isNull))throw new IOException("DS barrier requires two endpoints");List<Barrier> result=barriers.stream().sorted(Comparator.comparing(b->b.key.machineUUID().toString())).toList();
        for(int i=0;i<2;i++)if(!result.get(i).key.equals(key.pair.get(i)))throw new IOException("DS barrier machine/content differs");return result;
    }
    private static boolean matches(Key key,List<Barrier> barriers,MachineStateVault states,MachineProfileVault profiles)throws IOException {
        ordered(key,barriers);for(Barrier b:barriers){var state=states.latest(b.key);if(state.isEmpty()||!state.get().hash().equals(b.singleSHA)||!state.get().generationID().equals(b.singleGeneration)||state.get().frame()!=b.singleFrame||!Objects.equals(state.get().profileGeneration(),b.profileGeneration))return false;
            var profile=profiles.latest(new MachineProfileVault.Key(b.key.machineUUID(),Platform.NDS));if(b.profileGeneration==null){if(profile.isPresent())return false;}else if(profile.isEmpty()||!profile.get().generation().equals(b.profileGeneration)||!profile.get().hash().equals(b.profileSHA))return false;}return true;
    }
    private static void requireBarriers(Key key,List<Barrier> barriers,MachineStateVault states,MachineProfileVault profiles)throws IOException {if(!matches(key,barriers,states,profiles))throw new DomainException("link_save_stale","DS endpoint or native profile advanced beyond the group barrier");}
    private static String fingerprint(Key key){StringBuilder text=new StringBuilder("nds-wireless-v1\n").append(key.actualBridgeSHA);for(var k:key.pair)text.append('\n').append(k.machineUUID()).append(':').append(k.contentSHA());return Hashing.sha256(text.toString().getBytes(StandardCharsets.UTF_8));}
    private static UUID identity(Key key){return UUID.nameUUIDFromBytes(fingerprint(key).getBytes(StandardCharsets.US_ASCII));}
    // During commit the raw upload, extracted singles, envelope and atomic-vault incoming copy coexist.
    private static long charge(long size){return Math.addExact(Math.multiplyExact(size,4),3L*HEADER+1024L);}
    private static void directory(Path path)throws IOException {Files.createDirectories(path);if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||!path.equals(path.toRealPath()))throw new IOException("Unsafe DS group directory");}
    private static void ordinary(Path file)throws IOException {if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.equals(file.toRealPath()))throw new IOException("Unsafe DS group file");}
    private static void time(long now){if(now<0)throw new DomainException("link_save_time","DS checkpoint time is invalid");}
    private static boolean elapsed(long now,long then,long bound){return now>=then&&now-then>bound;}
    private void active()throws IOException {if(closed)throw new IOException("DS group vault is closed");}
    private void remove(Pending p) {
        pending.remove(p.actor,p);cleanup.put(p.id,p);tryCleanup(p);
    }
    private IOException tryCleanup(Pending p) {
        IOException failure=null;for(String suffix:List.of("part","single-0","single-1","record")){Path file=incoming.resolve(p.id+"."+suffix);try{if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){ordinary(file);Files.delete(file);}}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}}
        if(failure==null)cleanup.remove(p.id,p);return failure;
    }
    private static void copy(InputStream in,OutputStream out,long size)throws IOException {byte[] buffer=new byte[65536];for(long left=size;left>0;){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("DS checkpoint cancelled");int n=in.read(buffer,0,(int)Math.min(left,buffer.length));if(n<0)throw new EOFException();if(n>0){out.write(buffer,0,n);left-=n;}}}
    private static final class BoundedInput extends InputStream {
        final InputStream source;long left;BoundedInput(InputStream source,long left){this.source=source;this.left=left;}
        @Override public int read()throws IOException {if(left==0)return -1;int b=source.read();if(b<0)throw new EOFException();left--;return b;}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException {if(length==0)return 0;if(left==0)return -1;int n=source.read(bytes,offset,(int)Math.min(left,length));if(n<0)throw new EOFException();left-=n;return n;}
    }
    @Override public synchronized void close()throws IOException {
        if(closed)return;IOException failure=null;for(Pending p:List.copyOf(pending.values()))remove(p);for(Pending p:List.copyOf(cleanup.values())){IOException error=tryCleanup(p);if(error!=null){if(failure==null)failure=error;else failure.addSuppressed(error);}}try{store.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}closed=true;metadata.clear();completed.clear();if(failure!=null)throw failure;
    }
}
