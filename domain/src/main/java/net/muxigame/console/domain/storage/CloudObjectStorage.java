package net.muxigame.console.domain.storage;

import net.muxigame.console.domain.Hashing;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class CloudObjectStorage implements ObjectStorage {
    /** Deliberately bounded single-PUT admission; multipart uploads are a separate protocol. */
    static final long MAX_PUT_BYTES = MAX_SINGLE_PUT_BYTES;
    static final int MAX_CONTROL_BYTES = 1024 * 1024;
    private final StorageConfig config;
    private final Clock clock;
    private final Sender sender;

    /** Bounded control XML only; object bodies are never fetched by this adapter. */
    record Response(int status, HttpHeaders headers, byte[] body) {
        Response(int status, HttpHeaders headers) { this(status, headers, new byte[0]); }
        Response { body = body.clone(); if (body.length > MAX_CONTROL_BYTES) throw new IllegalArgumentException("Oversize storage control response"); }
        @Override public byte[] body() { return body.clone(); }
        @Override public String toString() { return "StorageResponse[status=" + status + ", metadata=<redacted>]"; }
    }
    @FunctionalInterface interface Sender { Response send(HttpRequest request) throws IOException, InterruptedException; }

    CloudObjectStorage(StorageConfig config, Clock clock) {
        this(config, clock, httpSender());
    }
    CloudObjectStorage(StorageConfig config, Clock clock, Sender sender) {
        this.config = config; this.clock = clock; this.sender = sender;
    }
    private static Sender httpSender() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return request -> {
            var task = client.sendAsync(request, ignored -> new ControlBody());
            try {
                var response = task.get(request.timeout().orElse(Duration.ofSeconds(20)).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                return new Response(response.statusCode(), response.headers(), response.body());
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
                throw new IOException("Object storage control request failed");
            } finally { task.cancel(true); }
        };
    }

    private static final class ControlBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        @Override public java.util.concurrent.CompletionStage<byte[]> getBody(){return result;}
        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        @Override public void onNext(List<java.nio.ByteBuffer> buffers){
            for(var buffer:buffers){if(buffer.remaining()>MAX_CONTROL_BYTES-bytes.size()){subscription.cancel();result.completeExceptionally(new IOException("Oversize storage control response"));return;}
                byte[] block=new byte[Math.min(32768,buffer.remaining())];while(buffer.hasRemaining()){int count=Math.min(block.length,buffer.remaining());buffer.get(block,0,count);bytes.write(block,0,count);}}
            subscription.request(1);
        }
        @Override public void onError(Throwable error){result.completeExceptionally(new IOException("Storage control response interrupted"));}
        @Override public void onComplete(){result.complete(bytes.toByteArray());}
    }

    @Override public Optional<Metadata> head(String hash) throws IOException {
        String normalized = Hashing.normalizeSha256(hash);
        Response response = request(ticket("HEAD", normalized, Map.of()), HttpRequest.BodyPublishers.noBody());
        if (response.status == 404) return Optional.empty();
        if (response.status != 200) throw status("HEAD", response.status);
        try {
            String sizeHeader = single(response.headers, "content-length");
            if (!sizeHeader.matches("[0-9]{1,19}")) throw new IllegalArgumentException();
            long size = Long.parseLong(sizeHeader);
            if (size < 1) throw new IllegalArgumentException();
            String prefix = config.provider == StorageConfig.Provider.S3 ? "x-amz-meta-" : "x-oss-meta-";
            String storedHash = Hashing.normalizeSha256(single(response.headers, prefix + "sha256"));
            String platform = platform(single(response.headers, prefix + "platform"));
            if (!storedHash.equals(normalized)) throw new IllegalArgumentException();
            // S3 checksum is a provider validation distinct from user metadata. Never infer it from ETag.
            List<String> checksum = response.headers.allValues("x-amz-checksum-sha256");
            if (config.provider == StorageConfig.Provider.S3 && !checksum.isEmpty()) {
                List<String> types = response.headers.allValues("x-amz-checksum-type");
                if (types.size() == 1 && types.getFirst().equals("COMPOSITE")) {
                    // Multipart SHA256 is a checksum of part checksums, NOT the original file SHA256.
                    if (checksum.size() != 1 || !checksum.getFirst().matches("[A-Za-z0-9+/]{43}=[-][1-9][0-9]{0,4}")) throw new IllegalArgumentException();
                } else if (types.size() > 1 || !types.isEmpty() && !types.getFirst().equals("FULL_OBJECT")
                        || checksum.size() != 1 || !checksum.getFirst().equals(base64Hash(normalized))) throw new IllegalArgumentException();
            }
            return Optional.of(new Metadata(size, storedHash, platform));
        } catch (RuntimeException invalid) { throw new IOException("Object storage returned invalid or inconsistent metadata"); }
    }

    @Override public Ticket upload(String hash, long size, String platform) {
        return upload(hash, size, platform, null);
    }
    private Ticket upload(String hash, long size, String platform, String contentMd5) {
        String normalized = Hashing.normalizeSha256(hash);
        if (size < 1 || size > MAX_PUT_BYTES) throw new IllegalArgumentException("Object exceeds single-PUT size policy");
        String selected = platform(platform);
        HashMap<String, String> headers = new HashMap<>();
        headers.put("content-length", Long.toString(size));
        headers.put("content-type", "application/octet-stream");
        if (config.provider == StorageConfig.Provider.S3) {
            headers.put("if-none-match", "*");
            headers.put("x-amz-meta-sha256", normalized); headers.put("x-amz-meta-platform", selected);
            // Native S3 rejects a body whose SHA-256 differs, without another local file pass.
            headers.put("x-amz-checksum-sha256", base64Hash(normalized));
        } else {
            headers.put("x-oss-forbid-overwrite", "true");
            headers.put("x-oss-meta-sha256", normalized); headers.put("x-oss-meta-platform", selected);
            // OSS URL V4 requires UNSIGNED-PAYLOAD. These metadata values alone do NOT validate body SHA.
            if (contentMd5 != null) headers.put("content-md5", contentMd5);
        }
        return ticket("PUT", normalized, headers);
    }
    @Override public Ticket download(String hash) {
        return ticket("GET", Hashing.normalizeSha256(hash), Map.of());
    }
    private Ticket ticket(String method, String hash, Map<String, String> headers) {
        return ticket(method, hash, headers, Map.of());
    }
    Ticket ticket(String method, String hash, Map<String, String> headers, Map<String, String> query) {
        String key = config.keyPrefix + "/" + hash.substring(0, 2) + "/" + hash + ".rom";
        return config.provider == StorageConfig.Provider.S3
                ? S3V4Signer.sign(config, clock.instant(), config.ticketSeconds, method, key, headers, query)
                : OssV4Signer.sign(config, clock.instant(), config.ticketSeconds, method, key, headers, query, Set.of("host", "content-length"));
    }

    @Override public Optional<Multipart> multipart() { return Optional.of(new CloudMultipart(this, config.provider)); }

    @Override public boolean putExisting(Path file, String hash, String platform) throws IOException {
        String normalized = Hashing.normalizeSha256(hash), selected = platform(platform);
        long size;
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            size = Files.size(file);
        } catch (IOException | RuntimeException invalid) { throw new IOException("Migration source is not a readable regular file"); }
        if (size < 1 || size > MAX_PUT_BYTES) throw new IOException("Migration source exceeds single-PUT size policy");
        Metadata expected = new Metadata(size, normalized, selected);
        Optional<Metadata> prior = head(normalized);
        if (prior.isPresent()) { requireSame(prior.get(), expected); return false; }

        // Migration is the only server body read: one local hash pass and one conditional streaming PUT.
        // For OSS compute MD5 in that SAME pass so its native body check also detects file changes.
        String md5 = null;
        try {
            MessageDigest sha = Hashing.sha256Digest();
            MessageDigest md = config.provider == StorageConfig.Provider.OSS ? MessageDigest.getInstance("MD5") : null;
            long count = 0;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[32768];
                for (int n; (n = input.read(buffer)) != -1;) {
                    count += n; if (count > size) throw new IOException();
                    sha.update(buffer, 0, n); if (md != null) md.update(buffer, 0, n);
                }
            }
            if (count != size || !Signing.hex(sha.digest()).equals(normalized) || Files.size(file) != size)
                throw new IOException();
            if (md != null) md5 = Base64.getEncoder().encodeToString(md.digest());
        } catch (IOException | RuntimeException | NoSuchAlgorithmException invalid) {
            throw new IOException("Migration source does not match the expected content hash and size");
        }
        Ticket upload = upload(normalized, size, selected, md5);
        HttpRequest.BodyPublisher body;
        try { body = HttpRequest.BodyPublishers.ofFile(file); }
        catch (IOException | RuntimeException invalid) { throw new IOException("Migration source could not be opened"); }
        if (body.contentLength() != size) throw new IOException("Migration source changed before upload");
        Response response = request(upload, body);
        boolean created = response.status == 200 || response.status == 201 || response.status == 204;
        if (!created && response.status != 409 && response.status != 412) throw status("PUT", response.status);
        Metadata after = head(normalized).orElseThrow(() -> new IOException("Object storage did not confirm the migration"));
        requireSame(after, expected);
        return created;
    }

    Response request(Ticket ticket, HttpRequest.BodyPublisher body) throws IOException {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(ticket.url())
                    .timeout(Duration.ofSeconds(ticket.method().equals("PUT") || ticket.method().equals("POST") ? 600 : 20));
            for (var header : ticket.headers().entrySet()) {
                // HttpClient owns these restricted headers. ofFile supplies precisely the signed length.
                if (!header.getKey().equals("host") && !header.getKey().equals("content-length"))
                    builder.header(header.getKey(), header.getValue());
            }
            return sender.send(builder.method(ticket.method(), body).build());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("Object storage operation interrupted");
        } catch (IOException | RuntimeException failure) {
            throw new IOException("Object storage operation failed");
        }
    }
    private static String single(HttpHeaders headers, String name) {
        List<String> values = headers.allValues(name);
        if (values.size() != 1) throw new IllegalArgumentException();
        return values.getFirst();
    }
    private static String platform(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9_-]{0,31}")) throw new IllegalArgumentException("Invalid storage platform identifier");
        return value;
    }
    private static String base64Hash(String hash) { return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hash)); }
    private static void requireSame(Metadata actual, Metadata expected) throws IOException {
        if (!actual.equals(expected)) throw new IOException("Existing object metadata conflicts with the requested content");
    }
    private static IOException status(String method, int code) { return new IOException("Object storage " + method + " returned HTTP " + code); }
    @Override public String toString() { return "ObjectStorage[provider=" + config.provider.name().toLowerCase(java.util.Locale.ROOT) + ", configuration=<redacted>]"; }
}
