package net.muxigame.console.client.input;

import java.nio.file.*;
import java.util.*;
import net.muxigame.console.domain.Platform;

/** Pure Java disk round-trips; no Minecraft singleton, GLFW polling or native gamepad access. */
public final class InputProfilesPersistenceTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]).toAbsolutePath().normalize(); Files.createDirectories(output);
        Path temporary = Files.createTempDirectory(output, "profiles-");
        if (!temporary.toRealPath().startsWith(output.toRealPath())) throw new AssertionError("Temporary directory escaped test output");
        try {
            InputProfiles original = new InputProfiles(temporary.toFile());
            check(original.loadWarning().isEmpty(), "Absent config is a normal first launch");
            for (Platform platform : Platform.values()) {
                int key = 290 + platform.ordinal(); // F1... key codes, excluding reserved Escape/F.
                original.bind(platform, 0, key);
                original.setEnabled(platform, platform.ordinal() % 2 == 0);
                original.profile(platform).setGamepad("diagnostic-guid-" + platform.id(), platform.ordinal() % 16);
            }
            original.save(); check(Files.isRegularFile(original.path()), "Saved config exists");
            InputProfiles second = new InputProfiles(temporary.toFile());
            check(second.loadWarning().isEmpty(), "Valid config reloads without warning");
            for (Platform platform : Platform.values()) {
                var stored = second.profile(platform);
                check(stored.keyBinding(0) == 290 + platform.ordinal(), platform.id() + " custom key survives disk reload");
                check(stored.enabled() == (platform.ordinal() % 2 == 0), platform.id() + " gamepad toggle survives disk reload");
                check(stored.gamepadGuid().equals("diagnostic-guid-" + platform.id()), platform.id() + " GUID survives disk reload");
                check(stored.preferredJoystick() == platform.ordinal() % 16, platform.id() + " joystick preference survives disk reload");
            }
            second.reset(Platform.NES); second.save();
            InputProfiles third = new InputProfiles(temporary.toFile());
            check(third.profile(Platform.NES).keyBinding(0) == 75, "Reset platform default key is persisted");
            check(!third.profile(Platform.NES).enabled() && third.profile(Platform.NES).gamepadGuid().isEmpty(), "Reset clears only that platform's gamepad config");
            for (Platform platform : Platform.values()) if (platform != Platform.NES)
                check(third.profile(platform).keyBinding(0) == 290 + platform.ordinal(), platform.id() + " unaffected by another platform reset");
            third.bind(Platform.GBA, 1, third.profile(Platform.GBA).keyBinding(0));
            check(third.profile(Platform.GBA).keyBinding(0) == -1, "Rebinding a physical key releases its previous logical button");
            try { third.bind(Platform.GBA, 2, 256); throw new AssertionError("Reserved Escape accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
            try { third.bind(Platform.GBA, 2, 70); throw new AssertionError("Reserved F accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
            third.save(); InputProfiles fourth = new InputProfiles(temporary.toFile());
            check(fourth.profile(Platform.GBA).keyBinding(0) == -1 && fourth.profile(Platform.GBA).keyBinding(1) == 294, "Rebinding and unbound key survive another disk reload");
            Files.writeString(fourth.path(), "{broken-json");
            InputProfiles recovered = new InputProfiles(temporary.toFile());
            check(!recovered.loadWarning().isEmpty(), "Malformed config reports recovery warning");
            for (Platform platform : Platform.values())
                check(recovered.profile(platform).keyBinding(0) == 75 && !recovered.profile(platform).enabled(), platform.id() + " malformed file uses defaults");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        check(!Files.exists(temporary), "Owned temporary config directory removed");
        System.out.println("PASS " + checks + " input-profile persistence checks; temporary configuration removed");
    }
}
