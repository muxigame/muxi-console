package net.muxigame.console.tests.stream;

import net.muxigame.console.domain.DomainException;
import net.muxigame.console.domain.HostFrameClock;

/** Elapsed-time admission, packet reordering, server pauses and numeric boundaries. */
public final class HostFrameClockTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void denied(Runnable action, String message) {
        try { action.run(); throw new AssertionError(message); }
        catch (DomainException expected) { checks++; }
    }
    public static void main(String[] args) {
        HostFrameClock clock = new HostFrameClock();
        check(!clock.started() && clock.frame() == 0, "Unstarted clock is empty");
        denied(() -> clock.observe(1, 0, 0), "Unstarted observation accepted");
        clock.reset(1, 60, 100, 0);
        check(clock.started() && clock.frame() == 100, "Initial frame retained");
        check(clock.observe(1, 220, 0) == 220, "Two-second initial reorder slack accepted");
        denied(() -> clock.observe(1, 221, 0), "Initial slack exceeded");
        check(clock.frame() == 220, "Rejected fast-forward did not mutate maximum");
        check(clock.observe(1, 101, 10) == 220, "Late progress retained maximum");
        check(clock.observe(1, 100, 11) == 220, "Origin remains a valid late observation");
        denied(() -> clock.observe(1, 99, 12), "Frame before origin accepted");
        denied(() -> clock.observe(2, 220, 12), "Foreign epoch accepted");
        denied(() -> clock.observe(1, 220, 10), "Monotonic time regression accepted");
        check(clock.observe(1, 220, 11) == 220, "Rejected observations changed time frontier");

        clock.reset(2, 60, 0, 0);
        clock.observe(2, 3, 50_000_000);
        check(clock.observe(2, 39, 650_000_000) == 39,
                "600 ms server pause did not reject independently running 60 Hz host");
        check(clock.observe(2, 12, 700_000_000) == 39, "Late AV did not rewind progress");
        denied(() -> clock.observe(1, 45, 750_000_000), "Stale previous epoch accepted");
        check(clock.observe(2, 189, 1_000_000_000) == 189, "Exact 115% plus slack boundary accepted");
        denied(() -> clock.observe(2, 190, 1_000_000_000), "One-frame fast-forward beyond boundary accepted");
        check(clock.observe(2, 600, 10_000_000_000L) == 600, "Ten seconds of normal progress accepted");
        check(clock.observe(2, 810, 10_000_000_000L) == 810, "Longer elapsed boundary accepted");
        denied(() -> clock.observe(2, 811, 10_000_000_000L), "Forged accelerated progress accepted");

        clock.reset(3, 59.7275, 0, -1_000_000_000);
        check(clock.observe(3, 188, 0) == 188, "Fractional fps and negative nanoTime accepted");
        denied(() -> clock.observe(3, 189, 0), "Fractional fps allowance rounded upward");
        clock.reset(4, 10, 7, 0);
        check(clock.observe(4, 38, 1_000_000_000) == 38, "Minimum fps accepted");
        denied(() -> clock.observe(4, 39, 1_000_000_000), "Minimum fps allowance exceeded");
        clock.reset(5, 120, 0, 0);
        check(clock.observe(5, 378, 1_000_000_000) == 378, "Maximum fps accepted");
        denied(() -> clock.observe(5, 379, 1_000_000_000), "Maximum fps allowance exceeded");

        for (double fps : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0, -1, 9.99, 120.01}) {
            denied(() -> clock.reset(6, fps, 0, 0), "Invalid fps accepted");
            check(clock.started() && clock.frame() == 378, "Invalid reset mutated active state");
        }
        denied(() -> clock.reset(0, 60, 0, 0), "Zero epoch accepted");
        denied(() -> clock.reset(-1, 60, 0, 0), "Negative epoch accepted");
        denied(() -> clock.reset(6, 60, -1, 0), "Negative initial frame accepted");

        clock.reset(6, 60, Long.MAX_VALUE - 2, 100);
        check(clock.observe(6, Long.MAX_VALUE, 101) == Long.MAX_VALUE,
                "Large origin did not overflow when adding slack");
        denied(() -> clock.observe(6, -1, 102), "Negative completed frame accepted");
        clock.reset(7, 60, 0, Long.MIN_VALUE);
        check(clock.observe(7, 1_000_000_000_000L, Long.MAX_VALUE) == 1_000_000_000_000L,
                "Signed nanoTime subtraction did not overflow");
        denied(() -> clock.observe(7, Long.MAX_VALUE, Long.MAX_VALUE), "Overflow bypassed fast-forward guard");
        check(clock.frame() == 1_000_000_000_000L, "Overflow rejection retained last valid maximum");

        clock.reset(8, 60, 15, -10);
        check(clock.frame() == 15, "New epoch cleared prior maximum");
        denied(() -> clock.observe(7, 15, -9), "Previous epoch survived reset");
        check(clock.observe(8, 15, -9) == 15, "Negative monotonic origin retained");
        clock.clear();
        check(!clock.started() && clock.frame() == 0, "Clear removed clock state");
        denied(() -> clock.observe(8, 15, 0), "Cleared epoch accepted");
        clock.reset(9, 60, 0, 0);
        check(clock.observe(9, 0, 0) == 0, "Clock reused after clear");
        System.out.println("HostFrameClock checks=" + checks);
    }
}
