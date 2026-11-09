import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import net.muxigame.console.client.DeadlineWaiter;

/** Actual same-thread Windows wait precision; neither Minecraft nor an emulator is started. */
public final class DeadlineWaiterProbe {
    private static final double FPS = 59.7275;
    private static final int FRAMES = 600;
    private static int checks;
    private interface Kernel32 extends StdCallLibrary {
        Pointer GetCurrentProcess();
        int GetProcessHandleCount(Pointer process, IntByReference count);
    }
    private static final Kernel32 API = Native.load("kernel32", Kernel32.class);
    private static int handles() throws IOException {
        var count = new IntByReference();
        if (API.GetProcessHandleCount(API.GetCurrentProcess(), count) == 0)
            throw new IOException("GetProcessHandleCount failed: " + Native.getLastError());
        return count.getValue();
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static long percentile(long[] samples, double fraction) {
        long[] sorted = samples.clone();Arrays.sort(sorted);
        return sorted[(int)Math.ceil(sorted.length * fraction) - 1];
    }
    private static double ms(long nanos) { return nanos / 1e6; }
    private static String distribution(long[] values) {
        return "{\"p50Millis\":" + ms(percentile(values,.5)) + ",\"p95Millis\":" + ms(percentile(values,.95))
                + ",\"p99Millis\":" + ms(percentile(values,.99)) + ",\"minMillis\":" + ms(Arrays.stream(values).min().orElse(0))
                + ",\"maxMillis\":" + ms(Arrays.stream(values).max().orElse(0)) + "}";
    }
    private static String cadence(boolean precise) throws Exception {
        long[] overshoot = new long[FRAMES], intervals = new long[FRAMES];
        var os = (OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        long cpuStart = os.getProcessCpuTime(), started = System.nanoTime(), previous = started;
        try (DeadlineWaiter waiter = precise ? DeadlineWaiter.create() : null) {
            // Exclude native library/handle initialization from the cadence origin.
            started = previous = System.nanoTime();
            for (int i = 0; i < FRAMES; i++) {
                long deadline = started + Math.round((i + 1) * 1e9 / FPS);
                if (precise) waiter.awaitUntil(deadline);
                else {
                    long remaining;
                    while ((remaining = deadline - System.nanoTime()) > 0) {
                        if (Thread.interrupted()) throw new InterruptedException();
                        LockSupport.parkNanos(Math.min(50_000_000L, remaining));
                    }
                }
                long now = System.nanoTime();overshoot[i] = now - deadline;intervals[i] = now - previous;previous = now;
            }
        }
        check(Arrays.stream(overshoot).min().orElse(-1) >= 0, "Wait returned before its monotonic deadline");
        return "{\"method\":\"" + (precise ? "windows-high-resolution" : "LockSupport") + "\",\"frames\":" + FRAMES
                + ",\"fps\":" + FPS + ",\"wallMillis\":" + ms(previous-started) + ",\"processCpuMillis\":" + ms(os.getProcessCpuTime()-cpuStart)
                + ",\"overshoot\":" + distribution(overshoot) + ",\"interval\":" + distribution(intervals)
                + ",\"overshootAbove2ms\":" + Arrays.stream(overshoot).filter(n -> n > 2_000_000L).count()
                + ",\"overshootAbove10ms\":" + Arrays.stream(overshoot).filter(n -> n > 10_000_000L).count() + "}";
    }
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);Files.createDirectories(output);
        check(System.getProperty("os.name").startsWith("Windows"), "This probe requires actual Windows");
        // The management bean retains its own process/performance resources on Windows.
        // Initialize it before measuring the timer's exact handle lifetime.
        ((OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
        try (DeadlineWaiter warm = DeadlineWaiter.create()) { warm.awaitUntil(System.nanoTime()+1_000_000); }
        // HotSpot lazily caches Windows parking/join events when an ordinary Java
        // thread first sleeps and is interrupted. This control never creates a timer.
        int beforeJavaThreadWarmup=handles();CountDownLatch javaWaiting=new CountDownLatch(1);
        Thread javaWarmup=new Thread(()->{javaWaiting.countDown();try{Thread.sleep(5000);}catch(InterruptedException expected){}},"ordinary-java-wait-control");
        javaWarmup.start();check(javaWaiting.await(2,TimeUnit.SECONDS),"Java wait control did not start");
        Thread.sleep(10);javaWarmup.interrupt();javaWarmup.join(2000);check(!javaWarmup.isAlive(),"Java wait control did not stop");
        int before = handles();List<DeadlineWaiter> timers = new ArrayList<>();int opened;
        try {
            for (int i=0;i<64;i++) timers.add(DeadlineWaiter.create());
            opened = handles();check(opened == before+64, "Each session must own exactly one timer handle");
        } finally { for (DeadlineWaiter timer : timers) timer.close(); }
        int afterBatch = handles();check(afterBatch == before, "Timer handles leaked after 64 session closes");
        DeadlineWaiter closed = DeadlineWaiter.create();closed.close();closed.close();
        try { closed.awaitUntil(System.nanoTime());throw new AssertionError("Closed waiter accepted await"); }
        catch (IOException expected) { checks++; }
        try (DeadlineWaiter expired = DeadlineWaiter.create()) {
            expired.awaitUntil(System.nanoTime()-1);checks++;
            Thread.currentThread().interrupt();
            try { expired.awaitUntil(System.nanoTime());throw new AssertionError("Pending interrupt ignored"); }
            catch (InterruptedException expected) { checks++; }
        }
        CountDownLatch waiting = new CountDownLatch(1);AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread interrupted = new Thread(() -> {
            try (DeadlineWaiter waiter = DeadlineWaiter.create()) {
                waiting.countDown();waiter.awaitUntil(System.nanoTime()+5_000_000_000L);
                failure.set(new AssertionError("Long wait ignored interruption"));
            } catch (InterruptedException expected) { }
            catch (Throwable exception) { failure.set(exception); }
        }, "deadline-interrupt-probe");
        interrupted.start();check(waiting.await(2,TimeUnit.SECONDS), "Interrupt test did not start");
        Thread.sleep(10);long interruptStart=System.nanoTime();interrupted.interrupt();interrupted.join(2000);
        long interruptNanos=System.nanoTime()-interruptStart;
        check(!interrupted.isAlive()&&failure.get()==null, "Wait failed to interrupt and close: "+failure.get());
        check(interruptNanos<250_000_000L, "Interrupt response exceeded bounded slice plus scheduling allowance");
        String baseline=cadence(false),precise=cadence(true);int afterCadence=handles();
        Files.writeString(output.resolve("cadence-results.json"),"{\"handlesBefore\":"+before+",\"handlesAfterCadence\":"+afterCadence+",\"cadences\":["+baseline+","+precise+"]}\n");
        check(afterCadence==before, "Process handle count changed across cadence/interrupt tests: "+before+" -> "+afterCadence);
        String json="{\"checks\":"+checks+",\"physicalOSInput\":false,\"minecraftStarted\":false,\"globalTimerResolutionChanged\":false,\"threadPriorityChanged\":false,\"handlesBeforeJavaThreadWarmup\":"+beforeJavaThreadWarmup+",\"handlesAfterJavaThreadWarmup\":"+before+",\"handlesBefore\":"+before+",\"handlesWith64Timers\":"+opened+",\"handlesAfterBatch\":"+afterBatch+",\"handlesAfterCadence\":"+afterCadence+",\"interruptMillis\":"+ms(interruptNanos)+",\"cadences\":["+baseline+","+precise+"]}";
        Files.writeString(output.resolve("result.json"),json+"\n");System.out.println(json);
    }
}
