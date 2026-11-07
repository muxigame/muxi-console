package net.muxigame.console.client.input;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.muxigame.console.domain.Platform;
import net.muxigame.console.runtime.PortInput;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.system.MemoryStack;

/** Per-platform level-state polling; key-repeat events never create extra inputs. */
public final class InputProfiles {
    public static final String[] BUTTON_NAMES = {
        "B", "Y", "Select", "Start", "↑", "↓", "←", "→", "A", "X", "L", "R", "L2", "R2", "L3", "R3"
    };
    private static final int[] DEFAULTS = {
        GLFW.GLFW_KEY_K, GLFW.GLFW_KEY_U, GLFW.GLFW_KEY_RIGHT_SHIFT, GLFW.GLFW_KEY_ENTER,
        GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_D,
        GLFW.GLFW_KEY_J, GLFW.GLFW_KEY_I, GLFW.GLFW_KEY_Q, GLFW.GLFW_KEY_E,
        GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_Z, GLFW.GLFW_KEY_C
    };
    private static final int[] GAMEPAD_BUTTONS = {
        GLFW.GLFW_GAMEPAD_BUTTON_A, GLFW.GLFW_GAMEPAD_BUTTON_X,
        GLFW.GLFW_GAMEPAD_BUTTON_BACK, GLFW.GLFW_GAMEPAD_BUTTON_START,
        GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP, GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN,
        GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT, GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT,
        GLFW.GLFW_GAMEPAD_BUTTON_B, GLFW.GLFW_GAMEPAD_BUTTON_Y,
        GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER, GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER,
        -1, -1, GLFW.GLFW_GAMEPAD_BUTTON_LEFT_THUMB, GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_THUMB
    };
    private static InputProfiles instance;
    private final Path path;
    private final EnumMap<Platform, Profile> profiles = new EnumMap<>(Platform.class);
    private String loadWarning = "";

    public static synchronized InputProfiles get() {
        if (instance == null) instance = new InputProfiles(Minecraft.getInstance().gameDirectory);
        return instance;
    }

    public InputProfiles(File gameDirectory) {
        path = Objects.requireNonNull(gameDirectory).toPath().resolve("config/muxi-console-input.json");
        for (Platform platform : Platform.values()) profiles.put(platform, new Profile());
        try { load(); } catch (IOException | RuntimeException error) {
            loadWarning = "输入配置未能读取，当前使用默认值";
        }
    }

    public synchronized Profile profile(Platform platform) { return profiles.get(Objects.requireNonNull(platform)); }
    public Path path() { return path; }
    public synchronized String loadWarning() { return loadWarning; }
    public synchronized void bind(Platform platform, int bit, int key) { profile(platform).bind(bit, key); }
    public synchronized void reset(Platform platform) { profiles.put(platform, new Profile()); }
    public synchronized void setEnabled(Platform platform, boolean enabled) { profile(platform).setEnabled(enabled); }

    public synchronized void setGamepad(Platform platform, int joystickId) {
        Profile profile = profile(platform);
        if (joystickId < GLFW.GLFW_JOYSTICK_1 || joystickId > GLFW.GLFW_JOYSTICK_LAST
                || !GLFW.glfwJoystickIsGamepad(joystickId)) {
            profile.setGamepad("", -1);
            return;
        }
        profile.setGamepad(Objects.requireNonNullElse(GLFW.glfwGetJoystickGUID(joystickId), ""), joystickId);
    }

    public static List<GamepadDevice> availableGamepads() {
        List<GamepadDevice> result = new ArrayList<>();
        for (int id = GLFW.GLFW_JOYSTICK_1; id <= GLFW.GLFW_JOYSTICK_LAST; id++) {
            if (GLFW.glfwJoystickPresent(id) && GLFW.glfwJoystickIsGamepad(id)) {
                result.add(new GamepadDevice(id, Objects.requireNonNullElse(GLFW.glfwGetJoystickGUID(id), ""),
                        Objects.requireNonNullElse(GLFW.glfwGetGamepadName(id), "标准手柄")));
            }
        }
        return List.copyOf(result);
    }

