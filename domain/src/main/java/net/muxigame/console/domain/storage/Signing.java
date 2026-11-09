package net.muxigame.console.domain.storage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Shared standard primitives only. Provider canonical requests and key derivation stay separate. */
final class Signing {
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private Signing() { }
    static String encode(String text, boolean slashSafe) {
        StringBuilder out = new StringBuilder();
        for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
            int c = value & 255;
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                    || c == '-' || c == '_' || c == '.' || c == '~' || slashSafe && c == '/') out.append((char) c);
            else out.append('%').append("0123456789ABCDEF".charAt(c >>> 4)).append("0123456789ABCDEF".charAt(c & 15));
        }
        return out.toString();
    }
    static String query(Map<String, String> values, boolean omitEmptyEquals) {
        TreeMap<String, String> encoded = new TreeMap<>();
        values.forEach((key, value) -> encoded.put(encode(key, false), encode(value, false)));
        return String.join("&", encoded.entrySet().stream().map(e -> e.getKey()
                + (omitEmptyEquals && e.getValue().isEmpty() ? "" : "=" + e.getValue())).toList());
    }
    static String canonicalHeaders(Map<String, String> headers) {
        StringBuilder out = new StringBuilder();
        new TreeMap<>(headers).forEach((name, value) -> out.append(name).append(':').append(value.trim()).append('\n'));
        return out.toString();
    }
    static String host(URI uri) { return uri.getRawAuthority(); }
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(bytes(value));
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException("HMAC-SHA256 unavailable"); }
    }
    static String sha(String value) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes(value))); }
        catch (GeneralSecurityException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
}
