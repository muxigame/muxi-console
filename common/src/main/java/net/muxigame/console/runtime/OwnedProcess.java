package net.muxigame.console.runtime;

import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

/** An owned process generation. On Windows an exit code alone does not prove termination. */
public abstract class OwnedProcess implements AutoCloseable {
    private static boolean windows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

    /** Fail before spawning a worker if Windows process supervision is unavailable. */
    public static void prepare() throws IOException {
        if (windows()) try { WindowsOwnedProcess.prepare(); }
        catch (RuntimeException | LinkageError unavailable) { throw new IOException("Windows owned-process supervision is unavailable", unavailable); }
    }

    public static OwnedProcess capture(Process process) throws IOException {
        if (windows()) {
            Instant expected = process.info().startInstant().orElseThrow(() -> new IOException("Owned process creation time is unavailable"));
            return new WindowsOwnedProcess(process, expected);
        }
        return new JavaOwnedProcess(process);
    }

    public abstract boolean isAlive() throws IOException;
    public abstract OptionalInt exitCode() throws IOException;
    /** A nonempty value may mean that exit has started while native detach is still running. */
    public abstract OptionalInt reportedExitCode() throws IOException;
    public abstract boolean awaitExit(int milliseconds) throws IOException;
    /** Returns true only when an explicit termination was issued to this exact owned process. */
    public abstract boolean terminate() throws IOException;
    @Override public abstract void close() throws IOException;

    private static final class JavaOwnedProcess extends OwnedProcess {
        private final Process process;
        JavaOwnedProcess(Process process) { this.process = process; }
        public boolean isAlive() { return process.isAlive(); }
        public OptionalInt exitCode() { return process.isAlive() ? OptionalInt.empty() : OptionalInt.of(process.exitValue()); }
        public OptionalInt reportedExitCode() { return exitCode(); }
        public boolean awaitExit(int milliseconds) throws IOException {
            if (milliseconds < 0 || milliseconds > 60000) throw new IOException("Invalid owned process wait bound");
            try { return process.waitFor(milliseconds, TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return !process.isAlive(); }
        }
        public boolean terminate() { if (!process.isAlive()) return false; process.destroyForcibly(); return true; }
        public void close() { }
    }
}
