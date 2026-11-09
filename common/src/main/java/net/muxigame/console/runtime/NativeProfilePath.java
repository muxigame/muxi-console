package net.muxigame.console.runtime;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.WString;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** A native spelling of the same directory; never relocates a machine's files. */
final class NativeProfilePath {
    // Azahar appends up to 94 characters for its built-in NAND initialization.
    // Its Windows file APIs do not consistently support extended-length paths.
    private static final int MAX_AZAHAR_BASE = 160;

    private NativeProfilePath() { }

    static String forCore(Path directory, String platform) throws IOException {
        Path actual = directory.toAbsolutePath().normalize();
        if (!platform.equals("n3ds") || !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
            return actual.toString();
        return windows(actual, NativeProfilePath::shortName);
    }

    @FunctionalInterface interface ShortName { String get(String path) throws IOException; }

    static String windows(Path actual, ShortName names) throws IOException {
        String original = actual.toString(), candidate = names.get(original);
        if (candidate != null && !candidate.isEmpty() && candidate.length() < original.length()) {
            Path alias = Path.of(candidate);
            if (!alias.isAbsolute() || !Files.isSameFile(actual, alias))
                throw new IOException("3DS native save directory alias does not identify the machine directory");
            original = candidate;
        }
        if (original.length() > MAX_AZAHAR_BASE)
            throw new IOException("3DS save directory is too long for this core and has no sufficiently short Windows alias. Use a shorter Minecraft installation path; this volume may have 8.3 names disabled.");
        return original;
    }

    private static String shortName(String path) throws IOException {
        try (Memory buffer = new Memory(32768L * 2)) {
            Function function = Function.getFunction("kernel32", "GetShortPathNameW", Function.ALT_CONVENTION);
            int length = function.invokeInt(new Object[] { new WString(path), buffer, 32768 });
            if (length == 0) return null;
            if (length >= 32768) throw new IOException("Windows native save directory alias exceeds the supported bound");
            return buffer.getWideString(0);
        }
    }
}
