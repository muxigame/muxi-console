package net.muxigame.console.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Server-authored membership. Minecraft must check distance, device and real inventory before calling it. */
public final class ConsoleSession {
    private final UUID consoleId;
    private final UUID host;
    private final int ports;
    private final TreeMap<Integer, UUID> controllers = new TreeMap<>();
    private Cartridge cartridge;
    private long epoch = 1;
    private boolean closed;

    public ConsoleSession(UUID consoleId, Cartridge cartridge, UUID host, int ports) {
        this.consoleId = Objects.requireNonNull(consoleId);
        this.cartridge = Objects.requireNonNull(cartridge);
        this.host = Objects.requireNonNull(host);
        if (ports < 1 || ports > 4) throw new DomainException("port", "A session supports one to four ports");
        this.ports = ports;
        controllers.put(0, host);
    }

    public UUID consoleId() { return consoleId; }
    public UUID host() { return host; }
    public int ports() { return ports; }
    public synchronized long epoch() { return epoch; }
    public synchronized Cartridge cartridge() { return cartridge; }
    public synchronized boolean isClosed() { return closed; }
    public synchronized Map<Integer, UUID> controllers() { return Map.copyOf(controllers); }

    public synchronized void grantControl(UUID requester, UUID player, int port) {
        requireHost(requester); requirePort(port); Objects.requireNonNull(player);
        UUID existing = controllers.get(port);
        if (player.equals(existing)) return;
        if (existing != null) throw new DomainException("occupied", "Controller port is already occupied");
        if (controllers.containsValue(player)) throw new DomainException("controller", "A player may occupy only one controller port");
        controllers.put(port, player);
        epoch++;
    }

    /** A departing or removed controller neutralizes its input by starting a fresh epoch for everybody. */
    public synchronized boolean leave(UUID player) {
        if (closed) return false;
        if (!controllers.values().removeIf(player::equals)) return false;
        epoch++;
        if (player.equals(host)) {
            closed = true;
            controllers.clear();
        }
        return true;
    }

    public synchronized void removeControl(UUID requester, UUID player) {
        requireHost(requester);
        leave(player);
    }

    public synchronized void replaceCartridge(UUID requester, Cartridge replacement) {
        requireHost(requester);
        Objects.requireNonNull(replacement);
        if (replacement.equals(cartridge)) return;
        cartridge = replacement;
        epoch++;
    }

    public synchronized void restart(UUID requester) {
        requireHost(requester);
        epoch++;
    }

    public synchronized void close(UUID requester) {
        requireHost(requester);
        closed = true;
        controllers.clear();
        epoch++;
    }

    public synchronized boolean controls(UUID player, int port) {
        return !closed && port >= 0 && port < ports && player != null && player.equals(controllers.get(port));
    }

    public synchronized boolean isController(UUID player) {
        return !closed && player != null && controllers.containsValue(player);
    }

    /** actualHeldCardIds must come from authoritative inventory, never a client's packet payload. */
    public synchronized boolean canReadRom(UUID player, Set<UUID> actualHeldCardIds) {
        return player != null && ((actualHeldCardIds != null && actualHeldCardIds.contains(cartridge.instanceId())) || isController(player));
    }

    private void requireHost(UUID requester) {
        if (closed) throw new DomainException("closed", "Console session is closed");
        if (!host.equals(requester)) throw new DomainException("authority", "Only the console host may change control membership");
    }
    private void requirePort(int port) {
        if (port < 0 || port >= ports) throw new DomainException("port", "Controller port is outside this session");
    }
}
