package net.muxigame.console.runtime;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class NativeStateBindingTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static byte[] header(byte[] binding) throws Exception { var bytes = new ByteArrayOutputStream(); NativeStateBinding.write(new DataOutputStream(bytes), binding); return bytes.toByteArray(); }
    private static void reject(byte[] header, byte[] binding, boolean mismatch) throws Exception {
        try { NativeStateBinding.read(new DataInputStream(new ByteArrayInputStream(header)), binding); throw new AssertionError("unsafe header accepted"); }
        catch (IOException failure) { check(NativeStateBinding.mismatch(failure) == mismatch, "rejection distinguishes native ownership from corruption"); }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("muxi-state-owner-");
        Path first = Files.createDirectory(root.resolve("machine-a")), second = Files.createDirectory(root.resolve("machine-b"));
        byte[] a = NativeStateBinding.identity(first), b = NativeStateBinding.identity(second);
        check(Arrays.equals(a, NativeStateBinding.identity(first.resolve("."))), "equivalent directory spellings retain the binding");
        check(!Arrays.equals(a, b), "another machine directory differs");
        check(!Arrays.equals(a, NativeStateBinding.identity(first, "changed-native-alias")), "reassigned native aliases cannot reopen an old path");
        byte[] legacy = header(null), bound = header(a);
        check(Arrays.equals(legacy, new byte[] {0x4d,0x58,0x53,0x31}), "existing platforms retain the exact MXS1 header");
        check(bound.length == 36, "bound header contains only a fixed-size identity");
        NativeStateBinding.read(new DataInputStream(new ByteArrayInputStream(bound)), a); checks++;
        NativeStateBinding.read(new DataInputStream(new ByteArrayInputStream(legacy)), null); checks++;
        reject(bound, b, true); reject(legacy, a, true); reject(bound, null, false);
        reject(Arrays.copyOf(bound, 35), a, false); reject(new byte[4], a, false);
        var nested = new IOException("Worker rejected state", new IOException("NATIVE_PROFILE_PATH_MISMATCH: local binding differs"));
        check(NativeStateBinding.mismatch(nested), "worker error wrapping retains the safe-boot signal");
        check(!NativeStateBinding.mismatch(new IOException("damaged quickstate")), "unrelated corruption never silently boots");
        String coreSha="e83a278dfd08bce93a6c19823adb6cbb27ba62337f5ddc96f466d498193412f3";
        byte[] portable=NativeStateBinding.portableFormat(coreSha,1);
        check(portable!=null&&portable.length==32,"reviewed DLL and actual native ABI jointly enable portable format");
        check(NativeStateBinding.portableFormat(coreSha,0)==null,"reviewed DLL alone cannot enable portable format");
        check(NativeStateBinding.portableFormat("0".repeat(64),1)==null,"an unreviewed DLL cannot enable portable format with a matching symbol");
        var portableBytes=new ByteArrayOutputStream();NativeStateBinding.write(new DataOutputStream(portableBytes),a,portable);
        byte[] movable=portableBytes.toByteArray();check(movable.length==36&&movable[3]=='3',"MXS3 explicitly binds the native format SHA");
        NativeStateBinding.read(new DataInputStream(new ByteArrayInputStream(movable)),b,portable);checks++;
        try{NativeStateBinding.read(new DataInputStream(new ByteArrayInputStream(legacy)),a,portable);throw new AssertionError("portable ABI accepted old unbound MXS1");}catch(IOException expected){check(NativeStateBinding.mismatch(expected),"old unbound state selects safe native-files boot");}
        try{NativeStateBinding.read(new DataInputStream(new ByteArrayInputStream(bound)),a,portable);throw new AssertionError("portable ABI accepted old MXS2");}catch(IOException expected){check(NativeStateBinding.mismatch(expected),"even same-directory old MXS2 is never passed to the new native format");}
        reject(movable,a,true);reject(movable,null,false);
        byte[] anotherFormat=portable.clone();anotherFormat[0]^=1;
        try{NativeStateBinding.read(new DataInputStream(new ByteArrayInputStream(movable)),b,anotherFormat);throw new AssertionError("different portable native format accepted");}catch(IOException expected){check(NativeStateBinding.mismatch(expected),"portable state cannot cross native format identities");}
        try{NativeStateBinding.read(new DataInputStream(new ByteArrayInputStream(Arrays.copyOf(movable,35))),b,portable);throw new AssertionError("truncated portable state accepted");}catch(IOException expected){check(!NativeStateBinding.mismatch(expected),"portable header corruption is not mistaken for an ownership mismatch");}
        Path file=root.resolve("preferred-native.bin");Files.write(file,new byte[]{1,2,3});
        check(NativeStateBinding.libraryDigest(file).equals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"),"native DLL digest uses actual complete bytes");
        check(NativeStateBinding.envelopeBytes(null,null)==16,"ordinary MXS1 preserves its exact existing spool allowance");
        check(NativeStateBinding.envelopeBytes(a,null)==48,"bound MXS2 accounts for its actual 32 additional bytes");
        check(NativeStateBinding.envelopeBytes(a,portable)==48,"portable MXS3 accounts for its actual native-format identity");
        long spool=512L*1024*1024,memory=24L*1024*1024,coreLimit=spool-2*memory-NativeStateBinding.envelopeBytes(a,portable);
        check(coreLimit+2*memory+NativeStateBinding.envelopeBytes(a,portable)==spool&&coreLimit+1+2*memory+NativeStateBinding.envelopeBytes(a,portable)>spool,"extreme core plus both maximum memories fits exactly, and one additional byte exceeds the spool cap without allocating them");
        String secondSha="19b7331fef796c243577ac1e5d442c69d700ee1282f2c1bcb6227b33a3d50e69";byte[] secondFormat=NativeStateBinding.portableFormat(secondSha,2);
        check(secondFormat!=null&&secondFormat.length==32,"second reviewed native DLL requires its actual ABI2");
        check(NativeStateBinding.portableFormat(secondSha,1)==null&&NativeStateBinding.portableFormat(coreSha,2)==null,"reviewed DLL hashes cannot exchange ABI claims");
        try{NativeStateBinding.read(new DataInputStream(new ByteArrayInputStream(movable)),b,secondFormat);throw new AssertionError("ABI1 quickstate mixed into ABI2");}catch(IOException expected){check(NativeStateBinding.mismatch(expected),"actual ABI1 MXS3 stays distinct from the ABI2 native format");}
        var secondBytes=new ByteArrayOutputStream();NativeStateBinding.write(new DataOutputStream(secondBytes),b,secondFormat);
        try{NativeStateBinding.read(new DataInputStream(new ByteArrayInputStream(secondBytes.toByteArray())),a,portable);throw new AssertionError("ABI2 quickstate mixed into ABI1");}catch(IOException expected){check(NativeStateBinding.mismatch(expected),"actual ABI2 MXS3 stays distinct from the ABI1 native format");}
        NativeStateBinding.read(new DataInputStream(new ByteArrayInputStream(secondBytes.toByteArray())),a,secondFormat);checks++;
        System.out.println("NativeStateBindingTest " + checks + " checks");
    }
}
