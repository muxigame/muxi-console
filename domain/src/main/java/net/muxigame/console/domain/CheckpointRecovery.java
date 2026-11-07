package net.muxigame.console.domain;

import java.util.Objects;
import java.util.UUID;

/** Optional recovery model, separate from initial epoch restart. State is available only to controllers. */
public final class CheckpointRecovery {
    public record Checkpoint(long epoch, long tick, String romSha256, String coreIdentity, String sha256, byte[] state) {
        public Checkpoint {
            romSha256 = Hashing.normalizeSha256(romSha256);
            sha256 = Hashing.normalizeSha256(sha256);
            if (epoch < 1 || tick < 0 || coreIdentity == null || coreIdentity.isBlank() || coreIdentity.length() > 256 || state == null)
                throw new DomainException("checkpoint", "Invalid checkpoint metadata");
            state = state.clone();
            if (!Hashing.sha256(state).equals(sha256)) throw new DomainException("hash", "Checkpoint digest mismatch");
        }
        @Override public byte[] state() { return state.clone(); }
    }

    public enum Comparison { MATCH, RESTORE_REQUIRED, UNAVAILABLE }
    private final ConsoleSession session;
    private final int maxStateBytes;
    private Checkpoint checkpoint;

    public CheckpointRecovery(ConsoleSession session, int maxStateBytes) {
        this.session = Objects.requireNonNull(session);
        if (maxStateBytes < 1 || maxStateBytes > 32 * 1024 * 1024)
            throw new DomainException("checkpoint", "Checkpoint limit must be 1 byte to 32 MiB");
        this.maxStateBytes = maxStateBytes;
    }

    public synchronized void publish(UUID actor, long epoch, long tick, String coreIdentity, String sha256, byte[] state) {
        if (!session.host().equals(actor) || !session.isController(actor)) throw new DomainException("authority", "Only the active host may publish a checkpoint");
        if (epoch != session.epoch()) throw new DomainException("epoch", "Checkpoint belongs to another session epoch");
        if (state == null || state.length < 1 || state.length > maxStateBytes) throw new DomainException("size", "Checkpoint exceeds state limit");
        if (checkpoint != null && checkpoint.epoch() == epoch && tick <= checkpoint.tick())
            throw new DomainException("tick", "Checkpoint must advance monotonically");
        checkpoint = new Checkpoint(epoch, tick, session.cartridge().sha256(), coreIdentity, sha256, state);
    }

    public synchronized Comparison compare(UUID actor, long epoch, long tick, String coreIdentity, String stateSha256) {
        requireController(actor);
        String hash = Hashing.normalizeSha256(stateSha256);
        if (!available(epoch, tick, coreIdentity)) return Comparison.UNAVAILABLE;
        return checkpoint.sha256().equals(hash) ? Comparison.MATCH : Comparison.RESTORE_REQUIRED;
    }

    public synchronized Checkpoint restore(UUID actor, long epoch, long tick, String coreIdentity) {
        requireController(actor);
        if (!available(epoch, tick, coreIdentity)) throw new DomainException("checkpoint", "No compatible checkpoint is available");
        return checkpoint;
    }

    private boolean available(long epoch, long tick, String coreIdentity) {
        return checkpoint != null && !session.isClosed() && epoch == session.epoch() && checkpoint.epoch() == epoch
                && checkpoint.tick() == tick && checkpoint.coreIdentity().equals(coreIdentity)
                && checkpoint.romSha256().equals(session.cartridge().sha256());
    }
    private void requireController(UUID actor) {
        if (!session.isController(actor)) throw new DomainException("authority", "Observers cannot read emulator state");
    }
}
