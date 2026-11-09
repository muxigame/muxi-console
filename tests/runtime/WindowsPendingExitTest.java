package net.muxigame.console.tests.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import net.muxigame.console.runtime.*;

/** Manual native fixture: DLL_PROCESS_DETACH stays live after ExitProcess sets its reported exit code. */
public final class WindowsPendingExitTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); System.out.println("CHECK " + message); }
    public static void main(String[] args) throws Exception {
        Path dll = Path.of(args[0]).toRealPath(), jna = Path.of(args[1]).toRealPath(), root = Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(root); Path classes = root.resolve("classes"), source = root.resolve("source/net/muxigame/console/runtime/WorkerMain.java");
        Files.createDirectories(classes); Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package net.muxigame.console.runtime;
            public final class WorkerMain {
                public static void main(String[] args) {
                    com.sun.jna.NativeLibrary.getInstance(System.getProperty("fixture.dll"));
                    System.out.println("READY"); System.out.flush();
                    try { System.in.read(); } catch (Exception ignored) { }
                    System.exit(23);
                }
            }
            """);
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-cp", jna.toString(), "-d", classes.toString(), source.toString()) == 0, "compile the independent real child");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        OwnedProcess.prepare();
        Process child = new ProcessBuilder(java.toString(), "-Xmx32m", "-XX:ActiveProcessorCount=1", "-Dfixture.dll=" + dll, "-cp", classes + File.pathSeparator + jna, "net.muxigame.console.runtime.WorkerMain").start();
        try (OwnedProcess owned = OwnedProcess.capture(child)) {
            check(new BufferedReader(new InputStreamReader(child.getInputStream())).readLine().equals("READY"), "real DLL loaded only inside the owned child");
            child.getOutputStream().close(); long deadline = System.nanoTime() + 5_000_000_000L;
            while (owned.reportedExitCode().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            check(owned.reportedExitCode().orElse(-1) == 23, "Windows reports exit23 while detach is still blocked");
            check(owned.isAlive() && owned.exitCode().isEmpty() && !owned.awaitExit(50), "unsignaled handle is alive despite the reported exit code");
            check(owned.terminate(), "terminate the exact owned pending-exit handle");
            check(owned.awaitExit(5000), "termination completes before native files can be cleaned");
            check(owned.exitCode().orElse(-1) == 23, "forced cleanup preserves the original nonzero failure");
        }
        // The actual runtime's pre-connect failure also has to use the same guard.
        String fixture = Files.readString(source).replace("System.getProperty(\"fixture.dll\")", "\"" + dll.toString().replace("\\", "\\\\") + "\"").replace("try { System.in.read(); } catch (Exception ignored) { }", "");
        Files.writeString(source, fixture);
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-cp", jna.toString(), "-d", classes.toString(), source.toString()) == 0, "compile runtime startup fixture");
        Path dummy = root.resolve("not-an-emulator.dll"); Files.write(dummy, new byte[] { 0 }); long started = System.nanoTime();
        try { ProcessConsoleRuntime.open(new LaunchConfig(java, List.of(classes, jna), dummy, null, root.resolve("system"), root.resolve("save"))); throw new AssertionError("startup failure accepted"); }
        catch (IOException expected) { check(expected.getMessage().contains("exit=23") && expected.getMessage().contains("forced=true"), "startup preserves the failure and discloses forced termination"); }
        check(System.nanoTime() - started < 10_000_000_000L, "pending exit is detected without waiting for the full accept deadline");
        try (var files = Files.list(root)) { check(files.noneMatch(p -> p.getFileName().toString().startsWith("muxi-console-worker-")), "native temporary cleanup occurs after the real process signal"); }
        System.out.println("WindowsPendingExitTest " + checks + " checks");
    }
}
