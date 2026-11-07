package net.muxigame.console.runtime;

import java.nio.file.*;
import java.util.*;
import java.io.File;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Measures Pong's paddle columns, never its moving ball or a whole-frame checksum. */
public final class PongInputSmoke {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static AvFrame run(ConsoleRuntime runtime, InputFrame input, int count) throws Exception {
        AvFrame frame = null; for (int i = 0; i < count; i++) frame = runtime.step(input); return frame;
    }
    private record Paddle(int x, int minY, int maxY) { double center() { return (minY + maxY) * .5; } }
    private static Paddle paddle(AvFrame frame, int x) {
        byte[] rgba = frame.rgba(); int bestStart = -1, bestEnd = -1, start = -1;
        for (int y = 0; y <= frame.height(); y++) {
            int offset = (y * frame.width() + x) * 4;
            boolean bright = y < frame.height() && (rgba[offset] & 255) > 140 && (rgba[offset + 1] & 255) > 140 && (rgba[offset + 2] & 255) > 140;
            if (bright && start < 0) start = y;
            if (!bright && start >= 0) {
                if (y - start > bestEnd - bestStart + 1) { bestStart = start; bestEnd = y - 1; }
                start = -1;
            }
        }
        if (bestStart < 0 || bestEnd - bestStart + 1 < 8 || bestEnd - bestStart + 1 > 20) throw new AssertionError("No paddle at x=" + x);
        return new Paddle(x, bestStart, bestEnd);
    }
    private record Observation(Paddle left, Paddle right) { Paddle port(int index) { return index == 0 ? left : right; } }
    private static Observation observe(String label, AvFrame frame, Path output) throws Exception {
        if (frame.width() != 1552 || frame.height() != 240) throw new AssertionError("Unexpected Pong raster " + frame.width() + "x" + frame.height());
        Paddle left = paddle(frame, 232), right = paddle(frame, 1255);
        System.out.println("PONG_CASE {\"label\":\"" + label + "\",\"width\":" + frame.width() + ",\"height\":" + frame.height()
                + ",\"leftX\":" + left.x + ",\"leftMinY\":" + left.minY + ",\"leftMaxY\":" + left.maxY
                + ",\"rightX\":" + right.x + ",\"rightMinY\":" + right.minY + ",\"rightMaxY\":" + right.maxY + "}");
        BufferedImage image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_RGB); byte[] raw = frame.rgba();
        for (int y = 0; y < frame.height(); y++) for (int x = 0; x < frame.width(); x++) {
            int i = (y * frame.width() + x) * 4; image.setRGB(x, y, (raw[i] & 255) << 16 | (raw[i + 1] & 255) << 8 | raw[i + 2] & 255);
        }
        ImageIO.write(image, "PNG", output.resolve(label + ".png").toFile());
        return new Observation(left, right);
    }
    public static void main(String[] args) throws Exception {
        List<Path> classpath = Arrays.stream(args[1].split(File.pathSeparator)).map(Path::of).toList(); Path output = Path.of(args[4]);
        ProcessConsoleRuntime runtime = ProcessConsoleRuntime.open(new LaunchConfig(Path.of(args[0]), classpath, Path.of(args[2]), Path.of(args[3]), output.resolve("system"), output.resolve("save")));
        long pid = runtime.workerPid();
        try {
            // Pong displays score digits before the first coin, not playable
            // paddles. Use the core's documented RetroPad Select coin input.
            run(runtime, new InputFrame(List.of(new PortInput(1 << 2))), 3);
            run(runtime, InputFrame.idle(), 3);
            Observation baseline = observe("idle", run(runtime, InputFrame.idle(), 120), output);
            for (int port = 0; port < 2; port++) for (int sign : new int[]{1, -1}) {
                PortInput state = new PortInput(0, (short) (sign * 24000), (short) 0, (short) 0, (short) 0);
                Observation moved = observe("p" + (port + 1) + (sign > 0 ? "-up" : "-down"), run(runtime, new InputFrame(port == 0 ? List.of(state) : List.of(PortInput.IDLE, state)), 30), output);
                check(sign > 0 ? moved.port(port).center() < baseline.port(port).center() - 60 : moved.port(port).center() > baseline.port(port).center() + 60, "Analog input moves the selected paddle by over 60 raster rows");
                check(Math.abs(moved.port(1 - port).center() - baseline.port(1 - port).center()) <= 2, "Analog input leaves the other paddle fixed");
                run(runtime, InputFrame.idle(), 10);
            }
            Observation lastDigital = null;
            for (int port = 0; port < 2; port++) {
                AvFrame beforeFrame = run(runtime, InputFrame.idle(), 10);
                Observation before = new Observation(paddle(beforeFrame, 232), paddle(beforeFrame, 1255));
                PortInput state = new PortInput(1 << 6);
                Observation moved = observe("p" + (port + 1) + "-digital-left", run(runtime, new InputFrame(port == 0 ? List.of(state) : List.of(PortInput.IDLE,state)), 60), output);
                check(moved.port(port).center() > before.port(port).center() + 60, "Digital left moves the selected paddle down");
                check(Math.abs(moved.port(1 - port).center() - before.port(1 - port).center()) <= 2, "Digital input leaves the other paddle fixed");
                lastDigital = moved;
                run(runtime, InputFrame.idle(), 10);
            }
            Observation released = observe("released", run(runtime, InputFrame.idle(), 10), output);
            for (int port = 0; port < 2; port++) check(Math.abs(released.port(port).center() - lastDigital.port(port).center()) <= 2, "Pong's non-centering digital paddle retains its released position");
        } finally { runtime.close(); }
        check(!runtime.isAlive() && runtime.exitCode().orElse(-1) == 0 && !runtime.wasForcedShutdown(), "Pong worker exits normally");
        check(runtime.nativeTemporaryFilesCleaned() && ProcessHandle.of(pid).isEmpty(), "Owned Pong process and native files are released");
        System.out.println("PONG_ASSERTIONS " + checks);
        System.out.println("PONG_CLOSED exit0 forced=false cleaned=true");
    }
}
