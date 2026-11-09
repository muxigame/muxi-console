package net.muxigame.console.tests.link;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import net.muxigame.console.runtime.*;

/** Cold restores an actual DS CPU/frontend/transport barrier, including a real pending wireless packet. */
public final class LinkDsGroupSmoke {
    private static int checks;
    private static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    private static String sha(Path file)throws Exception{var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))>=0;)if(n>0)digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());}
    private static int peerSequence(AvFrame frame){byte[] rgba=frame.rgba();int value=0;for(int bit=0;bit<16;bit++){int at=(16*256+36+bit*8)*4;if((rgba[at+1]&255)>200&&(rgba[at]&255)<32)value|=1<<bit;}return value;}
    private static int[] pending(Path group)throws Exception{
        int[] counts={0,0,0,0,-1,-1};try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(group)))){
            if(in.readInt()!=0x444e4c31||in.readUnsignedShort()!=1)throw new IOException();in.readUTF();in.readUTF();in.readLong();
            for(int i=0;i<2;i++){in.readUTF();in.readUTF();in.readUTF();long size=in.readLong();in.readUTF();if(in.readInt()!=0x44534531||in.readUnsignedShort()!=1)throw new IOException();in.readLong();long core=in.readLong();in.skipNBytes(core);int mpLength=in.readInt();byte[] mp=in.readNBytes(mpLength);int transportLength=in.readInt();byte[] transport=in.readNBytes(transportLength);if(size!=22+core+4+mpLength+4+transportLength)throw new IOException();counts[i*2]=ByteBuffer.wrap(mp).getInt(14);counts[i*2+1]=ByteBuffer.wrap(transport).getInt(4);counts[4+i]=mp[11]==0?-1:Short.toUnsignedInt(ByteBuffer.wrap(mp).getShort(12));}
        }return counts;
    }
    private static List<String> queueHashes(Path group)throws Exception{
        List<String> result=new ArrayList<>();try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(group)))){in.readInt();in.readUnsignedShort();in.readUTF();in.readUTF();in.readLong();for(int i=0;i<2;i++){in.readUTF();in.readUTF();in.readUTF();in.readLong();in.readUTF();in.readInt();in.readUnsignedShort();in.readLong();in.skipNBytes(in.readLong());for(int field=0;field<2;field++){int length=in.readInt();byte[] bytes=in.readNBytes(length);result.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));}}}return result;
    }
    @FunctionalInterface private interface Action{void run()throws Exception;}
    private static void rejects(Action action,String text)throws Exception{boolean rejected=false;try{action.run();}catch(IOException expected){rejected=true;}check(rejected,text);}
    public static void main(String[] args)throws Exception{
        if(args.length!=7)throw new IllegalArgumentException("java core exactSHA originalLeft originalRight output frontendQueued");boolean frontendQueued=Boolean.parseBoolean(args[6]);Path java=Path.of(args[0]),core=Path.of(args[1]),left=Path.of(args[3]),right=Path.of(args[4]),output=Path.of(args[5]);Files.createDirectories(output);List<Path> cp=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator)).map(Path::of).toList();List<LinkDsRuntime.Endpoint> endpoints=new ArrayList<>();
        for(int i=0;i<2;i++){Path rom=i==0?left:right,save=Files.createDirectories(output.resolve("machine-"+i));endpoints.add(new LinkDsRuntime.Endpoint(new UUID(0,i+1),sha(rom),new LaunchConfig(java,cp,core,rom,Files.createDirectories(save.resolve("system")),save,Map.of("melonds_boot_mode","direct"),"nds")));}
        var config=new LinkDsRuntime.Config(args[2],endpoints);LinkDsRuntime runtime=LinkDsRuntime.open(config);Path group=null;int[] queues=null;long savedFrame=-1;int[] beforeSequence=new int[2];List<Long> firstPids=runtime.workerPids();
        try{
            LinkRuntime.Frame frame=null;for(int i=0;i<32;i++)frame=runtime.step(PortInput.IDLE,PortInput.IDLE);
            for(int attempt=0;attempt<48;attempt++){
                frame=runtime.step(PortInput.IDLE,PortInput.IDLE);Path candidate=output.resolve("candidate-"+attempt+".dnl1");runtime.saveLinkStateTo(candidate,frontendQueued);queues=pending(candidate);
                if((frontendQueued?queues[0]+queues[2]:Arrays.stream(queues,0,4).sum())>0){group=candidate;savedFrame=frame.number()+1;beforeSequence[0]=peerSequence(frame.left());beforeSequence[1]=peerSequence(frame.right());break;}Files.delete(candidate);
            }
            check(group!=null,"an actual radio packet is pending at the captured CPU/MP/transport barrier");check(Files.size(group)>16*1024*1024,"DS group uses its independent file capacity rather than GB LNK1 limit");
            if(frontendQueued){check(queues[0]+queues[2]>0,"a real undelivered radio packet resides inside native MpState, not only Java transport");check(queues[4]>=0||queues[5]>=0,"actual hardware CMD traffic establishes a captured frontend host id");}
            for(int i=0;i<12;i++)runtime.step(new PortInput(256),PortInput.IDLE);
        }finally{runtime.close();check(runtime.naturalExit(),"original two workers naturally close before cold restore");}
        LinkDsRuntime cold=LinkDsRuntime.open(config);List<Long> coldPids=cold.workerPids();int[] afterSequence=new int[2];LinkDsRuntime.NetworkStats[] stats=new LinkDsRuntime.NetworkStats[2];
        try{
            check(Collections.disjoint(firstPids,coldPids),"cold group runs in two new OS processes");Path actualGroup=group;
            rejects(()->cold.restoreEndpointFrom(0,actualGroup),"DNL1 cannot be mistaken for an individual MXS1 even before execution");
            Path wrong=output.resolve("wrong-core.dnl1");Files.copy(group,wrong);try(var file=new RandomAccessFile(wrong.toFile(),"rw")){file.readInt();file.readUnsignedShort();file.readUTF();int length=file.readUnsignedShort();if(length!=64)throw new IOException();long at=file.getFilePointer();int value=file.readUnsignedByte();file.seek(at);file.writeByte(value=='a'?'b':'a');}
            rejects(()->cold.loadLinkStateFrom(wrong),"different actual native bridge fingerprint cannot enter either CPU");
            cold.loadLinkStateFrom(group);Path untouched=output.resolve("restored-before-first-step.dnl1");cold.saveLinkStateTo(untouched);
            check(queueHashes(group).equals(queueHashes(untouched)),"native MpState queue, host id, timeout fields and transport queue restore byte-exactly before any CPU can reestablish them");
            LinkRuntime.Frame frame=cold.step(PortInput.IDLE,PortInput.IDLE);check(frame.number()==savedFrame,"both restored frontend frame counters resume the committed barrier");
            for(int i=0;i<48;i++)frame=cold.step(PortInput.IDLE,PortInput.IDLE);
            afterSequence[0]=peerSequence(frame.left());afterSequence[1]=peerSequence(frame.right());for(int i=0;i<2;i++)stats[i]=cold.networkStats(i);
            check(afterSequence[0]>beforeSequence[0]&&afterSequence[1]>beforeSequence[1],"both actual receiving ARM7 CPUs recognize newer peer sequences after cold restore");
            check(stats[0].sentPackets()>0&&stats[0].receivedPackets()>0&&stats[1].sentPackets()>0&&stats[1].receivedPackets()>0,"real MAC packets continue in both directions after restoring native MpState and pending transport");
            Path next=output.resolve("cold-next.dnl1");cold.saveLinkStateTo(next);check(Files.size(next)>0,"continued cold pair can commit another complete barrier");
        }finally{cold.close();check(cold.naturalExit(),"restored workers naturally close with private native cleanup");}
        String result="{\"checks\":"+checks+",\"coreSHA256\":\""+args[2]+"\",\"groupSHA256\":\""+sha(group)+"\",\"groupBytes\":"+Files.size(group)+",\"pendingCounts_MP_transport\":"+Arrays.toString(Arrays.copyOf(queues,4))+",\"frontendHostIds\":"+Arrays.toString(Arrays.copyOfRange(queues,4,6))+",\"beforePeerSequences\":"+Arrays.toString(beforeSequence)+",\"afterPeerSequences\":"+Arrays.toString(afterSequence)+",\"firstWorkers\":"+firstPids+",\"coldWorkers\":"+coldPids+",\"naturalWorkers\":4}";Files.writeString(output.resolve("result.json"),result);System.out.println(result);
    }
}
