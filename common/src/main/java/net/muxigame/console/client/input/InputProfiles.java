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
        for (Platform platform : Platform.values()) profiles.put(platform, new Profile(platform));
        try { load(); } catch (IOException | RuntimeException error) {
            loadWarning = "输入配置未能读取，当前使用默认值";
        }
    }

    public synchronized Profile profile(Platform platform) { return profiles.get(Objects.requireNonNull(platform)); }
    public Path path() { return path; }
    public synchronized String loadWarning() { return loadWarning; }
    public synchronized void bind(Platform platform, int bit, int key) { profile(platform).bind(bit, key); }
    public synchronized void bindAxis(Platform platform,int direction,int key){profile(platform).bindAxis(direction,key);}
    public synchronized void bindGamepad(Platform platform,int bit,int button){profile(platform).bindGamepad(bit,button);}
    public synchronized void reset(Platform platform) { profiles.put(platform, new Profile(platform)); }
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

    public synchronized PortInput sample(Platform platform, long window) { return sample(platform,window,key->true,false); }

    public synchronized PortInput sampleGamepad(Platform platform) { return sample(platform,0,key->false,false,key->false); }

    /** Legacy keyboard-profile reader retained for migration and deterministic frontend diagnostics. */
    public synchronized PortInput sampleKeys(Platform platform, java.util.Set<Integer> keys) {
        java.util.Set<Integer> down = java.util.Set.copyOf(keys);
        return sample(platform, 0, key -> true, false, down::contains);
    }

    /** World mode never borrows a key assigned to Minecraft; arrows also provide a safe direction alternative. */
    public synchronized PortInput sampleWorld(Platform platform,long window,java.util.function.IntPredicate allowed){return sample(platform,window,Objects.requireNonNull(allowed),true);}

    private PortInput sample(Platform platform,long window,java.util.function.IntPredicate allowed,boolean world) {
        return sample(platform, window, allowed, world, key -> window != 0 && GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS);
    }
    private PortInput sample(Platform platform,long window,java.util.function.IntPredicate allowed,boolean world,java.util.function.IntPredicate down) {
        Profile profile = profile(platform);
        int mask = 0;
        for (int bit = 0; bit < 16; bit++) {
            int key = profile.keyBinding(bit);
            if (key >= GLFW.GLFW_KEY_SPACE && allowed.test(key) && down.test(key))
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
                            int button = profile.gamepadButton(bit);
                            if (button >= 0 && button<=GLFW.GLFW_GAMEPAD_BUTTON_LAST && state.buttons(button) == GLFW.GLFW_PRESS) mask |= 1 << bit;
                        }
                        float x = safeAxis(state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X));
                        float y = safeAxis(state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y));
                        if(platform!=Platform.N64){if (x < -0.35f) mask |= 1 << 6;if (x > 0.35f) mask |= 1 << 7;if (y < -0.35f) mask |= 1 << 4;if (y > 0.35f) mask |= 1 << 5;}
                        for(int bit=0;bit<16;bit++){int button=profile.gamepadButton(bit);if(button==15&&state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_TRIGGER)>0.2f||button==16&&state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER)>0.2f)mask|=1<<bit;}
                        lx = analog(x); ly = analog(y);
                        rx = analog(state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_X));
                        ry = analog(state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_Y));
                    }
                }
            }
        }
        {short[] axes={lx,ly,rx,ry};for(int axis=0;axis<4;axis++){int n=profile.axisBinding(axis*2),p=profile.axisBinding(axis*2+1);boolean negative=n>=GLFW.GLFW_KEY_SPACE&&allowed.test(n)&&down.test(n),positive=p>=GLFW.GLFW_KEY_SPACE&&allowed.test(p)&&down.test(p);if(negative||positive)axes[axis]=(short)(negative==positive?0:negative?-32767:32767);}lx=axes[0];ly=axes[1];rx=axes[2];ry=axes[3];}
        if(world&&window!=0)return HeldInputPolicy.directions(platform,new PortInput(mask,lx,ly,rx,ry),held(window,GLFW.GLFW_KEY_UP,allowed),held(window,GLFW.GLFW_KEY_DOWN,allowed),held(window,GLFW.GLFW_KEY_LEFT,allowed),held(window,GLFW.GLFW_KEY_RIGHT,allowed));
        if ((mask & 0x30) == 0x30) mask &= ~0x30;
        if ((mask & 0xc0) == 0xc0) mask &= ~0xc0;
        return new PortInput(mask, lx, ly, rx, ry);
    }
    /** Physical standard-controller buttons for the binding screen, independent of the mapping being edited. */
    public synchronized int pressedGamepad(Platform platform){int joystick=resolveJoystick(profile(platform));if(joystick<0)return 0;try(MemoryStack stack=MemoryStack.stackPush()){GLFWGamepadState state=GLFWGamepadState.malloc(stack);if(!GLFW.glfwGetGamepadState(joystick,state))return 0;int pressed=0;for(int b=0;b<=GLFW.GLFW_GAMEPAD_BUTTON_LAST;b++)if(state.buttons(b)==GLFW.GLFW_PRESS)pressed|=1<<b;if(state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_TRIGGER)>.2f)pressed|=1<<15;if(state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER)>.2f)pressed|=1<<16;return pressed;}}

    private static boolean held(long window,int key,java.util.function.IntPredicate allowed){return key>=GLFW.GLFW_KEY_SPACE&&allowed.test(key)&&GLFW.glfwGetKey(window,key)==GLFW.GLFW_PRESS;}
    private static float safeAxis(float value) { return Float.isFinite(value) ? Math.max(-1, Math.min(1, value)) : 0; }
    private static short analog(float value) {
        value = safeAxis(value);
        if (Math.abs(value) < 0.15f) return 0;
        return (short) Math.round(value * 32767);
    }
    private static int resolveJoystick(Profile profile) {
        if (profile.gamepadGuid().equals("*")) { var devices=availableGamepads();return devices.isEmpty()?-1:devices.getFirst().id(); }
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
        boolean reservedMigrated = false;
        for (Platform platform : Platform.values()) {
            Profile profile = new Profile(platform);
            if (input.has(platform.id())) {
                JsonObject stored = input.getAsJsonObject(platform.id());
                JsonArray keys = stored.getAsJsonArray("keys");
                if (keys.size() != 16) throw new IOException("Expected sixteen key bindings");
                for (int bit = 0; bit < 16; bit++) {
                    int key = keys.get(bit).getAsInt();
                    if (key == GLFW.GLFW_KEY_ESCAPE) { key = GLFW.GLFW_KEY_UNKNOWN; reservedMigrated = true; }
                    profile.bind(bit, key);
                }
                if(stored.has("axes")){JsonArray axes=stored.getAsJsonArray("axes");if(axes.size()!=8)throw new IOException("Expected eight analog directions");for(int i=0;i<8;i++){int key=axes.get(i).getAsInt();if(key==GLFW.GLFW_KEY_ESCAPE){key=GLFW.GLFW_KEY_UNKNOWN;reservedMigrated=true;}profile.bindAxis(i,key);}}
                if(stored.has("gamepadButtons")){JsonArray buttons=stored.getAsJsonArray("gamepadButtons");if(buttons.size()!=16)throw new IOException("Expected sixteen controller bindings");for(int i=0;i<16;i++)profile.bindGamepad(i,buttons.get(i).getAsInt());}
                profile.setEnabled(stored.get("gamepadEnabled").getAsBoolean());
                String guid = stored.get("gamepadGuid").getAsString();
                if (guid.length() > 128) throw new IOException("Invalid gamepad identity");
                int joystick = stored.has("joystick") ? stored.get("joystick").getAsInt() : -1;
                profile.setGamepad(guid, joystick);
                if(stored.has("controllerDevice"))profile.setControllerDevice(stored.get("controllerDevice").getAsInt());
            }
            loaded.put(platform, profile);
        }
        profiles.clear(); profiles.putAll(loaded);
        loadWarning = reservedMigrated ? "已清除与 Esc 退出键冲突的旧绑定；其他设置保留" : "";
    }

    public synchronized void save() throws IOException {
        JsonObject root = new JsonObject(); root.addProperty("schema", 1);
        JsonObject output = new JsonObject();
        for (Platform platform : Platform.values()) {
            Profile profile = profile(platform);
            JsonObject stored = new JsonObject(); JsonArray keys = new JsonArray();
            for (int bit = 0; bit < 16; bit++) keys.add(profile.keyBinding(bit));
            stored.add("keys", keys); stored.addProperty("gamepadEnabled", profile.enabled());
            JsonArray axes=new JsonArray(),buttons=new JsonArray();for(int i=0;i<8;i++)axes.add(profile.axisBinding(i));for(int i=0;i<16;i++)buttons.add(profile.gamepadButton(i));stored.add("axes",axes);stored.add("gamepadButtons",buttons);
            stored.addProperty("gamepadGuid", profile.gamepadGuid());
            stored.addProperty("joystick", profile.preferredJoystick());
            stored.addProperty("controllerDevice",profile.controllerDevice());
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
    public static String buttonName(Platform platform,int bit){
        if(bit<0||bit>=16)throw new IllegalArgumentException("Invalid button");
        return switch(platform){
            case MD -> new String[]{"A","X","Mode","Start","↑","↓","←","→","B","Y","Z","C","L2（未用）","R2（未用）","L3（未用）","R3（未用）"}[bit];
            case N64 -> new String[]{"A / C↓模式","B / C←模式","Select（未用）","Start","十字↑","十字↓","十字←","十字→","C→","C↑","L","R","Z 扳机","C 按钮模式","L3（未用）","R3（未用）"}[bit];
            case FDS -> bit==10?"磁盘换面":bit==11?"弹出 / 插入磁盘":BUTTON_NAMES[bit];
            default -> BUTTON_NAMES[bit];
        };
    }
    public static final class Profile {
        private final int[] keys = DEFAULTS.clone();
        private final int[] axes={-1,-1,-1,-1,-1,-1,-1,-1};
        private final int[] pads=GAMEPAD_BUTTONS.clone();
        private boolean gamepadEnabled;
        private String guid = "";
        private int joystick = -1;
        private int controllerDevice=1;
        public Profile(){this(Platform.NES);}
        public Profile(Platform platform){
            pads[12]=15;pads[13]=16;
            if(platform==Platform.N64){keys[0]=GLFW.GLFW_KEY_J;keys[1]=GLFW.GLFW_KEY_K;keys[8]=GLFW.GLFW_KEY_L;keys[4]=GLFW.GLFW_KEY_UP;keys[5]=GLFW.GLFW_KEY_DOWN;keys[6]=GLFW.GLFW_KEY_LEFT;keys[7]=GLFW.GLFW_KEY_RIGHT;axes[0]=GLFW.GLFW_KEY_A;axes[1]=GLFW.GLFW_KEY_D;axes[2]=GLFW.GLFW_KEY_W;axes[3]=GLFW.GLFW_KEY_S;}
            if(platform==Platform.MD){keys[0]=GLFW.GLFW_KEY_J;keys[8]=GLFW.GLFW_KEY_K;keys[11]=GLFW.GLFW_KEY_L;keys[10]=GLFW.GLFW_KEY_O;}
        }
        public int keyBinding(int bit) { checkBit(bit); return keys[bit]; }
        public void bind(int bit, int key) {
            checkBit(bit);
            if (key != GLFW.GLFW_KEY_UNKNOWN && (key < GLFW.GLFW_KEY_SPACE || key > GLFW.GLFW_KEY_LAST))
                throw new IllegalArgumentException("Invalid keyboard key");
            if (key == GLFW.GLFW_KEY_ESCAPE)
                throw new IllegalArgumentException("Escape is reserved for leaving fullscreen");
            // One physical key controls one logical button in a platform profile.
            if (key != GLFW.GLFW_KEY_UNKNOWN)
                for (int other = 0; other < keys.length; other++) if (other != bit && keys[other] == key) keys[other] = GLFW.GLFW_KEY_UNKNOWN;
            if(key!=GLFW.GLFW_KEY_UNKNOWN)for(int i=0;i<axes.length;i++)if(axes[i]==key)axes[i]=GLFW.GLFW_KEY_UNKNOWN;
            keys[bit] = key;
        }
        public int axisBinding(int direction){if(direction<0||direction>=8)throw new IllegalArgumentException("Analog direction");return axes[direction];}
        public void bindAxis(int direction,int key){axisBinding(direction);if(key!=GLFW.GLFW_KEY_UNKNOWN&&(key<GLFW.GLFW_KEY_SPACE||key>GLFW.GLFW_KEY_LAST)||key==GLFW.GLFW_KEY_ESCAPE)throw new IllegalArgumentException("Reserved or invalid analog key");if(key!=GLFW.GLFW_KEY_UNKNOWN){for(int i=0;i<keys.length;i++)if(keys[i]==key)keys[i]=-1;for(int i=0;i<axes.length;i++)if(i!=direction&&axes[i]==key)axes[i]=-1;}axes[direction]=key;}
        public int gamepadButton(int bit){checkBit(bit);return pads[bit];}
        public void bindGamepad(int bit,int physical){checkBit(bit);if(physical< -1||physical>16)throw new IllegalArgumentException("Invalid gamepad button");if(physical>=0)for(int i=0;i<pads.length;i++)if(i!=bit&&pads[i]==physical)pads[i]=-1;pads[bit]=physical;}
        public boolean enabled() { return gamepadEnabled; }
        public void setEnabled(boolean enabled) { gamepadEnabled = enabled; }
        public String gamepadGuid() { return guid; }
        public int preferredJoystick() { return joystick; }
        public int controllerDevice(){return controllerDevice;}
        public void setControllerDevice(int value){if(!java.util.Set.of(1,513,769,1025,1793,2305).contains(value))throw new IllegalArgumentException("Unsupported controller device");controllerDevice=value;}
        public void setGamepad(String value, int id) {
            if (value == null || value.length() > 128 || id < -1 || id > GLFW.GLFW_JOYSTICK_LAST)
                throw new IllegalArgumentException("Invalid gamepad selection");
            guid = value; joystick = id;
        }
        private static void checkBit(int bit) { if (bit < 0 || bit >= 16) throw new IllegalArgumentException("Invalid RetroPad button"); }
    }
}
