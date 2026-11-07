package net.muxigame.console.domain;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Independent storage/identity/crash-boundary checks. No emulator, ROM or Minecraft initialization. */
public final class StateVaultTest {
    private static int checks;
    private static final String FP = "ab".repeat(32);
    private static final UUID ACTOR = UUID.randomUUID(), OTHER = UUID.randomUUID(), CARD = UUID.randomUUID();
    @FunctionalInterface interface Action { void run() throws Exception; }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void denied(String code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected rejection " + code); }
        catch (DomainException expected) { check(expected.code().equals(code), "Rejection code " + code + ", got " + expected.code()); }
    }
    private static void ioFailure(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected I/O rejection"); }
        catch (IOException expected) { checks++; }
    }
    private static byte[] bytes(int seed, int size) { byte[] data = new byte[size]; new Random(seed).nextBytes(data); return data; }
    private static StateVault.Snapshot save(StateVault vault, UUID actor, UUID card, byte[] data, long frame, long now, int chunk) throws Exception {
        var status = vault.begin(actor, card, FP, frame, data.length, Hashing.sha256(data), now);
        for (int offset = 0; offset < data.length; offset += chunk) {
            int end = Math.min(data.length, offset + chunk);
            status = vault.append(actor, status.uploadId(), offset, Arrays.copyOfRange(data, offset, end), now);
            check(status.offset() == end && status.total() == data.length, "Contiguous acknowledged offset");
        }
        return vault.commit(actor, status.uploadId(), now);
    }
    private static byte[] download(StateVault vault, StateVault.Snapshot snapshot, int chunk) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (long offset = 0; offset < snapshot.size(); offset += chunk) {
            byte[] piece = vault.read(snapshot.cardUUID(), snapshot.generationID(), offset, chunk);
            check(piece.length == Math.min(chunk, snapshot.size() - offset), "Bounded checkpoint chunk"); result.write(piece);
        }
        return result.toByteArray();
    }
    private static StateVault.Limits limits(long quota, long timeout, long retention) { return new StateVault.Limits(quota, 1024, 24, timeout, retention); }

    private static void identityAndBytes(Path path) throws Exception {
        long now = System.currentTimeMillis(); byte[] data = bytes(10, 73);
        StateVault.Snapshot complete;
        try (StateVault vault = new StateVault(path, limits(100000, 60, 60))) {
            check(vault.latest(CARD).isEmpty(), "New card has no checkpoint");
            denied("save_identity", () -> vault.begin(null, CARD, FP, 0, 73, Hashing.sha256(data), now));
            denied("save_identity", () -> vault.begin(ACTOR, null, FP, 0, 73, Hashing.sha256(data), now));
            denied("save_hash", () -> vault.begin(ACTOR, CARD, "../" + FP, 0, 73, Hashing.sha256(data), now));
            denied("save_hash", () -> vault.begin(ACTOR, CARD, FP.toUpperCase(Locale.ROOT), 0, 73, Hashing.sha256(data), now));
            denied("save_hash", () -> vault.begin(ACTOR, CARD, FP, 0, 73, "x".repeat(64), now));
            denied("save_size", () -> vault.begin(ACTOR, CARD, FP, -1, 73, Hashing.sha256(data), now));
            denied("save_size", () -> vault.begin(ACTOR, CARD, FP, 0, 0, Hashing.sha256(data), now));
            denied("save_size", () -> vault.begin(ACTOR, CARD, FP, 0, 1025, Hashing.sha256(data), now));
            denied("save_time", () -> vault.begin(ACTOR, CARD, FP, 0, 73, Hashing.sha256(data), -1));
            var status = vault.begin(ACTOR, CARD, FP, 360, data.length, Hashing.sha256(data), now); UUID id = status.uploadId();
            check(status.offset() == 0 && status.total() == 73, "Begin acknowledgement");
            denied("save_active", () -> vault.begin(ACTOR, UUID.randomUUID(), FP, 0, 10, FP, now));
            denied("save_session", () -> vault.append(OTHER, id, 0, new byte[1], now));
            denied("save_session", () -> vault.append(ACTOR, UUID.randomUUID(), 0, new byte[1], now));
            denied("save_chunk", () -> vault.append(ACTOR, id, 1, new byte[1], now));
            denied("save_chunk", () -> vault.append(ACTOR, id, 0, new byte[0], now));
            denied("save_chunk", () -> vault.append(ACTOR, id, 0, new byte[25], now));
            denied("save_incomplete", () -> vault.commit(ACTOR, id, now));
            for (int offset = 0; offset < data.length; offset += 24) {
                int end = Math.min(data.length, offset + 24);
                var ack = vault.append(ACTOR, id, offset, Arrays.copyOfRange(data, offset, end), now);
                check(ack.offset() == end, "Append offset advances exactly");
            }
            denied("save_chunk", () -> vault.append(ACTOR, id, 73, new byte[1], now));
            denied("save_session", () -> vault.commit(OTHER, id, now));
            complete = vault.commit(ACTOR, id, now);
            check(complete.cardUUID().equals(CARD) && complete.frame() == 360 && complete.fingerprint().equals(FP), "Snapshot identity metadata");
            check(complete.hash().equals(Hashing.sha256(data)) && complete.size() == 73, "Committed expected content identity");
            check(vault.latest(CARD).orElseThrow().equals(complete), "Atomic latest references complete snapshot");
            check(Arrays.equals(download(vault, complete, 24), data), "Exact multi-chunk checkpoint round trip");
            check(vault.read(CARD, complete.generationID(), 73, 24).length == 0, "Exact EOF is empty");
            denied("save_missing", () -> vault.read(UUID.randomUUID(), complete.generationID(), 0, 24));
            denied("save_read", () -> vault.read(CARD, complete.generationID(), -1, 24));
            denied("save_read", () -> vault.read(CARD, complete.generationID(), 74, 24));
            denied("save_read", () -> vault.read(CARD, complete.generationID(), 0, 0));
            denied("save_read", () -> vault.read(CARD, complete.generationID(), 0, 25));
        }
        try (StateVault reopened = new StateVault(path, limits(100000, 60, 60))) {
            check(reopened.latest(CARD).orElseThrow().equals(complete), "Complete head survives a fresh vault instance");
            check(Arrays.equals(download(reopened, complete, 24), data), "Persisted opaque runtime envelope bytes survive restart");
        }
    }

    private static void abortExpiryAndQuota(Path path) throws Exception {
        long now = System.currentTimeMillis(); byte[] data = bytes(20, 24);
        try (StateVault vault = new StateVault(path, limits(1048, 60, 60))) {
            var first = vault.begin(ACTOR, CARD, FP, 0, 24, Hashing.sha256(data), now);
            denied("save_quota", () -> vault.begin(OTHER, CARD, FP, 0, 24, Hashing.sha256(data), now));
            denied("save_session", () -> vault.abort(ACTOR, UUID.randomUUID()));
            vault.append(ACTOR, first.uploadId(), 0, data, now); checks++;
            vault.abort(ACTOR, first.uploadId()); vault.abort(ACTOR, first.uploadId()); checks++;
            var second = vault.begin(OTHER, CARD, FP, 0, 24, "00".repeat(32), now);
            vault.append(OTHER, second.uploadId(), 0, data, now);
            denied("save_hash", () -> vault.commit(OTHER, second.uploadId(), now));
            var third = vault.begin(ACTOR, CARD, FP, 0, 24, Hashing.sha256(data), now);
            vault.append(ACTOR, third.uploadId(), 0, data, now + 59);
            denied("save_expired", () -> vault.commit(ACTOR, third.uploadId(), now + 120));
            var fourth = vault.begin(ACTOR, CARD, FP, 0, 24, Hashing.sha256(data), now + 121);
            vault.expire(now + 182);
            denied("save_session", () -> vault.append(ACTOR, fourth.uploadId(), 0, data, now + 182));
            var fifth = vault.begin(ACTOR, CARD, FP, 0, 24, Hashing.sha256(data), now + 183);
            vault.append(ACTOR, fifth.uploadId(), 0, data, now + 183); vault.commit(ACTOR, fifth.uploadId(), now + 183);
            denied("save_quota", () -> vault.begin(OTHER, CARD, FP, 0, 1, Hashing.sha256(new byte[1]), now + 184));
            try (var files = Files.list(path.resolve("incoming"))) { check(files.findAny().isEmpty(), "Abort/hash/expiry release all temporary files"); }
        }
        try (StateVault vault = new StateVault(path.resolveSibling("abandoned"), limits(1048, 60, 60))) {
            vault.begin(ACTOR, CARD, FP, 0, 24, Hashing.sha256(data), now); checks++;
        }
        try (StateVault vault = new StateVault(path.resolveSibling("abandoned"), limits(1048, 60, 60))) {
            vault.begin(ACTOR, CARD, FP, 0, 24, Hashing.sha256(data), now); checks++;
        }
    }

    private static void generationRetention(Path path) throws Exception {
        long now = System.currentTimeMillis(); StateVault.Snapshot first, second, third;
        try (StateVault vault = new StateVault(path, limits(100000, 60000, 100))) {
            first = save(vault, ACTOR, CARD, bytes(1, 24), 1, now - 10000, 24);
            second = save(vault, ACTOR, CARD, bytes(2, 24), 2, now - 9000, 24);
            third = save(vault, ACTOR, CARD, bytes(3, 24), 3, now - 8000, 24);
            // It is old by retirement time, but a live download refreshes its grace.
            check(vault.read(CARD, first.generationID(), 0, 24).length == 24, "Retired snapshot remains readable before collection");
            long touched = System.currentTimeMillis(); vault.expire(touched + 50);
            check(Files.exists(path.resolve("cards").resolve(CARD.toString()).resolve(first.generationID() + ".state")), "Active old download gets a full retention grace");
            vault.expire(touched + 200);
            StateVault.Snapshot expired = first;
            denied("save_missing", () -> vault.read(CARD, expired.generationID(), 0, 24));
            check(vault.latest(CARD).orElseThrow().equals(third), "Collector preserves current head");
            check(Arrays.equals(vault.read(CARD, second.generationID(), 0, 24), bytes(2, 24)), "Collector preserves previous generation regardless of age");
        }
        Path resumed = path.resolveSibling("resume-retention");
        try (StateVault vault = new StateVault(resumed, limits(100000, 60000, 100))) {
            first = save(vault, ACTOR, CARD, bytes(1, 24), 1, now - 10000, 24);
            save(vault, ACTOR, CARD, bytes(2, 24), 2, now - 9000, 24);
            save(vault, ACTOR, CARD, bytes(3, 24), 3, now - 8000, 24);
        }
        try (StateVault reopened = new StateVault(resumed, limits(100000, 60000, 100))) {
            long opened = System.currentTimeMillis(); reopened.expire(opened + 50);
            check(reopened.read(CARD, first.generationID(), 0, 24).length == 24, "Reopen protects a resumable old-generation download");
        }
        Path bounded = path.resolveSibling("bounded-history");
        try (StateVault vault = new StateVault(bounded, limits(16 * 1048, 60000, 100))) {
            for (int i = 0; i < 100; i++) save(vault, ACTOR, CARD, bytes(i, 24), i, now + i * 10, 24);
            try (var files = Files.list(bounded.resolve("cards").resolve(CARD.toString()))) {
                long count = files.filter(file -> file.getFileName().toString().endsWith(".state")).count();
                check(count <= 14, "History collection is bounded while checkpoints continue");
            }
            check(vault.latest(CARD).orElseThrow().frame() == 99, "Collection never rewinds latest checkpoint");
        }
    }

    private static void recoveryAndHeadFailure(Path path) throws Exception {
        long now = System.currentTimeMillis(); byte[] original = bytes(42, 24); StateVault.Snapshot current;
        try (StateVault vault = new StateVault(path, limits(100000, 60000, 100))) {
            current = save(vault, ACTOR, CARD, original, 1, now, 24);
            ioFailure(() -> { try (StateVault ignored = new StateVault(path, limits(100000, 60000, 100))) { } });
            var pending = vault.begin(ACTOR, CARD, FP, 2, 24, Hashing.sha256(bytes(43, 24)), now);
            vault.append(ACTOR, pending.uploadId(), 0, bytes(43, 24), now);
            Path folder = path.resolve("cards").resolve(CARD.toString()); Path head = folder.resolve("head"), prior = folder.resolve("head-before-fault");
            byte[] headBytes = Files.readAllBytes(head); Files.move(head, prior); Files.createDirectory(head);
            ioFailure(() -> vault.commit(ACTOR, pending.uploadId(), now));
            Files.delete(head); Files.move(prior, head);
            check(Arrays.equals(Files.readAllBytes(head), headBytes), "Failed atomic head write leaves prior head bytes recoverable");
            check(vault.latest(CARD).orElseThrow().equals(current), "Failed publication does not advance in-memory latest");
        }
        // Simulate a crash after payload/metadata force but before publishing head.
        UUID orphan = UUID.randomUUID(); Path folder = path.resolve("cards").resolve(CARD.toString()); byte[] raw = bytes(44, 24);
        Files.write(folder.resolve(orphan + ".state"), raw);
        try (var out = new DataOutputStream(Files.newOutputStream(folder.resolve(orphan + ".meta")))) {
            out.writeInt(0x53564d31); uuid(out, CARD); uuid(out, orphan); out.writeUTF(FP); out.writeLong(9); out.writeUTF(Hashing.sha256(raw));
            out.writeLong(24); out.writeLong(now); out.writeLong(-1);
        }
        UUID abandonedUpload = UUID.randomUUID(); Files.write(path.resolve("incoming").resolve(abandonedUpload + ".part"), raw);
        try (StateVault reopened = new StateVault(path, limits(100000, 60000, 100))) {
            check(reopened.latest(CARD).orElseThrow().equals(current), "Recovery never promotes a prepared orphan");
            check(Arrays.equals(reopened.read(CARD, current.generationID(), 0, 24), original), "Original committed payload survives interrupted publication");
            check(!Files.exists(folder.resolve(orphan + ".state")) && !Files.exists(folder.resolve(orphan + ".meta")), "Prepared orphan data and metadata are reclaimed");
            check(!Files.exists(path.resolve("incoming").resolve(abandonedUpload + ".part")), "Unknown pre-restart upload reservation is reclaimed");
        }
    }
    private static void corruptionAndCleanupRetry(Path path) throws Exception {
        long now = System.currentTimeMillis(); byte[] original = bytes(77, 24); StateVault.Snapshot saved;
        try (StateVault vault = new StateVault(path, limits(100000, 60000, 100))) {
            saved = save(vault, ACTOR, CARD, original, 1, now, 24);
        }
        Path payload = path.resolve("cards").resolve(CARD.toString()).resolve(saved.generationID() + ".state");
        byte[] damaged = original.clone(); damaged[4] ^= 1; Files.write(payload, damaged);
        ioFailure(() -> { try (StateVault ignored = new StateVault(path, limits(100000, 60000, 100))) { } });
        check(Arrays.equals(Files.readAllBytes(payload), damaged), "Corruption rejection preserves the damaged original for diagnosis");
        Files.write(payload, original);
        try (StateVault repaired = new StateVault(path, limits(100000, 60000, 100))) {
            check(repaired.latest(CARD).orElseThrow().equals(saved), "Failed recovery releases its OS writer lock");
        }
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            Path retry = path.resolveSibling("metadata-delete-retry");
            try (StateVault vault = new StateVault(retry, limits(100000, 60000, 100))) {
                var first = save(vault, ACTOR, CARD, bytes(1, 24), 1, now - 10000, 24);
                save(vault, ACTOR, CARD, bytes(2, 24), 2, now - 9000, 24);
                var last = save(vault, ACTOR, CARD, bytes(3, 24), 3, now - 8000, 24);
                Path metadata = retry.resolve("cards").resolve(CARD.toString()).resolve(first.generationID() + ".meta");
                Files.setAttribute(metadata, "dos:readonly", true);
                try { ioFailure(() -> vault.expire(now)); }
                finally { if (Files.exists(metadata)) Files.setAttribute(metadata, "dos:readonly", false); }
                check(Files.exists(metadata), "Failed metadata deletion remains tracked for retry");
                vault.expire(now);
                check(!Files.exists(metadata), "A later collection retries and removes metadata after the I/O fault clears");
                check(vault.latest(CARD).orElseThrow().equals(last), "Cleanup retry never replaces the protected head");
            }
        }
        try (StateVault vault = new StateVault(path.resolveSibling("default-limits"))) {
            var status = vault.begin(ACTOR, CARD, FP, 0, 32L * 1024 * 1024, "00".repeat(32), now);
            check(status.total() == 32L * 1024 * 1024, "Default 32 MiB upper bound is accepted without buffering the full state");
            vault.abort(ACTOR, status.uploadId());
            denied("save_size", () -> vault.begin(ACTOR, CARD, FP, 0, 32L * 1024 * 1024 + 1, FP, now));
        }
    }
    private static void uuid(DataOutputStream out, UUID value) throws IOException { out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits()); }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]).toAbsolutePath().normalize(); Files.createDirectories(output);
        Path temporary = Files.createTempDirectory(output, "vault-");
        if (!temporary.toRealPath().startsWith(output.toRealPath())) throw new AssertionError("Temporary test path escaped output");
        try {
            identityAndBytes(temporary.resolve("identity"));
            abortExpiryAndQuota(temporary.resolve("quota"));
            generationRetention(temporary.resolve("retention"));
            recoveryAndHeadFailure(temporary.resolve("recovery"));
            corruptionAndCleanupRetry(temporary.resolve("corruption"));
        } finally {
            try (var paths = Files.walk(temporary)) { for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
        }
        check(!Files.exists(temporary), "Owned vault fixtures removed");
        System.out.println("PASS " + checks + " state-vault checks; temporary checkpoint data removed");
    }
}
