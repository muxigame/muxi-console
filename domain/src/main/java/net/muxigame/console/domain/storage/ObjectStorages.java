package net.muxigame.console.domain.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

public final class ObjectStorages {
    private ObjectStorages() { }

    /**
     * Explicit server-private UTF-8 properties file; no environment/classpath lookup or cloud defaults.
     * Required: provider(s3/oss), endpoint, bucket, region, keyPrefix, accessKeyId, accessKeySecret.
     * Optional: sessionToken, addressingStyle(virtual/path; OSS requires virtual), ticketSeconds(900).
     * OSS additionally requires ossVersioning=disabled, asserting operator-verified unversioned bucket;
     * Suspended is NOT disabled. Its credential must not be able to change bucket versioning.
     * A missing file disables this adapter. A malformed/unreadable existing file fails closed.
     */
    public static Optional<ObjectStorage> load(Path externalConfig) throws IOException {
        if (externalConfig == null) throw new IllegalArgumentException("An explicit private configuration path is required");
        if (Files.notExists(externalConfig, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        return Optional.of(new CloudObjectStorage(StorageConfig.load(externalConfig), Clock.systemUTC()));
    }
}
