package net.muxigame.console.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** The native alias must retain the actual machine directory, including existing files. */
public final class NativeProfilePathTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("muxi-native-profile-").toAbsolutePath().normalize();
        Path machine = root.resolve("long-machine-directory-name-6541d7d3-d991-4d95-8341-343f32e67778");
        Files.createDirectories(machine);
        Path file = machine.resolve("retained.bin"); Files.write(file, new byte[] { 3, 1, 4 });
        check(NativeProfilePath.forCore(machine, "gba").equals(machine.toString()), "old platforms retain the original path");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            String nativePath = NativeProfilePath.forCore(machine, "n3ds");
            check(Files.isSameFile(machine, Path.of(nativePath)), "native alias identifies the same machine");
            check(Files.isSameFile(file, Path.of(nativePath).resolve("retained.bin")), "existing files are not copied or replaced");
            Files.write(Path.of(nativePath).resolve("written.bin"), new byte[] { 9 });
            check(Files.readAllBytes(machine.resolve("written.bin"))[0] == 9, "native writes remain in the Java profile");
        }
        Path tooLong = machine.resolve("x".repeat(100)); Files.createDirectories(tooLong);
        try { NativeProfilePath.windows(tooLong, path -> path); throw new AssertionError("missing alias must reject an unsafe path"); }
        catch (IOException expected) { check(expected.getMessage().contains("8.3"), "disabled aliases produce an actionable error before native initialization"); }
        Path other = root.resolve("other"); Files.createDirectory(other);
        try { NativeProfilePath.windows(machine, path -> other.toString()); throw new AssertionError("different directory must be rejected"); }
        catch (IOException expected) { check(expected.getMessage().contains("does not identify"), "alias cannot change machine ownership"); }
        check(Files.readAllBytes(file).length == 3, "original data remains intact");
        System.out.println("NativeProfilePathTest " + checks + " checks");
    }
}
