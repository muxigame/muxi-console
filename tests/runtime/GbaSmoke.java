package net.muxigame.console.runtime;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Real mGBA game execution from our own ARM ROM. No Minecraft or original BIOS. */
public final class GbaSmoke {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static AvFrame run(ConsoleRuntime runtime, int buttons, int frames) throws IOException {
        AvFrame frame = null; InputFrame input = new InputFrame(List.of(new PortInput(buttons)));
        for (int i = 0; i < frames; i++) frame = runtime.step(input);
        return frame;
    }
    private static void color(AvFrame frame, int channel, String label) {
        check(frame.width() == 240 && frame.height() == 160, label + " has genuine GBA geometry");
        for (int y = 8; y < 160; y += 16) for (int x = 8; x < 240; x += 16) {
            int index = (y * 240 + x) * 4;
            for (int component = 0; component < 3; component++) {
                int value = frame.rgba()[index + component] & 255;
                check(component == channel ? value > 220 : value < 20,
                        label + " pixel " + x + "," + y + " RGB=" + (frame.rgba()[index] & 255) + "," + (frame.rgba()[index + 1] & 255) + "," + (frame.rgba()[index + 2] & 255));
            }
        }
    }
    private static void picture(AvFrame frame, Path path) throws IOException {
        BufferedImage image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < frame.height(); y++) for (int x = 0; x < frame.width(); x++) {
            int index = (y * frame.width() + x) * 4;
            image.setRGB(x, y, ((frame.rgba()[index] & 255) << 16) | ((frame.rgba()[index + 1] & 255) << 8) | (frame.rgba()[index + 2] & 255));
        }
        ImageIO.write(image, "PNG", path.toFile());
    }
    private record Envelope(byte[] core, byte[] sram, byte[] rtc) { }
    private static Envelope envelope(byte[] state) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(state))) {
            if (in.readInt() != 0x4d585331) throw new IOException("Unexpected independently defined runtime envelope");
            byte[][] pieces = new byte[3][];
            for (int i = 0; i < pieces.length; i++) {
                int count = in.readInt(); if (count < 0 || count > state.length) throw new IOException("Invalid state component");
                pieces[i] = in.readNBytes(count); if (pieces[i].length != count) throw new EOFException();
            }
            if (in.read() != -1) throw new IOException("Unexpected state suffix");
            return new Envelope(pieces[0], pieces[1], pieces[2]);
        }
    }
    private static byte[] packageState(Envelope state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x4d585331);
            for (byte[] piece : List.of(state.core, state.sram, state.rtc)) { out.writeInt(piece.length); out.write(piece); }
        }
        return bytes.toByteArray();
    }
    private static void closed(ProcessConsoleRuntime runtime) throws Exception {
        runtime.close();
        check(!runtime.isAlive() && runtime.exitCode().orElse(-1) == 0 && !runtime.wasForcedShutdown(), "Owned mGBA worker exits normally");
        check(ProcessHandle.of(runtime.workerPid()).isEmpty(), "Owned mGBA process is released");
        check(runtime.nativeTemporaryFilesCleaned() && !Files.exists(runtime.nativeTemporaryDirectory(), LinkOption.NOFOLLOW_LINKS), "Only the owned native temporary directory is removed");
    }
    private static Set<Path> temporaryChildren(Path parent) throws IOException {
        Set<Path> result = new HashSet<>();
        try (var children = Files.newDirectoryStream(parent, "muxi-console-worker-*")) { for (Path child : children) result.add(child); }
        return result;
    }
    public static void main(String[] args) throws Exception {
        Path java = Path.of(args[0]); List<Path> classpath = Arrays.stream(args[1].split(File.pathSeparator)).map(Path::of).toList();
        Path core = Path.of(args[2]), rom = Path.of(args[3]), output = Path.of(args[4]);
        // Resolve the optional full-product domain test dependency at execution time,
        // keeping the existing runtime-only compile entry point independent of MC/domain.
        Class<?> platformType = Class.forName("net.muxigame.console.domain.Platform");
        Object gba = platformType.getField("GBA").get(null);
        Object inspector = Class.forName("net.muxigame.console.domain.RomInspector").getConstructor().newInstance();
        Object inspected = inspector.getClass().getMethod("inspect", Path.class, platformType).invoke(inspector, rom, gba);
        check(inspected.getClass().getMethod("platform").invoke(inspected).equals(gba), "Own ARM ROM passes real GBA admission");
        byte[] raw = Files.readAllBytes(rom);
        check(Arrays.equals(Arrays.copyOfRange(raw, 4, 0xA0), new byte[0x9C]), "No manufacturer logo is included");
        Path system = output.resolve("empty-system"); Files.createDirectories(system);
        try (var files = Files.list(system)) { check(files.findAny().isEmpty(), "No original BIOS is supplied"); }
        LaunchConfig config = new LaunchConfig(java, classpath, core, rom, system, output.resolve("first-save"), Map.of("mgba_use_bios", "OFF"));
        ProcessConsoleRuntime first = ProcessConsoleRuntime.open(config); byte[] checkpoint, expectedSram; int rtcBytes;
        check(first.nativeTemporaryDirectory().getParent().equals(core.getParent().toRealPath()), "Native extraction is inside the core's private parent");
        check(!first.nativeTemporaryDirectory().startsWith(config.saveDirectory().toAbsolutePath()), "Native extraction does not pollute checkpoint save directories");
        try {
            check(first.info().name().equals("mGBA"), "The real non-NES mGBA core is running");
            AvFrame boot = run(first, 0, 90); color(boot, 2, "ARM mode3 blue boot"); picture(boot, output.resolve("boot-blue.png"));
            check(boot.stereoPcm().length > 0 && boot.sampleRate() > 0, "mGBA audio callback emits stereo frames");
            byte[] initial = first.readSram(); check(initial.length == 131072, "Pinned mGBA exports its 128 KiB save-memory region, actual=" + initial.length);
            AvFrame a = run(first, 1 << 8, 18); color(a, 0, "A turns screen red"); picture(a, output.resolve("a-red.png"));
            check((first.readSram()[0] & 255) == 0xA1, "GBA CPU writes A marker into SRAM");
            run(first, 0, 3);
            AvFrame start = run(first, 1 << 3, 18); color(start, 1, "Start turns screen green"); picture(start, output.resolve("start-green.png"));
            check((first.readSram()[1] & 255) == 0x51, "GBA CPU writes Start marker into SRAM");
            run(first, 0, 3); expectedSram = first.readSram(); expectedSram[31] = 0x5A; first.writeSram(expectedSram);
            checkpoint = first.saveState(); Envelope snapshot = envelope(checkpoint); rtcBytes = snapshot.rtc.length;
            System.out.println("mGBA checkpoint: initial-save-region=" + initial.length + "; detected-SRAM=" + expectedSram.length + "; core-state=" + snapshot.core.length + "; RTC-region=" + rtcBytes);
            check(snapshot.core.length > 0 && Arrays.equals(snapshot.sram, expectedSram), "Full runtime checkpoint explicitly contains GBA SRAM");
            byte[] changed = expectedSram.clone(); changed[31] = 7; first.writeSram(changed);
            run(first, 1 << 8, 18);
            byte[] beforeBad = first.readSram();
            byte[] badSram = packageState(new Envelope(snapshot.core, Arrays.copyOf(snapshot.sram, snapshot.sram.length - 1), snapshot.rtc));
            try { first.loadState(badSram); throw new AssertionError("Wrong SRAM component size was accepted"); }
            catch (IOException expected) { check(expected.getMessage().contains("previous running state restored"), "Rejected SRAM reports successful rollback"); }
            check(Arrays.equals(first.readSram(), beforeBad), "Rejected SRAM state leaves battery memory intact");
            color(run(first, 0, 18), 0, "Rejected SRAM state preserves prior red CPU/video state");
            byte[] badRtc = packageState(new Envelope(snapshot.core, snapshot.sram, new byte[1]));
            try { first.loadState(badRtc); throw new AssertionError("Wrong RTC component size was accepted"); }
            catch (IOException expected) { check(expected.getMessage().contains("previous running state restored"), "Rejected RTC component rolls back"); }
            check(Arrays.equals(first.readSram(), beforeBad), "Rejected RTC state preserves prior SRAM");
            int beforeRestore = first.readSram().length;
            try { first.loadState(checkpoint); }
            catch (IOException failure) {
                System.out.println("mGBA restore diagnostic: core-state=" + snapshot.core.length + "; envelope-SRAM=" + snapshot.sram.length + "; before-SRAM=" + beforeRestore + "; after-SRAM=" + first.readSram().length + "; envelope-RTC=" + rtcBytes);
                throw failure;
            }
            check(Arrays.equals(first.readSram(), expectedSram), "Load restores SRAM independently of core serialize behavior");
            color(run(first, 0, 18), 1, "Load restores ARM registers and green VRAM");
        } finally { closed(first); }
        ProcessConsoleRuntime second = ProcessConsoleRuntime.open(new LaunchConfig(java, classpath, core, rom, system, output.resolve("second-save"), Map.of("mgba_use_bios", "OFF")));
        try {
            check(!first.nativeTemporaryDirectory().equals(second.nativeTemporaryDirectory()), "Separate workers use uniquely owned native temporary directories");
            color(run(second, 0, 90), 2, "Fresh worker starts from ARM boot");
            int freshRegion = second.readSram().length;
            try { second.loadState(checkpoint); }
            catch (IOException failure) {
                System.out.println("Fresh-worker restore: expected-SRAM=" + expectedSram.length + "; new-core-region=" + freshRegion + "; after-attempt=" + second.readSram().length);
                throw failure;
            }
            AvFrame resumed = run(second, 0, 18);
            color(resumed, 1, "New worker resumes complete prior checkpoint"); picture(resumed, output.resolve("resumed-green.png"));
            check(Arrays.equals(second.readSram(), expectedSram), "SRAM survives full process replacement through the checkpoint envelope");
        } finally { closed(second); }
        Set<Path> beforeFailure = temporaryChildren(core.getParent());
        try { ProcessConsoleRuntime.open(new LaunchConfig(java, classpath, core, null, system, output.resolve("failed-start-save"))); throw new AssertionError("mGBA unexpectedly accepted no content"); }
        catch (IOException expected) { check(expected.getMessage().contains("requires game content"), "Expected startup failure is reported"); }
        check(temporaryChildren(core.getParent()).equals(beforeFailure), "Failed startup removes its own native extraction directory");
        ProcessConsoleRuntime cleanup = ProcessConsoleRuntime.open(config);
        Path obstacle = cleanup.nativeTemporaryDirectory().resolve("unexpected-directory"); Files.createDirectory(obstacle);
        try {
            try { cleanup.close(); throw new AssertionError("Unknown nested native directory was recursively deleted"); }
            catch (IOException expected) { check(expected.getMessage().contains("Unexpected native temporary"), "Unsafe/unexpected cleanup is explicitly observable"); }
            check(!cleanup.isAlive() && cleanup.exitCode().orElse(-1) == 0 && !cleanup.wasForcedShutdown() && !cleanup.nativeTemporaryFilesCleaned(), "Cleanup failure does not become a false normal-cleanup result");
        } finally { Files.delete(obstacle); closed(cleanup); }
        System.out.println("PASS " + checks + " GBA checks; mGBA=" + first.info().version() + "; geometry=240x160; save-memory-region=" + expectedSram.length + "; RTC-region=" + rtcBytes + "; three exercised workers exit0; forced=false; startup failure cleaned");
        System.out.println("RTC GPIO hardware is not exercised by this original SRAM-only diagnostic; zero-length RTC region is reported without claiming clock validation.");
    }
}
