package net.muxigame.console.domain;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Authoritative tick queue. Packet arrival order cannot change the port order of an emitted frame. */
public final class InputTimeline {
    public record Frame(long epoch, long tick, int[] masks) {
        public Frame {
            if (epoch < 1 || tick < 0 || masks == null || masks.length < 1 || masks.length > 4)
                throw new DomainException("frame", "Invalid deterministic frame");
            masks = masks.clone();
            for (int mask : masks) if (mask < 0 || mask > 0xffff) throw new DomainException("buttons", "Invalid controller mask");
        }
        @Override public int[] masks() { return masks.clone(); }
        public int mask(int port) { return masks[port]; }
        @Override public boolean equals(Object other) {
            return other instanceof Frame f && epoch == f.epoch && tick == f.tick && Arrays.equals(masks, f.masks);
        }
        @Override public int hashCode() { return 31 * Objects.hash(epoch, tick) + Arrays.hashCode(masks); }
    }

    private record Seat(UUID player, int port) { }
    private final ConsoleSession session;
    private final int maxLeadTicks;
    private final Map<Seat, Long> sequences = new HashMap<>();
    private final TreeMap<Long, int[]> queue = new TreeMap<>();
    private final TreeMap<Long, boolean[]> occupied = new TreeMap<>();
    private long epoch;
    private long nextTick;

    public InputTimeline(ConsoleSession session, int maxLeadTicks) {
        this.session = Objects.requireNonNull(session);
        if (maxLeadTicks < 1 || maxLeadTicks > 120) throw new DomainException("tick", "Input lead window must be 1 to 120 ticks");
        this.maxLeadTicks = maxLeadTicks;
        resetForEpoch();
    }

    public synchronized long epoch() { synchronizeEpoch(); return epoch; }
    public synchronized long nextTick() { synchronizeEpoch(); return nextTick; }
    public int maxLeadTicks() { return maxLeadTicks; }

    public synchronized long expectedSequence(UUID player, int port) {
        synchronizeEpoch();
        return sequences.getOrDefault(new Seat(player, port), 0L);
    }

    public synchronized void submit(UUID player, int port, long sequence, long targetTick, int buttons) {
        submit(player, session.epoch(), port, sequence, targetTick, buttons);
    }

    /** Network adapters must supply the epoch carried by the packet to reject late packets after a restart. */
    public synchronized void submit(UUID player, long packetEpoch, int port, long sequence, long targetTick, int buttons) {
        synchronizeEpoch();
        if (packetEpoch != epoch) throw new DomainException("epoch", "Input belongs to another session epoch");
        if (!session.controls(player, port)) throw new DomainException("authority", "Player does not control this port");
        if (sequence < 0 || sequence != sequences.getOrDefault(new Seat(player, port), 0L))
            throw new DomainException("sequence", "Input must use the next acknowledged sequence");
        if (targetTick < nextTick || targetTick - nextTick > maxLeadTicks)
            throw new DomainException("tick", "Input tick is stale or outside the lead window");
        if (buttons < 0 || buttons > 0xffff) throw new DomainException("buttons", "Controller buttons exceed the 16-bit mask");
        boolean[] present = occupied.computeIfAbsent(targetTick, ignored -> new boolean[session.ports()]);
        if (present[port]) throw new DomainException("tick", "This port already has input for the requested tick");
        int[] masks = queue.computeIfAbsent(targetTick, ignored -> new int[session.ports()]);
        masks[port] = buttons;
        present[port] = true;
        sequences.put(new Seat(player, port), Math.addExact(sequence, 1));
    }

    /** Called exactly once per server tick; adapters may run the same mask for their fixed frames-per-tick. */
    public synchronized Frame advance() {
        synchronizeEpoch();
        if (session.isClosed()) throw new DomainException("closed", "Console session is closed");
        int[] masks = queue.remove(nextTick);
        occupied.remove(nextTick);
        Frame frame = new Frame(epoch, nextTick, masks == null ? new int[session.ports()] : masks);
        nextTick = Math.addExact(nextTick, 1);
        return frame;
    }

    public synchronized void resetForEpoch() {
        epoch = session.epoch();
        nextTick = 0;
        sequences.clear(); queue.clear(); occupied.clear();
    }

    private void synchronizeEpoch() { if (epoch != session.epoch()) resetForEpoch(); }
}
