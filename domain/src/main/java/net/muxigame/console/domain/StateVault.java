package net.muxigame.console.domain;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Server-side opaque checkpoints. Authorize every operation and verify the final download SHA.
 * Head replacement provides atomic visibility and JVM-crash recovery; portable Java directory
 * persistence is insufficient to claim recovery from sudden host power loss.
 */
public final class StateVault implements AutoCloseable {
    public static final Limits DEFAULT_LIMITS = new Limits(1024L * 1024 * 1024, 32L * 1024 * 1024, 24576, 60000, 60000);
    private static final int META_MAGIC = 0x53564d31, NAMESPACED_META_MAGIC = 0x53564d32, HEAD_MAGIC = 0x53564831;
    private static final long METADATA_CHARGE = 1024;
    public record Limits(long quotaBytes, long maxStateBytes, int chunkBytes, long uploadTimeoutMillis, long retentionMillis) {
        public Limits {
            if (quotaBytes < 1 || maxStateBytes < 1 || maxStateBytes > 32L * 1024 * 1024 ||
                    maxStateBytes > quotaBytes || chunkBytes < 1 || chunkBytes > 24576 ||
                    uploadTimeoutMillis < 1 || retentionMillis < 1)
                throw new IllegalArgumentException("Invalid checkpoint storage limits");
        }
    }
    public record Status(UUID uploadId, long offset, long total) { }
    public record Snapshot(UUID cardUUID, String fingerprint, long frame, String hash, long size, UUID generationID) { }
    private record Key(UUID card, UUID generation) { }
    private record Head(UUID current, UUID previous) { }
    private static final class Generation {
        final Snapshot snapshot; final long created;
        long retired, lastRead;
        Generation(Snapshot snapshot, long created, long retired) { this.snapshot = snapshot; this.created = created; this.retired = retired; }
    }
    private static final class Upload {
        final UUID actor, id, card; final String fingerprint, hash; final long frame, total; final Path file;
        long offset, lastProgress;
        Upload(UUID actor, UUID id, UUID card, String fingerprint, long frame, long total, String hash, Path file, long now) {
            this.actor = actor; this.id = id; this.card = card; this.fingerprint = fingerprint; this.frame = frame;
            this.total = total; this.hash = hash; this.file = file; lastProgress = now;
        }
        Status status() { return new Status(id, offset, total); }
    }
    private final Path root, cards, incoming;
    private final Limits limits;
    private final String namespace;
    private final Map<UUID, Upload> uploads = new HashMap<>();
    private final Map<UUID, Head> heads = new HashMap<>();
    private final Map<Key, Generation> generations = new HashMap<>();
    private final FileChannel lockChannel;
    private final FileLock lock;
    private long storedCharge, reservedCharge;
    private boolean closed;