    public synchronized PortInput sample(Platform platform, long window) {
        Profile profile = profile(platform);
        int mask = 0;
        for (int bit = 0; bit < 16; bit++) {
            int key = profile.keyBinding(bit);
            if (window != 0 && key >= GLFW.GLFW_KEY_SPACE && GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS)
                mask |= 1 << bit;
        }
        short lx = 0, ly = 0, rx = 0, ry = 0;
        if (profile.enabled()) {
            int joystick = resolveJoystick(profile);
            if (joystick >= 0) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    GLFWGamepadState state = GLFWGamepadState.malloc(stack);
                    if (GLFW.glfwGetGamepadState(joystick, state)) {
                        for (int bit = 0; bit < 16; bit++) {
                            int button = GAMEPAD_BUTTONS[bit];
                            if (button >= 0 && state.buttons(button) == GLFW.GLFW_PRESS) mask |= 1 << bit;
                        }
                        float x = safeAxis(state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X));
                        float y = safeAxis(state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y));
                        if (x < -0.35f) mask |= 1 << 6;
                        if (x > 0.35f) mask |= 1 << 7;
                        if (y < -0.35f) mask |= 1 << 4;
                        if (y > 0.35f) mask |= 1 << 5;
                        if (state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_TRIGGER) > 0.2f) mask |= 1 << 12;
                        if (state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER) > 0.2f) mask |= 1 << 13;
                        lx = analog(x); ly = analog(y);
                        rx = analog(state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_X));
                        ry = analog(state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_Y));
                    }
                }
            }
        }
        if ((mask & 0x30) == 0x30) mask &= ~0x30;
        if ((mask & 0xc0) == 0xc0) mask &= ~0xc0;
        return new PortInput(mask, lx, ly, rx, ry);
    }

    private static float safeAxis(float value) { return Float.isFinite(value) ? Math.max(-1, Math.min(1, value)) : 0; }
    private static short analog(float value) {
        value = safeAxis(value);
        if (Math.abs(value) < 0.15f) return 0;
        return (short) Math.round(value * 32767);
    }
    private static int resolveJoystick(Profile profile) {
        if (profile.gamepadGuid().isEmpty()) return -1;
        int preferred = profile.preferredJoystick();
        if (preferred >= 0 && GLFW.glfwJoystickIsGamepad(preferred)
                && profile.gamepadGuid().equals(GLFW.glfwGetJoystickGUID(preferred))) return preferred;
        for (GamepadDevice device : availableGamepads())
            if (device.guid().equals(profile.gamepadGuid())) return device.id();
        return -1;
    }

    public synchronized void load() throws IOException {
        if (!Files.exists(path)) return;
        if (Files.size(path) > 262144) throw new IOException("Input profile file exceeds limit");
        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        if (root.get("schema").getAsInt() != 1) throw new IOException("Unknown input profile version");
        JsonObject input = root.getAsJsonObject("platforms");
        EnumMap<Platform, Profile> loaded = new EnumMap<>(Platform.class);
        for (Platform platform : Platform.values()) {
            Profile profile = new Profile();
            if (input.has(platform.id())) {
                JsonObject stored = input.getAsJsonObject(platform.id());
                JsonArray keys = stored.getAsJsonArray("keys");
                if (keys.size() != 16) throw new IOException("Expected sixteen key bindings");
                for (int bit = 0; bit < 16; bit++) profile.bind(bit, keys.get(bit).getAsInt());
                profile.setEnabled(stored.get("gamepadEnabled").getAsBoolean());
                String guid = stored.get("gamepadGuid").getAsString();
                if (guid.length() > 128) throw new IOException("Invalid gamepad identity");
                int joystick = stored.has("joystick") ? stored.get("joystick").getAsInt() : -1;
                profile.setGamepad(guid, joystick);
            }
            loaded.put(platform, profile);
        }
        profiles.clear(); profiles.putAll(loaded); loadWarning = "";
    }

    public synchronized void save() throws IOException {
        JsonObject root = new JsonObject(); root.addProperty("schema", 1);
        JsonObject output = new JsonObject();
        for (Platform platform : Platform.values()) {
            Profile profile = profile(platform);
            JsonObject stored = new JsonObject(); JsonArray keys = new JsonArray();
            for (int bit = 0; bit < 16; bit++) keys.add(profile.keyBinding(bit));
            stored.add("keys", keys); stored.addProperty("gamepadEnabled", profile.enabled());
            stored.addProperty("gamepadGuid", profile.gamepadGuid());
            stored.addProperty("joystick", profile.preferredJoystick());
            output.add(platform.id(), stored);
        }
        root.add("platforms", output);
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException error) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
    }

    public record GamepadDevice(int id, String guid, String name) { }
    public static final class Profile {
        private final int[] keys = DEFAULTS.clone();
        private boolean gamepadEnabled;
        private String guid = "";
        private int joystick = -1;
        public int keyBinding(int bit) { checkBit(bit); return keys[bit]; }
        public void bind(int bit, int key) {
            checkBit(bit);
            if (key != GLFW.GLFW_KEY_UNKNOWN && (key < GLFW.GLFW_KEY_SPACE || key > GLFW.GLFW_KEY_LAST))
                throw new IllegalArgumentException("Invalid keyboard key");
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_F)
                throw new IllegalArgumentException("Escape and F are reserved for game view controls");
            // One physical key controls one logical button in a platform profile.
            if (key != GLFW.GLFW_KEY_UNKNOWN)
                for (int other = 0; other < keys.length; other++) if (other != bit && keys[other] == key) keys[other] = GLFW.GLFW_KEY_UNKNOWN;
            keys[bit] = key;
        }
        public boolean enabled() { return gamepadEnabled; }
        public void setEnabled(boolean enabled) { gamepadEnabled = enabled; }
        public String gamepadGuid() { return guid; }
        public int preferredJoystick() { return joystick; }
        public void setGamepad(String value, int id) {
            if (value == null || value.length() > 128 || id < -1 || id > GLFW.GLFW_JOYSTICK_LAST)
                throw new IllegalArgumentException("Invalid gamepad selection");
            guid = value; joystick = id;
        }
        private static void checkBit(int bit) { if (bit < 0 || bit >= 16) throw new IllegalArgumentException("Invalid RetroPad button"); }
    }
}
