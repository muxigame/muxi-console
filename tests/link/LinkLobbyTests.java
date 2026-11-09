package net.muxigame.console.tests.link;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.muxigame.console.domain.*;

/** Consent/revocation, actual transport bounds and machine/group checkpoint barrier tests. */
public final class LinkLobbyTests {
    private static int checks;
    private static void check(boolean condition,String name){checks++;if(!condition)throw new AssertionError(name);}
    @FunctionalInterface private interface Action{void run()throws Exception;}
    private static void rejects(Action action,String name)throws Exception{try{action.run();throw new AssertionError(name);}catch(IOException|DomainException expected){checks++;}}
    private static LinkLobby.Endpoint endpoint(String platform){return new LinkLobby.Endpoint(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),platform);}
    private static byte[] single(int data)throws IOException {var b=new ByteArrayOutputStream();var out=new DataOutputStream(b);out.writeInt(0x4d585331);for(byte[] field:List.of(new byte[]{(byte)data},new byte[]{4,5},new byte[]{6})){out.writeInt(field.length);out.write(field);}return b.toByteArray();}
    private static void text(DataOutputStream out,String text)throws IOException {byte[] bytes=text.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    private static byte[] group(List<LinkLobby.Endpoint> endpoints,String bridge,byte[][] states)throws IOException {var b=new ByteArrayOutputStream();var out=new DataOutputStream(b);out.writeInt(0x4c4e4b31);out.writeShort(1);text(out,bridge);for(int i=0;i<2;i++){var e=endpoints.get(i);text(out,e.machine().toString());text(out,e.platform());text(out,e.content());out.writeInt(states[i].length);out.write(states[i]);}out.writeByte(3);return b.toByteArray();}
    private static byte[] gbaGroup(List<LinkLobby.Endpoint> endpoints,String bridge,byte[][] states,String backend)throws IOException{var b=new ByteArrayOutputStream();var out=new DataOutputStream(b);out.writeInt(0x4c4e4b31);out.writeShort(2);text(out,backend);text(out,bridge);for(int i=0;i<2;i++){var e=endpoints.get(i);text(out,e.machine().toString());text(out,e.platform());text(out,e.content());out.writeInt(states[i].length);out.write(states[i]);}var nativeBytes=new ByteArrayOutputStream();var nativeOut=new DataOutputStream(nativeBytes);nativeOut.writeInt(0x4d474c31);for(byte[] raw:List.of(new byte[]{1},new byte[]{2})){nativeOut.writeInt(raw.length);nativeOut.write(raw);}out.writeInt(nativeBytes.size());out.write(nativeBytes.toByteArray());return b.toByteArray();}
    private static void save(MachineStateVault vault,UUID actor,MachineSaveKey key,String fp,byte[] bytes)throws IOException{var status=vault.begin(actor,key,fp,8,bytes.length,Hashing.sha256(bytes),1);vault.append(actor,status.uploadId(),0,bytes,2);vault.commit(actor,status.uploadId(),3);}
    public static void main(String[] args)throws Exception {
        var a=endpoint("gb");var b=endpoint("gbc");var lobby=new LinkLobby(a,b,100);UUID id=lobby.id();long invite=lobby.epoch();
        check(!lobby.locked(),"invitation has no machine lock");check(!lobby.mayDownload(a.actor(),id,invite,b.content()),"invitation grants no partner ROM");
        rejects(()->lobby.accept(a.actor(),id,invite,101),"host cannot self-consent");
        rejects(()->lobby.accept(UUID.randomUUID(),id,invite,101),"outsider cannot consent");
        lobby.accept(b.actor(),id,invite,101);long prepare=lobby.epoch();check(lobby.locked(),"acceptance locks preparation");
        rejects(()->lobby.require(a.actor(),id,invite),"old invitation epoch revoked");
        check(!lobby.mayDownload(a.actor(),id,prepare,b.content()),"prepare grants no ROM before old saves leave");
        var changed=new LinkLobby.Endpoint(a.actor(),UUID.randomUUID(),a.card(),a.content(),a.platform());
        rejects(()->lobby.prepared(changed,id,prepare,102),"changed physical machine rejected");
        check(!lobby.prepared(a,id,prepare,102,true),"A can prepare first while B is still saving");
        rejects(()->lobby.prepared(b,id,prepare,102,false),"B cannot prepare before its own ordinary checkpoint leaves");
        check(lobby.prepared(b,id,prepare,103,true),"B later exits and both actual machines start without resending A");long run=lobby.epoch();
        check(lobby.mayDownload(a.actor(),id,run,b.content()),"accepted host may download partner");check(!lobby.mayDownload(b.actor(),id,run,a.content()),"partner does not download host ROM");
        check(!lobby.mayDownload(a.actor(),UUID.randomUUID(),run,a.content()),"other link id denied");check(!lobby.mayDownload(a.actor(),id,prepare,a.content()),"old preparation revoked");
        lobby.closing(104);check(lobby.locked(),"closing retains machine lock until final barrier");check(!lobby.mayDownload(a.actor(),id,run,a.content()),"closing immediately revokes old grant");
        rejects(()->lobby.require(a.actor(),id,run),"in-flight old frame revoked");lobby.close();check(!lobby.locked(),"closed releases lock");
        rejects(()->new LinkLobby(a,new LinkLobby.Endpoint(b.actor(),a.machine(),b.card(),b.content(),b.platform()),0),"same machine cannot link itself");
        var late=new LinkLobby(a,b,0);rejects(()->late.accept(b.actor(),late.id(),late.epoch(),30_001),"expired consent rejected");
        rejects(()->new LinkLobby(a,endpoint("gba"),0),"GB serial family cannot join a GBA cable coordinator");
        var gba=new LinkLobby(endpoint("gba"),endpoint("gba"),0);check(gba.endpoints().size()==2,"two real GBA machines use a distinct compatible backend");
        var dsLeft=endpoint("nds");var dsRight=endpoint("nds");var ds=new LinkLobby(dsLeft,dsRight,0);check(ds.endpoints().size()==2,"two actual NDS machines can negotiate their wireless backend");
        rejects(()->new LinkLobby(dsLeft,endpoint("gba"),0),"NDS wireless cannot silently substitute a GBA cable");
        ds.accept(dsRight.actor(),ds.id(),ds.epoch(),1);ds.prepared(dsLeft,ds.id(),ds.epoch(),2,true);ds.prepared(dsRight,ds.id(),ds.epoch(),3,true);ds.closing(4,300000);check(ds.deadline()==300004&&ds.locked(),"large wireless group checkpoint retains its explicit bounded closing lease");

        byte[] picture=new byte[LinkMedia.RGBA_BYTES];new Random(7).nextBytes(picture);byte[] encoded=LinkMedia.encode(picture);check(Arrays.equals(picture,LinkMedia.decode(encoded)),"lossless high-entropy picture");
        var assembler=new LinkMedia.Assembler();byte[] full=null;
        for(int off=0;off<encoded.length;off+=LinkMedia.PART){full=assembler.accept(7,off,encoded.length,Arrays.copyOfRange(encoded,off,Math.min(off+LinkMedia.PART,encoded.length)),100);check(off+LinkMedia.PART>=encoded.length?full!=null:full==null,"ordered fragmentation");}
        check(Arrays.equals(picture,LinkMedia.decode(full)),"assembled exact picture");check(assembler.accept(6,0,10,new byte[]{1},101)==null,"stale video ignored");
        rejects(()->LinkMedia.decode(Arrays.copyOf(encoded,encoded.length-1)),"truncated compressed stream rejected");
        byte[] trailing=Arrays.copyOf(encoded,encoded.length+1);rejects(()->LinkMedia.decode(trailing),"trailing compressed stream rejected");
        rejects(()->assembler.accept(8,0,LinkMedia.MAX_COMPRESSED+1,new byte[]{1},102),"allocation bound checked before allocation");
        short[] pcm={Short.MIN_VALUE,-1,0,Short.MAX_VALUE};check(Arrays.equals(pcm,LinkMedia.pcm(LinkMedia.pcm(pcm))),"signed PCM exact independent pairs");
        rejects(()->LinkMedia.pcm(new byte[3]),"non-stereo PCM rejected");
        byte[] gbaPixels=new byte[240*160*4];new Random(9).nextBytes(gbaPixels);byte[] gbaEncoded=LinkMedia.encode(gbaPixels,240,160);
        check(Arrays.equals(gbaPixels,LinkMedia.decode(gbaEncoded,240,160)),"GBA 240x160 pixels remain lossless without GB crop or stretch");
        rejects(()->LinkMedia.decode(gbaEncoded),"GB decoder cannot silently accept larger GBA geometry");
        var gbaAssembler=new LinkMedia.Assembler(240,160);byte[] gbaFull=null;for(int off=0;off<gbaEncoded.length;off+=LinkMedia.PART)gbaFull=gbaAssembler.accept(3,off,gbaEncoded.length,Arrays.copyOfRange(gbaEncoded,off,Math.min(off+LinkMedia.PART,gbaEncoded.length)),100);
        check(Arrays.equals(gbaPixels,LinkMedia.decode(gbaFull,240,160)),"GBA fragments preserve the full actual hardware surface");
        rejects(()->new LinkMedia.Assembler(2048,2048),"unnegotiated wireless geometry cannot allocate an arbitrary image");
        byte[] dsPixels=new byte[256*384*4];new Random(10).nextBytes(dsPixels);byte[] dsEncoded=LinkMedia.encode(dsPixels,256,384);var dsAssembler=new LinkMedia.Assembler(256,384);byte[] dsFull=null;for(int off=0;off<dsEncoded.length;off+=LinkMedia.PART)dsFull=dsAssembler.accept(5,off,dsEncoded.length,Arrays.copyOfRange(dsEncoded,off,Math.min(off+LinkMedia.PART,dsEncoded.length)),100);
        check(Arrays.equals(dsPixels,LinkMedia.decode(dsFull,256,384)),"two NDS surfaces cross the real fragment path without cropping the lower screen");

        var threeLeft=endpoint("n3ds");var threeRight=endpoint("n3ds");var threeRoom=new LinkLobby(threeLeft,threeRight,10);
        rejects(()->new LinkLobby(threeLeft,endpoint("nds"),10),"3DS private Room does not masquerade as DS wireless");
        check(!threeRoom.mayDownload(threeLeft.actor(),threeRoom.id(),threeRoom.epoch(),threeRight.content()),"3DS invitation alone grants no partner ROM");
        threeRoom.accept(threeRight.actor(),threeRoom.id(),threeRoom.epoch(),11);check(!threeRoom.prepared(threeLeft,threeRoom.id(),threeRoom.epoch(),12),"3DS waits for both actual machines to save and leave ordinary play");
        check(threeRoom.prepared(threeRight,threeRoom.id(),threeRoom.epoch(),13)&&threeRoom.mayDownload(threeLeft.actor(),threeRoom.id(),threeRoom.epoch(),threeRight.content()),"3DS host gets partner content only after explicit consent and both preparation barriers");
        check(!threeRoom.mayDownload(threeRight.actor(),threeRoom.id(),threeRoom.epoch(),threeLeft.content()),"3DS guest never receives host ROM authority");
        byte[] threePixels=new byte[400*480*4];new Random(11).nextBytes(threePixels);byte[] threeEncoded=LinkMedia.encode(threePixels,400,480);var threeAssembler=new LinkMedia.Assembler(400,480);byte[] threeFull=null;
        for(int off=0;off<threeEncoded.length;off+=LinkMedia.PART)threeFull=threeAssembler.accept(8,off,threeEncoded.length,Arrays.copyOfRange(threeEncoded,off,Math.min(off+LinkMedia.PART,threeEncoded.length)),100);
        check(Arrays.equals(threePixels,LinkMedia.decode(threeFull,400,480)),"3DS 400x240 top and centered 320x240 bottom fit the actual 400x480 framebuffer without cropping");
        rejects(()->LinkStateStore.exports(group(threeRoom.endpoints(),"b".repeat(64),new byte[][]{single(1),single(2)}),threeRoom.endpoints(),"b".repeat(64)),"3DS ordinary checkpoints cannot enter the cable LNK1 group channel");

        Path temp=Files.createTempDirectory("muxi-link-tests-");String bridge="b".repeat(64),fp="c".repeat(64);List<LinkLobby.Endpoint> endpoints=new LinkLobby(a,b,0).endpoints();byte[][] singles={single(1),single(2)};byte[] state=group(endpoints,bridge,singles);
        try(MachineStateVault vault=new MachineStateVault(temp.resolve("states"))){
            LinkStateStore groups=new LinkStateStore(temp.resolve("link"));check(groups.latest(endpoints,bridge,vault).isEmpty(),"no fabricated link state");
            rejects(()->groups.publish(endpoints,bridge,9,state,vault),"group cannot publish before machine barrier");
            save(vault,a.actor(),endpoints.get(0).key(),fp,singles[0]);rejects(()->groups.publish(endpoints,bridge,9,state,vault),"partial machine barrier cannot publish group");
            save(vault,a.actor(),endpoints.get(1).key(),fp,singles[1]);var saved=groups.publish(endpoints,bridge,9,state,vault);check(saved.hash().equals(Hashing.sha256(state)),"group checksum exact");
            check(Arrays.equals(groups.latest(endpoints,bridge,vault).orElseThrow().state(),state),"cold group load exact");
            rejects(()->LinkStateStore.exports(state,endpoints,"d".repeat(64)),"different actual bridge cannot restore");
            List<LinkLobby.Endpoint> swapped=List.of(endpoints.get(1),endpoints.get(0));rejects(()->LinkStateStore.exports(state,swapped,bridge),"wrong endpoint order cannot restore");
            save(vault,a.actor(),endpoints.get(0).key(),fp,single(8));check(groups.latest(endpoints,bridge,vault).isEmpty(),"subsequent single-machine progress invalidates older cable group");
            check(Arrays.equals(vault.read(endpoints.get(1).key(),vault.latest(endpoints.get(1).key()).orElseThrow().generationID(),0,1024),singles[1]),"other endpoint state retained");
            List<LinkLobby.Endpoint> gbaEndpoints=gba.endpoints();byte[] gbaState=gbaGroup(gbaEndpoints,bridge,singles,"mgba");
            check(LinkStateStore.exports(gbaState,gbaEndpoints,bridge).size()==2,"GBA group exposes two independent ordinary machine exports");
            rejects(()->LinkStateStore.exports(gbaGroup(gbaEndpoints,bridge,singles,"other"),gbaEndpoints,bridge),"GBA backend identity cannot be substituted despite identical outer magic and hash");
            rejects(()->LinkStateStore.exports(group(gbaEndpoints,bridge,singles),gbaEndpoints,bridge),"GB version1 cannot be misread as GBA coordinator state");
            for(int i=0;i<2;i++)save(vault,a.actor(),gbaEndpoints.get(i).key(),fp,singles[i]);
            groups.publish(gbaEndpoints,bridge,10,gbaState,vault);check(Arrays.equals(groups.latest(gbaEndpoints,bridge,vault).orElseThrow().state(),gbaState),"GBA version2 cold barrier remains separate while GB version1 is still readable");
        }finally{try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
        System.out.println("LinkLobbyTests "+checks+" checks passed");
    }
}