    public StateVault(Path root) throws IOException { this(root, DEFAULT_LIMITS); }
    public StateVault(Path root, Limits limits) throws IOException { this(root,limits,null); }
    /** Explicit namespace binds each generation's metadata to its full outer machine/content key. */
    public StateVault(Path root, Limits limits, String namespace) throws IOException {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize(); this.limits = Objects.requireNonNull(limits);this.namespace=namespace;
        if(namespace!=null&&!namespace.matches("[a-f0-9-]{36}:[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid checkpoint namespace");
        directory(this.root); cards = this.root.resolve("cards"); incoming = this.root.resolve("incoming"); directory(cards); directory(incoming);
        FileChannel channel = FileChannel.open(this.root.resolve("vault.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired = null;
        try {
            try { acquired = channel.tryLock(); } catch (OverlappingFileLockException busy) { throw new IOException("Checkpoint vault is already open", busy); }
            if (acquired == null) throw new IOException("Checkpoint vault is already open");
            recover();
        } catch (IOException | RuntimeException error) {
            if (acquired != null) acquired.release(); channel.close(); throw error;
        }
        lockChannel = channel; lock = acquired;
    }

    public synchronized Status begin(UUID actor, UUID cardUUID, String fingerprint, long frameNumber,
                                     long expectedSize, String expectedSHA, long now) throws IOException {
        active(); identity(actor); identity(cardUUID); digest(fingerprint); digest(expectedSHA); time(now);
        if (frameNumber < 0 || expectedSize < 1 || expectedSize > limits.maxStateBytes()) reject("save_size", "Checkpoint bounds are invalid");
        expire(now);
        if (uploads.containsKey(actor)) reject("save_active", "This actor already has a checkpoint upload");
        long charge = charge(expectedSize);
        if (charge > limits.quotaBytes() - storedCharge - reservedCharge) reject("save_quota", "Checkpoint storage quota is exhausted");
        UUID id = UUID.randomUUID(); Path file = incoming.resolve(id + ".part");
        try (FileChannel ignored = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { }
        Upload upload = new Upload(actor, id, cardUUID, fingerprint, frameNumber, expectedSize, expectedSHA, file, now);
        uploads.put(actor, upload); reservedCharge += charge; return upload.status();
    }

    public synchronized Status append(UUID actor, UUID id, long offset, byte[] bytes, long now) throws IOException {
        active(); time(now); Upload upload = upload(actor, id, now); Objects.requireNonNull(bytes);
        if (offset != upload.offset || bytes.length == 0 || bytes.length > limits.chunkBytes() || bytes.length > upload.total - upload.offset)
            reject("save_chunk", "Checkpoint chunk is stale, oversized or out of order");
        try (FileChannel file = FileChannel.open(upload.file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            if (file.size() != upload.offset) throw new IOException("Checkpoint temporary file changed");
            file.position(upload.offset); ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) file.write(buffer);
        }
        upload.offset += bytes.length; upload.lastProgress = now; return upload.status();
    }

    public synchronized Snapshot commit(UUID actor, UUID id, long now) throws IOException {
        active(); time(now); Upload upload = upload(actor, id, now);
        if (upload.offset != upload.total) reject("save_incomplete", "Checkpoint upload is incomplete");
        if (Files.size(upload.file) != upload.total || !Hashing.sha256(upload.file).equals(upload.hash)) {
            removeUpload(upload); reject("save_hash", "Checkpoint SHA-256 does not match");
        }
        try (FileChannel file = FileChannel.open(upload.file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { file.force(true); }
        prune(now);
        Path folder = cardDirectory(upload.card); UUID generationID = UUID.randomUUID();
        Snapshot snapshot = new Snapshot(upload.card, upload.fingerprint, upload.frame, upload.hash, upload.total, generationID);
        Generation generation = new Generation(snapshot, now, -1); Key key = new Key(upload.card, generationID);
        Head prior = heads.get(upload.card); UUID previous = prior == null ? null : prior.current;
        Files.move(upload.file, payload(snapshot), StandardCopyOption.ATOMIC_MOVE);
        uploads.remove(actor); reservedCharge -= charge(upload.total);
        generations.put(key, generation); storedCharge += charge(upload.total);
        boolean published = false;
        try {
            writeGeneration(generation);
            // Set the retirement clock when a generation leaves the two protected slots,
            // rather than using its potentially much older creation time.
            if (prior != null && prior.previous != null) {
                Generation retired = generations.get(new Key(upload.card, prior.previous));
                if (retired == null) throw new IOException("Previous checkpoint is missing");
                retired.retired = now; writeGeneration(retired);
            }
            Head next = new Head(generationID, previous); writeHead(upload.card, next);
            heads.put(upload.card, next); published = true; return snapshot;
        } finally {
            if (!published) {
                // Atomic head replacement is the sole commit point. Never publish a
                // prepared orphan through recovery or silently free its disk charge.
                Head disk = readHead(folder.resolve("head"));
                if (disk != null && disk.current.equals(generationID)) heads.put(upload.card, disk);
                else {
                    generation.retired = now;
                    deleteGeneration(key, generation);
                }
            }
        }
    }

    public synchronized void abort(UUID actor, UUID uploadId) throws IOException {
        active(); identity(actor); identity(uploadId); Upload upload = uploads.get(actor);
        if (upload == null) return;
        if (!upload.id.equals(uploadId)) reject("save_session", "Checkpoint upload identity does not match");
        removeUpload(upload);
    }

    public synchronized Optional<Snapshot> latest(UUID cardUUID) throws IOException {
        active(); identity(cardUUID); Head head = heads.get(cardUUID);
        if (head == null) return Optional.empty();
        Generation generation = generations.get(new Key(cardUUID, head.current));
        if (generation == null) throw new IOException("Checkpoint head has no complete generation");
        return Optional.of(generation.snapshot);
    }

    public synchronized byte[] read(UUID cardUUID, UUID generationID, long offset, int limit) throws IOException {
        active(); identity(cardUUID); identity(generationID); Generation generation = generations.get(new Key(cardUUID, generationID));
        if (generation == null) throw new DomainException("save_missing", "Checkpoint generation is no longer retained");
        if (offset < 0 || offset > generation.snapshot.size() || limit < 1 || limit > limits.chunkBytes())
            reject("save_read", "Checkpoint read bounds are invalid");
        generation.lastRead = System.currentTimeMillis();
        byte[] result = new byte[(int) Math.min(limit, generation.snapshot.size() - offset)];
        try (FileChannel file = FileChannel.open(payload(generation.snapshot), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (file.size() != generation.snapshot.size()) throw new IOException("Stored checkpoint size changed");
            file.position(offset); ByteBuffer buffer = ByteBuffer.wrap(result);
            while (buffer.hasRemaining()) if (file.read(buffer) < 0) throw new EOFException("Stored checkpoint is truncated");
        }
        return result;
    }

    public synchronized void expire(long now) throws IOException {
        active(); time(now);
        for (Upload upload : List.copyOf(uploads.values())) if (elapsed(now, upload.lastProgress, limits.uploadTimeoutMillis())) removeUpload(upload);
        prune(now);
    }

    /** Includes complete generations and live upload reservations, for a shared outer quota. */
    public synchronized long chargedBytes() throws IOException { active(); return Math.addExact(storedCharge, reservedCharge); }

    private Upload upload(UUID actor, UUID id, long now) throws IOException {
        identity(actor); identity(id); Upload upload = uploads.get(actor);
        if (upload == null || !upload.id.equals(id)) throw new DomainException("save_session", "Checkpoint upload identity does not match");
        if (elapsed(now, upload.lastProgress, limits.uploadTimeoutMillis())) {
            removeUpload(upload); throw new DomainException("save_expired", "Checkpoint upload has expired");
        }
        return upload;
    }
    private void removeUpload(Upload upload) throws IOException {
        Files.deleteIfExists(upload.file);
        if (uploads.remove(upload.actor, upload)) reservedCharge -= charge(upload.total);
    }
    private void prune(long now) throws IOException {
        for (var entry : List.copyOf(generations.entrySet())) {
            Generation generation = entry.getValue(); Head head = heads.get(entry.getKey().card);
            if (head != null && (head.current.equals(entry.getKey().generation) || Objects.equals(head.previous, entry.getKey().generation))) continue;
            if (generation.retired >= 0 && elapsed(now, Math.max(generation.retired, generation.lastRead), limits.retentionMillis())) deleteGeneration(entry.getKey(), generation);
        }
    }
    private void deleteGeneration(Key key, Generation generation) throws IOException {
        Files.deleteIfExists(payload(generation.snapshot));
        Files.deleteIfExists(metadata(generation.snapshot));
        if (generations.remove(key, generation)) storedCharge -= charge(generation.snapshot.size());
    }

    private void recover() throws IOException {
        // There are no live upload identities after a restart.
        try (DirectoryStream<Path> files = Files.newDirectoryStream(incoming)) {
            for (Path file : files) if (file.getFileName().toString().matches("[0-9a-f-]{36}\\.part")) { regular(file); Files.delete(file); }
        }
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(cards)) {
            for (Path folder : folders) {
                UUID card = canonicalUUID(folder.getFileName().toString()); directory(folder); Head head = readHead(folder.resolve("head"));
                try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.meta")) {
                    for (Path file : files) {
                        Generation generation = readGeneration(file); Snapshot snapshot = generation.snapshot;
                        if (!snapshot.cardUUID().equals(card) || !file.getFileName().toString().equals(snapshot.generationID() + ".meta")) throw new IOException("Checkpoint metadata identity mismatch");
                        boolean referenced = head != null && (head.current.equals(snapshot.generationID()) || Objects.equals(head.previous, snapshot.generationID()));
                        if (!referenced && generation.retired < 0) { Files.deleteIfExists(payload(snapshot)); Files.delete(file); continue; }
                        if (!Files.exists(payload(snapshot), LinkOption.NOFOLLOW_LINKS)) {
                            if (referenced) throw new IOException("Protected checkpoint payload is missing");
                            Files.delete(file); continue;
                        }
                        regular(payload(snapshot));
                        if (Files.size(payload(snapshot)) != snapshot.size() || !Hashing.sha256(payload(snapshot)).equals(snapshot.hash())) throw new IOException("Stored checkpoint content identity mismatch");
                        // A restarted client may resume an old-generation download.
                        generations.put(new Key(card, snapshot.generationID()), generation); storedCharge = Math.addExact(storedCharge, charge(snapshot.size()));
                    }
                }
                if (head != null) {
                    if (!generations.containsKey(new Key(card, head.current)) || (head.previous != null && !generations.containsKey(new Key(card, head.previous)))) throw new IOException("Checkpoint head references missing metadata");
                    heads.put(card, head);
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
                    for (Path file : files) {
                        String name = file.getFileName().toString();
                        if (name.matches("[0-9a-f-]{36}\\.state")) {
                            UUID id = canonicalUUID(name.substring(0, 36)); if (!generations.containsKey(new Key(card, id))) { regular(file); Files.delete(file); }
                        } else if (name.matches("meta-[0-9a-f-]{36}\\.tmp")) { regular(file); Files.delete(file); }
                    }
                }
            }
        }
        long recoveryTime = System.currentTimeMillis();
        for (Generation generation : generations.values()) generation.lastRead = recoveryTime;
    }

    private Path cardDirectory(UUID card) throws IOException { Path folder = cards.resolve(card.toString()); directory(folder); return folder; }
    private Path payload(Snapshot snapshot) { return cards.resolve(snapshot.cardUUID().toString()).resolve(snapshot.generationID() + ".state"); }
    private Path metadata(Snapshot snapshot) { return cards.resolve(snapshot.cardUUID().toString()).resolve(snapshot.generationID() + ".meta"); }
    private interface MetadataWriter { void write(DataOutputStream out) throws IOException; }
    private void atomicMetadata(Path target, MetadataWriter writer) throws IOException {
        Path temporary = target.resolveSibling("meta-" + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 DataOutputStream out = new DataOutputStream(Channels.newOutputStream(file))) {
                writer.write(out); out.flush(); file.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private void writeHead(UUID card, Head head) throws IOException {
        atomicMetadata(cards.resolve(card.toString()).resolve("head"), out -> {
            out.writeInt(HEAD_MAGIC); uuid(out, head.current); out.writeBoolean(head.previous != null); if (head.previous != null) uuid(out, head.previous);
        });
    }
    private Head readHead(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try (DataInputStream in = metadataInput(file)) {
            if (in.readInt() != HEAD_MAGIC) throw new IOException("Invalid checkpoint head");
            UUID current = uuid(in), previous = in.readBoolean() ? uuid(in) : null;
            if (current.equals(previous) || in.read() != -1) throw new IOException("Invalid checkpoint head suffix"); return new Head(current, previous);
        }
    }
    private void writeGeneration(Generation generation) throws IOException {
        Snapshot s = generation.snapshot;
        atomicMetadata(metadata(s), out -> {
            out.writeInt(namespace==null?META_MAGIC:NAMESPACED_META_MAGIC);if(namespace!=null)out.writeUTF(namespace);uuid(out, s.cardUUID()); uuid(out, s.generationID()); out.writeUTF(s.fingerprint());
            out.writeLong(s.frame()); out.writeUTF(s.hash()); out.writeLong(s.size()); out.writeLong(generation.created); out.writeLong(generation.retired);
        });
    }
    private Generation readGeneration(Path file) throws IOException {
        try (DataInputStream in = metadataInput(file)) {
            int magic=in.readInt();if(namespace==null?magic!=META_MAGIC:magic!=NAMESPACED_META_MAGIC||!namespace.equals(in.readUTF()))throw new IOException("Invalid checkpoint metadata namespace");
            UUID card = uuid(in), id = uuid(in); String fingerprint = in.readUTF(); long frame = in.readLong(); String hash = in.readUTF();
            long size = in.readLong(), created = in.readLong(), retired = in.readLong();
            if (!validDigest(fingerprint) || !validDigest(hash) || frame < 0 || size < 1 || size > limits.maxStateBytes() || created < 0 || retired < -1 || in.read() != -1)
                throw new IOException("Invalid checkpoint metadata bounds");
            return new Generation(new Snapshot(card, fingerprint, frame, hash, size, id), created, retired);
        }
    }
    private DataInputStream metadataInput(Path file) throws IOException {
        regular(file); if (Files.size(file) > 1024) throw new IOException("Checkpoint metadata exceeds bounds");
        return new DataInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS));
    }
    private static void uuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static UUID canonicalUUID(String value) throws IOException {
        try { UUID id = UUID.fromString(value); if (!id.toString().equals(value)) throw new IllegalArgumentException(); return id; }
        catch (IllegalArgumentException invalid) { throw new IOException("Unexpected checkpoint directory identity", invalid); }
    }
    private static void directory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Checkpoint path is not a direct directory");
        Files.createDirectories(path);
    }
    private static void regular(Path path) throws IOException { if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Checkpoint path is not a direct regular file"); }
    private static boolean elapsed(long now, long then, long duration) { return now > then && now - then > duration; }
    private static long charge(long size) { return Math.addExact(size, METADATA_CHARGE); }
    private static boolean validDigest(String digest) { return digest != null && digest.matches("[0-9a-f]{64}"); }
    private static void digest(String digest) { if (!validDigest(digest)) reject("save_hash", "Expected a lowercase SHA-256 identity"); }
    private static void identity(UUID id) { if (id == null) reject("save_identity", "Checkpoint identity is required"); }
    private static void time(long now) { if (now < 0) reject("save_time", "Checkpoint time must not be negative"); }
    private static void reject(String code, String message) { throw new DomainException(code, message); }
    private void active() throws IOException { if (closed) throw new IOException("Checkpoint vault is closed"); }
    @Override public synchronized void close() throws IOException {
        if (closed) return; IOException failure = null;
        for (Upload upload : List.copyOf(uploads.values())) try { removeUpload(upload); } catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        closed = true;
        try { lock.release(); } catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        try { lockChannel.close(); } catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        if (failure != null) throw failure;
    }
}
