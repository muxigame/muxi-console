package net.muxigame.console.domain;

/** Bounded checkpoint lease: heartbeat frequency never replaces actual byte progress or fixed deadlines. */
public final class SaveProgressWatch {
    public static final long SERIALIZING_MILLIS=120_000, IDLE_MILLIS=60_000, ABSOLUTE_MILLIS=900_000;
    public static final long MAX_BYTES=512L*1024*1024;
    public static final long MAX_PREPARE_BYTES=2L*1024*1024*1024;
    private final long started;
    private long advanced,accepted=-1,seen;
    private int stage=-1;
    private long bytes,total;
    public SaveProgressWatch(long now){if(now<0)throw new IllegalArgumentException("Invalid save clock");started=advanced=seen=now;}
    /** False means rate-limited; it must not refresh a room deadline. */
    public boolean observe(String name,long count,long length,long now){
        int next=switch(name){case "serializing"->0;case "profile"->1;case "state"->2;default->throw new DomainException("save_progress","未知存档阶段");};
        boolean unknown=next==0&&length==0;
        if(now<seen||count<0||length<0||unknown&&count>MAX_PREPARE_BYTES||!unknown&&(count>length||length>MAX_BYTES)||next>0&&length==0)
            throw new DomainException("save_progress","存档进度无效");
        if(expired(now))throw new DomainException("save_timeout","存档操作已超时，请重试");
        if(next<stage||next==stage&&(count<bytes||length!=total))throw new DomainException("save_progress","存档阶段或字节进度不能回退");
        seen=now;if(accepted>=0&&now-accepted<1000)return false;
        if(next!=stage||count>bytes){advanced=now;stage=next;bytes=count;total=length;}
        accepted=now;return true;
    }
    public boolean expired(long now){
        if(now<started||now-started>=ABSOLUTE_MILLIS)return true;
        return stage<=0?now-started>=SERIALIZING_MILLIS:now-advanced>=IDLE_MILLIS;
    }
    public boolean isSerializing(long now){return stage==0&&!expired(now);}
}
