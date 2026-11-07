package net.muxigame.console.runtime;

import java.nio.file.*;
import java.util.*;

/** Independently authored process/ABI integration checks against explicitly supplied public cores. */
public final class RuntimeSmoke {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static AvFrame run(ConsoleRuntime runtime, InputFrame input, int count) throws Exception {
        AvFrame frame = null;
        for (int i = 0; i < count; i++) frame = runtime.step(input);
        return frame;
    }
    public static void main(String[] args) throws Exception {
        Path javaExecutable = Path.of(args[0]); List<Path> classpath = Arrays.stream(args[1].split(java.io.File.pathSeparator)).map(Path::of).toList();
        Path core = Path.of(args[2]), output = Path.of(args[3]); Path content = args.length > 4 ? Path.of(args[4]) : null;
        try { new InputFrame(Collections.nCopies(5, PortInput.IDLE)); throw new AssertionError("Five ports accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        check(InputFrame.idle().port(3).equals(PortInput.IDLE), "Missing ports must be idle");
        ProcessConsoleRuntime runtime = ProcessConsoleRuntime.open(new LaunchConfig(javaExecutable, classpath, core, content, output.resolve("system"), output.resolve("save")));
        long pid = runtime.workerPid();
        try {
            check(pid != ProcessHandle.current().pid() && runtime.isAlive(), "Core must have its own live process");
            check(!runtime.info().name().isBlank() && runtime.info().fps() > 0, "Core metadata");
            AvFrame baseline = run(runtime, InputFrame.idle(), 90);
            check(baseline.width() > 0 && baseline.height() > 0 && baseline.rgba().length == baseline.width() * baseline.height() * 4, "Packed video frame");
            check((baseline.stereoPcm().length & 1) == 0, "Interleaved stereo output");
            check(baseline.number() == 89, "Monotonic frame counter");
            if (content != null) {
                byte[] initialSram = runtime.readSram(); check(initialSram.length == 8192, "Battery-backed diagnostic cartridge SRAM");
                byte[] desiredSram = initialSram.clone(); desiredSram[31] = 91; runtime.writeSram(desiredSram);
                byte[] snapshot = runtime.saveState(); check(snapshot.length > desiredSram.length, "State envelope includes core and SRAM");
                byte[] changedSram = desiredSram.clone(); changedSram[31] = 7; runtime.writeSram(changedSram);
                check(runtime.readSram()[31] == 7, "Explicit SRAM write");
                runtime.loadState(snapshot); check(Arrays.equals(runtime.readSram(), desiredSram), "State restore includes SRAM");
                try { runtime.writeSram(new byte[3]); throw new AssertionError("Invalid SRAM size accepted"); }
                catch (java.io.IOException expected) { checks++; }
                check(Arrays.equals(runtime.readSram(), desiredSram), "Rejected SRAM write leaves memory intact");
                AvFrame p1 = run(runtime, new InputFrame(List.of(new PortInput(1 << 8))), 5);
                int left = ((baseline.height() / 2) * baseline.width() + baseline.width() / 4) * 4;
                int right = ((baseline.height() / 2) * baseline.width() + baseline.width() * 3 / 4) * 4;
                check(!samePixel(baseline, p1, left), "P1 A changes the left half");
                check(samePixel(baseline, p1, right), "P1 A leaves the right half unchanged");
                check(runtime.readSram()[0] == 1 && runtime.readSram()[1] != 2, "P1 isolated SRAM marker");
                runtime.loadState(snapshot);
                AvFrame p2 = run(runtime, new InputFrame(List.of(PortInput.IDLE, new PortInput(1 << 8))), 5);
                check(samePixel(baseline, p2, left), "P2 A leaves the left half unchanged");
                check(!samePixel(baseline, p2, right), "P2 A changes the right half");
                check(runtime.readSram()[0] != 1 && runtime.readSram()[1] == 2, "P2 isolated SRAM marker");
                runtime.loadState(snapshot);
                AvFrame restored = run(runtime, InputFrame.idle(), 5);
                check(samePixel(baseline, restored, left) && samePixel(baseline, restored, right), "Core state restores both display halves");
                runtime.reset(); run(runtime, InputFrame.idle(), 90); checks++;
            } else {
                // Public 2048 is a content-free ABI probe, not an emulator compatibility claim.
                run(runtime, new InputFrame(List.of(new PortInput(1 << 7), PortInput.IDLE, PortInput.IDLE, PortInput.IDLE)), 3); checks++;
            }
        } finally { runtime.close(); }
        check(!runtime.isAlive() && runtime.exitCode().orElse(-1) == 0 && !runtime.wasForcedShutdown(), "Worker must exit normally with code zero");
        check(ProcessHandle.of(pid).isEmpty(), "Owned worker process released");
        System.out.println("PASS " + checks + " runtime checks; core=" + runtime.info().name() + "; worker=" + pid + "; exit=0; forced=false");
    }
    private static boolean samePixel(AvFrame a, AvFrame b, int offset) {
        return Arrays.equals(Arrays.copyOfRange(a.rgba(), offset, offset + 4), Arrays.copyOfRange(b.rgba(), offset, offset + 4));
    }
}
