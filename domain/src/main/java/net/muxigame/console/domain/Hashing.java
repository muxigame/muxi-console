package net.muxigame.console.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class Hashing {
    private Hashing() { }

    public static String normalizeSha256(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}"))
            throw new DomainException("hash", "Expected a 64 digit SHA-256 digest");
        return value.toLowerCase(Locale.ROOT);
    }

    public static MessageDigest sha256Digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String sha256(byte[] bytes) { return HexFormat.of().formatHex(sha256Digest().digest(bytes)); }

    public static String sha256(Path path) throws IOException {
        var digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[32768];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
