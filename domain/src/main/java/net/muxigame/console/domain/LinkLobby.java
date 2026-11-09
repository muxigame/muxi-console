package net.muxigame.console.domain;

import java.util.*;

/** Consent and generation boundary for two physical machines, independent of Minecraft/networking. */
public final class LinkLobby {
    public enum Stage { INVITED, PREPARING, RUNNING, CLOSING, CLOSED }
    public record Endpoint(UUID actor, UUID machine, UUID card, String content, String platform) {
        public Endpoint {
            Objects.requireNonNull(actor); Objects.requireNonNull(machine); Objects.requireNonNull(card);
            content = Hashing.normalizeSha256(content);
            if (!Set.of("gb", "gbc", "gba", "nds", "n3ds").contains(platform)) throw new DomainException("link_platform", "此机型尚无设备间连线");
        }
        public MachineSaveKey key() { return new MachineSaveKey(machine, content); }
    }
    public static boolean compatible(String first,String second){return Set.of("gb","gbc").contains(first)&&Set.of("gb","gbc").contains(second)||"gba".equals(first)&&"gba".equals(second)||"nds".equals(first)&&"nds".equals(second)||"n3ds".equals(first)&&"n3ds".equals(second);}
    private final UUID id = UUID.randomUUID();
    private final UUID host;
    private final List<Endpoint> endpoints;
    private final Set<UUID> prepared = new HashSet<>();
    private Stage stage = Stage.INVITED;
    private long epoch = 1, deadline;

    public LinkLobby(Endpoint inviter, Endpoint invited, long now) {
        if(!compatible(inviter.platform,invited.platform))throw new DomainException("link_platform","双方需要使用兼容的连线机型");
        if (inviter.actor.equals(invited.actor) || inviter.machine.equals(invited.machine) || inviter.card.equals(invited.card))
            throw new DomainException("link_identity", "连线需要两位玩家各自持有不同的掌机和卡带");
        host = inviter.actor;
        endpoints = List.of(inviter, invited).stream().sorted(Comparator.comparing(e -> e.machine.toString())).toList();
        deadline = Math.addExact(now, 30_000);
    }
    public UUID id() { return id; }
    public UUID host() { return host; }
    public long epoch() { return epoch; }
    public long deadline() { return deadline; }
    public Stage stage() { return stage; }
    public List<Endpoint> endpoints() { return endpoints; }
    public int port(UUID actor) { for (int i=0;i<2;i++) if(endpoints.get(i).actor.equals(actor)) return i; return -1; }
    public Endpoint endpoint(UUID actor) { int port=port(actor); if(port<0)throw new DomainException("link_permission", "你不在这次连线中"); return endpoints.get(port); }
    public void require(UUID actor, UUID link, long generation) {
        endpoint(actor);
        if (!id.equals(link) || generation != epoch || stage == Stage.CLOSED) throw new DomainException("link_epoch", "这次连线已改变");
    }
    public void accept(UUID actor, UUID link, long generation, long now) {
        require(actor,link,generation);
        if (stage != Stage.INVITED || host.equals(actor) || now > deadline) throw new DomainException("link_consent", "邀请已过期或不能由邀请人接受");
        stage=Stage.PREPARING; epoch++; deadline=Math.addExact(now,60_000);
    }
    public boolean prepared(Endpoint current, UUID link, long generation, long now) {
        return prepared(current,link,generation,now,true);
    }
    public boolean prepared(Endpoint current, UUID link, long generation, long now,boolean standaloneStopped) {
        require(current.actor,link,generation);
        if(stage!=Stage.PREPARING||now>deadline||!endpoint(current.actor).equals(current))throw new DomainException("link_identity", "准备期间掌机或卡带已改变");
        if(!standaloneStopped)throw new DomainException("link_prepare", "请等待这台机器的单机存档完成");
        prepared.add(current.actor);
        if(prepared.size()==2){stage=Stage.RUNNING;epoch++;deadline=Math.addExact(now,180_000);return true;}
        return false;
    }
    public boolean mayDownload(UUID actor, UUID link, long generation, String hash) {
        return id.equals(link)&&epoch==generation&&stage==Stage.RUNNING&&host.equals(actor)&&endpoints.stream().anyMatch(e->e.content.equals(hash));
    }
    public boolean locked() { return stage==Stage.PREPARING||stage==Stage.RUNNING||stage==Stage.CLOSING; }
    public void ready() { if(stage==Stage.RUNNING)deadline=Long.MAX_VALUE; }
    /** Changing generation immediately revokes all prior input, media and download authority. */
    public void closing(long now) {
        closing(now,15_000);
    }
    public void closing(long now,long graceMillis) {
        if(graceMillis<15_000||graceMillis>300_000)throw new IllegalArgumentException("Invalid bounded link closing period");
        if(stage==Stage.CLOSED||stage==Stage.CLOSING)return;
        stage=Stage.CLOSING;epoch++;deadline=Math.addExact(now,graceMillis);
    }
    public void close() { if(stage!=Stage.CLOSED){stage=Stage.CLOSED;epoch++;prepared.clear();} }
}
