package net.muxigame.console.domain;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Combined cable checkpoints live outside ordinary machine vaults. Endpoints remain ordinary MXS1 exports. */
public final class LinkStateStore {
    public static final int MAX_BYTES=8*1024*1024;
    private static final long QUOTA=128L*1024*1024;
    public record Group(long frame,byte[] state,List<byte[]> endpoints,String hash){}
    private final Path root;
    public LinkStateStore(Path root)throws IOException {
        this.root=root.toAbsolutePath().normalize();Files.createDirectories(this.root);
        if(Files.isSymbolicLink(this.root)||!this.root.toRealPath().equals(this.root))throw new IOException("Unsafe link save directory");
    }
    public static List<byte[]> exports(byte[] bytes,List<LinkLobby.Endpoint> endpoints,String bridge)throws IOException {
        if(bytes==null||bytes.length<1||bytes.length>MAX_BYTES||endpoints.size()!=2)throw new IOException("Invalid combined link state");
        var in=new DataInputStream(new ByteArrayInputStream(bytes));
        if(in.readInt()!=0x4c4e4b31)throw new IOException("Invalid link state magic");int version=in.readUnsignedShort();
        boolean gba=endpoints.stream().allMatch(e->e.platform().equals("gba"));
        if(gba){if(version!=2||!text(in).equals("mgba"))throw new IOException("GBA link backend identity differs");}
        else if(version!=1||endpoints.stream().anyMatch(e->!Set.of("gb","gbc").contains(e.platform())))throw new IOException("GB link backend identity differs");
        if(!text(in).equals(bridge))throw new IOException("Link bridge identity differs");
        List<byte[]> result=new ArrayList<>();
        for(var endpoint:endpoints){
            if(!text(in).equals(endpoint.machine().toString())||!text(in).equals(endpoint.platform())||!text(in).equals(endpoint.content()))throw new IOException("Link machine/content identity differs");
            byte[] single=bytes(in,4*1024*1024);single(single);result.add(single);
        }
        if(gba){byte[] nativeState=bytes(in,4*1024*1024);var nativeInput=new DataInputStream(new ByteArrayInputStream(nativeState));if(nativeState.length<12||nativeInput.readInt()!=0x4d474c31)throw new IOException("Invalid GBA coordinator state");if(bytes(nativeInput,4*1024*1024).length==0||bytes(nativeInput,4*1024*1024).length==0||nativeInput.available()!=0)throw new IOException("Invalid GBA coordinator endpoints");}
        else if(in.readUnsignedByte()>3)throw new IOException("Invalid cable bus state");
        if(in.available()!=0)throw new IOException("Trailing link state");return List.copyOf(result);
    }
    private static void single(byte[] bytes)throws IOException {
        var in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=0x4d585331)throw new IOException("Link endpoint is not a single-machine export");
        byte[] core=bytes(in,2*1024*1024);bytes(in,512*1024);bytes(in,64*1024);
        if(core.length<1||in.available()!=0)throw new IOException("Invalid single-machine export");
    }
    private static byte[] bytes(DataInputStream in,int max)throws IOException {int n=in.readInt();if(n<0||n>max||n>in.available())throw new IOException("Invalid link state field");return in.readNBytes(n);}
    private static String text(DataInputStream in)throws IOException{return new String(bytes(in,32768),StandardCharsets.UTF_8);}
    private static String pair(List<LinkLobby.Endpoint> endpoints){return Hashing.sha256((endpoints.get(0).key()+"\n"+endpoints.get(1).key()).getBytes(StandardCharsets.UTF_8));}
    private Path path(List<LinkLobby.Endpoint> endpoints)throws IOException {Path file=root.resolve(pair(endpoints)+".lnk");safe(file);return file;}
    private static void safe(Path path)throws IOException {if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path)))throw new IOException("Unsafe link save file");}
    /** Returns a group only while both normal machine latest snapshots still equal this barrier. */
    public synchronized Optional<Group> latest(List<LinkLobby.Endpoint> endpoints,String bridge,MachineStateVault vault)throws IOException {
        Path file=path(endpoints);if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return Optional.empty();
        if(Files.size(file)>MAX_BYTES+128)throw new IOException("Link save exceeds bounds");
        var in=new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(file)));
        if(in.readInt()!=0x4d584c47||in.readUnsignedShort()!=1)throw new IOException("Invalid link save record");long frame=in.readLong();String hash=text(in);byte[] state=bytes(in,MAX_BYTES);
        if(frame<0||in.available()!=0||!Hashing.sha256(state).equals(hash))throw new IOException("Invalid link save checksum");
        List<byte[]> singles=exports(state,endpoints,bridge);
        for(int i=0;i<2;i++){var saved=vault.latest(endpoints.get(i).key());if(saved.isEmpty()||!saved.get().hash().equals(Hashing.sha256(singles.get(i))))return Optional.empty();}
        return Optional.of(new Group(frame,state,singles,hash));
    }
    /** Caller serially commits both extracted machine exports before publishing this group barrier. */
    public synchronized Group publish(List<LinkLobby.Endpoint> endpoints,String bridge,long frame,byte[] state,MachineStateVault vault)throws IOException {
        List<byte[]> singles=exports(state,endpoints,bridge);if(frame<0)throw new IOException("Invalid link save frame");
        for(int i=0;i<2;i++){var saved=vault.latest(endpoints.get(i).key());if(saved.isEmpty()||!saved.get().hash().equals(Hashing.sha256(singles.get(i))))throw new IOException("Link endpoint barrier has not committed");}
        Path file=path(endpoints);ByteArrayOutputStream buffer=new ByteArrayOutputStream();String hash=Hashing.sha256(state);
        try(var out=new DataOutputStream(buffer)){out.writeInt(0x4d584c47);out.writeShort(1);out.writeLong(frame);byte[] digest=hash.getBytes(StandardCharsets.US_ASCII);out.writeInt(digest.length);out.write(digest);out.writeInt(state.length);out.write(state);}
        byte[] record=buffer.toByteArray();long used=0;
        try(var entries=Files.newDirectoryStream(root,"*.lnk")){for(Path entry:entries){safe(entry);used=Math.addExact(used,Files.size(entry));}}
        long old=Files.exists(file,LinkOption.NOFOLLOW_LINKS)?Files.size(file):0;if(record.length>QUOTA-used+old)throw new IOException("Link checkpoint quota exhausted");
        Path temp=Files.createTempFile(root,"link-save-",".tmp");
        try{try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){ByteBuffer bytes=ByteBuffer.wrap(record);while(bytes.hasRemaining())channel.write(bytes);channel.force(true);}
            safe(file);try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){throw new IOException("Link saves require atomic replacement",unsupported);}
        }finally{Files.deleteIfExists(temp);}
        return new Group(frame,state.clone(),singles,hash);
    }
}
