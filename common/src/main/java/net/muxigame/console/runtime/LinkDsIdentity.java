package net.muxigame.console.runtime;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Stable local/unicast MAC identity shared by ordinary and wireless DS workers. */
public final class LinkDsIdentity {
    public static SortedMap<String,String> singleOptions(UUID machine){
        SortedMap<String,String> options=new TreeMap<>();options.put("melonds_render_mode","software");options.put("melonds_boot_mode","direct");options.put("melonds_screen_layout1","top-bottom");options.put("melonds_number_of_screen_layouts","1");options.put("melonds_screen_gap","0");options.put("melonds_touch_mode","touch");options.put("melonds_threaded_renderer","disabled");options.put("melonds_mac_address_mode",machineMac(machine));options.put("melonds_mic_input","microphone");options.put("melonds_mic_input_active","always");return Collections.unmodifiableSortedMap(options);
    }
    /** Ordinary MC save identity includes pinned frontend dependencies and each physical machine's actual options. */
    public static String singleFingerprint(String baseSHA,List<String> orderedDependencyIdentities,UUID machine){
        if(baseSHA==null||!baseSHA.matches("[a-f0-9]{64}")||orderedDependencyIdentities.size()!=6)throw new IllegalArgumentException("Exact ordinary DS dependency identities required");StringBuilder first=new StringBuilder(baseSHA);Set<String> files=new HashSet<>();
        for(String identity:orderedDependencyIdentities){if(identity==null||!identity.matches("worker/[A-Za-z0-9._-]+\\.jar=[a-f0-9]{64}")||!files.add(identity.substring(0,identity.indexOf('='))))throw new IllegalArgumentException("Invalid ordinary DS dependency identity");first.append('\n').append(identity);}
        return sha(sha(first.append("\n{}").toString())+"\n"+singleOptions(machine));
    }
    private static String sha(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    public static String machineMac(UUID machine){
        Objects.requireNonNull(machine);byte[] bytes;
        try{bytes=MessageDigest.getInstance("SHA-256").digest(("muxi-console/nds/mac/"+machine).getBytes(StandardCharsets.UTF_8));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        bytes[0]=(byte)((bytes[0]&0xfc)|2);StringJoiner result=new StringJoiner(":");for(int i=0;i<6;i++)result.add(String.format(Locale.ROOT,"%02X",bytes[i]&255));return result.toString();
    }
    private LinkDsIdentity(){}
}
