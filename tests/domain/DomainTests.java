package net.muxigame.console.tests.domain;

import net.muxigame.console.domain.*;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Independent fixtures contain only synthetic bytes, never third-party game content or firmware. */
public final class DomainTests {
    private static final UUID HOST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OBSERVER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String ZERO_HASH = "0".repeat(64);
    private static int checks;
    private static Path scratch;

    public static void main(String[] args) throws Exception {
        Path output = Path.of("build", "domain-tests").toAbsolutePath().normalize();
        Files.createDirectories(output);
        scratch = Files.createTempDirectory(output, "scratch-");
        try {
            platformsAndHashing();
            inspector();
            contentStore();
            uploads();
            permissionsAndInput();
            checkpoints();
            System.out.println("Domain tests: " + checks + " checks passed; synthetic fixtures only");
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void platformsAndHashing() throws Exception {
        eq(19, Platform.values().length, "all requested platforms are registered");
        Set<String> ids = new HashSet<>();
        Set<Integer> models = new HashSet<>();
        for (Platform platform : Platform.values()) {
            check(ids.add(platform.id()), "unique stable platform id");
            check(models.add(platform.customModelData()), "unique cartridge model selector");
            eq(platform, Platform.fromId(platform.id()), "platform id round trip");
            check(platform.minBytes() > 0 && platform.minBytes() <= platform.maxBytes(), "bounded platform size");
            check(platform.controllerPorts() >= 1 && platform.controllerPorts() <= 4, "bounded controller count");
        }
        eq(Platform.MD, Platform.fromId("MD"), "platform case normalization");
        reject("platform", () -> Platform.fromId("unknown"));
        reject("platform", () -> RomInspector.selectPlatform("program.bin", null));
        reject("extension", () -> RomInspector.selectPlatform("program.zip", Platform.NES));
        reject("filename", () -> RomInspector.selectPlatform("../program.nes", Platform.NES));
        reject("filename", () -> RomInspector.selectPlatform("C:\\program.nes", Platform.NES));
        check(RomInspector.selectPlatform("Bioshock-homebrew.gba", Platform.GBA) == Platform.GBA, "Titles do not override platform/header admission");
        eq("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.sha256(new byte[]{97, 98, 99}), "SHA-256 known vector");
        eq("a".repeat(64), Hashing.normalizeSha256("A".repeat(64)), "SHA digest normalized");
        reject("hash", () -> Hashing.normalizeSha256("../bad"));
    }

    private static void inspector() throws Exception {
        RomInspector inspector = new RomInspector();
        byte[] nes = nes();
        Path file = write("valid.nes", nes);
        RomDescriptor descriptor = inspector.inspect(file, null);
        eq(Platform.NES, descriptor.platform(), "NES platform recognized");
        eq(Hashing.sha256(nes), descriptor.sha256(), "ROM digest covers every byte");
        eq("ines-header", descriptor.detection(), "header detection is explicit");
        byte[] trainer = new byte[nes.length + 512];
        System.arraycopy(nes, 0, trainer, 0, 16);
        trainer[6] = 4;
        inspector.inspect(write("trainer.nes", trainer), Platform.NES);
        checks++;
        byte[] nes2 = nes.clone(); nes2[7] = 8;
        eq("nes2-linear-header", inspector.inspect(write("linear.nes", nes2), Platform.NES).detection(), "NES 2 linear sizes");
        byte[] exponent = nes2.clone(); exponent[9] = 15;
        reject("format", () -> inspector.inspect(write("exponent.nes", exponent), Platform.NES));
        reject("size", () -> inspector.inspect(write("short.nes", new byte[16]), Platform.NES));
        reject("size", () -> inspector.inspect(write("truncated.nes", Arrays.copyOf(nes, nes.length - 1)), Platform.NES));
        reject("format", () -> inspector.inspect(write("extra.nes", Arrays.copyOf(nes, nes.length + 1)), Platform.NES));
        reject("format", () -> inspector.inspect(file, "wrong.pce", Platform.PCE));
        byte[] archive = new byte[32768]; archive[0] = 0x50; archive[1] = 0x4b;
        reject("format", () -> inspector.inspect(write("archive.sfc", archive), Platform.SNES));
        eq("extension-and-size", inspector.inspect(write("raw.sfc", new byte[32768]), Platform.SNES).detection(), "raw detection is not overstated");
        reject("size", () -> inspector.inspect(write("raw-small.sfc", new byte[1]), Platform.SNES));
        byte[] gb = gameBoy(false);
        eq(Platform.GB, inspector.inspect(write("synthetic.gb", gb), null).platform(), "GB header checksum and size accepted");
        byte[] gbc = gameBoy(true);
        eq(Platform.GBC, inspector.inspect(write("synthetic.gbc", gbc), null).platform(), "GBC header checksum accepted");
        reject("format", () -> inspector.inspect(write("monochrome.gbc", gb), Platform.GBC));
        byte[] badGb = gb.clone(); badGb[0x134] = 1;
        reject("format", () -> inspector.inspect(write("bad.gb", badGb), Platform.GB));
        byte[] badGbSize = gb.clone(); badGbSize[0x148] = 1; fixGbChecksum(badGbSize);
        reject("format", () -> inspector.inspect(write("banks.gb", badGbSize), Platform.GB));
        byte[] gba = new byte[1024]; gba[0xb2] = (byte) 0x96; fixGbaChecksum(gba);
        eq("gba-marker-and-header-checksum", inspector.inspect(write("synthetic.gba", gba), Platform.GBA).detection(), "GBA independent checksum");
        gba[0xa0] = 1;
        reject("format", () -> inspector.inspect(write("broken.gba", gba), Platform.GBA));
        for (int[] marker : List.of(new int[]{0x80,0x37,0x12,0x40}, new int[]{0x37,0x80,0x40,0x12}, new int[]{0x40,0x12,0x37,0x80})) {
            byte[] n64 = new byte[4096]; for (int i = 0; i < 4; i++) n64[i] = (byte) marker[i];
            eq(Platform.N64, inspector.inspect(write("synthetic-" + marker[0] + ".z64", n64), null).platform(), "N64 byte order admitted without rewriting original bytes");
        }
        byte[] md = new byte[1024]; System.arraycopy(new byte[]{'S','E','G','A'}, 0, md, 0x100, 4);
        eq(Platform.MD, inspector.inspect(write("synthetic.md", md), null).platform(), "Mega Drive marker");
        md[0x180] = 'B'; md[0x181] = 'R';
        reject("format", () -> inspector.inspect(write("boot.md", md), Platform.MD));
    }

    private static void contentStore() throws Exception {
        Path source = write("store.nes", nes());
        RomDescriptor descriptor = new RomInspector().inspect(source, Platform.NES);
        ContentStore store = new ContentStore(scratch.resolve("content"), descriptor.size() * 2);
        check(!store.has(descriptor.sha256()), "absent content is not visible");
        eq(descriptor, store.publish(source, descriptor), "publish re-inspects exact bytes");
        check(store.has(descriptor.sha256()), "published content available");
        eq(descriptor.size(), store.size(descriptor.sha256()), "stored content length");
        eq(descriptor.size(), store.usedBytes(), "ROM quota counts exact bytes");
        eq(descriptor, store.describe(descriptor.sha256()), "metadata round trip");
        store.publish(source, descriptor);
        eq(descriptor.size(), store.usedBytes(), "same bytes deduplicate");
        try (var lockChannel = FileChannel.open(store.root().resolve(".writer.lock"), StandardOpenOption.WRITE);
             var lock = lockChannel.lock()) {
            reject("store_busy", () -> new ContentStore(store.root()).publish(source, descriptor));
        }
        check(Arrays.equals(nes(), store.read(descriptor.sha256(), 0, 24000)), "download chunk matches verified original bytes");
        eq(0, store.read(descriptor.sha256(), descriptor.size(), 1).length, "EOF yields empty chunk");
        reject("range", () -> store.read(descriptor.sha256(), -1, 1));
        reject("range", () -> store.read(descriptor.sha256(), 0, 24577));
        reject("range", () -> store.read(descriptor.sha256(), descriptor.size() + 1, 1));
        reject("hash", () -> store.read("../escape", 0, 1));
        Cartridge first = store.issue(descriptor, "Synthetic program");
        Cartridge second = store.issue(descriptor, "Synthetic program");
        check(!first.instanceId().equals(second.instanceId()), "physical cartridges have distinct instance ids");
        eq(first.sha256(), second.sha256(), "physical instances reference one immutable ROM");
        ContentStore restarted = new ContentStore(scratch.resolve("content"), descriptor.size() * 2);
        eq(descriptor, restarted.describe(descriptor.sha256()), "metadata survives server recreation");
        ContentStore small = new ContentStore(scratch.resolve("small-content"), descriptor.size() - 1);
        reject("quota", () -> small.publish(source, descriptor));
        check(!small.has(descriptor.sha256()), "quota rejection exposes no object");
        ContentStore changed = new ContentStore(scratch.resolve("changed-content"));
        byte[] altered = nes(); altered[100] = 7;
        Path changedSource = write("changed.nes", altered);
        reject("hash", () -> changed.publish(changedSource, descriptor));
        check(!changed.has(descriptor.sha256()), "failed content transaction exposes no partial object");
        eq(0L, changed.usedBytes(), "failed content transaction consumes no ROM quota");
        Path existingObject = store.root().resolve(descriptor.sha256().substring(0, 2)).resolve(descriptor.sha256() + ".rom");
        Files.write(existingObject, new byte[2]);
        ioReject(() -> store.publish(source, descriptor));
        checks++;
    }

    private static void uploads() throws Exception {
        byte[] payload = nes(); String sha = Hashing.sha256(payload);
        ContentStore store = new ContentStore(scratch.resolve("upload-content"));
        UploadManager.Limits limits = new UploadManager.Limits(1024 * 1024, 8192, 2, 1, payload.length * 2L, 60000);
        Path staging = scratch.resolve("upload-stage");
        try (UploadManager manager = new UploadManager(store, staging, limits)) {
            var omitted = new UploadManager.Request("upload.nes", Platform.NES, payload.length, sha);
            check(!omitted.rightsConfirmed(), "rights declaration defaults false");
            reject("rights_required", () -> manager.begin(HOST, omitted, 0));
            eq(0, manager.activeCount(), "unconfirmed upload allocates no session");
            try (var files = Files.list(staging)) { eq(0L, files.filter(p -> p.toString().endsWith(".part")).count(), "unconfirmed upload creates no payload file"); }
            reject("size", () -> manager.begin(HOST, new UploadManager.Request("huge.nes", Platform.NES, 2L * 1024 * 1024, sha, true), 0));
            var request = new UploadManager.Request("upload.nes", Platform.NES, payload.length, sha, true);
            UploadManager.Status status = manager.begin(HOST, request, 100);
            ioReject(() -> new UploadManager(store, staging, limits));
            check(Files.exists(staging.resolve("upload-" + status.id() + ".part")), "second staging owner cannot reap a live upload");
            eq(0L, status.offset(), "initial acknowledged offset");
            eq((long) payload.length, status.total(), "declared upload length in status");
            check(status.rightsConfirmed(), "server status preserves explicit declaration");
            reject("limit", () -> manager.begin(HOST, request, 100));
            reject("upload", () -> manager.status(PLAYER, status.id(), 101));
            reject("upload", () -> manager.abort(PLAYER, status.id()));
            reject("incomplete", () -> manager.finish(HOST, status.id(), 102));
            eq(1, manager.activeCount(), "incomplete finish keeps resumable transaction");
            byte[] first = Arrays.copyOfRange(payload, 0, 8192);
            reject("offset", () -> manager.append(HOST, status.id(), 1, first, Hashing.sha256(first), 103));
            reject("hash", () -> manager.append(HOST, status.id(), 0, first, ZERO_HASH, 103));
            reject("chunk", () -> manager.append(HOST, status.id(), 0, new byte[8193], ZERO_HASH, 103));
            eq(0L, manager.status(HOST, status.id(), 104).offset(), "rejected chunks do not advance acknowledgement");
            long offset = 0;
            while (offset < payload.length) {
                byte[] chunk = Arrays.copyOfRange(payload, (int) offset, (int) Math.min(offset + 8192, payload.length));
                UploadManager.Status updated = manager.append(HOST, status.id(), offset, chunk, Hashing.sha256(chunk), 105 + offset);
                offset += chunk.length;
                eq(offset, updated.offset(), "each chunk advances exact offset");
            }
            RomDescriptor descriptor = manager.finish(HOST, status.id(), 40000);
            eq(sha, descriptor.sha256(), "finish verifies whole-file SHA");
            eq(0, manager.activeCount(), "committed transaction retired");
            eq(0L, manager.reservedBytes(), "committed transaction releases reservation");
            check(store.has(sha), "committed content atomically visible");
            try (var files = Files.list(staging)) { eq(0L, files.filter(p -> p.toString().endsWith(".part")).count(), "committed staging payload removed"); }
            var expiring = manager.begin(HOST, request, 50000);
            eq(0, manager.expire(109999), "timeout not premature");
            eq(1, manager.expire(110000), "timeout exact boundary");
            reject("upload", () -> manager.status(HOST, expiring.id(), 110001));
            eq(0L, manager.reservedBytes(), "expiry releases pending byte reservation");
            var wrongHash = manager.begin(HOST, new UploadManager.Request("wrong.nes", Platform.NES, payload.length, ZERO_HASH, true), 120000);
            uploadAll(manager, HOST, wrongHash.id(), payload, 120001, 8192);
            reject("hash", () -> manager.finish(HOST, wrongHash.id(), 120002));
            eq(0, manager.activeCount(), "failed final hash retires transaction");
            var one = manager.begin(HOST, request, 130000);
            manager.begin(PLAYER, request, 130000);
            reject("limit", () -> manager.begin(OBSERVER, request, 130000));
            reject("clock", () -> manager.status(HOST, one.id(), 129999));
        }
        try (var files = Files.list(staging)) { eq(0L, files.filter(p -> p.toString().endsWith(".part")).count(), "shutdown cleans all owned pending payloads"); }
        Path stale = staging.resolve("upload-00000000-0000-0000-0000-000000000001.part");
        Files.write(stale, new byte[]{1});
        Files.write(staging.resolve("unrelated.txt"), new byte[]{1});
        try (UploadManager restarted = new UploadManager(store, staging, limits)) {
            check(!Files.exists(stale), "restart cleans exact stale transaction filename");
            check(Files.exists(staging.resolve("unrelated.txt")), "restart preserves unrelated files");
        }
        ContentStore tiny = new ContentStore(scratch.resolve("upload-quota"), payload.length - 1);
        try (UploadManager manager = new UploadManager(tiny, scratch.resolve("upload-quota-stage"))) {
            reject("quota", () -> manager.begin(HOST, new UploadManager.Request("upload.nes", Platform.NES, payload.length, sha, true), 0));
            eq(0, manager.activeCount(), "quota rejection makes no pending transaction");
        }
    }

    private static void permissionsAndInput() throws Exception {
        Cartridge card = card();
        ConsoleSession session = new ConsoleSession(UUID.randomUUID(), card, HOST, 4);
        check(session.canReadRom(HOST, Set.of()), "active host may read ROM");
        check(!session.canReadRom(OBSERVER, Set.of()), "pure observer receives no ROM");
        check(session.canReadRom(PLAYER, Set.of(card.instanceId())), "transferred physical cartridge grants present holder access");
        check(!session.canReadRom(PLAYER, Set.of(UUID.randomUUID())), "different card id is not possession proof");
        reject("authority", () -> session.grantControl(OBSERVER, PLAYER, 1));
        session.grantControl(HOST, PLAYER, 1);
        check(session.canReadRom(PLAYER, Set.of()), "active second controller may read ROM");
        reject("controller", () -> session.grantControl(HOST, PLAYER, 2));
        reject("occupied", () -> session.grantControl(HOST, OBSERVER, 1));
        reject("port", () -> session.grantControl(HOST, OBSERVER, 4));
        InputTimeline inputs = new InputTimeline(session, 6);
        long epoch = session.epoch();
        inputs.submit(PLAYER, epoch, 1, 0, 0, 2);
        inputs.submit(HOST, epoch, 0, 0, 0, 1);
        eq(1L, inputs.expectedSequence(HOST, 0), "input sequence acknowledgement");
        reject("sequence", () -> inputs.submit(HOST, epoch, 0, 0, 1, 1));
        reject("sequence", () -> inputs.submit(HOST, epoch, 0, 3, 1, 1));
        reject("authority", () -> inputs.submit(OBSERVER, epoch, 2, 0, 0, 1));
        reject("authority", () -> inputs.submit(HOST, epoch, 1, 1, 1, 1));
        reject("tick", () -> inputs.submit(HOST, epoch, 0, 1, 7, 1));
        reject("buttons", () -> inputs.submit(HOST, epoch, 0, 1, 1, 65536));
        InputTimeline.Frame frame = inputs.advance();
        eq(epoch, frame.epoch(), "emitted frame carries epoch");
        eq(0L, frame.tick(), "first authoritative tick is zero");
        check(Arrays.equals(new int[]{1,2,0,0}, frame.masks()), "port order independent of packet arrival order");
        int[] escaped = frame.masks(); escaped[0] = 99;
        eq(1, frame.mask(0), "frame arrays are immutable to consumers");
        check(Arrays.equals(new int[4], inputs.advance().masks()), "missing tick input becomes neutral rather than sticky");
        reject("tick", () -> inputs.submit(HOST, epoch, 0, 1, 0, 1));
        inputs.submit(HOST, epoch, 0, 1, 3, 4);
        reject("tick", () -> inputs.submit(HOST, epoch, 0, 2, 3, 8));
        session.grantControl(HOST, OBSERVER, 2);
        check(inputs.epoch() != epoch, "membership change creates new epoch");
        eq(0L, inputs.nextTick(), "new epoch restarts deterministic tick");
        eq(0L, inputs.expectedSequence(HOST, 0), "new epoch restarts input sequence");
        reject("epoch", () -> inputs.submit(HOST, epoch, 0, 0, 0, 1));
        check(Arrays.equals(new int[4], inputs.advance().masks()), "old queued input cannot leak into new epoch");
        session.leave(PLAYER);
        check(!session.canReadRom(PLAYER, Set.of()), "leaving revokes session ROM access");
        check(session.canReadRom(PLAYER, Set.of(card.instanceId())), "leaving does not revoke genuine held cartridge");
        long previous = session.epoch(); session.replaceCartridge(HOST, card());
        check(session.epoch() > previous, "cartridge replacement creates new epoch");
        check(!session.canReadRom(PLAYER, Set.of(card.instanceId())), "old cartridge does not authorize replacement bytes");
        session.leave(HOST);
        check(session.isClosed(), "host departure closes session");
        check(session.controllers().isEmpty(), "host departure releases every controller seat");
        check(!session.canReadRom(OBSERVER, Set.of()), "closed session revokes every control seat");
        reject("closed", inputs::advance);
        reject("closed", () -> session.restart(HOST));
        deterministicPair(2); deterministicPair(3); deterministicPair(4);
    }

    private static void deterministicPair(int ports) throws Exception {
        ConsoleSession left = new ConsoleSession(UUID.randomUUID(), card(), HOST, ports);
        ConsoleSession right = new ConsoleSession(UUID.randomUUID(), card(), HOST, ports);
        UUID[] actors = new UUID[ports]; actors[0] = HOST;
        for (int port = 1; port < ports; port++) {
            actors[port] = new UUID(0, port + 10);
            left.grantControl(HOST, actors[port], port); right.grantControl(HOST, actors[port], port);
        }
        InputTimeline first = new InputTimeline(left, 8), second = new InputTimeline(right, 8);
        for (int tick = 0; tick < 100; tick++) {
            for (int port = 0; port < ports; port++) first.submit(actors[port], left.epoch(), port, tick, tick, (tick * 17 + port) & 65535);
            for (int port = ports - 1; port >= 0; port--) second.submit(actors[port], right.epoch(), port, tick, tick, (tick * 17 + port) & 65535);
            eq(first.advance(), second.advance(), "arrival-order invariance across " + ports + " ports");
        }
    }

    private static void checkpoints() throws Exception {
        ConsoleSession session = new ConsoleSession(UUID.randomUUID(), card(), HOST, 2);
        session.grantControl(HOST, PLAYER, 1);
        CheckpointRecovery recovery = new CheckpointRecovery(session, 1024);
        byte[] state = {1,2,3}; String digest = Hashing.sha256(state);
        reject("authority", () -> recovery.publish(PLAYER, session.epoch(), 10, "core@hash", digest, state));
        reject("epoch", () -> recovery.publish(HOST, session.epoch() - 1, 10, "core@hash", digest, state));
        reject("hash", () -> recovery.publish(HOST, session.epoch(), 10, "core@hash", ZERO_HASH, state));
        reject("size", () -> recovery.publish(HOST, session.epoch(), 10, "core@hash", digest, new byte[1025]));
        recovery.publish(HOST, session.epoch(), 10, "core@hash", digest, state);
        state[0] = 9;
        eq(CheckpointRecovery.Comparison.MATCH, recovery.compare(PLAYER, session.epoch(), 10, "core@hash", digest), "matching state hash needs no recovery");
        eq(CheckpointRecovery.Comparison.RESTORE_REQUIRED, recovery.compare(PLAYER, session.epoch(), 10, "core@hash", ZERO_HASH), "mismatch explicitly requests compatible checkpoint");
        eq(CheckpointRecovery.Comparison.UNAVAILABLE, recovery.compare(PLAYER, session.epoch(), 10, "different-core", digest), "different emulator build cannot restore incompatible state");
        reject("authority", () -> recovery.restore(OBSERVER, session.epoch(), 10, "core@hash"));
        byte[] restored = recovery.restore(PLAYER, session.epoch(), 10, "core@hash").state();
        eq(1, (int) restored[0], "checkpoint owns a defensive state copy");
        restored[0] = 7;
        eq(1, (int) recovery.restore(PLAYER, session.epoch(), 10, "core@hash").state()[0], "returned state cannot mutate retained checkpoint");
        reject("tick", () -> recovery.publish(HOST, session.epoch(), 10, "core@hash", digest, new byte[]{1,2,3}));
        session.restart(HOST);
        eq(CheckpointRecovery.Comparison.UNAVAILABLE, recovery.compare(PLAYER, session.epoch(), 10, "core@hash", digest), "checkpoint from old epoch cannot be restored");
        reject("checkpoint", () -> recovery.restore(PLAYER, session.epoch(), 10, "core@hash"));
    }

    private static Cartridge card() { return new Cartridge(UUID.randomUUID(), Platform.NES, Hashing.sha256(nes()), nes().length, "Synthetic program"); }
    private static byte[] nes() {
        byte[] bytes = new byte[16 + 16384];
        bytes[0] = 'N'; bytes[1] = 'E'; bytes[2] = 'S'; bytes[3] = 0x1a; bytes[4] = 1;
        return bytes;
    }
    private static byte[] gameBoy(boolean color) {
        byte[] bytes = new byte[32768]; if (color) bytes[0x143] = (byte) 0x80;
        fixGbChecksum(bytes); return bytes;
    }
    private static void fixGbChecksum(byte[] bytes) {
        int sum = 0; for (int i = 0x134; i <= 0x14c; i++) sum = (sum - (bytes[i] & 255) - 1) & 255;
        bytes[0x14d] = (byte) sum;
    }
    private static void fixGbaChecksum(byte[] bytes) {
        int sum = 0; for (int i = 0xa0; i <= 0xbc; i++) sum -= bytes[i] & 255;
        bytes[0xbd] = (byte) (sum - 0x19);
    }
    private static Path write(String filename, byte[] bytes) throws IOException { return Files.write(scratch.resolve(filename), bytes); }
    private static void uploadAll(UploadManager manager, UUID actor, UUID id, byte[] payload, long now, int chunkSize) throws IOException {
        for (int offset = 0; offset < payload.length; offset += chunkSize) {
            byte[] chunk = Arrays.copyOfRange(payload, offset, Math.min(offset + chunkSize, payload.length));
            manager.append(actor, id, offset, chunk, Hashing.sha256(chunk), now);
        }
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static void eq(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        checks++;
    }
    private static void reject(String code, Checked request) throws Exception {
        try { request.run(); throw new AssertionError("Expected rejection " + code); }
        catch (DomainException expected) { eq(code, expected.code(), "stable rejection code"); }
    }
    private static void ioReject(Checked request) throws Exception {
        try { request.run(); throw new AssertionError("Expected an I/O integrity rejection"); }
        catch (IOException expected) { checks++; }
    }
}
