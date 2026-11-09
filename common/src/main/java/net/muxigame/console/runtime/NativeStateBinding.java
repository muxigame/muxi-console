package net.muxigame.console.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.HexFormat;
import java.util.Map;
import java.nio.file.Files;

/** Old Azahar snapshots bind absolute mounts; one reviewed native ABI relocates them. */
public final class NativeStateBinding {
    private static final int MXS1 = 0x4d585331, MXS2 = 0x4d585332, MXS3 = 0x4d585333;
    private static final Map<String,Integer> PORTABLE_CORES = Map.of(
            "e83a278dfd08bce93a6c19823adb6cbb27ba62337f5ddc96f466d498193412f3",1,
            "19b7331fef796c243577ac1e5d442c69d700ee1282f2c1bcb6227b33a3d50e69",2);
    private static final String MISMATCH = "NATIVE_PROFILE_PATH_MISMATCH";
    private static final String FORMAT_MISMATCH = "NATIVE_PROFILE_FORMAT_MISMATCH";
    private NativeStateBinding() { }

    static byte[] identity(Path profile) throws IOException {
        return identity(profile, profile.toAbsolutePath().normalize().toString());
    }

    static byte[] identity(Path profile, String nativeSpelling) throws IOException {
        String path = profile.toRealPath().toString() + "\n" + nativeSpelling;
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) path = path.toLowerCase(Locale.ROOT);
        try { return MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static void write(DataOutput output, byte[] expected) throws IOException {
        write(output,expected,null);
    }

    static boolean reviewedPortableLibrary(String sha) { return sha!=null&&PORTABLE_CORES.containsKey(sha); }
    /** Magic/optional identity plus the three component lengths; component bytes are separate. */
    static int envelopeBytes(byte[] pathBinding,byte[] portableFormat){return pathBinding==null&&portableFormat==null?16:48;}

    static byte[] portableFormat(String actualLibrarySha,int nativeAbi) {
        return reviewedPortableLibrary(actualLibrarySha)&&PORTABLE_CORES.get(actualLibrarySha)==nativeAbi?HexFormat.of().parseHex(actualLibrarySha):null;
    }

    static String libraryDigest(Path library)throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(library)){byte[] block=new byte[65536];for(int n;(n=input.read(block))!=-1;)digest.update(block,0,n);}
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }

    static void write(DataOutput output, byte[] expected,byte[] portable) throws IOException {
        if(portable!=null){if(portable.length!=32)throw new IOException("Invalid portable native format identity");output.writeInt(MXS3);output.write(portable);return;}
        output.writeInt(expected == null ? MXS1 : MXS2);
        if (expected != null) { if (expected.length != 32) throw new IOException("Invalid native profile identity"); output.write(expected); }
    }

    static void read(DataInput input, byte[] expected) throws IOException {
        read(input,expected,null);
    }

    static void read(DataInput input, byte[] expected,byte[] portable) throws IOException {
        int magic = input.readInt();
        if(magic==MXS3){
            if(portable==null){if(expected!=null)throw new IOException(FORMAT_MISMATCH+": this native core is not approved for portable quickstates");throw new IOException("Invalid Muxi Console state envelope");}
            if(portable.length!=32)throw new IOException("Invalid portable native format identity");
            byte[] actual=new byte[32];input.readFully(actual);
            if(!MessageDigest.isEqual(actual,portable))throw new IOException(FORMAT_MISMATCH+": quickstate requires another native format");
            return;
        }
        if(portable!=null&&(magic==MXS1||magic==MXS2))throw new IOException(FORMAT_MISMATCH+": old quickstate has no reviewed portable native format; use native game saves");
        if (magic == MXS1) {
            if (expected != null) throw new IOException(MISMATCH + ": legacy quickstate has no native directory binding");
            return;
        }
        if (magic != MXS2 || expected == null) throw new IOException("Invalid Muxi Console state envelope");
        byte[] actual = new byte[32]; input.readFully(actual);
        if (!MessageDigest.isEqual(actual, expected))
            throw new IOException(MISMATCH + ": quickstate belongs to another native directory; use the current machine's native files");
    }

    /** The worker preserves this bounded error code across its IOException transport. */
    public static boolean mismatch(Throwable failure) {
        for (int count = 0; failure != null && count < 16; count++, failure = failure.getCause())
            if (failure.getMessage() != null && (failure.getMessage().contains(MISMATCH)||failure.getMessage().contains(FORMAT_MISMATCH))) return true;
        return false;
    }
}
