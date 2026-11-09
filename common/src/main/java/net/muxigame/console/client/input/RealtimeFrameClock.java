package net.muxigame.console.client.input;

/** Monotonic one-frame deadlines. A pause or lost lease never creates catch-up work. */
public final class RealtimeFrameClock {
    private final double period;private double deadline;private volatile long leaseUntil,completed,lateFrames;private volatile boolean running;
    public RealtimeFrameClock(double fps){if(!Double.isFinite(fps)||fps<10||fps>120)throw new IllegalArgumentException("Core frame rate");period=1_000_000_000d/fps;}
    public void renew(long now,long leaseMillis){if(leaseMillis<1||leaseMillis>30000)throw new IllegalArgumentException("Clock lease");if(!running||now>=leaseUntil)deadline=now;running=true;leaseUntil=Math.addExact(now,Math.multiplyExact(leaseMillis,1_000_000));}
    public boolean leased(long now){return running&&now<leaseUntil;}
    public long delay(long now){return !leased(now)?-1:Math.max(0,(long)Math.ceil(deadline-now));}
    public void completed(long now){completed++;deadline+=period;if(now-deadline>period){lateFrames++;deadline=now+period;}}
    public void reanchor(long now){deadline=now;}
    /** A small capture inside the frame budget must not move the next audible/video beat. */
    public void resumeAfterSnapshot(long now){if(now-deadline>period)deadline=now;}
    public void pause(){running=false;}
    public long completedFrames(){return completed;}
    public long lateFrames(){return lateFrames;}
}
