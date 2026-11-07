package net.muxigame.console.domain;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded, sequential server upload transactions. No network assertion substitutes for real held-item checks. */
public final class UploadManager implements AutoCloseable {
    public record Limits(long maxRomBytes, int maxChunkBytes, int maxSessions, int maxSessionsPerActor,
                         long maxReservedBytes, long timeoutMillis) {
        public Limits {
            if (maxRomBytes < 1 || maxRomBytes > RomInspector.MAX_ROM_BYTES || maxChunkBytes < 1 || maxChunkBytes > 24 * 1024
                    || maxSessions < 1 || maxSessionsPerActor < 1 || maxSessionsPerActor > maxSessions
                    || maxReservedBytes < 1 || timeoutMillis < 1)
                throw new DomainException("limits", "Invalid upload limits");
        }
        public static Limits defaults() { return new Limits(RomInspector.MAX_ROM_BYTES, 24 * 1024, 16, 1, 1024L * 1024 * 1024, 60000); }
    }

    public record Request(String filename, Platform platform, long size, String sha256, boolean rightsConfirmed) {
        public Request {
            RomInspector.validateFilename(filename);
            Objects.requireNonNull(platform, "platform");
            sha256 = Hashing.normalizeSha256(sha256);
        }
        public Request(String filename, Platform platform, long size, String sha256) { this(filename, platform, size, sha256, false); }
    }

    public record Status(UUID id, long offset, long total, boolean rightsConfirmed) { }

    private static final class Pending {
        final UUID id = UUID.randomUUID();
        final UUID actor;
        final Request request;
        final Path path;
        long offset;
        long lastActivity;
        Pending(UUID actor, Request request, Path root, long now) {
            this.actor = actor; this.request = request; this.path = root.resolve("upload-" + id + ".part"); this.lastActivity = now;
        }
        Status status() { return new Status(id, offset, request.size(), request.rightsConfirmed()); }
    }

    private final ContentStore store;
    private final Path staging;
    private final Limits limits;
    private final FileChannel ownershipChannel;
    private final FileLock ownershipLock;
    private final RomInspector inspector = new RomInspector();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private long reservedBytes;
    private boolean closed;

