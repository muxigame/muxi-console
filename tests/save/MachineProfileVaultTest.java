package net.muxigame.console.tests.save;

import java.nio.file.*;
import java.util.*;
import net.muxigame.console.domain.*;

/** Native files belong to the machine/platform, independently of the ROM-specific quick states. */
public final class MachineProfileVaultTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    @FunctionalInterface private interface Attempt{void run()throws Exception;}
    private static void denied(Attempt attempt)throws Exception{try{attempt.run();throw new AssertionError("Invalid profile transaction accepted");}catch(DomainException expected){checks++;}}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory(Files.createDirectories(Path.of(args[0])),"machine-profile-");UUID actor=UUID.randomUUID(),other=UUID.randomUUID(),machine=UUID.randomUUID();var key=new MachineProfileVault.Key(machine,Platform.N3DS);byte[] data=new byte[7000];new Random(81).nextBytes(data);long now=System.currentTimeMillis();UUID generation;
        try(MachineProfileVault vault=new MachineProfileVault(root)){
            check(vault.latest(key).isEmpty(),"new machine starts without native profile");var upload=vault.begin(actor,key,null,99,data.length,Hashing.sha256(data),now);
            denied(()->vault.append(other,upload.uploadId(),0,data,now));denied(()->vault.append(actor,upload.uploadId(),1,data,now));
            vault.append(actor,upload.uploadId(),0,data,now);var saved=vault.commit(actor,upload.uploadId(),now);generation=saved.generation();check(saved.key().equals(key),"commit retains machine/platform identity");check(Arrays.equals(vault.read(key,generation,0,data.length),data),"streamed profile is readable");
            check(vault.latest(new MachineProfileVault.Key(UUID.randomUUID(),Platform.N3DS)).isEmpty(),"another machine does not inherit its filesystem");check(vault.latest(new MachineProfileVault.Key(machine,Platform.WII)).isEmpty(),"Wii and 3DS native files remain separate");
            denied(()->vault.begin(actor,key,null,100,data.length,Hashing.sha256(data),now));
            var newer=vault.begin(actor,key,generation,100,data.length,Hashing.sha256(data),now);vault.append(actor,newer.uploadId(),0,Arrays.copyOf(data,100),now);denied(()->vault.commit(actor,newer.uploadId(),now));check(vault.latest(key).orElseThrow().generation().equals(generation),"incomplete native files preserve previous generation");
            var large=vault.begin(actor,key,generation,101,40L*1024*1024,"ab".repeat(32),now);check(large.total()==40L*1024*1024,"profile transfer can exceed the old quick-state cap without allocating its body");vault.abort(actor,large.uploadId());
        }
        try(MachineProfileVault recovered=new MachineProfileVault(root)){check(recovered.latest(key).orElseThrow().generation().equals(generation),"native profile survives world-vault reopening");check(Arrays.equals(recovered.read(key,generation,0,data.length),data),"machine files retain exact bytes after reopening");}
        MachineSaveKey game=new MachineSaveKey(machine,"ac".repeat(32));Path states=root.resolve("game-states");UUID stateGeneration;
        try(MachineStateVault vault=new MachineStateVault(states)){var upload=vault.begin(actor,game,"bc".repeat(32),120,data.length,Hashing.sha256(data),now,generation);vault.append(actor,upload.uploadId(),0,data,now);var snapshot=vault.commit(actor,upload.uploadId(),now);stateGeneration=snapshot.generationID();check(generation.equals(snapshot.profileGeneration()),"instant state records the exact native filesystem generation");}
        try(MachineStateVault recovered=new MachineStateVault(states)){var snapshot=recovered.latest(game).orElseThrow();check(snapshot.generationID().equals(stateGeneration)&&generation.equals(snapshot.profileGeneration()),"version-three metadata retains profile binding after reopening");check(Arrays.equals(recovered.read(game,stateGeneration,0,data.length),data),"profile binding leaves the core-state payload unchanged");}
        check(StateVault.DEFAULT_LIMITS.maxStateBytes()==32L*1024*1024,"ordinary legacy quick-state default is unchanged");
        System.out.println("MachineProfileVaultTest: "+checks+" checks passed");
    }
}
