package net.muxigame.console.client.ui;

import java.io.IOException;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.muxigame.console.client.input.InputProfiles;
import net.muxigame.console.domain.Platform;
import org.lwjgl.glfw.GLFW;

/** Standalone settings: every platform owns a separate sixteen-button profile. */
public final class ControlsScreen extends ConsoleScreen {
    private final Screen parent;
    private final InputProfiles profiles;
    private Platform platform;
    private int pending = -1;
    private int capturedKey = GLFW.GLFW_KEY_UNKNOWN;
    private int page;
    private int rows;
    private String message = "";
    private ConsoleButton platformButton;
    private ConsoleButton gamepadButton;
    private final ConsoleButton[] keyButtons = new ConsoleButton[16];

    public ControlsScreen(Screen parent, Platform platform) {
        this(parent, platform, InputProfiles.get());
    }
    public ControlsScreen(Screen parent, Platform platform, InputProfiles profiles) {
        super(Component.translatable("ui.muxi_console.controls"));
        this.parent = parent; this.platform = platform == null ? Platform.NES : platform;
        this.profiles = java.util.Objects.requireNonNull(profiles);
        message = profiles.loadWarning();
    }
    @Override protected void init() {
        layout(548, 338);
        rows = Math.max(2, Math.min(8, (panelHeight - 156) / 22));
        int pageSize = rows * 2, pages = (16 + pageSize - 1) / pageSize;
        page = Math.min(page, pages - 1);
        java.util.Arrays.fill(keyButtons, null);
        platformButton = button(left + 14, top + 56, (panelWidth - 36) / 2, "", ignored -> {
            platform = Platform.values()[(platform.ordinal() + 1) % Platform.values().length];
            pending = -1; page = 0; rebuildWidgets();
        });
        addRenderableWidget(Checkbox.builder(Component.literal("启用手柄"), font)
                .pos(left + panelWidth / 2 + 4, top + 56).selected(profiles.profile(platform).enabled())
                .maxWidth(panelWidth / 2 - 18).onValueChange((box, enabled) -> profiles.setEnabled(platform, enabled)).build());
        gamepadButton = button(left + 14, top + 82, panelWidth - 28, "", ignored -> cycleGamepad());
        int cellWidth = (panelWidth - 36) / 2;
        for (int offset = 0; offset < pageSize && page * pageSize + offset < 16; offset++) {
            int bit = page * pageSize + offset;
            int col = offset / rows, row = offset % rows;
            keyButtons[bit] = button(left + 14 + col * (cellWidth + 8), top + 108 + row * 22,
                    cellWidth, "", ignored -> { pending = bit; message = "按下新按键；Esc 取消，Delete 清除"; refresh(); });
        }
        if (pages > 1) {
            button(left + 14, top + panelHeight - 52, 44, "上页", ignored -> { pending = -1; page = (page + pages - 1) % pages; rebuildWidgets(); });
            button(left + 64, top + panelHeight - 52, 44, "下页", ignored -> { pending = -1; page = (page + 1) % pages; rebuildWidgets(); });
        }
        button(left + 14, top + panelHeight - 28, 86, "恢复默认", ignored -> {
            profiles.reset(platform); pending = -1; message = "已恢复当前平台默认值，点击保存写入配置"; rebuildWidgets();
        });
        button(left + panelWidth - 170, top + panelHeight - 28, 72, "返回", ignored -> onClose());
        button(left + panelWidth - 90, top + panelHeight - 28, 76, "保存", ignored -> {
            try { profiles.save(); message = "当前所有平台配置已保存"; }
            catch (IOException | RuntimeException error) { message = "配置未能保存，请检查游戏目录"; }
        });
        refresh();
    }
    private void cycleGamepad() {
        List<InputProfiles.GamepadDevice> devices = InputProfiles.availableGamepads();
        int selected = -1;
        for (int i = 0; i < devices.size(); i++) {
            var device = devices.get(i);
            if (device.id() == profiles.profile(platform).preferredJoystick()
                    && device.guid().equals(profiles.profile(platform).gamepadGuid())) selected = i;
        }
        if (devices.isEmpty() || selected + 1 == devices.size()) profiles.setGamepad(platform, -1);
        else profiles.setGamepad(platform, devices.get(selected + 1).id());
        refresh();
    }
    private void refresh() {
        if (platformButton != null) platformButton.setMessage(Component.translatable("platform.muxi_console." + platform.id()));
        if (gamepadButton != null) {
            String deviceName = profiles.profile(platform).gamepadGuid().isEmpty() ? "未选择" : "设备未连接";
            for (var device : InputProfiles.availableGamepads())
                if (device.guid().equals(profiles.profile(platform).gamepadGuid())) {
                    deviceName = device.name() + " #" + (device.id() + 1); break;
                }
            gamepadButton.setMessage(Component.literal("手柄：" + deviceName));
        }
        for (int bit = 0; bit < keyButtons.length; bit++) if (keyButtons[bit] != null)
            keyButtons[bit].setMessage(Component.literal(InputProfiles.BUTTON_NAMES[bit] + " · "
                    + (pending == bit ? "按下按键…" : keyName(profiles.profile(platform).keyBinding(bit)))));
    }
    private static String keyName(int key) {
        String result = GLFW.glfwGetKeyName(key, 0);
        if (result != null) return result.toUpperCase(java.util.Locale.ROOT);
        return switch (key) {
            case GLFW.GLFW_KEY_UNKNOWN -> "未绑定";
            case GLFW.GLFW_KEY_ENTER -> "Enter";
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "右 Shift";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "左 Shift";
            case GLFW.GLFW_KEY_SPACE -> "Space";
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_UP -> "↑"; case GLFW.GLFW_KEY_DOWN -> "↓";
            case GLFW.GLFW_KEY_LEFT -> "←"; case GLFW.GLFW_KEY_RIGHT -> "→";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "左 Ctrl"; case GLFW.GLFW_KEY_RIGHT_CONTROL -> "右 Ctrl";
            case GLFW.GLFW_KEY_LEFT_ALT -> "左 Alt"; case GLFW.GLFW_KEY_RIGHT_ALT -> "右 Alt";
            default -> key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F25 ? "F" + (key - GLFW.GLFW_KEY_F1 + 1) : "键 " + key;
        };
    }
    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == capturedKey) return true;
        if (pending >= 0) {
            if (key == GLFW.GLFW_KEY_ESCAPE) { pending = -1; message = "已取消绑定"; refresh(); return true; }
            if (key == GLFW.GLFW_KEY_F) { message = "F 用于切换电视视角，请选择其他按键"; return true; }
            try {
                profiles.bind(platform, pending, key == GLFW.GLFW_KEY_DELETE ? GLFW.GLFW_KEY_UNKNOWN : key);
                capturedKey = key; pending = -1; message = "绑定已更新，点击保存写入配置"; refresh();
            } catch (IllegalArgumentException error) { message = "请选择可用的键盘按键"; }
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }
    @Override public boolean keyReleased(int key, int scanCode, int modifiers) {
        if (key == capturedKey) capturedKey = GLFW.GLFW_KEY_UNKNOWN;
        return super.keyReleased(key, scanCode, modifiers);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        panel(graphics, "每个平台独立保存 · 标准手柄下键 B / 右键 A");
        int available = panelWidth - 28;
        int textX = left + 14;
        if (rows * 2 < 16) { textX += 102; available -= 102; }
        line(graphics, message, textX, top + panelHeight - 46, available, AMBER);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
