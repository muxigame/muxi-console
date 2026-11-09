package net.muxigame.console.runtime;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Stable per-machine private Room identity; never derived from the current cartridge. */
public final class Link3dsIdentity {
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static byte[] mac(UUID machine) {
        byte[] bytes=Arrays.copyOf(digest("muxi-console/n3ds/mac/"+Objects.requireNonNull(machine)),6);
        bytes[0]=(byte)((bytes[0]&0xfc)|2);return bytes;
    }
    public static String machineMac(UUID machine) {
        StringJoiner result=new StringJoiner(":");for(byte value:mac(machine))result.add(String.format(Locale.ROOT,"%02X",value&255));return result.toString();
    }
    public static String consoleId(UUID machine) { return HexFormat.of().formatHex(digest("muxi-console/n3ds/console/"+Objects.requireNonNull(machine))); }
    public static String nickname(UUID machine) { return "MC-"+consoleId(machine).substring(0,16); }
    public static SortedMap<String,String> singleOptions(UUID machine) {
        Objects.requireNonNull(machine);SortedMap<String,String> options=new TreeMap<>();
        options.put("citra_graphics_api","OpenGL");options.put("citra_resolution_factor","1");
        options.put("citra_layout_option","default");options.put("citra_use_cpu_jit","enabled");
        options.put("citra_render_3d","off");options.put("citra_use_libretro_save_path","LibRetro Default");
        options.put("citra_enable_touch_touchscreen","enabled");options.put("citra_enable_motion","enabled");options.put("citra_input_type","frontend");
        return Collections.unmodifiableSortedMap(options);
    }
    /** Same ordinary-runtime dependency/options fingerprint contract; does not claim old official snapshot compatibility. */
    public static String singleFingerprint(String actualCoreSHA,List<String> orderedDependencyIdentities,UUID machine) {
        if(actualCoreSHA==null||!actualCoreSHA.matches("[a-f0-9]{64}")||orderedDependencyIdentities.size()!=6)throw new IllegalArgumentException("Exact 3DS dependency identities required");
        StringBuilder first=new StringBuilder(actualCoreSHA);Set<String> files=new HashSet<>();
        for(String identity:orderedDependencyIdentities){if(identity==null||!identity.matches("worker/[A-Za-z0-9._-]+\\.jar=[a-f0-9]{64}")||!files.add(identity.substring(0,identity.indexOf('='))))throw new IllegalArgumentException("Invalid 3DS dependency identity");first.append('\n').append(identity);}
        String base=HexFormat.of().formatHex(digest(first.append("\n{}").toString()));
        return HexFormat.of().formatHex(digest(base+"\n"+singleOptions(machine)));
    }
    private Link3dsIdentity() { }
}
