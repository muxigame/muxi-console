package net.muxigame.console.runtime;

import java.nio.file.*;
import java.util.*;
import java.io.*;

/** The write mode also runs against our frozen MXCW-v1 worker and its unchanged public constructors. */
public final class LegacyStateSmoke {
    public static void main(String[] args) throws Exception {
        boolean write = args[0].equals("write");
        List<Path> cp = Arrays.stream(args[2].split(File.pathSeparator)).map(Path::of).toList();
        Path output = Path.of(args[5]); String platform = args[6];
        ProcessConsoleRuntime runtime = ProcessConsoleRuntime.open(new LaunchConfig(Path.of(args[1]), cp,
                Path.of(args[3]), Path.of(args[4]), output.resolve("legacy-state-system"), output.resolve(platform + "-legacy-state-save")));
        try {
            Path checkpoint = output.resolve(platform + "-from-v1.mxs1");
            if (write) {
                for (int i = 0; i < 90; i++) runtime.step(InputFrame.idle());
                for (int i = 0; i < 18; i++) runtime.step(new InputFrame(List.of(new PortInput(platform.equals("gba") ? 1 << 3 : 1 << 8))));
                byte[] sram = runtime.readSram(); sram[31] = 90; runtime.writeSram(sram);
                Files.write(checkpoint, runtime.saveState());
                System.out.println(platform + " legacy worker wrote " + Files.size(checkpoint) + " MXS1 bytes");
            } else {
                byte[] raw = Files.readAllBytes(checkpoint);
                try (var input = new DataInputStream(new ByteArrayInputStream(raw))) {
                    if (input.readInt() != 0x4d585331) throw new AssertionError("Legacy MXS1 magic changed");
                    for (int part = 0; part < 3; part++) { int size = input.readInt(); if (size < 0 || size > input.available()) throw new AssertionError("Invalid legacy envelope"); input.skipNBytes(size); }
                    if (input.available() != 0) throw new AssertionError("Legacy envelope gained metadata");
                }
                runtime.loadState(raw); AvFrame frame = null;
                for (int i = 0; i < 5; i++) frame = runtime.step(InputFrame.idle());
                if (runtime.readSram()[31] != 90) throw new AssertionError("Legacy SRAM marker was not restored");
                if (!Double.isFinite(frame.displayAspect()) || frame.displayAspect() <= 0) throw new AssertionError("Legacy state has invalid display aspect");
                System.out.println(platform + " v2 worker restored legacy MXS1; raster=" + frame.width() + "x" + frame.height() + "; aspect=" + frame.displayAspect());
            }
        } finally { runtime.close(); }
        if (runtime.isAlive() || runtime.exitCode().orElse(-1) != 0 || runtime.wasForcedShutdown() || !runtime.nativeTemporaryFilesCleaned())
            throw new AssertionError("Legacy compatibility worker did not close normally");
    }
}
