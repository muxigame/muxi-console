package net.muxigame.console.runtime;

import java.nio.file.*;
import java.util.*;
import java.io.File;

/** Focused real-core shutdown sequences, including the product's save-and-continue path. */
public final class N64CloseSmoke {
    public static void main(String[] args) throws Exception {
        List<Path> classpath = Arrays.stream(args[1].split(File.pathSeparator)).map(Path::of).toList();
        Path output = Path.of(args[4]); String sequence = args[5]; int repeats = Integer.parseInt(args[6]);
        Map<String,String> options = Map.of("mupen64plus-rdp-plugin", "gliden64", "mupen64plus-rsp-plugin", "hle", "mupen64plus-cpucore", "pure_interpreter");
        LaunchConfig config = new LaunchConfig(Path.of(args[0]), classpath, Path.of(args[2]), Path.of(args[3]), output.resolve("close-system"), output.resolve("close-save"), options);
        for (int repeat = 0; repeat < repeats; repeat++) {
            byte[] saved = null;
            ProcessConsoleRuntime runtime = ProcessConsoleRuntime.open(config); long pid = runtime.workerPid();
            try {
                if (!sequence.equals("open-only")) for (int i = 0; i < 120; i++) runtime.step(InputFrame.idle());
                System.out.println("close-sequence " + sequence + " repeat " + repeat + " pid " + pid);
                if (!sequence.equals("no-save") && !sequence.equals("open-only")) {
                    byte[] state = runtime.saveState();
                    saved = state;
                    System.out.println("saved " + state.length);
                    if (sequence.equals("save-continue")) for (int i = 0; i < 5; i++) runtime.step(InputFrame.idle());
                    if (sequence.equals("save-load")) { runtime.step(InputFrame.idle()); runtime.loadState(state); runtime.step(InputFrame.idle()); }
                }
            } finally { runtime.close(); }
            if (runtime.isAlive() || runtime.exitCode().orElse(-1) != 0 || runtime.wasForcedShutdown() || !runtime.nativeTemporaryFilesCleaned() || ProcessHandle.of(pid).isPresent())
                throw new AssertionError("N64 worker did not close normally");
            System.out.println("closed exit0 forced=false cleaned=true");
            if (sequence.equals("fresh-load")) {
                ProcessConsoleRuntime fresh = ProcessConsoleRuntime.open(config); long freshPid = fresh.workerPid();
                try {
                    fresh.loadState(saved);
                    for (int i = 0; i < 5; i++) fresh.step(InputFrame.idle());
                } finally { fresh.close(); }
                if (fresh.isAlive() || fresh.exitCode().orElse(-1) != 0 || fresh.wasForcedShutdown() || !fresh.nativeTemporaryFilesCleaned() || ProcessHandle.of(freshPid).isPresent())
                    throw new AssertionError("Fresh N64 restore did not close normally");
                System.out.println("fresh load-before-step closed exit0 forced=false cleaned=true");
            }
        }
    }
}
