package net.muxigame.console.domain;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Machine/ROM ownership, restart, global quota and foreign-generation rejection; no MC or ROM execution. */
public final class MachineStateVaultTest {
    private static int checks;
    private static final UUID ACTOR=UUID.randomUUID(),OTHER=UUID.randomUUID(),A=UUID.randomUUID(),B=UUID.randomUUID();
    private static final String GAME="12".repeat(32),SECOND="34".repeat(32),FP="ab".repeat(32);
    private static final MachineSaveKey AX=new MachineSaveKey(A,GAME),AY=new MachineSaveKey(A,SECOND),BX=new MachineSaveKey(B,GAME);
    @FunctionalInterface private interface Work{void run()throws Exception;}
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static void denied(String code,Work work)throws Exception{try{work.run();throw new AssertionError("Expected "+code);}catch(DomainException e){check(e.code().equals(code),"Correct rejection: "+code);}}
    private static void ioDenied(Work work)throws Exception{try{work.run();throw new AssertionError("Expected fail-closed metadata rejection");}catch(IOException e){checks++;}}
    private static byte[] bytes(int seed){byte[] data=new byte[64];new Random(seed).nextBytes(data);return data;}
    private static MachineStateVault.Snapshot save(MachineStateVault vault,MachineSaveKey key,byte[] data,long now)throws Exception{
        var upload=vault.begin(ACTOR,key,FP,300,data.length,Hashing.sha256(data),now);
        for(int offset=0;offset<data.length;offset+=24)vault.append(ACTOR,upload.uploadId(),offset,Arrays.copyOfRange(data,offset,Math.min(offset+24,data.length)),now);
        return vault.commit(ACTOR,upload.uploadId(),now);
    }
    private static byte[] read(MachineStateVault vault,MachineStateVault.Snapshot snapshot)throws Exception{
        var out=new ByteArrayOutputStream();for(int offset=0;offset<snapshot.size();offset+=24)out.write(vault.read(snapshot.key(),snapshot.generationID(),offset,24));return out.toByteArray();
    }
    private static void ownership(Path root)throws Exception{
        long now=System.currentTimeMillis();byte[] one=bytes(1),two=bytes(2),three=bytes(3);MachineStateVault.Snapshot first,other,game;
        try(var vault=new MachineStateVault(root)){
            check(vault.latest(AX).isEmpty()&&vault.latest(BX).isEmpty(),"New machines start without another machine's progress");
            first=save(vault,AX,one,now);check(first.machineUUID().equals(A)&&first.contentSHA().equals(GAME),"Checkpoint reports full machine and ROM identity");
            check(vault.latest(new MachineSaveKey(A,GAME)).orElseThrow().equals(first),"Another cartridge with the same content uses the same machine checkpoint");
            check(vault.latest(BX).isEmpty()&&vault.latest(AY).isEmpty(),"Borrowed game and different game do not inherit progress");
            other=save(vault,BX,two,now);game=save(vault,AY,three,now);
            check(Arrays.equals(read(vault,first),one)&&Arrays.equals(read(vault,other),two)&&Arrays.equals(read(vault,game),three),"Distinct machines and games retain independent bytes");
            denied("save_missing",()->vault.read(BX,first.generationID(),0,24));denied("save_missing",()->vault.read(AY,first.generationID(),0,24));
            var pending=vault.begin(ACTOR,AX,FP,301,64,Hashing.sha256(one),now);
            denied("save_active",()->vault.begin(OTHER,AX,FP,301,64,Hashing.sha256(one),now));
            denied("save_active",()->vault.begin(ACTOR,AY,FP,301,64,Hashing.sha256(one),now));
            denied("save_session",()->vault.append(OTHER,pending.uploadId(),0,one,now));
            vault.abort(ACTOR,pending.uploadId());
            var empty=vault.begin(ACTOR,AX,FP,301,64,Hashing.sha256(one),now);denied("save_incomplete",()->vault.commit(ACTOR,empty.uploadId(),now));
            check(vault.latest(AX).orElseThrow().equals(first),"Incomplete upload never replaces an existing machine head");
            var retry=vault.begin(ACTOR,AX,FP,301,64,Hashing.sha256(one),now);vault.abort(ACTOR,retry.uploadId());
        }
        try(var vault=new MachineStateVault(root)){
            check(vault.latest(AX).orElseThrow().equals(first)&&vault.latest(BX).orElseThrow().equals(other)&&vault.latest(AY).orElseThrow().equals(game),"Restart preserves every machine/game head");
            check(Arrays.equals(read(vault,first),one),"Restart verifies and returns actual bytes");
        }
    }
    private static void quota(Path root)throws Exception{
        long now=System.currentTimeMillis();byte[] data=bytes(4);var limits=new StateVault.Limits(3000,1024,24,10,100);
        try(var vault=new MachineStateVault(root,limits)){
            save(vault,AX,data,now);var pending=vault.begin(ACTOR,AY,FP,0,64,Hashing.sha256(data),now);
            denied("save_quota",()->vault.begin(OTHER,BX,FP,0,64,Hashing.sha256(data),now));
            vault.expire(now+11);var next=vault.begin(OTHER,BX,FP,0,64,Hashing.sha256(data),now+11);vault.abort(OTHER,next.uploadId());
            denied("save_session",()->vault.append(ACTOR,pending.uploadId(),0,new byte[1],now+11));
            save(vault,AY,data,now+11);denied("save_quota",()->vault.begin(OTHER,BX,FP,0,64,Hashing.sha256(data),now+11));
            check(vault.latest(AX).isPresent()&&vault.latest(AY).isPresent(),"Shared quota keeps committed machine checkpoints");
        }
    }
    private static void legacyAndNamespace(Path root)throws Exception{
        long now=System.currentTimeMillis();UUID card=UUID.randomUUID();byte[] data=bytes(5);String legacyHash;
        try(var old=new StateVault(root)){
            var upload=old.begin(ACTOR,card,FP,0,64,Hashing.sha256(data),now);for(int offset=0;offset<64;offset+=24)old.append(ACTOR,upload.uploadId(),offset,Arrays.copyOfRange(data,offset,Math.min(offset+24,64)),now);old.commit(ACTOR,upload.uploadId(),now);
        }
        Path oldHead=root.resolve("cards").resolve(card.toString()).resolve("head");legacyHash=Hashing.sha256(oldHead);
        try(var vault=new MachineStateVault(root.resolve("machines"))){check(vault.latest(AX).isEmpty(),"An old card checkpoint is never implicitly imported");save(vault,AX,data,now);}
        check(Hashing.sha256(oldHead).equals(legacyHash),"Opening/saving new machines leaves the legacy card head untouched");
        Path good=root.resolve("machines").resolve(A.toString()).resolve(GAME),bad=root.resolve("foreign").resolve(A.toString()).resolve(SECOND);
        try(var walk=Files.walk(good)){for(Path p:walk.toList()){Path target=bad.resolve(good.relativize(p));if(Files.isDirectory(p))Files.createDirectories(target);else Files.copy(p,target);}}
        ioDenied(()->{try(var ignored=new MachineStateVault(root.resolve("foreign"))){throw new AssertionError("Another game accepted foreign generation metadata");}});
        try(var vault=new MachineStateVault(root.resolve("machines"))){check(vault.latest(AX).isPresent(),"Rejected foreign namespace does not damage original progress or leak its lock");}
    }
    public static void main(String[] args)throws Exception{
        Path base=Path.of(args.length==0?"build/test-data":args[0]).toAbsolutePath().normalize();Files.createDirectories(base);Path root=Files.createTempDirectory(base,"machine-vault-");
        try{denied("save_identity",()->new MachineSaveKey(null,GAME));denied("save_hash",()->new MachineSaveKey(A,"../"+GAME));ownership(root.resolve("ownership"));quota(root.resolve("quota"));legacyAndNamespace(root.resolve("legacy"));System.out.println("MachineStateVaultTest "+checks+" checks passed");}
        finally{try(var walk=Files.walk(root)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList()){if(!p.toAbsolutePath().normalize().startsWith(root))throw new IOException("Cleanup escaped owned test root");Files.delete(p);}}}
    }
}
