package net.muxigame.console.domain;

import java.util.*;

/** Retired exact checkpoint identities cannot acquire a new active lease through late packets. */
public final class SaveTransactionTombstones<K> {
    public enum Outcome { COMPLETED, CANCELLED, EXPIRED }
    public static final long RETAIN_MILLIS=300_000;
    public static final int MAX_ACTIVE=16,DEFAULT_CAPACITY=2048;
    private record End(Outcome outcome,long at){ }
    private final int capacity;
    private final Set<K> active=new HashSet<>();
    private final Map<K,End> ended=new LinkedHashMap<>();
    public SaveTransactionTombstones(){this(DEFAULT_CAPACITY);}
    public SaveTransactionTombstones(int capacity){if(capacity<1||capacity>8192)throw new IllegalArgumentException("Invalid checkpoint history capacity");this.capacity=capacity;}
    /** A slot is reserved before activation, so its close record never needs to evict a protected entry. */
    public boolean reserve(K scope,long now){
        purge(now);Objects.requireNonNull(scope);if(ended.containsKey(scope))return false;if(active.contains(scope))return true;
        if(active.size()>=MAX_ACTIVE||active.size()+ended.size()>=capacity)throw new DomainException("save_limit","存档事务过多，请稍后重试");active.add(scope);return true;
    }
    public void retire(K scope,Outcome outcome,long now){
        purge(now);Objects.requireNonNull(scope);Objects.requireNonNull(outcome);if(ended.containsKey(scope))return;
        if(!active.contains(scope)&&active.size()+ended.size()>=capacity)throw new DomainException("save_limit","存档事务记录暂满");
        active.remove(scope);ended.put(scope,new End(outcome,now));
    }
    public Optional<Outcome> outcome(K scope,long now){purge(now);End value=ended.get(scope);return value==null?Optional.empty():Optional.of(value.outcome);}
    public Set<K> retained(long now){purge(now);return Set.copyOf(ended.keySet());}
    public boolean active(K scope){return active.contains(scope);}
    public int size(long now){purge(now);return ended.size();}
    private void purge(long now){if(now<0)throw new DomainException("clock","存档时间无效");ended.values().removeIf(end->now>=end.at&&now-end.at>RETAIN_MILLIS);}
}
