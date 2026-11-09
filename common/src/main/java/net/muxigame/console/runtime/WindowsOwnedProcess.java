package net.muxigame.console.runtime;

import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.time.Instant;
import java.util.OptionalInt;

/** Loaded only on Windows. The retained kernel handle cannot target a later reuse of the PID. */
final class WindowsOwnedProcess extends OwnedProcess {
    private interface Kernel32 extends StdCallLibrary {
        Pointer OpenProcess(int rights, int inherit, int pid);
        int GetProcessTimes(Pointer process, LongByReference creation, LongByReference exit, LongByReference kernel, LongByReference user);
        int GetExitCodeProcess(Pointer process, IntByReference code);
        int WaitForSingleObject(Pointer object, int milliseconds);
        int TerminateProcess(Pointer process, int code);
        int CloseHandle(Pointer object);
    }
    private static final Kernel32 API = Native.load("kernel32", Kernel32.class);
    private Pointer handle;
    private boolean exited;
    private int finalCode;

    public static void prepare() { /* Initializes the Windows-only API before the child is spawned. */ }

    WindowsOwnedProcess(Process process, Instant expected) throws IOException {
        long pid = process.pid();
        if (pid < 1 || pid > 0xffffffffL) throw new IOException("Invalid owned process PID");
        Pointer opened = API.OpenProcess(0x100000 | 0x1000 | 1, 0, (int)pid);
        if (opened == null) throw error("Cannot open the owned worker process");
        try {
            var creation = new LongByReference();
            if (API.GetProcessTimes(opened, creation, new LongByReference(), new LongByReference(), new LongByReference()) == 0)
                throw error("Cannot verify the owned worker creation time");
            long milliseconds = creation.getValue() / 10000L - 11644473600000L;
            if (milliseconds != expected.toEpochMilli()) throw new IOException("Owned worker process generation changed");
            handle = opened; opened = null;
        } finally { if (opened != null) API.CloseHandle(opened); }
    }

    private static IOException error(String message) { return new IOException(message + " (Windows error " + Native.getLastError() + ")"); }
    private void requireHandle() throws IOException { if (handle == null) throw new IOException("Owned worker handle is closed before confirmed termination"); }
    private int readCode() throws IOException {
        var value = new IntByReference();
        if (API.GetExitCodeProcess(handle, value) == 0) throw error("Cannot read the owned worker exit code");
        return value.getValue();
    }
    private boolean waitFor(int milliseconds) throws IOException {
        if (exited) return true;
        requireHandle(); int result = API.WaitForSingleObject(handle, milliseconds);
        if (result == 0) { finalCode = readCode(); exited = true; return true; }
        if (result == 258) return false;
        throw error("Cannot wait for the owned worker termination signal");
    }
    public synchronized boolean isAlive() throws IOException { return !waitFor(0); }
    public synchronized OptionalInt exitCode() throws IOException { return waitFor(0) ? OptionalInt.of(finalCode) : OptionalInt.empty(); }
    public synchronized OptionalInt reportedExitCode() throws IOException {
        if (waitFor(0)) return OptionalInt.of(finalCode);
        int value = readCode(); return value == 259 ? OptionalInt.empty() : OptionalInt.of(value);
    }
    public synchronized boolean awaitExit(int milliseconds) throws IOException {
        if (milliseconds < 0 || milliseconds > 60000) throw new IOException("Invalid owned process wait bound");
        return waitFor(milliseconds);
    }
    public synchronized boolean terminate() throws IOException {
        if (waitFor(0)) return false;
        int reported = readCode(), code = reported != 0 && reported != 259 ? reported : 1;
        if (API.TerminateProcess(handle, code) == 0) {
            if (waitFor(0)) return false;
            throw error("Cannot terminate the exact owned worker");
        }
        return true;
    }
    public synchronized void close() throws IOException {
        if (handle == null) return;
        // Cache a real termination signal before releasing the native handle.
        waitFor(0);
        if (API.CloseHandle(handle) == 0) throw error("Cannot close the owned worker handle");
        handle = null;
    }
}
