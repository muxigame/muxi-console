package net.muxigame.console.domain;

import java.util.ArrayDeque;

/** Bounded host-completed batches. A slow client pauses issuance without dropping emulated frames. */
public final class FrameFlowWindow {
    private record Batch(long tick,long frame) {}
    private final int capacity;
    private final long timeout;
    private final ArrayDeque<Batch> pending=new ArrayDeque<>();
    private long epoch,issuedTick=-1,issuedFrame,completedTick=-1,completedFrame,progressAt;
    public FrameFlowWindow(int capacity,long timeoutMillis) {
        if(capacity<1||capacity>32||timeoutMillis<1000||timeoutMillis>600000)throw new IllegalArgumentException("Invalid frame flow limits");
        this.capacity=capacity;timeout=timeoutMillis;
    }
    public synchronized void reset(long epoch,long initialFrame,long now) {
        if(epoch<1||initialFrame<0||now<0)throw new DomainException("frame_flow","Invalid frame flow epoch");
        this.epoch=epoch;issuedTick=completedTick=-1;issuedFrame=completedFrame=initialFrame;progressAt=now;pending.clear();
    }
    public synchronized boolean available(long epoch){epoch(epoch);return pending.size()<capacity;}
    public synchronized int outstanding(){return pending.size();}
    public synchronized long completedFrame(){return completedFrame;}
    public synchronized long issuedFrame(){return issuedFrame;}
    public synchronized void issued(long epoch,long tick,long endFrame,long now) {
        epoch(epoch);time(now);
        if(pending.size()>=capacity||tick<0||tick!=issuedTick+1||endFrame<issuedFrame||endFrame-issuedFrame>6)throw new DomainException("frame_flow","Invalid or over-window frame batch");
        if(pending.isEmpty())progressAt=now;
        pending.addLast(new Batch(tick,endFrame));issuedTick=tick;issuedFrame=endFrame;
    }
    /** Only an exact issued batch boundary may cumulatively release earlier work. */
    public synchronized boolean acknowledge(long epoch,long tick,long endFrame,long now) {
        epoch(epoch);time(now);
        if(tick<0||endFrame<0)throw new DomainException("frame_flow","Invalid completed frame batch");
        if(tick<=completedTick){
            if(endFrame>completedFrame||tick==completedTick&&endFrame!=completedFrame)throw new DomainException("frame_flow","Stale frame completion differs");
            return false;
        }
        Batch match=null;for(Batch batch:pending)if(batch.tick==tick){match=batch;break;}
        if(match==null||match.frame!=endFrame)throw new DomainException("frame_flow","Frame completion was not issued");
        while(!pending.isEmpty()&&pending.peekFirst().tick<=tick)pending.removeFirst();
        completedTick=tick;completedFrame=endFrame;progressAt=now;return true;
    }
    public synchronized boolean expired(long epoch,long now) {
        epoch(epoch);time(now);return !pending.isEmpty()&&now>=progressAt&&now-progressAt>timeout;
    }
    private void epoch(long epoch){if(this.epoch<1||this.epoch!=epoch)throw new DomainException("epoch","Frame completion belongs to another session epoch");}
    private static void time(long now){if(now<0)throw new DomainException("frame_flow","Invalid frame completion time");}
}