    public UploadManager(ContentStore store, Path staging, Limits limits) throws IOException {
        this.store = Objects.requireNonNull(store);
        this.staging = staging.toAbsolutePath().normalize();
        this.limits = Objects.requireNonNull(limits);
        Files.createDirectories(this.staging);
        if (Files.isSymbolicLink(this.staging) || !Files.isDirectory(this.staging, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unsafe upload directory");
        ownershipChannel = FileChannel.open(this.staging.resolve(".uploads.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired;
        try {
            acquired = ownershipChannel.tryLock();
            if (acquired == null) throw new IOException("Upload staging directory already has an active owner");
        } catch (IOException | OverlappingFileLockException failure) {
            ownershipChannel.close();
            throw new IOException("Upload staging directory already has an active owner", failure);
        }
        ownershipLock = acquired;
        try {
            // Only the exclusive owner may reap exact stale transaction filenames after a restart.
            try (var files = Files.list(this.staging)) {
                for (Path path : files.filter(p -> p.getFileName().toString().matches("upload-[0-9a-f-]{36}\\.part")).toList())
                    Files.delete(path);
            }
        } catch (IOException failure) {
            ownershipLock.release(); ownershipChannel.close(); throw failure;
        }
    }

    public UploadManager(ContentStore store, Path staging) throws IOException { this(store, staging, Limits.defaults()); }
    public Limits limits() { return limits; }
    public synchronized int activeCount() { return pending.size(); }
    public synchronized long reservedBytes() { return reservedBytes; }

    public synchronized Status begin(UUID actor, Request request, long nowMillis) throws IOException {
        checkOpen(); checkTime(nowMillis);
        Objects.requireNonNull(actor); Objects.requireNonNull(request);
        if (!request.rightsConfirmed()) throw new DomainException("rights_required", "Confirm that you may upload and share this ROM");
        RomInspector.selectPlatform(request.filename(), request.platform());
        if (request.size() < request.platform().minBytes() || request.size() > Math.min(limits.maxRomBytes(), request.platform().maxBytes()))
            throw new DomainException("size", "ROM size exceeds upload or platform limit");
        expire(nowMillis);
        long actorCount = pending.values().stream().filter(p -> p.actor.equals(actor)).count();
        if (actorCount >= limits.maxSessionsPerActor() || pending.size() >= limits.maxSessions()
                || request.size() > limits.maxReservedBytes() - Math.min(reservedBytes, limits.maxReservedBytes()))
            throw new DomainException("limit", "Too many pending uploads");
        long used = store.usedBytes();
        if (!store.has(request.sha256()) && request.size() > store.quotaBytes() - Math.min(used, store.quotaBytes()))
            throw new DomainException("quota", "Content store is full");
        Pending upload = new Pending(actor, request, staging, nowMillis);
        Files.createFile(upload.path);
        pending.put(upload.id, upload);
        reservedBytes += request.size();
        return upload.status();
    }

    public synchronized Status append(UUID actor, UUID uploadId, long offset, byte[] bytes, String chunkSha256, long nowMillis) throws IOException {
        checkOpen();
        Pending upload = require(actor, uploadId, nowMillis);
        if (bytes == null || bytes.length < 1 || bytes.length > limits.maxChunkBytes())
            throw new DomainException("chunk", "Invalid upload chunk length");
        if (offset != upload.offset) throw new DomainException("offset", "Chunk offset must equal acknowledged offset");
        if (bytes.length > upload.request.size() - upload.offset) throw new DomainException("size", "Chunk exceeds declared content size");
        if (!Hashing.sha256(bytes).equals(Hashing.normalizeSha256(chunkSha256))) throw new DomainException("hash", "Chunk digest mismatch");
        try (FileChannel output = FileChannel.open(upload.path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            if (output.size() != upload.offset) throw new IOException("Upload staging size changed");
            output.position(upload.offset);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) output.write(buffer);
        } catch (IOException failure) { remove(upload); throw failure; }
        upload.offset += bytes.length;
        upload.lastActivity = nowMillis;
        return upload.status();
    }

    public synchronized RomDescriptor finish(UUID actor, UUID uploadId, long nowMillis) throws IOException {
        checkOpen();
        Pending upload = require(actor, uploadId, nowMillis);
        if (upload.offset != upload.request.size()) throw new DomainException("incomplete", "The complete ROM has not arrived");
        try {
            RomDescriptor descriptor = inspector.inspect(upload.path, upload.request.filename(), upload.request.platform());
            if (!descriptor.sha256().equals(upload.request.sha256()) || descriptor.size() != upload.request.size())
                throw new DomainException("hash", "Whole-file SHA-256 differs from the upload declaration");
            return store.publish(upload.path, descriptor);
        } finally { remove(upload); }
    }

    public synchronized Status status(UUID actor, UUID uploadId, long nowMillis) throws IOException {
        checkOpen(); return require(actor, uploadId, nowMillis).status();
    }

    public synchronized void abort(UUID actor, UUID uploadId) throws IOException {
        checkOpen();
        Pending upload = pending.get(uploadId);
        if (upload == null || !upload.actor.equals(actor)) throw new DomainException("upload", "Upload is not available to this actor");
        remove(upload);
    }

    public synchronized int expire(long nowMillis) throws IOException {
        checkTime(nowMillis);
        int removed = 0;
        for (Pending upload : new ArrayList<>(pending.values())) {
            if (nowMillis >= upload.lastActivity && nowMillis - upload.lastActivity >= limits.timeoutMillis()) {
                remove(upload); removed++;
            }
        }
        return removed;
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        IOException failure = null;
        for (Pending upload : new ArrayList<>(pending.values())) {
            try { remove(upload); } catch (IOException error) { failure = error; }
        }
        closed = true;
        try { ownershipLock.release(); } catch (IOException error) { failure = error; }
        try { ownershipChannel.close(); } catch (IOException error) { failure = error; }
        if (failure != null) throw failure;
    }

    private Pending require(UUID actor, UUID uploadId, long nowMillis) throws IOException {
        checkTime(nowMillis);
        Pending upload = pending.get(uploadId);
        if (upload == null || !upload.actor.equals(actor)) throw new DomainException("upload", "Upload is not available to this actor");
        if (nowMillis < upload.lastActivity) throw new DomainException("clock", "Upload time moved backwards");
        if (nowMillis - upload.lastActivity >= limits.timeoutMillis()) {
            remove(upload); throw new DomainException("expired", "Upload timed out");
        }
        return upload;
    }

    private void remove(Pending upload) throws IOException {
        // Keep the reservation if deletion fails, rather than losing track of a pending disk object.
        Files.deleteIfExists(upload.path);
        if (pending.remove(upload.id) != null) reservedBytes -= upload.request.size();
    }
    private void checkOpen() { if (closed) throw new DomainException("closed", "Upload manager is closed"); }
    private static void checkTime(long now) { if (now < 0) throw new DomainException("clock", "Negative upload time"); }
}
