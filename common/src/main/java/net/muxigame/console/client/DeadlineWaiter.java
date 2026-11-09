package net.muxigame.console.client;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/** Per-session monotonic waiting. Await and close are serialized by the emulator thread. */
public final class DeadlineWaiter implements AutoCloseable {
    private static final long SLICE_NANOS = 50_000_000L;
    private final WindowsTimer windows;
    private boolean closed;

    private DeadlineWaiter(WindowsTimer windows) { this.windows = windows; }

    public static DeadlineWaiter create() throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"))
            return new DeadlineWaiter(null);
        try { return new DeadlineWaiter(new WindowsTimer()); }
        catch (LinkageError | RuntimeException failure) {
            throw new IOException("Windows high-resolution timer API is unavailable", failure);
        }
    }

    /** Absolute System.nanoTime deadline, with the usual less-than-half-range horizon. */
    public synchronized void awaitUntil(long monotonicDeadlineNanos) throws IOException, InterruptedException {
        if (closed) throw new IOException("Deadline waiter is closed");
        while (true) {
            if (Thread.interrupted()) throw new InterruptedException("Deadline wait interrupted");
            // Subtraction deliberately supports nanoTime's signed counter wrapping.
            long remaining = monotonicDeadlineNanos - System.nanoTime();
            if (remaining <= 0) return;
            long slice = Math.min(remaining, SLICE_NANOS);
            if (windows == null) LockSupport.parkNanos(this, slice);
            else windows.await(slice);
        }
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        if (windows != null) windows.close();
        closed = true;
    }

    /** Never initialized on non-Windows platforms; no jna-platform dependency. */
    private static final class WindowsTimer {
        private interface Kernel32 extends StdCallLibrary {
            Pointer CreateWaitableTimerExW(Pointer attributes, WString name, int flags, int access);
            int SetWaitableTimer(Pointer timer, LongByReference dueTime, int period,
                                 Pointer completionRoutine, Pointer argument, int resume);
            int WaitForSingleObject(Pointer object, int milliseconds);
            int CloseHandle(Pointer object);
        }
        private static final Kernel32 API = Native.load("kernel32", Kernel32.class);
        private final LongByReference dueTime = new LongByReference();
        private Pointer handle;

        WindowsTimer() throws IOException {
            // HIGH_RESOLUTION only: omitting MANUAL_RESET gives one waiter an auto-reset signal.
            // SYNCHRONIZE | TIMER_MODIFY_STATE, unnamed and non-inheritable.
            handle = API.CreateWaitableTimerExW(null, null, 2, 0x00100002);
            if (handle == null) throw error("Cannot create a Windows high-resolution timer (Windows 10 1803+ required)");
        }

        void await(long nanos) throws IOException {
            // The 50 ms slice bounds the conversion; ceil avoids an early 100 ns expiration.
            long units = nanos / 100 + (nanos % 100 == 0 ? 0 : 1);
            dueTime.setValue(-Math.max(1, units));
            if (API.SetWaitableTimer(handle, dueTime, 0, null, null, 0) == 0)
                throw error("Cannot arm the Windows high-resolution timer");
            int result = API.WaitForSingleObject(handle, 50);
            if (result != 0 && result != 258)
                throw error("Cannot wait for the Windows high-resolution timer");
            // A timeout, early signal or scheduler delay always returns to the monotonic check.
        }

        void close() throws IOException {
            if (handle == null) return;
            if (API.CloseHandle(handle) == 0) throw error("Cannot close the Windows high-resolution timer");
            handle = null;
        }

        private static IOException error(String message) {
            return new IOException(message + " (Windows error " + Native.getLastError() + ")");
        }
    }
}
