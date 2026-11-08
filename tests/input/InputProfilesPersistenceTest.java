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
    private static int customKey(Platform platform) { int key = 290 + platform.ordinal(); return key >= 297 ? key + 1 : key; }
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]).toAbsolutePath().normalize(); Files.createDirectories(output);
        Path temporary = Files.createTempDirectory(output, "profiles-");
        if (!temporary.toRealPath().startsWith(output.toRealPath())) throw new AssertionError("Temporary directory escaped test output");
        try {
            InputProfiles original = new InputProfiles(temporary.toFile());
            check(original.loadWarning().isEmpty(), "Absent config is a normal first launch");
            for (Platform platform : Platform.values()) {
                int key = customKey(platform); // Function keys, excluding reserved F8.
                original.bind(platform, 0, key);
                original.setEnabled(platform, platform.ordinal() % 2 == 0);
                original.profile(platform).setGamepad("diagnostic-guid-" + platform.id(), platform.ordinal() % 16);
            }
            original.save(); check(Files.isRegularFile(original.path()), "Saved config exists");
            InputProfiles second = new InputProfiles(temporary.toFile());
            check(second.loadWarning().isEmpty(), "Valid config reloads without warning");
            for (Platform platform : Platform.values()) {
                var stored = second.profile(platform);
                check(stored.keyBinding(0) == customKey(platform), platform.id() + " custom key survives disk reload");
                check(stored.enabled() == (platform.ordinal() % 2 == 0), platform.id() + " gamepad toggle survives disk reload");
                check(stored.gamepadGuid().equals("diagnostic-guid-" + platform.id()), platform.id() + " GUID survives disk reload");
                check(stored.preferredJoystick() == platform.ordinal() % 16, platform.id() + " joystick preference survives disk reload");
            }
            second.reset(Platform.NES); second.save();
            InputProfiles third = new InputProfiles(temporary.toFile());
            check(third.profile(Platform.NES).keyBinding(0) == 75, "Reset platform default key is persisted");
            check(!third.profile(Platform.NES).enabled() && third.profile(Platform.NES).gamepadGuid().isEmpty(), "Reset clears only that platform's gamepad config");
            for (Platform platform : Platform.values()) if (platform != Platform.NES)
                check(third.profile(platform).keyBinding(0) == customKey(platform), platform.id() + " unaffected by another platform reset");
            third.bind(Platform.GBA, 1, third.profile(Platform.GBA).keyBinding(0));
            check(third.profile(Platform.GBA).keyBinding(0) == -1, "Rebinding a physical key releases its previous logical button");
            try { third.bind(Platform.GBA, 2, 256); throw new AssertionError("Reserved Escape accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
            try { third.bind(Platform.GBA, 2, 70); throw new AssertionError("Reserved F accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
            try { third.bind(Platform.GBA, 2, 297); throw new AssertionError("Reserved F8 accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
            third.save(); InputProfiles fourth = new InputProfiles(temporary.toFile());
            check(fourth.profile(Platform.GBA).keyBinding(0) == -1 && fourth.profile(Platform.GBA).keyBinding(1) == 294, "Rebinding and unbound key survive another disk reload");
            check(fourth.focusKey()==70,"Existing schema-one profiles default to F for view controls");
            fourth.bind(Platform.NES,8,72); // H
            fourth.bindAxis(Platform.N64,0,72);
            check(fourth.bindFocus(72)==2,"Global view assignment releases button and axis conflicts across platforms");
            check(fourth.profile(Platform.NES).keyBinding(8)==-1&&fourth.profile(Platform.N64).axisBinding(0)==-1,"No view key is also sent as gameplay input");
            fourth.bind(Platform.NES,8,70); // F is available after moving the view key.
            check(fourth.sampleKeys(Platform.NES,Set.of(70)).buttons()==1<<8,"Ordinary screen key callback follows custom A binding");
            check(fourth.sampleKeys(Platform.NES,Set.of()).buttons()==0,"Key release clears the sampled logical button");
            check(fourth.sampleKeys(Platform.NES,Set.of(70,87,83)).buttons()==1<<8,"Opposed callback directions center without dropping action input");
            for(int reserved:new int[]{-1,256,297})try{fourth.bindFocus(reserved);throw new AssertionError("Unsafe view key accepted");}catch(IllegalArgumentException expected){checks++;}
            try{fourth.bind(Platform.NES,0,72);throw new AssertionError("View key accepted as game action");}catch(IllegalArgumentException expected){checks++;}
            fourth.save();InputProfiles withFocus=new InputProfiles(temporary.toFile());
            check(withFocus.focusKey()==72&&withFocus.loadWarning().isEmpty(),"Custom global view key survives old-schema-compatible disk round-trip");
            check(withFocus.profile(Platform.NES).keyBinding(8)==70,"Former F key remains a custom game key after reload");
            withFocus.reset(Platform.NES);check(withFocus.focusKey()==72,"Platform reset preserves global view preference");
            withFocus.bindFocus(87);withFocus.reset(Platform.NES);
            check(withFocus.profile(Platform.NES).keyBinding(4)==-1,"Reset cannot reintroduce the view key as a default movement binding");
            Files.writeString(fourth.path(), "{broken-json");
            InputProfiles recovered = new InputProfiles(temporary.toFile());
            check(!recovered.loadWarning().isEmpty(), "Malformed config reports recovery warning");
            for (Platform platform : Platform.values())
                check(recovered.profile(platform).keyBinding(0) == (platform==Platform.MD||platform==Platform.N64?74:75) && !recovered.profile(platform).enabled(), platform.id() + " malformed file uses hardware defaults");
            check(recovered.focusKey()==70,"Malformed config recovers the safe default view key atomically");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        check(!Files.exists(temporary), "Owned temporary config directory removed");
        System.out.println("PASS " + checks + " input-profile persistence checks; temporary configuration removed");
    }
}
