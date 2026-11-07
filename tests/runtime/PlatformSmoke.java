package net.muxigame.console.runtime;

import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/** Real core execution; an export probe never qualifies as a platform run. */
public final class PlatformSmoke {
    private static int checks;
    private static void check(boolean condition, String description) {
        checks++; if (!condition) throw new AssertionError(description);
    }
    public static void main(String[] args) throws Exception {
        Path java = Path.of(args[0]), core = Path.of(args[2]), output = Path.of(args[4]);
        List<Path> classpath = Arrays.stream(args[1].split(File.pathSeparator)).map(Path::of).toList();
        String platform = args[5]; Map<String, String> options = new HashMap<>();
        if (platform.equals("n64")) {
            options.put("mupen64plus-rdp-plugin", "gliden64");
            options.put("mupen64plus-rsp-plugin", "hle");
            options.put("mupen64plus-cpucore", "pure_interpreter");
        }
        if (platform.equals("gb") || platform.equals("gbc")) {
            options.put("sameboy_model", platform.equals("gb") ? "Game Boy" : "Game Boy Color");
            options.put("sameboy_border", "never"); options.put("sameboy_color_correction_mode", "off");
        }
        LaunchConfig config = new LaunchConfig(java, classpath, core, args[3].isEmpty() ? null : Path.of(args[3]),
                Path.of(args[6]), output.resolve(platform + "-save"), options);
        if (platform.equals("fds")) {
            Path missing = output.resolve("missing-fds-firmware"); Files.createDirectories(missing);
            try {
                try (ProcessConsoleRuntime rejected = ProcessConsoleRuntime.open(new LaunchConfig(java, classpath, core, config.content(), missing, output.resolve("missing-fds-save")))) {
                    throw new AssertionError("Missing FDS firmware was accepted");
                }
            } catch (java.io.IOException expected) { checks++; }
        }
        byte[] checkpoint = null;
        AvFrame expectedFrame = null;
        ProcessConsoleRuntime runtime = ProcessConsoleRuntime.open(config); long pid = runtime.workerPid();
        try {
            System.out.println("Core " + runtime.info());
            check(runtime.isAlive() && pid != ProcessHandle.current().pid(), "Separate live worker");
            AvFrame frame = null; int frames = Integer.parseInt(args[7]);
            for (int i = 0; i < frames; i++) { frame = runtime.step(InputFrame.idle()); if (i % 30 == 0) System.out.println("Frame " + i + " " + frame.width() + "x" + frame.height()); }
            check(frame.number() == frames - 1, "All native frames executed");
            byte[] rgba = frame.rgba(); Set<Integer> colors = new HashSet<>();
            BufferedImage image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < frame.height(); y++) for (int x = 0; x < frame.width(); x++) {
                int offset = (y * frame.width() + x) * 4;
                int color = (rgba[offset] & 255) << 16 | (rgba[offset + 1] & 255) << 8 | rgba[offset + 2] & 255;
                colors.add(color); image.setRGB(x, y, color);
            }
            ImageIO.write(image, "PNG", output.resolve(platform + "-frame.png").toFile());
            System.out.println("Colors " + colors.size() + " " + colors.stream().limit(12).toList());
            System.out.println("Video raster=" + frame.width() + "x" + frame.height() + "; displayAspect=" + frame.displayAspect() + "; rotation=" + frame.rotation());
            check(colors.size() > 1, "Actual content produced a non-uniform frame");
            check(Double.isFinite(frame.displayAspect()) && frame.displayAspect() > 0, "Core display aspect is valid");
            if (platform.equals("arcade")) check(Math.abs(frame.displayAspect() - 4.0 / 3) < 0.000001, "MAME Pong public geometry retains its physical 4:3 display independently of raster width");
            expectedFrame = frame;
            if (Set.of("snes", "md", "sms", "gg", "sg1000", "gbc", "wsc", "pce", "ngpc", "vb").contains(platform)) {
                check(channel(frame, 1, 0) > 160 && channel(frame, 1, 1) < 100, "Original " + platform + " upper tile field is red");
                check(channel(frame, 3, 0) < 30 && channel(frame, 3, 1) < 30 && channel(frame, 3, 2) < 30, "Original " + platform + " lower tile field is black");
            }
            if (platform.equals("gb")) check(channel(frame, 1, 0) + 30 < channel(frame, 3, 0), "Original DMG upper tile field differs from the empty lower field");
            if (platform.equals("ws")) check(channel(frame, 1, 0) < 30 && channel(frame, 3, 0) > 220, "Original WonderSwan monochrome tile field is black above white");
            if (platform.equals("ngp")) check(channel(frame, 1, 0) > 220 && channel(frame, 3, 0) < 30, "Original NeoGeo Pocket monochrome tile field is white above dark");
            if (platform.equals("lynx")) {
                check(channel(frame, 1, 0) > 180 && channel(frame, 1, 1) < 50, "Original Lynx BS93 framebuffer upper half red");
                check(channel(frame, 3, 0) < 50 && channel(frame, 3, 1) < 50, "Original Lynx BS93 framebuffer lower half dark");
            }
            if (platform.equals("a7800")) {
                check(channel(frame, 1, 0) > 150 && channel(frame, 1, 0) > channel(frame, 1, 2) + 30, "Original MARIA upper raster band red");
                check(channel(frame, 3, 2) > 150 && channel(frame, 3, 2) > channel(frame, 3, 0) + 80, "Original MARIA lower raster band blue");
            }
            if (platform.equals("a2600")) {
                check(colors.size() >= 3, "TIA diagnostic has overscan, playfield and background colors");
                int offset = ((frame.height() / 2) * frame.width() + frame.width() / 4) * 4;
                check((rgba[offset] & 255) > 150 && (rgba[offset + 2] & 255) < 120, "Original TIA background band is red");
                AvFrame pressed = run(runtime, new InputFrame(List.of(new PortInput(1))), 12);
                check((pressed.rgba()[offset + 2] & 255) > (pressed.rgba()[offset] & 255) + 30, "Atari fire reads INPT4 and changes background band blue");
                AvFrame released = run(runtime, InputFrame.idle(), 12);
                check(Math.abs((released.rgba()[offset] & 255) - (rgba[offset] & 255)) < 8, "Atari fire release restores red background");
            }
            if (Set.of("snes", "md", "gb", "gbc", "sms", "gg", "sg1000", "ws", "wsc", "pce").contains(platform)) {
                int button = Set.of("md", "sms", "gg", "sg1000").contains(platform) ? 0 : 8;
                AvFrame pressed = run(runtime, new InputFrame(List.of(new PortInput(1 << button))), 12);
                if (platform.equals("gb")) check(channel(pressed, 1, 0) + 20 < channel(frame, 1, 0), "Original DMG A changes its tile palette");
                else if (platform.equals("ws")) check(channel(pressed, 1, 0) > channel(frame, 1, 0) + 60, "Original WonderSwan A changes monochrome palette");
                else check(channel(pressed, 1, 1) > 160 && channel(pressed, 1, 0) < 100, "Original " + platform + " P1 primary button changes its tile palette to green");
                AvFrame released = run(runtime, InputFrame.idle(), 12);
                check(Math.abs(channel(released, 1, 0) - channel(frame, 1, 0)) < 8, "Original " + platform + " P1 release restores the initial palette");
            }
            if (platform.equals("n64")) {
                check(channel(frame, 1, 0) > 180 && channel(frame, 1, 2) < 30, "Original VI upper half is red, top row first");
                check(channel(frame, 3, 2) > 180 && channel(frame, 3, 0) < 30, "Original VI lower half is blue");
                AvFrame p1 = run(runtime, new InputFrame(List.of(new PortInput(1))), 12);
                check(channel(p1, 1, 1) > 180 && channel(p1, 1, 0) < 30, "N64 P1 A reads through SI/PIF and turns upper half green");
                check(channel(p1, 3, 2) > 180 && channel(p1, 3, 0) < 30, "N64 P1 leaves P2 half unchanged");
                AvFrame p2 = run(runtime, new InputFrame(List.of(PortInput.IDLE, new PortInput(1))), 12);
                check(channel(p2, 1, 0) > 180 && channel(p2, 1, 1) < 30, "N64 P2 leaves P1 half unchanged");
                check(channel(p2, 3, 0) > 180 && channel(p2, 3, 1) > 180 && channel(p2, 3, 2) < 30, "N64 P2 A turns lower half yellow");
            }
            DiskState disk = runtime.diskState();
            System.out.println("Disk state " + disk);
            if (platform.equals("fds")) {
                check(channel(frame, 1, 0) > 180 && channel(frame, 1, 1) < 80, "Original FDS side A payload read and executed from disk");
                check(channel(frame, 3, 0) < 30 && channel(frame, 3, 1) < 30, "Original FDS side A lower half black");
                // The pinned FCEUmm exposes disk operations through documented
                // RetroPad L/R, and explicitly has no disk-control interface.
                run(runtime, new InputFrame(List.of(new PortInput(1 << 11))), 2);
                run(runtime, InputFrame.idle(), 3);
                run(runtime, new InputFrame(List.of(new PortInput(1 << 10))), 2);
                run(runtime, InputFrame.idle(), 3);
                run(runtime, new InputFrame(List.of(new PortInput(1 << 11))), 2);
                AvFrame sideB = run(runtime, InputFrame.idle(), 150);
                check(channel(sideB, 1, 1) > 100 && channel(sideB, 1, 0) < 40, "FDS eject, side switch and insert reads side B green payload");
                check(channel(sideB, 3, 0) < 30 && channel(sideB, 3, 1) < 30, "FDS side B lower half black");
            }
            byte[] state = runtime.saveState(); check(state.length > 16, "Native content saved");
            runtime.step(InputFrame.idle()); runtime.loadState(state); runtime.step(InputFrame.idle());
            check(runtime.isAlive(), "Native state restored");
            checkpoint = state;
        } finally { runtime.close(); }
        check(!runtime.isAlive() && runtime.exitCode().orElse(-1) == 0 && !runtime.wasForcedShutdown(), "Normal worker exit");
        check(runtime.nativeTemporaryFilesCleaned(), "Native extraction cleaned");
        check(ProcessHandle.of(pid).isEmpty(), "Owned process released");
        ProcessConsoleRuntime restored = ProcessConsoleRuntime.open(config); long restoredPid = restored.workerPid();
        try {
            check(restoredPid != pid, "Checkpoint reopens in a new native process");
            // Initialize the core before accepting a previous process's state.
            run(restored, InputFrame.idle(), platform.equals("fds") ? 120 : 2);
            restored.loadState(checkpoint); AvFrame after = run(restored, InputFrame.idle(), 20);
            check(after.rgba().length > 0 && !after.shutdown(), "Checkpoint executes after crossing process boundary");
            if (Set.of("snes", "md", "sms", "gg", "sg1000", "gb", "gbc", "a2600", "a7800", "ws", "wsc", "pce", "ngp", "ngpc", "vb", "lynx").contains(platform)) {
                check(after.width() == expectedFrame.width() && after.height() == expectedFrame.height(), "Restored diagnostic retains video geometry");
                boolean pattern = true;
                for (int quarter : new int[]{1,3}) for (int component = 0; component < 3; component++)
                    pattern &= Math.abs(channel(after,quarter,component) - channel(expectedFrame,quarter,component)) < 8;
                check(pattern, "Restored diagnostic retains its expected upper/lower color pattern");
            }
            if (platform.equals("fds")) check(channel(after, 1, 1) > 100 && channel(after, 1, 0) < 40, "FDS side B selection and RAM restored in new process");
        } finally { restored.close(); }
        check(!restored.isAlive() && restored.exitCode().orElse(-1) == 0 && !restored.wasForcedShutdown(), "Restored worker exits normally");
        check(restored.nativeTemporaryFilesCleaned() && ProcessHandle.of(restoredPid).isEmpty(), "Restored worker and native files released");
        System.out.println(platform + " platform checks passed: " + checks);
    }
    private static AvFrame run(ConsoleRuntime runtime, InputFrame input, int frames) throws Exception {
        AvFrame result = null; for (int i = 0; i < frames; i++) result = runtime.step(input); return result;
    }
    private static int channel(AvFrame frame, int quarter, int channel) {
        return frame.rgba()[((frame.height() * quarter / 4) * frame.width() + frame.width() / 2) * 4 + channel] & 255;
    }
}
