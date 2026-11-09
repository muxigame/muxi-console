package net.muxigame.console.tests.link;

import net.muxigame.console.runtime.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Actual isolated native workers and original serial challenge ROMs; no Minecraft or commercial media. */
public final class LinkSmoke {
    private static int checks;
    private static final List<String> workers = new ArrayList<>();
    private static final PortInput A = new PortInput(1 << 8);
    public static void main(String[] args) throws Exception {
        if (args.length != 8&&args.length!=9) throw new IllegalArgumentException("java bridge base rom1 rom2 system output gb/gbc/gba [reverse]");
        Path javaExecutable = Path.of(args[0]), bridge = Path.of(args[1]), base = Path.of(args[2]), first = Path.of(args[3]), second = Path.of(args[4]);
        Path system = Path.of(args[5]), output = Path.of(args[6]); String platform = args[7];
        boolean reversed=args.length==9&&args[8].equals("reverse");int leftOwn=reversed?0xa7:0x3c,rightOwn=reversed?0x3c:0xa7;
        Files.createDirectories(output);
        List<Path> cp = Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator)).map(Path::of).toList();
        var endpoint0 = new LinkRuntime.Endpoint(new UUID(0, 1), platform.equals("mixed") ? "gb" : platform, hash(first), first);
        var endpoint1 = new LinkRuntime.Endpoint(new UUID(0, 2), platform.equals("mixed") ? "gbc" : platform, hash(second), second);
        var config = new LinkRuntime.Config(javaExecutable, cp, bridge, hash(bridge), system, List.of(endpoint0, endpoint1));
        byte[][] exported = new byte[2][]; byte[] combined; int[] crossings = new int[2];
        LinkRuntime link = LinkRuntime.open(config);
        try {
            for (int i = 0; i < 100; i++) {
                LinkRuntime.Frame frame = link.step(PortInput.IDLE, PortInput.IDLE);
                check(frame.left().number() == frame.right().number(), "two endpoint frames belong to one committed step");
                for (int p = 0; p < 2; p++) {
                    short[] pcm = frame.endpoint(p).stereoPcm();
                    for (int n = 2; n < pcm.length; n += 2) if (pcm[n - 2] <= 0 && pcm[n] > 0) crossings[p]++;
                }
                if (i == 99) for (int p = 0; p < 2; p++){check(frame.endpoint(p).width()==(platform.equals("gba")?240:160)&&frame.endpoint(p).height()==(platform.equals("gba")?160:144),"actual selected hardware dimensions cross the worker protocol");png(output.resolve("serial-endpoint-" + (p + 1) + ".png"), frame.endpoint(p));}
            }
            byte[] l = parts(link.exportEndpoint(0))[1], r = parts(link.exportEndpoint(1))[1];
            eq(rightOwn, u(l[0]), "machine 1 receives machine 2 serial challenge"); eq(leftOwn, u(r[0]), "machine 2 receives machine 1 serial challenge");
            eq(leftOwn, u(l[3]), "machine 1 owns its distinct local challenge"); eq(rightOwn, u(r[3]), "machine 2 owns its distinct local challenge");
            eq(1, u(l[2]), "first guest CPU records its own completed serial interrupt"); eq(1, u(r[2]), "second guest CPU records completion");
            press(link, 0); eq(1, counter(link, 0), "P1 A changes only machine 1"); eq(0, counter(link, 1), "P1 never controls machine 2");
            press(link, 1); eq(1, counter(link, 0), "P2 never controls machine 1"); eq(1, counter(link, 1), "P2 A changes machine 2");
            int low=crossings[reversed?1:0],high=crossings[reversed?0:1];
            check(low > 50 && high > low * 1.7 && high < low * 2.3,
                    "per-endpoint native PCM retains two different diagnostic frequencies");
            exported[0] = link.exportEndpoint(0); exported[1] = link.exportEndpoint(1); combined = link.saveLinkState();
            eq(0x4d585331, new DataInputStream(new ByteArrayInputStream(exported[0])).readInt(), "export is single MXS1");
            eq(0x4c4e4b31, new DataInputStream(new ByteArrayInputStream(combined)).readInt(), "group checkpoint has distinct LNK1 format");
            eq(platform.equals("gba")?0:32, parts(exported[0])[2].length, "endpoint 1 RTC mapping exported"); eq(platform.equals("gba")?0:32, parts(exported[1])[2].length, "endpoint 2 RTC mapping exported");
            press(link, 0); eq(2, counter(link, 0), "progress advances before group restore");
            link.loadLinkState(combined); eq(1, counter(link, 0), "group snapshot restores first machine"); eq(1, counter(link, 1), "group restores second machine");
            expect(() -> link.restoreEndpoint(0, combined), "LNK1 cannot be loaded as a single machine");
            expect(() -> link.loadLinkState(exported[0]), "MXS1 cannot be loaded as a group");
            byte[][] bad = parts(exported[0]); bad[1] = Arrays.copyOf(bad[1], bad[1].length - 1);
            expect(() -> link.restoreEndpoint(0, single(bad)), "partial apply rejects mismatched SRAM and rolls both machines back");
            eq(1, counter(link, 0), "failed restore preserves first SRAM"); eq(1, counter(link, 1), "failed restore preserves other SRAM");
            byte[] identity = combined.clone(); replace(identity, endpoint0.machineId().toString(), new UUID(0, 3).toString());
            expect(() -> link.loadLinkState(identity), "group checkpoint cannot cross machine identity");
            Files.write(output.resolve("endpoint-1.mxs1"), exported[0]); Files.write(output.resolve("endpoint-2.mxs1"), exported[1]);
            Files.write(output.resolve("group.lnk1"), combined);
        } finally { link.close(); evidence(link); }

        byte[][] reverse = new byte[2][];
        for (int p = 0; p < 2; p++) {
            Path content = p == 0 ? first : second;
            ProcessConsoleRuntime single = ProcessConsoleRuntime.open(new LaunchConfig(javaExecutable, cp, base, content, system,
                    Files.createDirectories(output.resolve("single-" + p)), platform.equals("gba")?Map.of("mgba_use_bios","OFF"):Map.of("sameboy_model", config.endpoints().get(p).platform().equals("gbc") ? "Game Boy Color" : "Game Boy")));
            try {
                single.loadState(exported[p]);
                eq(1, u(single.readSram()[1]), "original official single core accepts bridge-exported MXS1");
                eq(p == 0 ? rightOwn : leftOwn, u(single.readSram()[0]), "official single restore retains own peer challenge");
                for (int i = 0; i < 3; i++) single.step(new InputFrame(List.of(A)));
                for (int i = 0; i < 3; i++) single.step(InputFrame.idle());
                eq(2, u(single.readSram()[1]), "original single core continues native input from link progress");
                reverse[p] = single.saveState(); eq(platform.equals("gba")?0:32, parts(reverse[p])[2].length, "official single RTC re-export retained");
                Files.write(output.resolve("official-single-" + (p + 1) + ".mxs1"), reverse[p]);
            } finally { single.close(); evidence(single); }
        }
        LinkRuntime cold = LinkRuntime.open(config);
        try {
            cold.restoreEndpoint(0, reverse[0]); cold.restoreEndpoint(1, reverse[1]);
            eq(2, counter(cold, 0), "bridge accepts original official single state for first machine");
            eq(2, counter(cold, 1), "bridge accepts original official single state for second machine");
            press(cold, 1); eq(2, counter(cold, 0), "reverse import preserves independent first progress"); eq(3, counter(cold, 1), "reverse import second machine continues native input");
            cold.loadLinkState(combined); eq(1, counter(cold, 0), "LNK1 cold restores first machine"); eq(1, counter(cold, 1), "LNK1 cold restores second machine");
        } finally { cold.close(); evidence(cold); }
        String result = "{\n  \"platform\":\"" + platform + "\",\n  \"sourceCommit\":\""+(platform.equals("gba")?"7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6":"90e2899574b7b63c59dce8a7276783426d52a068")+"\",\n"
                + "  \"bridgeSHA256\":\"" + hash(bridge) + "\",\n  \"baseSingleCoreSHA256\":\"" + hash(base) + "\",\n"
                + "  \"bidirectionalSingleStateCompatibility\":true,\n  \"endpointPCMCrossings\":[" + crossings[0] + "," + crossings[1] + "],\n"
                + "  \"physicalPortAuthors\":["+(reversed?"1,0":"0,1")+"],\n"
                + "  \"checks\":" + checks + ",\n  \"workers\":[" + String.join(",", workers) + "]\n}\n";
        Files.writeString(output.resolve("result.json"), result); System.out.println("Native link " + platform + ": " + checks + " checks passed; four workers exited normally");
    }
    private static void press(LinkRuntime link, int port) throws IOException {
        for (int i = 0; i < 3; i++) link.step(port == 0 ? A : PortInput.IDLE, port == 1 ? A : PortInput.IDLE);
        for (int i = 0; i < 3; i++) link.step(PortInput.IDLE, PortInput.IDLE);
    }
    private static int counter(LinkRuntime runtime, int endpoint) throws IOException { return u(parts(runtime.exportEndpoint(endpoint))[1][1]); }
    private static byte[][] parts(byte[] bytes) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(bytes)); if (in.readInt() != 0x4d585331) throw new IOException("Not a single MXS1");
        byte[][] parts = new byte[3][]; for (int i = 0; i < 3; i++) { int n = in.readInt(); if (n < 0 || n > in.available()) throw new IOException("Invalid MXS1 part"); parts[i] = in.readNBytes(n); }
        if (in.available() != 0) throw new IOException("Trailing MXS1"); return parts;
    }
    private static byte[] single(byte[][] parts) throws IOException {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes); out.writeInt(0x4d585331);
        for (byte[] part : parts) { out.writeInt(part.length); out.write(part); } return bytes.toByteArray();
    }
    private static void replace(byte[] bytes, String before, String after) {
        byte[] from = before.getBytes(java.nio.charset.StandardCharsets.UTF_8), to = after.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i <= bytes.length - from.length; i++) if (Arrays.equals(Arrays.copyOfRange(bytes, i, i + from.length), from)) { System.arraycopy(to, 0, bytes, i, to.length); return; }
        throw new AssertionError("Missing group identity");
    }
    private static void png(Path path, AvFrame frame) throws IOException {
        BufferedImage image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_ARGB); byte[] rgba = frame.rgba();
        for (int y = 0; y < frame.height(); y++) for (int x = 0; x < frame.width(); x++) { int p = (y * frame.width() + x) * 4; image.setRGB(x, y, 0xff000000 | u(rgba[p]) << 16 | u(rgba[p + 1]) << 8 | u(rgba[p + 2])); }
        ImageIO.write(image, "PNG", path.toFile());
    }
    private static void evidence(LinkRuntime runtime) {
        check(!runtime.isAlive() && runtime.exitCode().orElse(-1) == 0 && !runtime.wasForcedShutdown() && runtime.nativeTempCleaned(), "link worker natural exit and native cleanup");
        workers.add("{\"pid\":" + runtime.workerPid() + ",\"exitCode\":0,\"forced\":false,\"nativeTempCleaned\":true}");
    }
    private static void evidence(ProcessConsoleRuntime runtime) {
        check(!runtime.isAlive() && runtime.exitCode().orElse(-1) == 0 && !runtime.wasForcedShutdown() && runtime.nativeTemporaryFilesCleaned(), "official single worker natural exit and native cleanup");
        workers.add("{\"pid\":" + runtime.workerPid() + ",\"exitCode\":0,\"forced\":false,\"nativeTempCleaned\":true}");
    }
    private static String hash(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
    private static int u(byte b) { return b & 255; }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void eq(Object expected, Object actual, String message) { check(Objects.equals(expected, actual), message+" expected="+expected+" actual="+actual); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void expect(Checked action, String message) throws Exception { checks++; try { action.run(); } catch (IOException expected) { return; } throw new AssertionError(message); }
}
