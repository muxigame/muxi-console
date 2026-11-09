import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import com.sun.nio.file.ExtendedOpenOption;
import java.util.*;
import net.muxigame.console.domain.*;

/** Domain transaction and format tests; native/core compatibility is verified separately. */
public final class LinkDsGroupVaultTest {
    static int checks;
    static final String BRIDGE="3b0419d8c40c7aceb2a54b28daec7475c1776b8e6db06d0122071f69a7b099a7", SINGLE="26ad9fb7927ba9f72510958a5cdb8f360fcc02bda59b057c2ea394cc97bb75fa";
    static final UUID ACTOR=UUID.fromString("00000000-0000-0000-0000-000000000099");
    static final MachineSaveKey A=new MachineSaveKey(new UUID(0,1),"2fb44e3180794884fc22b1d79f7a92d7115da8dd507496fc5b81cb08d7a35950"),B=new MachineSaveKey(new UUID(0,2),"08344558e57969fa8dc0a2842d62dec4e50b3a09761867a9ad260ac297729826");
    static final LinkDsGroupVault.Key KEY=new LinkDsGroupVault.Key(List.of(B,A),BRIDGE);
    static final long NOW=System.currentTimeMillis();
    interface Checked {void run()throws Exception;}
    static void check(boolean value,String text){checks++;if(!value)throw new AssertionError(text);}
    static void rejects(Checked action,String text)throws Exception {boolean failed=false;try{action.run();}catch(IOException|IllegalArgumentException expected){failed=true;}check(failed,text);}
    static byte[] single(int seed)throws IOException {var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(0x4d585331);out.writeInt(65536);byte[] core=new byte[65536];Arrays.fill(core,(byte)seed);out.write(core);out.writeInt(4);out.writeInt(seed);out.writeInt(0);}return bytes.toByteArray();}
    static byte[] endpoint(long frame,int port,boolean badSender)throws IOException {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){byte[] single=single(port+1);out.writeInt(0x44534531);out.writeShort(1);out.writeLong(frame);out.writeLong(single.length);out.write(single);
            var nativeBytes=new ByteArrayOutputStream();try(var mp=new DataOutputStream(nativeBytes)){mp.writeInt(0x444d5031);mp.writeShort(1);mp.writeByte(1);mp.writeInt(17);mp.writeByte(1);mp.writeShort(0);mp.writeInt(1);mp.writeLong(1234);mp.writeByte(1);mp.writeByte(2);mp.writeInt(3);mp.write(new byte[]{1,2,3});}
            out.writeInt(nativeBytes.size());out.write(nativeBytes.toByteArray());out.writeInt(16);out.writeInt(0x44515431);out.writeInt(1);out.writeShort(badSender?port:1-port);out.writeInt(2);out.write(new byte[]{7,8});}
        return bytes.toByteArray();
    }
    static byte[] group(long frame,boolean badSender)throws IOException {var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(0x444e4c31);out.writeShort(1);out.writeUTF("melondsds");out.writeUTF(BRIDGE);out.writeLong(frame);for(int i=0;i<2;i++){MachineSaveKey key=KEY.pair().get(i);byte[] endpoint=endpoint(frame,i,badSender);out.writeUTF(key.machineUUID().toString());out.writeUTF(key.contentSHA());out.writeUTF("nds");out.writeLong(endpoint.length);out.writeUTF(Hashing.sha256(endpoint));out.write(endpoint);}}return bytes.toByteArray();}
    static StateVault.Status upload(LinkDsGroupVault vault,byte[] bytes,long frame)throws IOException {StateVault.Status status=vault.begin(ACTOR,KEY,frame,bytes.length,Hashing.sha256(bytes),NOW);for(int offset=0;offset<bytes.length;offset+=24576)status=vault.append(ACTOR,status.uploadId(),offset,Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+24576)),NOW);return status;}
    static List<LinkDsGroupVault.Barrier> singles(LinkDsGroupVault.Staged stage,MachineStateVault states,MachineProfileVault profiles)throws IOException {
        List<LinkDsGroupVault.Barrier> barriers=new ArrayList<>();for(var endpoint:stage.endpoints()){
            var profile=profiles.latest(new MachineProfileVault.Key(endpoint.key().machineUUID(),Platform.NDS));UUID generation=profile.map(MachineProfileVault.Snapshot::generation).orElse(null);
            var status=states.begin(ACTOR,endpoint.key(),SINGLE,stage.frame(),endpoint.size(),endpoint.hash(),NOW,generation);
            try(var in=Files.newInputStream(endpoint.path())){byte[] buffer=new byte[24576];long offset=0;for(int n;(n=in.read(buffer))!=-1;){states.append(ACTOR,status.uploadId(),offset,Arrays.copyOf(buffer,n),NOW);offset+=n;}}
            var saved=states.commit(ACTOR,status.uploadId(),NOW);check(saved.hash().equals(endpoint.hash()),"actual extracted single committed");barriers.add(new LinkDsGroupVault.Barrier(endpoint.key(),saved.hash(),saved.generationID(),saved.frame(),generation,profile.map(MachineProfileVault.Snapshot::hash).orElse(null)));
        }return barriers;
    }
    static void profile(MachineProfileVault profiles,MachineSaveKey key,int value)throws IOException {var k=new MachineProfileVault.Key(key.machineUUID(),Platform.NDS);var previous=profiles.latest(k);byte[] bytes={(byte)value,1,2};var status=profiles.begin(ACTOR,k,previous.map(MachineProfileVault.Snapshot::generation).orElse(null),40,bytes.length,Hashing.sha256(bytes),NOW);profiles.append(ACTOR,status.uploadId(),0,bytes,NOW);profiles.commit(ACTOR,status.uploadId(),NOW);}
    static String readHash(LinkDsGroupVault vault,LinkDsGroupVault.Snapshot snapshot)throws IOException {var digest=Hashing.sha256Digest();for(long offset=0;offset<snapshot.size();){byte[] bytes=vault.read(KEY,snapshot.generation(),offset,24576);if(bytes.length==0)throw new EOFException();digest.update(bytes);offset+=bytes.length;}return HexFormat.of().formatHex(digest.digest());}
    public static void main(String[] args)throws Exception {
        Path base=Path.of(args.length==0?"build/test-data":args[0]).toAbsolutePath();Files.createDirectories(base);Path root=Files.createTempDirectory(base,"ds-group-");
        check(KEY.pair().equals(List.of(A,B)),"canonical key ordering");rejects(()->new LinkDsGroupVault.Key(List.of(A,A),BRIDGE),"same machine rejected");
        try(var states=new MachineStateVault(root.resolve("states"));var profiles=new MachineProfileVault(root.resolve("profiles"))){
            LinkDsGroupVault.Snapshot original;
            try(var vault=new LinkDsGroupVault(root.resolve("groups"))){
                rejects(()->new LinkDsGroupVault(root.resolve("groups")),"second writer lock rejected");
                check(vault.latest(KEY,states,profiles).isEmpty(),"initial group absent");byte[] raw=group(33,false);var status=upload(vault,raw,33);
                rejects(()->vault.append(UUID.randomUUID(),status.uploadId(),raw.length,new byte[]{1},NOW),"foreign actor rejected");
                var stage=vault.finish(ACTOR,status.uploadId(),NOW);check(vault.finish(ACTOR,status.uploadId(),NOW).equals(stage),"finish is stable for exact transaction");
                check(stage.endpoints().size()==2&&stage.endpoints().get(0).size()==single(1).length,"two streaming endpoint exports");
                check(Arrays.equals(Files.readAllBytes(stage.endpoints().get(0).path()),single(1)),"MXS1 export preserves exact bytes");
                check(vault.latest(KEY,states,profiles).isEmpty(),"finish does not publish");var barriers=singles(stage,states,profiles);
                original=vault.commit(ACTOR,status.uploadId(),barriers,states,profiles,NOW);check(vault.latest(KEY,states,profiles).orElseThrow().equals(original),"complete barrier published");
                check(readHash(vault,original).equals(Hashing.sha256(raw)),"group reads original DNL1 without envelope bytes");
                check(vault.read(KEY,original.generation(),original.size(),1).length==0,"bounded EOF read");final var first=original;
                rejects(()->vault.read(KEY,first.generation(),-1,1),"negative read offset rejected");rejects(()->vault.read(KEY,first.generation(),0,24577),"oversize read rejected");
                var otherKey=new LinkDsGroupVault.Key(KEY.pair(),"0".repeat(64));rejects(()->vault.read(otherKey,first.generation(),0,10),"bridge mismatch cannot read cached generation");
                byte[] invalid=group(34,true);var bad=upload(vault,invalid,34);rejects(()->vault.finish(ACTOR,bad.uploadId(),NOW),"native transport sender checked");check(vault.latest(KEY,states,profiles).orElseThrow().equals(original),"invalid upload preserves previous head");
                var partial=vault.begin(ACTOR,KEY,34,100,"0".repeat(64),NOW);rejects(()->vault.finish(ACTOR,partial.uploadId(),NOW),"incomplete upload rejected");
                rejects(()->vault.append(ACTOR,partial.uploadId(),1,new byte[]{1},NOW),"out of order rejected");rejects(()->vault.abort(ACTOR,UUID.randomUUID()),"wrong cancellation id cannot affect upload");vault.abort(ACTOR,partial.uploadId());
                var hash=vault.begin(ACTOR,KEY,34,1,"0".repeat(64),NOW);vault.append(ACTOR,hash.uploadId(),0,new byte[]{1},NOW);rejects(()->vault.finish(ACTOR,hash.uploadId(),NOW),"body hash mismatch rejected");
                var idle=vault.begin(ACTOR,KEY,34,100,"0".repeat(64),NOW);rejects(()->vault.append(ACTOR,idle.uploadId(),0,new byte[]{1},NOW+180001),"idle timeout retires staging");
                var absolute=vault.begin(ACTOR,KEY,34,100,"0".repeat(64),NOW);long tick=NOW;for(int i=0;i<40;i++){tick+=179000;vault.append(ACTOR,absolute.uploadId(),i,new byte[]{1},tick);}final long beyond=NOW+7200001;rejects(()->vault.append(ACTOR,absolute.uploadId(),40,new byte[]{1},beyond),"absolute timeout despite continuing progress");
                rejects(()->vault.begin(ACTOR,KEY,34,LinkDsGroupVault.MAX_BYTES+1,"0".repeat(64),NOW),"large group bound rejected");
                var staleStatus=upload(vault,raw,33);var stale=vault.finish(ACTOR,staleStatus.uploadId(),NOW);profile(profiles,A,5);
                check(vault.latest(KEY,states,profiles).isEmpty(),"new filesystem invalidates old group");rejects(()->vault.commit(ACTOR,stale.uploadId(),barriers,states,profiles,NOW),"stale profile blocks new group publish");
                check(readHash(vault,original).equals(original.hash()),"old complete generation remains readable after failed newer commit");
                var next=upload(vault,group(35,false),35);var nextStage=vault.finish(ACTOR,next.uploadId(),NOW);var bound=singles(nextStage,states,profiles);original=vault.commit(ACTOR,next.uploadId(),bound,states,profiles,NOW);
                check(original.barriers().get(0).profileGeneration()!=null,"group carries actual profile generation");
            }
            try(var reopened=new LinkDsGroupVault(root.resolve("groups"))){var latest=reopened.latest(KEY,states,profiles).orElseThrow();check(latest.equals(original),"cold vault restores complete metadata and profile bindings");check(readHash(reopened,latest).equals(latest.hash()),"cold vault reads exact original group body");profile(profiles,A,5);check(reopened.latest(KEY,states,profiles).isEmpty(),"same bytes with later profile generation invalidate old group");
                var next=upload(reopened,group(37,false),37);var stage=reopened.finish(ACTOR,next.uploadId(),NOW);var barriers=singles(stage,states,profiles);latest=reopened.commit(ACTOR,next.uploadId(),barriers,states,profiles,NOW);
                byte[] same=single(1);var current=states.latest(A).orElseThrow();var update=states.begin(ACTOR,A,SINGLE,current.frame(),same.length,Hashing.sha256(same),NOW,current.profileGeneration());for(int offset=0;offset<same.length;offset+=24576)states.append(ACTOR,update.uploadId(),offset,Arrays.copyOfRange(same,offset,Math.min(same.length,offset+24576)),NOW);var advanced=states.commit(ACTOR,update.uploadId(),NOW);
                check(advanced.hash().equals(current.hash())&&!advanced.generationID().equals(current.generationID()),"same MXS1 body and frame have a genuinely new single generation");check(reopened.latest(KEY,states,profiles).isEmpty(),"new single generation invalidates old group even with equal content");
                if(System.getProperty("os.name").startsWith("Windows")){
                    var upload=upload(reopened,group(38,false),38);var blocked=reopened.finish(ACTOR,upload.uploadId(),NOW);var bound=singles(blocked,states,profiles);
                    LinkDsGroupVault.Snapshot published;StateVault.Status newer;
                    try(var held=FileChannel.open(blocked.endpoints().get(0).path(),StandardOpenOption.READ,ExtendedOpenOption.NOSHARE_DELETE)){
                        published=reopened.commit(ACTOR,upload.uploadId(),bound,states,profiles,NOW);check(reopened.cleanupPending()==1,"actual Windows sharing denial retains bounded cleanup accounting");
                        check(reopened.latest(KEY,states,profiles).orElseThrow().equals(published),"post-publish cleanup denial still reports actual committed head");check(reopened.commit(ACTOR,upload.uploadId(),bound,states,profiles,NOW).equals(published),"exact completed transaction retry is idempotent despite pending cleanup");
                        newer=reopened.begin(ACTOR,KEY,39,100,"0".repeat(64),NOW);reopened.append(ACTOR,newer.uploadId(),0,new byte[]{1},NOW);reopened.abort(ACTOR,upload.uploadId());reopened.append(ACTOR,newer.uploadId(),1,new byte[]{2},NOW);check(true,"late old abort cannot cancel the actor's newer upload");
                    }
                    reopened.expire(NOW+1);check(reopened.cleanupPending()==0,"released Windows handle permits deferred cleanup");reopened.abort(ACTOR,newer.uploadId());check(readHash(reopened,published).equals(published.hash()),"cleanup cannot alter the published group");
                }
            }
        }
        // Optional genuine native DNL1 validates the domain parser under the same 128 MiB test heap.
        if(args.length>1){Path source=Path.of(args[1]);try(var states=new MachineStateVault(root.resolve("real-states"));var profiles=new MachineProfileVault(root.resolve("real-profiles"));var vault=new LinkDsGroupVault(root.resolve("real-group"))){
            long size=Files.size(source);var status=vault.begin(ACTOR,KEY,33,size,Hashing.sha256(source),NOW);try(var in=Files.newInputStream(source)){byte[] bytes=new byte[24576];long offset=0;for(int n;(n=in.read(bytes))!=-1;){vault.append(ACTOR,status.uploadId(),offset,Arrays.copyOf(bytes,n),NOW);offset+=n;}}
            var stage=vault.finish(ACTOR,status.uploadId(),NOW);check(stage.endpoints().stream().allMatch(e->e.size()==19219847),"real 19 MiB native MXS1 endpoints streamed");var barriers=singles(stage,states,profiles);var snapshot=vault.commit(ACTOR,status.uploadId(),barriers,states,profiles,NOW);check(readHash(vault,snapshot).equals(Hashing.sha256(source)),"real native DNL1 atomically stored and streamed unchanged");
        }}
        System.out.println("DS group vault checks="+checks);
    }
}
