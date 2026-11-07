package net.muxigame.console.domain;

import java.util.Objects;
import java.util.UUID;

/** A transferable physical instance. There is deliberately no uploader/owner UUID. */
public record Cartridge(UUID instanceId, Platform platform, String sha256, long size, String title) {
    public Cartridge {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(platform, "platform");
        sha256 = Hashing.normalizeSha256(sha256);
        if (size < platform.minBytes() || size > platform.maxBytes()) throw new DomainException("size", "Invalid cartridge size");
        if (title == null || title.isBlank() || title.length() > 96 || title.codePoints().anyMatch(Character::isISOControl))
            throw new DomainException("title", "Cartridge title must contain 1 to 96 visible characters");
    }
}
