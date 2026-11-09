package net.muxigame.console.domain.storage;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** Package-private credentials deliberately have no generated record/toString or public accessors. */
final class StorageConfig {
    enum Provider { S3, OSS }
    final Provider provider;
    final URI endpoint;
    final String bucket, region, keyPrefix, accessKeyId, accessKeySecret, sessionToken;
    final boolean pathStyle;
    final int ticketSeconds;
    private static final Set<String> KEYS = Set.of("provider", "endpoint", "bucket", "region", "keyPrefix",
            "accessKeyId", "accessKeySecret", "sessionToken", "addressingStyle", "ticketSeconds", "ossVersioning");

    StorageConfig(Provider provider, URI endpoint, String bucket, String region, String keyPrefix,
                  String accessKeyId, String accessKeySecret, String sessionToken, boolean pathStyle, int ticketSeconds) {
        if (provider == null || endpoint == null || endpoint.getHost() == null || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !(endpoint.getRawPath().isEmpty() || endpoint.getRawPath().equals("/"))
                || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme()) && loopback(endpoint.getHost()))
                || endpoint.getPort() == 0 || endpoint.getPort() > 65535)
            throw new IllegalArgumentException("Object storage endpoint must be a bare HTTPS origin");
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || bucket.contains("..")
                || region == null || !region.matches("[a-z0-9][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("Invalid object storage bucket or region");
        if (keyPrefix == null || !keyPrefix.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,255}")
                || keyPrefix.endsWith("/") || keyPrefix.contains("//"))
            throw new IllegalArgumentException("Invalid object storage key prefix");
        for (String segment : keyPrefix.split("/")) if (segment.equals(".") || segment.equals(".."))
            throw new IllegalArgumentException("Invalid object storage key prefix");
        if (accessKeyId == null || !accessKeyId.matches("[A-Za-z0-9_-]{1,256}")
                || !opaque(accessKeySecret, false) || !opaque(sessionToken, true))
            throw new IllegalArgumentException("Invalid object storage credentials");
        if (ticketSeconds < 1 || ticketSeconds > 3600 || provider == Provider.OSS && pathStyle)
            throw new IllegalArgumentException("Invalid object storage addressing or ticket lifetime");
        this.provider = provider; this.endpoint = endpoint; this.bucket = bucket; this.region = region;
        this.keyPrefix = keyPrefix; this.accessKeyId = accessKeyId; this.accessKeySecret = accessKeySecret;
        this.sessionToken = sessionToken; this.pathStyle = pathStyle; this.ticketSeconds = ticketSeconds;
    }

    static StorageConfig load(Path file) throws IOException {
        try {
            if (!file.getFileSystem().provider().getScheme().equals("file")
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 32768)
                throw new IllegalArgumentException();
            Properties p = new Properties();
            try (var input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(input); }
            if (!KEYS.containsAll(p.stringPropertyNames())) throw new IllegalArgumentException();
            String style = p.getProperty("addressingStyle", "virtual");
            if (!style.equals("virtual") && !style.equals("path")) throw new IllegalArgumentException();
            Provider provider = Provider.valueOf(required(p, "provider").toUpperCase(Locale.ROOT));
            // OSS ignores forbid-overwrite for Enabled AND Suspended versioning. Operator verifies via
            // GetBucketVersioning separately; this adapter never fetches bucket or ROM bodies.
            if (provider == Provider.OSS && !"disabled".equals(p.getProperty("ossVersioning"))) throw new IllegalArgumentException();
            return new StorageConfig(provider,
                    URI.create(required(p, "endpoint")), required(p, "bucket"), required(p, "region"), required(p, "keyPrefix"),
                    required(p, "accessKeyId"), required(p, "accessKeySecret"), p.getProperty("sessionToken", ""),
                    style.equals("path"), Integer.parseInt(p.getProperty("ticketSeconds", "900")));
        } catch (IOException | RuntimeException failure) {
            // URI/Properties/HTTP exceptions can contain credentials or signed URLs. Never retain their causes.
            throw new IOException("Object storage private configuration could not be loaded");
        }
    }

    URI objectUri(String key) {
        String host = (pathStyle ? endpoint.getHost() : bucket + "." + endpoint.getHost()).toLowerCase(Locale.ROOT);
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        int port = endpoint.getPort();
        if ("https".equals(endpoint.getScheme()) && port == 443 || "http".equals(endpoint.getScheme()) && port == 80) port = -1;
        String authority = host + (port < 0 ? "" : ":" + port);
        return URI.create(endpoint.getScheme() + "://" + authority + "/"
                + (pathStyle ? Signing.encode(bucket, false) + "/" : "") + Signing.encode(key, true));
    }
    private static String required(Properties p, String key) {
        String value = p.getProperty(key); if (value == null || value.isBlank()) throw new IllegalArgumentException(); return value;
    }
    private static boolean opaque(String value, boolean emptyAllowed) {
        return value != null && (emptyAllowed || !value.isEmpty()) && value.length() <= 16384
                && value.chars().allMatch(c -> c >= 33 && c <= 126);
    }
    private static boolean loopback(String host) { return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]") || host.equals("::1"); }
    @Override public String toString() { return "ObjectStorageConfig[redacted]"; }
}
