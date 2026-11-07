package net.muxigame.console.runtime;

import java.io.*;

/** Display shape survives transport independently of framebuffer resolution and rotation. */
public final class DisplayAspectSmoke {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    public static void main(String[] args) throws Exception {
        byte[] pixels = new byte[16]; short[] audio = {123, -123};
        AvFrame raster = new AvFrame(7, 2, 2, 1, pixels, audio, 48000, false, 4.0 / 3);
        check(raster.displayAspect() == 4.0 / 3, "Quarter-turn metadata leaves core display aspect unrotated");
        check(new AvFrame(0, 2, 1, 0, new byte[8], new short[0], 0, false).displayAspect() == 2, "Legacy frame constructor retains pixel aspect");
        check(new AvFrame(0, 0, 0, 0, new byte[0], new short[0], 0, false).displayAspect() == 1, "Empty initial frame has a finite aspect");
        byte[] encoded = Wire.body(out -> Wire.frame(out, raster));
        var input = Wire.input(encoded); AvFrame decoded = Wire.frame(input); Wire.end(input);
        check(decoded.displayAspect() == raster.displayAspect() && decoded.rotation() == 1, "MXCW frame retains display aspect and rotation separately");
        check(java.util.Arrays.equals(decoded.rgba(), pixels) && java.util.Arrays.equals(decoded.stereoPcm(), audio), "Frame pixels/audio remain unmodified");
        CoreInfo core = new CoreInfo("diagnostic", "1", 1552, 240, 60, 48000, 4.0 / 3);
        input = Wire.input(Wire.body(out -> Wire.info(out, core))); CoreInfo restored = Wire.info(input); Wire.end(input);
        check(restored.equals(core), "MXCW core metadata retains a physical aspect distinct from its raster");
        check(new CoreInfo("legacy", "1", 320, 240, 60, 48000).displayAspect() == 4.0 / 3, "Legacy core-info constructor remains available");
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            try { new AvFrame(0, 2, 2, 0, pixels, audio, 48000, false, invalid); throw new AssertionError("Non-finite aspect accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        ByteArrayOutputStream old = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(old)) { out.writeInt(Wire.MAGIC); out.writeShort(1); out.writeShort(Wire.STEP); out.writeLong(1); out.writeInt(0); }
        try { Wire.read(new DataInputStream(new ByteArrayInputStream(old.toByteArray()))); throw new AssertionError("Old worker protocol accepted"); }
        catch (IOException expected) { checks++; }
        System.out.println("Display aspect checks passed: " + checks);
    }
}
