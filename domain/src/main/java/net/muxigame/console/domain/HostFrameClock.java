package net.muxigame.console.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** Validates host progress against elapsed monotonic time, without scheduling its CPU. */
public final class HostFrameClock {
    private static final BigDecimal RATE_MARGIN = new BigDecimal("1.15");
    private boolean started;
    private long epoch, initialFrame, frame, startNanos, lastNanos, slackFrames;
    private BigDecimal permittedFps;

    public synchronized void reset(long epoch, double fps, long initialFrame, long nowNanos) {
        if (epoch < 1) throw new DomainException("epoch", "Invalid host frame epoch");
        if (!Double.isFinite(fps) || fps < 10 || fps > 120 || initialFrame < 0)
            throw new DomainException("host_clock", "Invalid host frame clock limits");
        BigDecimal rate = BigDecimal.valueOf(fps).multiply(RATE_MARGIN);
        this.epoch = epoch;
        this.initialFrame = this.frame = initialFrame;
        startNanos = lastNanos = nowNanos;
        slackFrames = (long) Math.ceil(fps * 2);
        permittedFps = rate;
        started = true;
    }

    public synchronized boolean started() { return started; }

    /** Reordered observations retain the maximum completed frame within this epoch. */
    public synchronized long observe(long epoch, long completedFrame, long nowNanos) {
        if (!started || epoch != this.epoch)
            throw new DomainException("epoch", "Host progress belongs to another session epoch");
        if (nowNanos < lastNanos || completedFrame < initialFrame)
            throw new DomainException("host_clock", "Host progress time or frame moved before its origin");
        // nanoTime may be negative. Subtracting its signed endpoints, or adding the
        // frame allowance to a large initial frame, must not overflow a long.
        BigInteger elapsed = BigInteger.valueOf(nowNanos).subtract(BigInteger.valueOf(startNanos));
        long allowance = new BigDecimal(elapsed).multiply(permittedFps).movePointLeft(9)
                .setScale(0, RoundingMode.FLOOR).longValueExact() + slackFrames;
        if (completedFrame - initialFrame > allowance)
            throw new DomainException("host_clock", "Host progress exceeds elapsed-time allowance");
        frame = Math.max(frame, completedFrame);
        lastNanos = nowNanos;
        return frame;
    }

    public synchronized long frame() { return frame; }

    public synchronized void clear() {
        started = false;
        epoch = initialFrame = frame = startNanos = lastNanos = slackFrames = 0;
        permittedFps = null;
    }
}
