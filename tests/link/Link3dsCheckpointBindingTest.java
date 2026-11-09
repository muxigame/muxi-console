package net.muxigame.console.tests.link;

import java.nio.file.*;
import java.util.*;
import net.muxigame.console.domain.*;

/** Real world-vault generations, including equal-body replacements and reopening. */
public final class Link3dsCheckpointBindingTest {
    private static int checks;
    private static void check(boolean good,String text){checks++;if(!good)throw new AssertionError(text);}
    private static final byte[] BODY={1,2,3,4};
    private static MachineProfileVault.Snapshot profile(MachineProfileVault vault,UUID actor,MachineProfileVault.Key key)throws Exception{
        long now=System.currentTimeMillis();var previous=vault.latest(key).map(MachineProfileVault.Snapshot::generation).orElse(null);
        var upload=vault.begin(actor,key,previous,20,BODY.length,Hashing.sha256(BODY),now);vault.append(actor,upload.uploadId(),0,BODY,now);return vault.commit(actor,upload.uploadId(),now);
    }
    private static MachineStateVault.Snapshot state(MachineStateVault vault,UUID actor,MachineSaveKey key,String fp,UUID profile)throws Exception{
        long now=System.currentTimeMillis();var upload=vault.begin(actor,key,fp,20,BODY.length,Hashing.sha256(BODY),now,profile);vault.append(actor,upload.uploadId(),0,BODY,now);return vault.commit(actor,upload.uploadId(),now);
    }
    public static void main(String[] args)throws Exception{
        Path base=Files.createTempDirectory(Files.createDirectories(Path.of(args[0])),"link3ds-binding-").toRealPath();UUID actor=UUID.randomUUID(),machine=UUID.randomUUID();String fp="31".repeat(32);MachineSaveKey game=new MachineSaveKey(machine,"41".repeat(32));var key=new MachineProfileVault.Key(machine,Platform.N3DS);
        MachineStateVault.Snapshot finalState;MachineProfileVault.Snapshot finalProfile;
        try{
            try(var states=new MachineStateVault(base.resolve("states"));var profiles=new MachineProfileVault(base.resolve("profiles"))){
                check(!Link3dsCheckpointBinding.current(states,profiles,game,fp,null,null),"absent artifacts are not a checkpoint");
                var p=profile(profiles,actor,key);var s=state(states,actor,game,fp,p.generation());
                check(Link3dsCheckpointBinding.current(states,profiles,game,fp,s,p),"matching real ordinary state and native profile are accepted");
                check(!Link3dsCheckpointBinding.current(states,profiles,game,"32".repeat(32),s,p),"full core identity is required");
                check(!Link3dsCheckpointBinding.current(states,profiles,new MachineSaveKey(machine,"42".repeat(32)),fp,s,p),"same machine other game cannot borrow the state");
                check(!Link3dsCheckpointBinding.current(states,profiles,new MachineSaveKey(UUID.randomUUID(),game.contentSHA()),fp,s,p),"same game other machine cannot borrow either artifact");
                var wii=profile(profiles,actor,new MachineProfileVault.Key(machine,Platform.WII));
                check(!Link3dsCheckpointBinding.current(states,profiles,game,fp,s,wii),"another platform filesystem is not a 3DS binding");
                var unbound=state(states,actor,game,fp,null);
                check(!Link3dsCheckpointBinding.current(states,profiles,game,fp,unbound,p),"unbound legacy CPU cannot pretend to bind to native files");
                var next=profile(profiles,actor,key);
                check(next.hash().equals(p.hash())&&!next.generation().equals(p.generation()),"identical native bytes still have a distinct committed generation");
                check(!Link3dsCheckpointBinding.current(states,profiles,game,fp,s,p),"replaced native generation invalidates the prior pair");
                check(!Link3dsCheckpointBinding.current(states,profiles,game,fp,s,next),"new native files cannot be paired with the old CPU");
                var bound=state(states,actor,game,fp,next.generation());
                check(Link3dsCheckpointBinding.current(states,profiles,game,fp,bound,next),"new CPU binds to the real new native generation");
                finalState=state(states,actor,game,fp,next.generation());finalProfile=next;
                check(finalState.hash().equals(bound.hash())&&!finalState.generationID().equals(bound.generationID()),"identical CPU bytes still get a distinct generation");
                check(!Link3dsCheckpointBinding.current(states,profiles,game,fp,bound,next),"a stale same-body CPU receipt is rejected");
                check(Link3dsCheckpointBinding.current(states,profiles,game,fp,finalState,next),"only the latest exact receipt is current");
            }
            try(var states=new MachineStateVault(base.resolve("states"));var profiles=new MachineProfileVault(base.resolve("profiles"))){
                check(Link3dsCheckpointBinding.current(states,profiles,game,fp,finalState,finalProfile),"actual pair binding survives world vault reopening");
                check(Arrays.equals(states.read(game,finalState.generationID(),0,BODY.length),BODY),"binding does not rewrite the CPU body");
                check(Arrays.equals(profiles.read(key,finalProfile.generation(),0,BODY.length),BODY),"binding does not rewrite native files");
            }
        }finally{
            if(!base.getParent().equals(Path.of(args[0]).toRealPath()))throw new AssertionError("temporary test root moved");
            try(var files=Files.walk(base)){for(Path file:files.sorted(Comparator.reverseOrder()).toList()){if(Files.isSymbolicLink(file))throw new AssertionError("unexpected temporary link");Files.delete(file);}}
        }
        System.out.println("Link3dsCheckpointBindingTest "+checks+" checks passed");
    }
}
