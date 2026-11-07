package net.muxigame.console.domain;

import java.util.Objects;

public record RomDescriptor(Platform platform, String sha256, long size, String filename, String detection) {
    public RomDescriptor {
        Objects.requireNonNull(platform, "platform");
        sha256 = Hashing.normalizeSha256(sha256);
        RomInspector.validateFilename(filename);
        if (size < platform.minBytes() || size > platform.maxBytes()) throw new DomainException("size", "ROM size outside platform limit");
        if (detection == null || detection.isBlank() || detection.length() > 64)
            throw new DomainException("format", "A detection method is required");
    }
}
