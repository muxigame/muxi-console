package net.muxigame.console.domain;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** The cross-vault guard sees the old head and can reject after preparation without publishing an orphan. */
public final class StateVaultCommitGuardTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("publication guard check "+checks);}
    private static StateVault.Status upload(StateVault vault,UUID actor,UUID machine,int value,long now)throws IOException {
        byte[] bytes={(byte)value,2,3};var status=vault.begin(actor,machine,"a".repeat(64),value,bytes.length,Hashing.sha256(bytes),now);vault.append(actor,status.uploadId(),0,bytes,now);return status;
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory(Files.createDirectories(Path.of(args[0])),"state-guard-");UUID actor=UUID.randomUUID(),machine=UUID.randomUUID();long now=System.currentTimeMillis();
        UUID finalGeneration;
        try(var vault=new StateVault(root)){
            var base=upload(vault,actor,machine,1,now);var old=vault.commit(actor,base.uploadId(),now);long charge=vault.chargedBytes();
            var rejected=upload(vault,actor,machine,2,now);boolean failed=false;
            try{vault.commitGuarded(actor,rejected.uploadId(),now,publication->{check(vault.latest(machine).orElseThrow().equals(old));throw new IOException("barrier changed");});}catch(IOException expected){failed=true;}
            check(failed);check(vault.latest(machine).orElseThrow().equals(old));check(vault.chargedBytes()==charge);
            var forgotten=upload(vault,actor,machine,3,now);failed=false;try{vault.commitGuarded(actor,forgotten.uploadId(),now,publication->{});}catch(IOException expected){failed=true;}
            check(failed);check(vault.latest(machine).orElseThrow().equals(old));check(vault.chargedBytes()==charge);
            var accepted=upload(vault,actor,machine,4,now);int[] calls={0};var saved=vault.commitGuarded(actor,accepted.uploadId(),now,publication->{calls[0]++;check(vault.latest(machine).orElseThrow().equals(old));publication.publish();check(!vault.latest(machine).orElseThrow().equals(old));});
            check(calls[0]==1);check(vault.latest(machine).orElseThrow().equals(saved));check(Arrays.equals(vault.read(machine,saved.generationID(),0,3),new byte[]{4,2,3}));finalGeneration=saved.generationID();
        }
        try(var cold=new StateVault(root)){check(cold.latest(machine).orElseThrow().generationID().equals(finalGeneration));}
        System.out.println("StateVault publication guard checks="+checks);
    }
}
