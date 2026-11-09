package net.muxigame.console.domain;

import java.util.Set;

/** A quiet or stalled loader expires; actual long transfers are not a fixed sixty-second failure. */
public final class LoadingWatch {
    private static final Set<String> STAGES=Set.of("preparing","cache_check","downloading","verifying","starting","restoring","waiting","shared");
    private final long started;
    private long advanced;
    private String stage="preparing";
    private long bytes,total;
    public LoadingWatch(long now) { if(now<0)throw new IllegalArgumentException("clock");started=advanced=now; }
    public void observe(String next,long count,long length,long now) {
        if(!STAGES.contains(next)||count<0||length<0||count>length||now<advanced)throw new DomainException("progress","Invalid loading progress");
        if(!stage.equals(next)||count>bytes){advanced=now;stage=next;bytes=count;total=length;}
    }
    public boolean expired(long now) {
        long idle=stage.equals("starting")?600_000:180_000;
        return now<started||now-started>7_200_000||now-advanced>idle;
    }
}
