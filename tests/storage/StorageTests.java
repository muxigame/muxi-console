package net.muxigame.console.domain.storage;

import com.sun.net.httpserver.HttpServer;
import net.muxigame.console.domain.Hashing;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic credentials and bytes only. Official fixed vectors plus an actual loopback HTTP service. */
public final class StorageTests {
    private static int checks;
    private static final String HASH = Hashing.sha256("original storage fixture".getBytes(StandardCharsets.UTF_8));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T18:00:00Z"), ZoneOffset.UTC);
    private static Path scratch;

    public static void main(String[] args) throws Exception {
        Path output = Path.of("build", "storage-tests").toAbsolutePath(); Files.createDirectories(output);
        scratch = Files.createTempDirectory(output, "scratch-");
        try {
            officialVectors(); configurationAndTickets(); metadataAndFailureBoundaries(); migrationBoundaries(); loopbackHttp();
            System.out.println("Storage tests: " + checks + " checks passed; no cloud access or object-body GET");
        } finally {
            try (var paths = Files.walk(scratch)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }

    private static void officialVectors() {
        // Published AWS S3 query-auth test case, not a generated expectation from our implementation.
        // https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-query-string-auth.html
        StorageConfig aws = config(StorageConfig.Provider.S3, "https://s3.amazonaws.com", "examplebucket", "us-east-1",
                "fixtures", "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "", false);
        ObjectStorage.Ticket vector = S3V4Signer.sign(aws, Instant.parse("2013-05-24T00:00:00Z"), 86400,
                "GET", "test.txt", Map.of(), Map.of());
        eq("aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404",
                query(vector).get("X-Amz-Signature"), "official AWS signature vector");
        eq("/test.txt", vector.url().getRawPath(), "S3 virtual-host path omits bucket");

        // Public numerical vector from Alibaba's own V4 signer tests. Test logic is independently written.
        // https://github.com/aliyun/alibabacloud-oss-python-sdk-v2/blob/master/tests/unit/signer/test_v4.py#L195
        StorageConfig oss = config(StorageConfig.Provider.OSS, "https://oss-cn-hangzhou.aliyuncs.com", "bucket", "cn-hangzhou",
                "fixtures", "ak", "sk", "", false);
        Map<String, String> params = Map.of("param1", "value1", "+param1", "value3", "|param1", "value4",
                "+param2", "", "|param2", "", "param2", "");
        ObjectStorage.Ticket ossVector = OssV4Signer.sign(oss, Instant.ofEpochSecond(1702781677), 599, "PUT",
                "1234+-/123/1.txt", Map.of("x-oss-head1", "value", "abc", "value", "zabc", "value", "xyz", "value",
                        "content-type", "application/octet-stream"), params, Set.of());
        eq("a39966c61718be0d5b14e668088b3fa07601033f6518ac7b523100014269c0fe",
                query(ossVector).get("x-oss-signature"), "official Alibaba OSS signature vector");
        eq("ak/20231217/cn-hangzhou/oss/aliyun_v4_request", query(ossVector).get("x-oss-credential"), "OSS distinct scope");
        check(ossVector.url().getRawQuery().contains("%2Bparam2&"), "OSS empty query does not append equals");
        eq("%E6%B5%8B%E8%AF%95%20%2B%2F", Signing.encode("测试 +/", false), "UTF-8 byte encoding and uppercase hex");
    }

    private static void configurationAndTickets() throws Exception {
        eq(java.util.Optional.empty(), ObjectStorages.load(scratch.resolve(".private/missing.properties")), "absent explicit config stays local");
        Path privateDir = Files.createDirectories(scratch.resolve(".private"));
        Path file = privateDir.resolve("object-storage.properties");
        Files.writeString(file, properties("s3", "https://s3.example.test", "fixtures/roms"));
        ObjectStorage loaded = ObjectStorages.load(file).orElseThrow();
        ObjectStorage.Ticket put = loaded.upload(HASH.toUpperCase(java.util.Locale.ROOT), 512, "gba");
        eq("PUT", put.method(), "upload method");
        eq("/fixtures/roms/" + HASH.substring(0, 2) + "/" + HASH + ".rom", put.url().getPath(), "external prefix and normalized hash");
        eq("bucket.s3.example.test", put.url().getHost(), "virtual bucket host");
        eq("*", put.headers().get("if-none-match"), "S3 conditional immutable upload");
        eq(Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(HASH)), put.headers().get("x-amz-checksum-sha256"), "S3 body checksum without file read");
        for (String field : List.of("content-length", "content-type", "if-none-match", "x-amz-checksum-sha256", "x-amz-meta-sha256", "x-amz-meta-platform", "host"))
            check(query(put).get("X-Amz-SignedHeaders").contains(field), "signed PUT field " + field);
        check(!put.toString().contains(HASH) && !put.toString().contains("X-Amz") && !put.toString().contains("fixtureSecret"), "ticket logging redacted");
        check(!loaded.toString().contains("fixtureSecret") && !loaded.toString().contains("bucket"), "adapter logging redacted");
        expect(UnsupportedOperationException.class, () -> put.headers().put("range", "bytes=0-1"), "ticket headers immutable");
        ObjectStorage.Ticket get = loaded.download(HASH);
        eq("GET", get.method(), "download ticket only");
        check(get.headers().isEmpty() && query(get).get("X-Amz-SignedHeaders").equals("host"), "GET Range remains unsigned");
        expect(IllegalArgumentException.class, () -> loaded.upload("../bad", 1, "gba"), "key cannot escape prefix");
        expect(IllegalArgumentException.class, () -> loaded.upload(HASH, 0, "gba"), "zero body rejected");
        expect(IllegalArgumentException.class, () -> loaded.upload(HASH, CloudObjectStorage.MAX_PUT_BYTES + 1, "gba"), "multipart-sized upload not mislabeled single PUT");
        expect(IllegalArgumentException.class, () -> loaded.upload(HASH, 1, "gba\r\ninjected"), "platform header injection rejected");

        StorageConfig oss = config(StorageConfig.Provider.OSS, "https://oss.example.test", "bucket", "cn-example",
                "external/prefix", "fixtureAk", "fixtureSecret", "+/token=", false);
        ObjectStorage cloud = new CloudObjectStorage(oss, CLOCK, r -> { throw new AssertionError("ticket generation must not contact cloud"); });
        ObjectStorage.Ticket op = cloud.upload(HASH, 123, "nds");
        eq("true", op.headers().get("x-oss-forbid-overwrite"), "OSS native forbid-overwrite");
        eq("content-length;host", query(op).get("x-oss-additional-headers"), "OSS additional excludes mandatory x-oss/type headers");
        eq("+/token=", query(op).get("x-oss-security-token"), "STS token byte encoding");
        check(!op.headers().containsKey("x-oss-content-sha256") && !op.headers().containsKey("x-amz-checksum-sha256"), "OSS URL does not pretend AWS checksum support");
        eq(CLOCK.millis() + 900000, op.expiresAtMillis(), "ticket expiry uses UTC clock");
        check(query(cloud.download(HASH)).get("x-oss-additional-headers").equals("host"), "OSS GET Range unsigned");
        check(!oss.toString().contains("fixtureSecret") && !oss.toString().contains("token"), "credentials have no generated toString");
        StorageConfig path = config(StorageConfig.Provider.S3, "https://s3.example.test:9443", "bucket", "region",
                "fixtures", "ak", "sk", "+/token=", true);
        ObjectStorage.Ticket pathGet = new CloudObjectStorage(path, CLOCK).download(HASH);
        check(pathGet.url().getPath().startsWith("/bucket/fixtures/"), "S3 path addressing");
        eq("+/token=", query(pathGet).get("X-Amz-Security-Token"), "S3 STS token retained");

        for (String prefix : List.of("/leading", "trailing/", "a/../b", "a/./b", "a//b", "a\\b", "a%2Fb", "")) {
            Files.writeString(file, properties("oss", "https://oss.example.test", prefix));
            IOException failure = expect(IOException.class, () -> ObjectStorages.load(file), "unsafe prefix rejected");
            check(failure.getCause() == null && !failure.toString().contains("fixtureSecret"), "config exception has no secret-bearing cause");
        }
        for (String endpoint : List.of("https://user:fixtureSecret@example.test", "https://example.test/path", "https://example.test/?token=fixtureSecret", "http://external.example.test")) {
            Files.writeString(file, properties("s3", endpoint, "fixtures"));
            IOException failure = expect(IOException.class, () -> ObjectStorages.load(file), "unsafe endpoint rejected");
            check(!failure.toString().contains("fixtureSecret"), "endpoint exception redacted");
        }
        Files.writeString(file, properties("s3", "https://s3.example.test", "fixtures") + "typoKey=value\n");
        expect(IOException.class, () -> ObjectStorages.load(file), "unknown config key fails closed");
        String ossProperties = properties("oss", "https://oss.example.test", "fixtures");
        for (String versioning : List.of("", "Enabled", "Suspended")) {
            Files.writeString(file, ossProperties.replace("ossVersioning=disabled\n", "")
                    + (versioning.isEmpty() ? "" : "ossVersioning=" + versioning + "\n"));
            expect(IOException.class, () -> ObjectStorages.load(file), "OSS must explicitly assert operator-verified unversioned bucket");
        }
        Files.writeString(file, ossProperties);
        check(ObjectStorages.load(file).isPresent(), "OSS unversioned prerequisite accepted");
    }

    private static void metadataAndFailureBoundaries() throws Exception {
        for (StorageConfig.Provider provider : StorageConfig.Provider.values()) {
            StorageConfig c = fixture(provider);
            String prefix = provider == StorageConfig.Provider.S3 ? "x-amz-meta-" : "x-oss-meta-";
            AtomicInteger requests = new AtomicInteger();
            CloudObjectStorage storage = new CloudObjectStorage(c, CLOCK, r -> {
                eq("HEAD", r.method(), "metadata operation never GETs body"); requests.incrementAndGet();
                return response(200, Map.of("content-length", "12", prefix + "sha256", HASH, prefix + "platform", "gba"));
            });
            eq(new ObjectStorage.Metadata(12, HASH, "gba"), storage.head(HASH).orElseThrow(), "provider metadata parsed");
            eq(1, requests.get(), "one HEAD, no verification GET");
            eq(java.util.Optional.empty(), new CloudObjectStorage(c, CLOCK, r -> response(404, Map.of())).head(HASH), "404 alone means absent");
            expect(IOException.class, () -> new CloudObjectStorage(c, CLOCK, r -> response(403, Map.of())).head(HASH), "403 is not absent");
            expect(IOException.class, () -> new CloudObjectStorage(c, CLOCK, r -> response(301, Map.of())).head(HASH), "redirect is not success");
            for (Map<String, String> h : List.of(Map.of("content-length", "12"),
                    Map.of("content-length", "12", prefix + "sha256", "0".repeat(64), prefix + "platform", "gba"),
                    Map.of("content-length", "oops", prefix + "sha256", HASH, prefix + "platform", "gba"),
                    Map.of("content-length", "12", prefix + "sha256", HASH, prefix + "platform", "not a platform")))
                expect(IOException.class, () -> new CloudObjectStorage(c, CLOCK, r -> response(200, h)).head(HASH), "bad metadata fails closed");
            IOException failure = expect(IOException.class, () -> new CloudObjectStorage(c, CLOCK, r -> {
                throw new IOException("secret=fixtureSecret signed=" + r.uri());
            }).head(HASH), "transport exception sanitized");
            check(failure.getCause() == null && !failure.toString().contains("fixtureSecret") && !failure.toString().contains("Signature"), "URL/secret not in exception graph");
        }
        expect(IOException.class, () -> new CloudObjectStorage(fixture(StorageConfig.Provider.S3), CLOCK, r ->
                response(200, Map.of("content-length", "1", "x-amz-meta-sha256", HASH, "x-amz-meta-platform", "gba", "x-amz-checksum-sha256", "wrong"))).head(HASH), "S3 returned checksum conflict rejected");
    }

    private static void migrationBoundaries() throws Exception {
        byte[] bytes = "original storage fixture".getBytes(StandardCharsets.UTF_8);
        Path source = scratch.resolve("synthetic.rom"); Files.write(source, bytes);
        for (StorageConfig.Provider provider : StorageConfig.Provider.values()) {
            StorageConfig config = fixture(provider); String meta = provider == StorageConfig.Provider.S3 ? "x-amz-meta-" : "x-oss-meta-";
            AtomicInteger heads = new AtomicInteger(), puts = new AtomicInteger();
            CloudObjectStorage storage = new CloudObjectStorage(config, CLOCK, r -> {
                check(!r.method().equals("GET"), "migration never downloads object body");
                if (r.method().equals("HEAD") && heads.getAndIncrement() == 0) return response(404, Map.of());
                if (r.method().equals("PUT")) {
                    puts.incrementAndGet(); eq((long) bytes.length, r.bodyPublisher().orElseThrow().contentLength(), "PUT publisher supplies signed file size");
                    if (provider == StorageConfig.Provider.OSS) eq("oNUc5SpF61EX4ar3+uC9YQ==", r.headers().firstValue("content-md5").orElseThrow(), "migration OSS MD5 independently fixed vector");
                    return response(200, Map.of());
                }
                return response(200, Map.of("content-length", Integer.toString(bytes.length), meta + "sha256", HASH, meta + "platform", "gba"));
            });
            check(storage.putExisting(source, HASH, "gba"), "new synthetic migration created"); eq(1, puts.get(), "one PUT only");
            check(!storage.putExisting(source, HASH, "gba"), "identical existing object retained"); eq(1, puts.get(), "idempotent migration does not PUT again");
            expect(IOException.class, () -> storage.putExisting(source, HASH, "gb"), "conflicting existing platform never overwritten");

            AtomicInteger invalidPuts = new AtomicInteger();
            CloudObjectStorage invalid = new CloudObjectStorage(config, CLOCK, r -> {
                if (r.method().equals("PUT")) invalidPuts.incrementAndGet(); return response(404, Map.of());
            });
            expect(IOException.class, () -> invalid.putExisting(source, "0".repeat(64), "gba"), "wrong local source hash not uploaded");
            eq(0, invalidPuts.get(), "wrong local source leaves no poisoned object");
            for (int conditionalConflict : List.of(409, 412)) {
                AtomicInteger raceHeads = new AtomicInteger(), racePuts = new AtomicInteger();
                CloudObjectStorage race = new CloudObjectStorage(config, CLOCK, r -> {
                    if (r.method().equals("HEAD") && raceHeads.getAndIncrement() == 0) return response(404, Map.of());
                    if (r.method().equals("PUT")) { racePuts.incrementAndGet(); return response(conditionalConflict, Map.of()); }
                    return response(200, Map.of("content-length", Integer.toString(bytes.length), meta + "sha256", HASH, meta + "platform", "gba"));
                });
                check(!race.putExisting(source, HASH, "gba"), "conditional race accepts identical winning HEAD only");
                eq(1, racePuts.get(), "conditional conflict never retries with overwrite");
            }
        }
    }

    private static void loopbackHttp() throws Exception {
        byte[] bytes = "original storage fixture".getBytes(StandardCharsets.UTF_8);
        Path source = scratch.resolve("loopback.rom"); Files.write(source, bytes);
        AtomicInteger heads = new AtomicInteger(), puts = new AtomicInteger(), gets = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                String method = exchange.getRequestMethod();
                if (method.equals("HEAD")) {
                    heads.incrementAndGet();
                    if (puts.get() == 0) { exchange.sendResponseHeaders(404, -1); return; }
                    exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
                    exchange.getResponseHeaders().set("x-amz-meta-sha256", HASH);
                    exchange.getResponseHeaders().set("x-amz-meta-platform", "gba");
                    exchange.sendResponseHeaders(200, -1);
                } else if (method.equals("PUT")) {
                    check(java.util.Arrays.equals(bytes, exchange.getRequestBody().readAllBytes()), "real HTTP streaming PUT original bytes");
                    eq(Integer.toString(bytes.length), exchange.getRequestHeaders().getFirst("Content-Length"), "real HTTP content length matches signature");
                    eq("*", exchange.getRequestHeaders().getFirst("If-None-Match"), "real HTTP conditional PUT header");
                    puts.incrementAndGet(); exchange.sendResponseHeaders(200, -1);
                } else { gets.incrementAndGet(); exchange.sendResponseHeaders(405, -1); }
            } finally { exchange.close(); }
        });
        server.start();
        try {
            StorageConfig c = config(StorageConfig.Provider.S3, "http://127.0.0.1:" + server.getAddress().getPort(),
                    "bucket", "region", "fixtures", "fixtureAk", "fixtureSecret", "", true);
            ObjectStorage storage = new CloudObjectStorage(c, CLOCK);
            check(storage.head(HASH).isEmpty(), "native HttpClient HEAD on absent object");
            check(storage.putExisting(source, HASH, "gba"), "native HttpClient synthetic migration");
            check(storage.head(HASH).isPresent(), "native HttpClient metadata readback");
            eq(1, puts.get(), "actual loopback exactly one PUT"); eq(0, gets.get(), "actual loopback zero GETs");
            check(heads.get() >= 3, "actual loopback HEAD metadata only");
        } finally { server.stop(0); }
    }

    private static StorageConfig fixture(StorageConfig.Provider provider) {
        return config(provider, "https://storage.example.test", "bucket", "region", "fixtures", "fixtureAk", "fixtureSecret", "", false);
    }
    private static StorageConfig config(StorageConfig.Provider provider, String endpoint, String bucket, String region,
                                        String prefix, String ak, String secret, String token, boolean pathStyle) {
        return new StorageConfig(provider, URI.create(endpoint), bucket, region, prefix, ak, secret, token, pathStyle, 900);
    }
    private static CloudObjectStorage.Response response(int code, Map<String, String> headers) {
        Map<String, List<String>> values = new HashMap<>(); headers.forEach((k, v) -> values.put(k, List.of(v)));
        return new CloudObjectStorage.Response(code, HttpHeaders.of(values, (k, v) -> true));
    }
    private static Map<String, String> query(ObjectStorage.Ticket ticket) {
        Map<String, String> result = new HashMap<>();
        for (String pair : ticket.url().getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2); result.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length == 1 ? "" : URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return result;
    }
    private static String properties(String provider, String endpoint, String prefix) {
        return "provider=" + provider + "\nendpoint=" + endpoint + "\nbucket=bucket\nregion=region\nkeyPrefix=" + prefix.replace("\\", "\\\\")
                + "\naccessKeyId=fixtureAk\naccessKeySecret=fixtureSecret\n" + (provider.equals("oss") ? "ossVersioning=disabled\n" : "");
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void eq(Object expected, Object actual, String message) { check(java.util.Objects.equals(expected, actual), message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static <T extends Throwable> T expect(Class<T> type, Checked task, String message) throws Exception {
        checks++;
        try { task.run(); } catch (Throwable failure) { if (type.isInstance(failure)) return type.cast(failure); throw new AssertionError(message + " (wrong failure type)"); }
        throw new AssertionError(message + " (accepted)");
    }
}
