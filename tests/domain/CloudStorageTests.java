package net.muxigame.console.tests.domain;

import net.muxigame.console.domain.*;
import net.muxigame.console.domain.storage.ObjectStorage;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Domain fixtures are original synthetic ROM bytes; the fake service has no body-download operation. */
public final class CloudStorageTests {
    private static int checks;
    private static Path scratch;
    private static final UUID ACTOR = new UUID(0, 1), OTHER = new UUID(0, 2);

    public static void main(String[] args) throws Exception {
        Path root = Path.of("build", "cloud-domain-tests").toAbsolutePath(); Files.createDirectories(root);
        scratch = Files.createTempDirectory(root, "scratch-");
        try {
            registration(); localMigration(); transactions(); admissionAndLimits(); expiryAndFailures();
            System.out.println("Cloud storage domain tests: " + checks + " checks passed; no ROM GET or cloud access");
        } finally {
            try (var paths = Files.walk(scratch)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }

    private static void registration() throws Exception {
        ContentStore store = new ContentStore(scratch.resolve("remote-registry"), 65536);
        RomDescriptor d = remote("1".repeat(64), 192, Platform.GBA);
        eq(d, store.registerRemote(d), "register metadata without local object");
        check(store.has(d.sha256()) && store.isRemote(d.sha256()) && !store.hasLocal(d.sha256()), "remote and physical probes distinct");
        eq(192L, store.size(d.sha256()), "remote size from world metadata");
        eq(192L, store.usedBytes(), "registered remote charged once");
        eq(d, store.registerRemote(d), "remote registration idempotent"); eq(192L, store.usedBytes(), "no duplicate quota charge");
        expect(IOException.class, () -> store.read(d.sha256(), 0, 1), "read never downloads remote ROM");
        check(!Files.exists(object(store, d.sha256(), ".rom")), "no remote placeholder ROM created");
        eq(d, new ContentStore(store.root(), store.quotaBytes()).describe(d.sha256()), "remote registration survives reopen");
        check(new ContentStore(store.root()).isRemote(d.sha256()), "storage marker persists");
        expect(DomainException.class, () -> store.registerRemote(remote(d.sha256(), 193, Platform.GBA)), "size conflict immutable");
        expect(DomainException.class, () -> store.registerRemote(remote(d.sha256(), 192, Platform.NES)), "platform declaration min size checked");
        eq(d, store.describe(d.sha256()), "conflicts leave original metadata");

        ContentStore full = new ContentStore(scratch.resolve("remote-full"), 192);
        full.registerRemote(d);
        expect(DomainException.class, () -> full.registerRemote(remote("2".repeat(64), 192, Platform.GBA)), "new remote cannot exceed world quota");
        check(!full.has("2".repeat(64)), "quota denial exposes no new registry entry");
        ContentStore locked = new ContentStore(scratch.resolve("locked-registry"));
        try (var channel = java.nio.channels.FileChannel.open(locked.root().resolve(".writer.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            expect(DomainException.class, () -> locked.registerRemote(d), "second writer cannot race metadata transaction");
            check(!locked.has(d.sha256()), "busy writer leaves no metadata record");
        }
        eq(d, locked.registerRemote(d), "registration succeeds after exclusive writer releases");
        Files.writeString(object(store, d.sha256(), ".meta"), "version=1\nsha256=" + d.sha256() + "\nstorage=unknown\n");
        expect(IOException.class, () -> store.has(d.sha256()), "invalid storage marker fails closed");
        expect(DomainException.class, () -> store.isRemote("../../escape"), "remote lookup cannot escape object root");
        try (var files = Files.walk(scratch)) { check(files.noneMatch(p -> p.getFileName().toString().endsWith(".part")), "atomic registration leaves no staging files"); }
    }

    private static void localMigration() throws Exception {
        byte[] nes = nes(); Path input = scratch.resolve("original.nes"); Files.write(input, nes);
        RomDescriptor original = new RomInspector().inspect(input, Platform.NES);
        ContentStore store = new ContentStore(scratch.resolve("local-migration"), nes.length);
        store.publish(input, original);
        check(store.hasLocal(original.sha256()) && !store.isRemote(original.sha256()), "original local API remains physical");
        eq((long) nes.length, store.usedBytes(), "local object charged normally");

        // Make this private fixture invalid without changing length. Metadata conversion must not reparse/rehash ROM.
        byte[] changed = nes.clone(); changed[0] = 0; Files.write(object(store, original.sha256(), ".rom"), changed);
        eq(original, store.registerRemote(remote(original.sha256(), original.size(), Platform.NES)), "local metadata migration preserves prior inspected descriptor");
        check(store.isRemote(original.sha256()) && store.hasLocal(original.sha256()), "cloud registration keeps existing physical file");
        eq((long) nes.length, store.usedBytes(), "local plus remote same hash counts once at exact quota");
        eq((byte) 0, store.read(original.sha256(), 0, 1)[0], "same existing local bytes retained without cloud read");
        check(java.util.Arrays.equals(changed, Files.readAllBytes(object(store, original.sha256(), ".rom"))), "registration never rewrites ROM body");

        ContentStore onlyRemote = new ContentStore(scratch.resolve("remote-to-local"), nes.length);
        onlyRemote.registerRemote(remote(original.sha256(), original.size(), Platform.NES));
        check(!onlyRemote.hasLocal(original.sha256()), "remote-only before local reupload");
        RomDescriptor local = onlyRemote.publish(input, original);
        check(onlyRemote.hasLocal(original.sha256()), "same SHA can be republished locally when cloud disabled");
        eq((long) nes.length, onlyRemote.usedBytes(), "remote-to-local is not double charged");
        eq(original.sha256(), local.sha256(), "local publication retains identity");
        eq((byte) 'N', onlyRemote.read(original.sha256(), 0, 1)[0], "republished local ROM readable without cloud");
        check(onlyRemote.issue(local, "Own game").sha256().equals(original.sha256()), "cartridge issuance accepts registry-backed local object");
        expect(DomainException.class, () -> onlyRemote.registerRemote(remote(original.sha256(), original.size(), Platform.SNES)), "same-sized different platform rejected");
    }

    private static void transactions() throws Exception {
        ContentStore store = new ContentStore(scratch.resolve("transactions")); Fake cloud = new Fake();
        try (CloudUploads uploads = new CloudUploads(store, cloud)) {
            UploadManager.Request request = request("3".repeat(64), 192, true);
            UploadManager.Status pending = uploads.begin(ACTOR, request, 1000);
            eq(0L, pending.offset(), "new cloud upload starts unacknowledged");
            eq(192L, pending.total(), "declared total retained"); check(pending.rightsConfirmed(), "declaration acknowledged");
            eq(1, uploads.activeCount(), "one pending session");
            eq("PUT", uploads.uploadTicket(ACTOR, pending.id(), 1001).method(), "authorized owner receives PUT ticket");
            eq(1, cloud.tickets, "ticket calls do not GET body");
            expect(DomainException.class, () -> uploads.uploadTicket(OTHER, pending.id(), 1002), "other actor cannot obtain ticket");
            expect(DomainException.class, () -> uploads.status(OTHER, pending.id(), 1002), "other actor cannot inspect session");
            expect(DomainException.class, () -> uploads.abort(OTHER, pending.id()), "other actor cannot cancel session");
            eq(1, uploads.activeCount(), "unauthorized calls do not erase owner's pending state");
            cloud.objects.put(request.sha256(), new ObjectStorage.Metadata(192, request.sha256(), "gba"));
            RomDescriptor done = uploads.finish(ACTOR, pending.id(), 1003);
            eq("client_declared", done.detection(), "cloud never claims server ROM parsing");
            eq(0, uploads.activeCount(), "successful finish releases pending slot");
            check(store.isRemote(done.sha256()) && !store.hasLocal(done.sha256()), "finish commits metadata only");

            UploadManager.Status reuse = uploads.begin(ACTOR, request, 2000);
            eq(192L, reuse.offset(), "existing matched HEAD needs no upload");
            expect(DomainException.class, () -> uploads.uploadTicket(ACTOR, reuse.id(), 2001), "already complete object gets no new PUT");
            uploads.finish(ACTOR, reuse.id(), 2002);
            eq(0, cloud.downloads, "all transaction paths use zero downloads");
            try (var paths = Files.walk(store.root())) { check(paths.noneMatch(p -> p.toString().endsWith(".rom") || p.toString().endsWith(".part")), "no remote ROM/staging files"); }
        }
    }

    private static void admissionAndLimits() throws Exception {
        Fake cloud = new Fake(); ContentStore store = new ContentStore(scratch.resolve("admission"));
        try (CloudUploads uploads = new CloudUploads(store, cloud)) {
            expect(DomainException.class, () -> uploads.begin(ACTOR, request("4".repeat(64), 192, false), 0), "rights required before HEAD");
            expect(DomainException.class, () -> uploads.begin(ACTOR, request("4".repeat(64), 191, true), 0), "platform minimum checked before HEAD");
            expect(DomainException.class, () -> uploads.begin(ACTOR, new UploadManager.Request("wrong.gb", Platform.GBA, 192, "4".repeat(64), true), 0), "filename platform mismatch rejected");
            eq(0, cloud.heads, "invalid requests never contact object storage");
            UploadManager.Status first = uploads.begin(ACTOR, request("4".repeat(64), 192, true), 0);
            expect(DomainException.class, () -> uploads.begin(ACTOR, request("5".repeat(64), 192, true), 1), "one pending session per actor");
            for (int i = 2; i <= 16; i++) uploads.begin(new UUID(0, i), request(String.format("%064x", i), 192, true), 1);
            eq(16, uploads.activeCount(), "sixteen global sessions admitted");
            expect(DomainException.class, () -> uploads.begin(new UUID(0, 17), request("6".repeat(64), 192, true), 2), "seventeenth global session rejected");
            uploads.abort(ACTOR, first.id()); eq(15, uploads.activeCount(), "abort releases owner's slot");
        }
        ContentStore tight = new ContentStore(scratch.resolve("reserved-quota"), 192);
        try (CloudUploads uploads = new CloudUploads(tight, new Fake())) {
            UploadManager.Status first = uploads.begin(ACTOR, request("7".repeat(64), 192, true), 0);
            expect(DomainException.class, () -> uploads.begin(OTHER, request("8".repeat(64), 192, true), 1), "pending quota reservation prevents over-admission");
            uploads.abort(ACTOR, first.id()); uploads.begin(OTHER, request("8".repeat(64), 192, true), 2);
            eq(1, uploads.activeCount(), "abort returns quota reservation");
        }
        try (CloudUploads uploads = new CloudUploads(new ContentStore(scratch.resolve("actor-cleanup")), new Fake())) {
            UploadManager.Status a = uploads.begin(ACTOR, request("7".repeat(64), 192, true), 0);
            UploadManager.Status b = uploads.begin(OTHER, request("8".repeat(64), 192, true), 0);
            eq(1, uploads.abortActor(ACTOR), "server actor cleanup removes its old pending begin");
            eq(b, uploads.status(OTHER, b.id(), 1), "actor cleanup leaves other actor's transaction intact");
            expect(DomainException.class, () -> uploads.status(ACTOR, a.id(), 1), "old actor transaction is gone");
            eq(0, uploads.abortActor(ACTOR), "repeated actor cleanup is idempotent");
        }
        ContentStore full = new ContentStore(scratch.resolve("cloud-exists-world-full"), 192);
        full.registerRemote(remote("9".repeat(64), 192, Platform.GBA)); Fake existing = new Fake();
        existing.objects.put("a".repeat(64), new ObjectStorage.Metadata(192, "a".repeat(64), "gba"));
        try (CloudUploads uploads = new CloudUploads(full, existing)) {
            expect(DomainException.class, () -> uploads.begin(ACTOR, request("a".repeat(64), 192, true), 0), "existing cloud object cannot bypass world quota");
            eq(0, uploads.activeCount(), "quota failure creates no issuing session"); check(!full.has("a".repeat(64)), "quota failure registers no new content");
        }
    }

    private static void expiryAndFailures() throws Exception {
        Fake cloud = new Fake(); ContentStore store = new ContentStore(scratch.resolve("expiry"));
        CloudUploads uploads = new CloudUploads(store, cloud);
        UploadManager.Status s = uploads.begin(ACTOR, request("b".repeat(64), 192, true), 0);
        uploads.status(ACTOR, s.id(), 179999);
        UUID idleId = s.id();
        expect(DomainException.class, () -> uploads.status(ACTOR, idleId, 180000), "status does not renew idle lease");
        eq(0, uploads.activeCount(), "idle expiry frees session");
        s = uploads.begin(ACTOR, request("b".repeat(64), 192, true), 200000);
        UUID id = s.id(); uploads.touch(ACTOR, id, 379999); uploads.status(ACTOR, id, 400000);
        UUID backwardsId = id;
        expect(DomainException.class, () -> uploads.touch(ACTOR, backwardsId, 300000), "clock cannot go behind last real activity");
        uploads.abort(ACTOR, id);
        s = uploads.begin(ACTOR, request("b".repeat(64), 192, true), 1000000); id = s.id();
        for (long now = 1100000; now < 8200000; now += 100000) uploads.touch(ACTOR, id, now);
        UUID boundedId = id;
        expect(DomainException.class, () -> uploads.uploadTicket(ACTOR, boundedId, 8200000), "refresh cannot extend beyond absolute two-hour lifetime");
        eq(0, uploads.activeCount(), "absolute expiry frees session");

        s = uploads.begin(ACTOR, request("c".repeat(64), 192, true), 9000000); UUID missing = s.id();
        expect(DomainException.class, () -> uploads.finish(ACTOR, missing, 9000001), "HEAD still absent after client PUT/412 cannot finish");
        eq(0, uploads.activeCount(), "failed finish cleans pending without staging files");
        s = uploads.begin(ACTOR, request("d".repeat(64), 192, true), 10000000); UUID wrong = s.id();
        cloud.objects.put("d".repeat(64), new ObjectStorage.Metadata(193, "d".repeat(64), "gba"));
        expect(DomainException.class, () -> uploads.finish(ACTOR, wrong, 10000001), "HEAD byte mismatch cannot register");
        check(!store.has("d".repeat(64)), "mismatched remote object never enters world registry");
        cloud.fail = true;
        expect(IOException.class, () -> uploads.begin(ACTOR, request("e".repeat(64), 192, true), 11000000), "HEAD transport failure cannot mint a ticket");
        eq(0, uploads.activeCount(), "begin HEAD failure creates no pending session"); cloud.fail = false;
        s = uploads.begin(ACTOR, request("f".repeat(64), 192, true), 12000000); cloud.fail = true; UUID failed = s.id();
        expect(IOException.class, () -> uploads.finish(ACTOR, failed, 12000001), "finish transport failure preserved as failure");
        eq(0, uploads.activeCount(), "failed finish releases reservation"); cloud.fail = false;
        uploads.begin(ACTOR, request("f".repeat(64), 192, true), 13000000);
        eq(1, uploads.expire(13180000), "sweep expires idle transactions");
        uploads.close(); uploads.close();
        expect(DomainException.class, () -> uploads.begin(ACTOR, request("f".repeat(64), 192, true), 14000000), "closed manager rejects work");
        eq(0, cloud.downloads, "failed/expired paths never GET ROM body");
    }

    private static final class Fake implements ObjectStorage {
        final Map<String, Metadata> objects = new HashMap<>(); int heads, tickets, downloads; boolean fail;
        public java.util.Optional<Metadata> head(String hash) throws IOException { heads++; if (fail) throw new IOException("fixture unavailable"); return java.util.Optional.ofNullable(objects.get(hash)); }
        public Ticket upload(String hash, long size, String platform) { tickets++; return new Ticket("PUT", URI.create("https://storage.example.test/?signature=synthetic"), Map.of(), 10000000); }
        public Ticket download(String hash) { downloads++; throw new AssertionError("Cloud domain must never obtain a GET URL"); }
        public boolean putExisting(Path file, String hash, String platform) { throw new AssertionError("Upload transaction must not migrate a server body"); }
    }
    private static RomDescriptor remote(String hash, long size, Platform platform) { return new RomDescriptor(platform, hash, size, "own." + platform.extensions().iterator().next(), "client_declared"); }
    private static UploadManager.Request request(String hash, long size, boolean rights) { return new UploadManager.Request("own.gba", Platform.GBA, size, hash, rights); }
    private static Path object(ContentStore store, String hash, String suffix) { return store.root().resolve(hash.substring(0, 2)).resolve(hash + suffix); }
    private static byte[] nes() { byte[] b = new byte[16 + 32768]; b[0] = 'N'; b[1] = 'E'; b[2] = 'S'; b[3] = 0x1a; b[4] = 2; return b; }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void eq(Object expected, Object actual, String message) { check(java.util.Objects.equals(expected, actual), message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static <T extends Throwable> T expect(Class<T> type, Checked task, String message) throws Exception {
        checks++; try { task.run(); } catch (Throwable failure) { if (type.isInstance(failure)) return type.cast(failure); throw new AssertionError(message + " (wrong failure type)", failure); }
        throw new AssertionError(message + " (accepted)");
    }
}
