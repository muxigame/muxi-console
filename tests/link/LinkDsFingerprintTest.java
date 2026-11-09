package net.muxigame.console.tests.link;

import java.util.*;
import net.muxigame.console.runtime.LinkDsIdentity;
import net.muxigame.console.domain.Hashing;

/** Ordinary DS state identities must retain dependencies, machine options and their original two hash layers. */
public final class LinkDsFingerprintTest {
    private static int checks;
    private static void check(boolean yes,String label){checks++;if(!yes)throw new AssertionError(label);}
    public static void main(String[] args){
        UUID machine=new UUID(0,7);String base="a".repeat(64);List<String> dependencies=new ArrayList<>();for(int i=0;i<6;i++)dependencies.add("worker/dependency-"+i+".jar="+(char)('0'+i)+"0".repeat(63));
        var options=LinkDsIdentity.singleOptions(machine);check(options.size()==10&&options.get("melonds_mac_address_mode").equals(LinkDsIdentity.machineMac(machine)),"actual ordinary options contain this physical machine's forced local MAC");
        String legacy=Hashing.sha256((base+"\n"+String.join("\n",dependencies)+"\n{}").getBytes(java.nio.charset.StandardCharsets.UTF_8));legacy=Hashing.sha256((legacy+"\n"+options).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String fingerprint=LinkDsIdentity.singleFingerprint(base,dependencies,machine);check(fingerprint.equals(legacy),"exact legacy ordinary dependency/options hash retains both layers");check(!fingerprint.equals(base),"ordinary MC state identity cannot be compared with only its base core format SHA");
        check(!fingerprint.equals(LinkDsIdentity.singleFingerprint(base,dependencies,new UUID(0,8))),"another physical machine's MAC/configuration has another ordinary identity");Collections.swap(dependencies,0,1);check(!fingerprint.equals(LinkDsIdentity.singleFingerprint(base,dependencies,machine)),"dependency identity preserves the shipped manifest order");Collections.swap(dependencies,0,1);
        try{options.put("unexpected","value");throw new AssertionError("Options must be immutable");}catch(UnsupportedOperationException expected){checks++;}
        try{LinkDsIdentity.singleFingerprint(base,dependencies.subList(0,5),machine);throw new AssertionError("Missing dependency accepted");}catch(IllegalArgumentException expected){checks++;}
        dependencies.set(1,dependencies.getFirst());try{LinkDsIdentity.singleFingerprint(base,dependencies,machine);throw new AssertionError("Duplicate dependency accepted");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("LinkDsFingerprintTest "+checks+" checks passed");
    }
}
