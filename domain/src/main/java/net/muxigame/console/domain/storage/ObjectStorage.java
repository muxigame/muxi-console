package net.muxigame.console.domain.storage;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.List;

/** Optional server-side ticket issuer. No method downloads an object's body. */
public interface ObjectStorage {
    long MAX_SINGLE_PUT_BYTES = 5_000_000_000L;
    /** Object metadata, not a claim that an OSS metadata header verifies its body hash. */
    record Metadata(long size, String sha256, String platform) { }

    /** Headers must accompany the URL; host/content-length are set by the HTTP implementation. */
    record Ticket(String method, URI url, Map<String, String> headers, long expiresAtMillis) {
        public Ticket { headers = Map.copyOf(headers); }
        @Override public String toString() {
            return "ObjectStorage.Ticket[method=" + method + ", expiresAtMillis=" + expiresAtMillis
                    + ", url=<redacted>, headers=<redacted>]";
        }
    }

    Optional<Metadata> head(String hash) throws IOException;
    Ticket upload(String hash, long size, String platform);
    /** Range is deliberately unsigned so the client can resume a verified download. */
    Ticket download(String hash);
    /** One conditional streaming PUT for migration; false means an identical object already exists. */
    boolean putExisting(Path file, String hash, String platform) throws IOException;

    /** Optional extension. Control requests contain XML metadata only, never ROM bytes. */
    default Optional<Multipart> multipart() { return Optional.empty(); }

    interface Multipart {
        int PART_BYTES = 16 * 1024 * 1024;
        int MAX_PARTS = 10_000;
        record Upload(String id, String hash, long size, String platform, int partSize) {
            public Upload {
                hash = net.muxigame.console.domain.Hashing.normalizeSha256(hash);
                if (id == null || id.isEmpty() || id.length() > 2048 || id.chars().anyMatch(c -> c < 33 || c > 126)
                        || platform == null || !platform.matches("[a-z0-9][a-z0-9_-]{0,31}")
                        || partSize != PART_BYTES || size < 1 || size > (long)PART_BYTES * MAX_PARTS)
                    throw new IllegalArgumentException("Invalid multipart upload plan");
            }
            public int partCount() { return (int)((size - 1) / partSize + 1); }
            public long offset(int number) { check(number); return (long)(number - 1) * partSize; }
            public long partBytes(int number) { return Math.min(partSize, size - offset(number)); }
            private void check(int number) { if (number < 1 || number > partCount()) throw new IllegalArgumentException("Part is outside this upload"); }
            @Override public String toString() { return "MultipartUpload[size=" + size + ", parts=" + partCount() + ", identity=<redacted>]"; }
        }
        record Part(int number, long size, String etag) {
            public Part {
                if (number < 1 || number > MAX_PARTS || size < 1 || size > PART_BYTES
                        || etag == null || etag.isEmpty() || etag.length() > 256 || etag.chars().anyMatch(c -> c < 33 || c > 126))
                    throw new IllegalArgumentException("Invalid multipart receipt");
            }
        }
        Upload start(String hash, long size, String platform) throws IOException;
        Ticket partTicket(Upload upload, int number) throws IOException;
        List<Part> list(Upload upload) throws IOException;
        /** Validates original client receipts against provider ListParts, conditionally completes, then HEADs. */
        Metadata complete(Upload upload, List<Part> receipts) throws IOException;
        /** Idempotent; a provider error must remain visible so cancellation can be retried. */
        void abort(Upload upload) throws IOException;
    }
}
