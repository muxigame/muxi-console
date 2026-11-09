package net.muxigame.console.tests.transfer;

import java.io.*;
import java.nio.file.Path;
import net.muxigame.console.runtime.OwnedProcess;

/** Real independent Java children; only the explicitly captured child's handle may be terminated. */
public final class OwnedProcessTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static Process child(String mode) throws Exception {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-Xmx32m", "-XX:ActiveProcessorCount=1", "-cp", System.getProperty("java.class.path"), Child.class.getName(), mode).start();
    }
    private static void ready(Process process) throws Exception { check(new BufferedReader(new InputStreamReader(process.getInputStream())).readLine().equals("READY"), "real child reached its controlled wait"); }
    public static void main(String[] args) throws Exception {
        OwnedProcess.prepare();
        Process first = child("natural");
        try (OwnedProcess natural = OwnedProcess.capture(first)) {
            ready(first); check(natural.isAlive() && natural.exitCode().isEmpty(), "live child has no final exit code");
            Process second = child("hold");
            try (OwnedProcess forced = OwnedProcess.capture(second)) {
                ready(second); check(!forced.awaitExit(20), "a wait timeout is not successful termination");
                check(forced.terminate(), "explicit termination targets the captured generation");
                check(forced.awaitExit(5000), "forced process produces its termination signal");
                check(!forced.isAlive() && forced.exitCode().orElse(0) != 0, "forced termination cannot be a natural zero exit");
                check(natural.isAlive(), "terminating one worker leaves the independent child alive");
            }
            first.getOutputStream().close(); check(natural.awaitExit(5000), "EOF permits natural exit");
            check(natural.exitCode().orElse(-1) == 0, "natural exit retains zero");
            check(!natural.terminate(), "an already signaled process is never terminated again");
            natural.close(); check(!natural.isAlive() && natural.exitCode().orElse(-1) == 0, "confirmed result remains readable after closing the handle");
        }
        Process third = child("failure");
        try (OwnedProcess failure = OwnedProcess.capture(third)) {
            ready(third); third.getOutputStream().close(); check(failure.awaitExit(5000), "real failed child fully exits");
            check(failure.exitCode().orElse(-1) == 23, "failure is retained instead of being reported as natural success");
        }
        System.out.println("OwnedProcessTest " + checks + " checks");
    }
    public static final class Child {
        public static void main(String[] args) throws Exception {
            System.out.println("READY"); System.out.flush();
            if (args[0].equals("hold")) Thread.sleep(60000); else System.in.read();
            if (args[0].equals("failure")) System.exit(23);
        }
    }
}
