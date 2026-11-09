package net.muxigame.console.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Properties;
import java.util.UUID;

/** Immutable content registry with local objects and explicitly registered object-storage references. */
public final class ContentStore {
    public static final long DEFAULT_QUOTA_BYTES = 64L * 1024L * 1024L * 1024L;
    private final Path root;
    private final long quotaBytes;
    private final RomInspector inspector = new RomInspector();

    public ContentStore(Path root, long quotaBytes) throws IOException {
        if (quotaBytes < 1) throw new DomainException("quota", "Store quota must be positive");
        this.root = root.toAbsolutePath().normalize();
        this.quotaBytes = quotaBytes;
        safeDirectory(this.root);
    }

    public ContentStore(Path root) throws IOException { this(root, DEFAULT_QUOTA_BYTES); }
    public Path root() { return root; }
    public long quotaBytes() { return quotaBytes; }

    public synchronized long usedBytes() throws IOException {
        long total = 0;
        var localHashes = new HashSet<String>();
        try (var files = Files.walk(root, 2)) {
            for (Path path : files.filter(p -> p.getFileName().toString().matches("[0-9a-f]{64}\\.rom")).toList()) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe content object");
                total = Math.addExact(total, Files.size(path));
                localHashes.add(path.getFileName().toString().substring(0, 64));
            }
        }
        try (var files = Files.walk(root, 2)) {
            for (Path path : files.filter(p -> p.getFileName().toString().matches("[0-9a-f]{64}\\.meta")).toList()) {
                String hash = path.getFileName().toString().substring(0, 64);
                if (!path.equals(objectPath(hash, ".meta", false))) throw new IOException("Unsafe metadata location");
                Stored stored = readMetadata(hash);
                if (stored.remote && !localHashes.contains(hash)) total = Math.addExact(total, stored.descriptor.size());
            }
        }
        return total;
    }

    public synchronized boolean has(String sha256) throws IOException {
        if (hasLocal(sha256)) return true;
        Path metadata = objectPath(sha256, ".meta", false);
        return Files.exists(metadata, LinkOption.NOFOLLOW_LINKS) && readMetadata(Hashing.normalizeSha256(sha256)).remote;
    }

    /** Physical-object probe for legacy upload/download paths; remote metadata alone is not local data. */
    public synchronized boolean hasLocal(String sha256) throws IOException {
        Path path = objectPath(sha256, ".rom", false);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        requireRegular(path); return true;
    }

    public synchronized long size(String sha256) throws IOException {
        Path path = objectPath(sha256, ".rom", false);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { requireRegular(path); return Files.size(path); }
        Stored stored = readMetadata(Hashing.normalizeSha256(sha256));
        if (!stored.remote) throw new IOException("Missing local content object");
        return stored.descriptor.size();
    }

    public synchronized RomDescriptor describe(String sha256) throws IOException {
        String digest = Hashing.normalizeSha256(sha256);
        Stored stored = readMetadata(digest);
        Path local = objectPath(digest, ".rom", false);
        if (Files.exists(local, LinkOption.NOFOLLOW_LINKS)) {
            requireRegular(local);
            if (Files.size(local) != stored.descriptor.size()) throw new IOException("Content object and metadata size differ");
        } else if (!stored.remote) throw new IOException("Missing local content object");
        return stored.descriptor;
    }

    /** No network or ROM-body read: marker belongs to this world's checked metadata. */
    public synchronized boolean isRemote(String sha256) throws IOException {
        String digest = Hashing.normalizeSha256(sha256);
        Path metadata = objectPath(digest, ".meta", false);
        return Files.exists(metadata, LinkOption.NOFOLLOW_LINKS) && readMetadata(digest).remote;
    }

    private record Stored(RomDescriptor descriptor, boolean remote) { }
    private Stored readMetadata(String digest) throws IOException {
        Path metadata = objectPath(digest, ".meta", false);
        requireRegular(metadata);
        if (Files.size(metadata) > 4096) throw new IOException("Oversize content metadata");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(metadata, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(4097);
            if (bytes.length > 4096) throw new IOException("Oversize content metadata");
            try { properties.load(new java.io.ByteArrayInputStream(bytes)); }
            catch (IllegalArgumentException invalid) { throw new IOException("Invalid content metadata"); }
        }
        try {
            if (!"1".equals(properties.getProperty("version")) || !digest.equals(properties.getProperty("sha256")))
                throw new IOException("Invalid content metadata identity");
            String storage = properties.getProperty("storage", "local");
            if (!storage.equals("local") && !storage.equals("object")) throw new IOException("Invalid content storage marker");
            RomDescriptor descriptor = new RomDescriptor(Platform.fromId(properties.getProperty("platform")), digest,
                    Long.parseLong(properties.getProperty("size")), properties.getProperty("filename"), properties.getProperty("detection"));
            return new Stored(descriptor, storage.equals("object"));
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid content metadata", invalid); }
    }

    /** Caller must first validate remote HEAD against the declaration; this method never contacts cloud. */
    public synchronized RomDescriptor registerRemote(RomDescriptor descriptor) throws IOException {
        try (FileChannel channel = FileChannel.open(root.resolve(".writer.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            try (FileLock lock = channel.tryLock()) {
                if (lock == null) throw new DomainException("store_busy", "Another writer is using this content store");
                Path metadata = objectPath(descriptor.sha256(), ".meta", true);
                Stored prior = Files.exists(metadata, LinkOption.NOFOLLOW_LINKS) ? readMetadata(descriptor.sha256()) : null;
                if (prior != null && (prior.descriptor.platform() != descriptor.platform() || prior.descriptor.size() != descriptor.size()))
                    throw new DomainException("content", "This object is already registered for another platform or size");
                Path local = objectPath(descriptor.sha256(), ".rom", false);
                boolean localExists = Files.exists(local, LinkOption.NOFOLLOW_LINKS);
                if (localExists) {
                    requireRegular(local);
                    if (Files.size(local) != descriptor.size()) throw new IOException("Local object size conflicts with remote registration");
                }
                boolean charged = localExists || prior != null && prior.remote;
                if (!charged) {
                    long used = usedBytes();
                    if (descriptor.size() > quotaBytes - Math.min(used, quotaBytes))
                        throw new DomainException("quota", "Content store is full");
                }
                if (prior == null || !prior.remote)
                    writeMetadata(prior == null ? descriptor : prior.descriptor, true, prior != null);
                return describe(descriptor.sha256());
            } catch (OverlappingFileLockException busy) {
                throw new DomainException("store_busy", "Another writer is using this content store");
            }
        }
    }

    /** Only call after server authority has checked current possession or an active controller seat. */
    public synchronized byte[] read(String sha256, long offset, int limit) throws IOException {
        if (offset < 0 || limit < 1 || limit > 24 * 1024) throw new DomainException("range", "Invalid content chunk range");
        Path path = objectPath(sha256, ".rom", false);
        requireRegular(path);
        try (FileChannel input = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long length = input.size();
            if (offset > length) throw new DomainException("range", "Content offset past end");
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(limit, length - offset));
            input.position(offset);
            while (buffer.hasRemaining()) if (input.read(buffer) < 0) throw new IOException("Content object was truncated");
            return buffer.array();
        }
    }

    /** Copy, re-inspect, force and atomically install. A failed attempt never exposes partial ROM bytes. */
    public RomDescriptor publish(Path temporary, RomDescriptor expected) throws IOException {
        // The OS lock also protects the quota and dedup decision if two server instances share a data path.
        try (FileChannel channel = FileChannel.open(root.resolve(".writer.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            try (FileLock lock = channel.tryLock()) {
                if (lock == null) throw new DomainException("store_busy", "Another writer is using this content store");
                return publishLocked(temporary, expected);
            } catch (OverlappingFileLockException busy) {
                throw new DomainException("store_busy", "Another writer is using this content store");
            }
        }
    }

    private RomDescriptor publishLocked(Path temporary, RomDescriptor expected) throws IOException {
        Path object = objectPath(expected.sha256(), ".rom", true);
        if (hasLocal(expected.sha256())) {
            verifyExisting(object, expected);
            RomDescriptor admitted = inspector.inspect(object, expected.filename(), expected.platform());
            writeMetadataIfAbsent(admitted);
            return describe(expected.sha256());
        }
        Path priorMetadata = objectPath(expected.sha256(), ".meta", false);
        Stored registered = Files.exists(priorMetadata, LinkOption.NOFOLLOW_LINKS) ? readMetadata(expected.sha256()) : null;
        if (registered != null && (registered.descriptor.platform() != expected.platform() || registered.descriptor.size() != expected.size()))
            throw new DomainException("content", "This object is already registered for another platform or size");
        if (registered == null || !registered.remote) {
            long used = usedBytes();
            if (expected.size() > quotaBytes - Math.min(used, quotaBytes)) throw new DomainException("quota", "Content store is full");
        }
        Path candidate = object.getParent().resolve(".publish-" + UUID.randomUUID() + ".part");
        try {
            requireRegular(temporary);
            var digest = Hashing.sha256Digest();
            long copied = 0;
            try (InputStream input = Files.newInputStream(temporary, LinkOption.NOFOLLOW_LINKS);
                 FileChannel output = FileChannel.open(candidate, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[32768];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Content publication was cancelled");
                    copied += count;
                    if (copied > expected.size()) throw new DomainException("size", "Uploaded content exceeds declared size");
                    digest.update(buffer, 0, count);
                    ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, count);
                    while (bytes.hasRemaining()) output.write(bytes);
                }
                output.force(true);
            }
            if (copied != expected.size() || !HexFormat.of().formatHex(digest.digest()).equals(expected.sha256()))
                throw new DomainException("hash", "Uploaded object changed before publication");
            RomDescriptor inspected = inspector.inspect(candidate, expected.filename(), expected.platform());
            if (!inspected.sha256().equals(expected.sha256()) || inspected.size() != expected.size())
                throw new DomainException("hash", "Content reinspection differs");
            atomicMove(candidate, object);
            writeMetadataIfAbsent(inspected);
            return inspected;
        } finally { Files.deleteIfExists(candidate); }
    }

    public synchronized Cartridge issue(RomDescriptor descriptor, String title) throws IOException {
        RomDescriptor stored = describe(descriptor.sha256());
        if (stored.size() != descriptor.size() || stored.platform() != descriptor.platform())
            throw new DomainException("content", "Cartridge metadata differs from stored content");
        return new Cartridge(UUID.randomUUID(), descriptor.platform(), descriptor.sha256(), descriptor.size(), title);
    }

    private void verifyExisting(Path object, RomDescriptor expected) throws IOException {
        requireRegular(object);
        if (Files.size(object) != expected.size() || !Hashing.sha256(object).equals(expected.sha256()))
            throw new IOException("Existing immutable content object is corrupt");
    }

    private void writeMetadataIfAbsent(RomDescriptor descriptor) throws IOException {
        Path metadata = objectPath(descriptor.sha256(), ".meta", true);
        if (Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) {
            RomDescriptor existing = describe(descriptor.sha256());
            if (existing.platform() != descriptor.platform() || existing.size() != descriptor.size())
                throw new DomainException("content", "This object is already registered for another platform");
            return;
        }
        writeMetadata(descriptor, false, false);
    }

    private void writeMetadata(RomDescriptor descriptor, boolean remote, boolean replace) throws IOException {
        Path metadata = objectPath(descriptor.sha256(), ".meta", true);
        Path candidate = metadata.getParent().resolve(".metadata-" + UUID.randomUUID() + ".part");
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        properties.setProperty("platform", descriptor.platform().id());
        properties.setProperty("sha256", descriptor.sha256());
        properties.setProperty("size", Long.toString(descriptor.size()));
        properties.setProperty("filename", descriptor.filename());
        properties.setProperty("detection", descriptor.detection());
        if (remote) properties.setProperty("storage", "object");
        try {
            try (OutputStream output = Files.newOutputStream(candidate, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                properties.store(output, "Muxi Console content metadata");
            }
            try (FileChannel output = FileChannel.open(candidate, StandardOpenOption.WRITE)) { output.force(true); }
            if (replace) {
                requireRegular(metadata);
                try { Files.move(candidate, metadata, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unavailable) { throw new IOException("Content store requires atomic renames", unavailable); }
            } else atomicMove(candidate, metadata);
        } finally { Files.deleteIfExists(candidate); }
    }

    private Path objectPath(String sha256, String suffix, boolean create) throws IOException {
        String hash = Hashing.normalizeSha256(sha256);
        Path directory = root.resolve(hash.substring(0, 2));
        if (create) safeDirectory(directory);
        else if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unsafe object directory");
        return directory.resolve(hash + suffix);
    }

    private static void safeDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unsafe content directory");
    }

    private static void requireRegular(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing or unsafe content file");
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unavailable) { throw new IOException("Content store requires atomic renames", unavailable); }
    }
}
