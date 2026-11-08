package net.muxigame.console.client.ui;

import com.google.gson.JsonObject;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.muxigame.console.domain.Platform;

public final class DeviceScreen extends ConsoleScreen {
    private final JsonObject context;
    private final ClientActions actions;
    public DeviceScreen(JsonObject context, ClientActions actions) {
        super(Component.translatable("ui.muxi_console.title"));
        this.context = Objects.requireNonNull(context).deepCopy(); this.actions = Objects.requireNonNull(actions);
    }
    private boolean dock(){return context.has("kind")&&context.get("kind").getAsString().equalsIgnoreCase("dock");}
    @Override protected void init() {
        layout(426, 252);
        int gap = 8, w = (panelWidth - 36) / 2;
        button(left + 14, top + 58, w, "启动 / 停止", ignored -> act("power", 0));
        button(left + 22 + w, top + 58, w, dock()?"取出掌机":"取出卡带", ignored -> act("eject", 0));
        button(left + 14, top + 86, w, "连接附近屏幕", ignored -> act("link", 0));
        button(left + 22 + w, top + 86, w, "按键与手柄", ignored -> net.muxigame.console.client.input.NativeControls.openSettings(this));
        if(!dock()&&!context.has("card")&&!context.get("powered").getAsBoolean()){
            int id=context.has("model")?context.get("model").getAsInt():0;
            var body=net.muxigame.console.mc.HardwareCatalog.body(id);
            button(left+14,top+180,panelWidth-28,"机型："+(body==null?"通用主机":body.name())+" · 点击切换",ignored->act("body",(id+1)%23));
        }
        int seatWidth = (panelWidth - 28 - gap * 3) / 4;
        for (int port = 0; port < 4; port++) {
            int seat = port;
            var join=button(left + 14 + port * (seatWidth + gap), top + 139, seatWidth,
                    "P" + (port + 1) + " 加入", ignored -> act("join", seat));
            join.active=context.has("card")&&context.get("powered").getAsBoolean()&&port<Math.min(context.get("ports").getAsInt(),currentPlatform().controllerPorts());
        }
        button(left + 14, top + panelHeight - 36, 76, "关闭", ignored -> onClose());
    }
    private void act(String action, int port) { actions.device(context.deepCopy(), action, port); }
    private Platform currentPlatform() {
        try { return Platform.fromId(context.getAsJsonObject("card").get("platform").getAsString()); }
        catch (RuntimeException error) { return Platform.NES; }
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        panel(graphics, dock()?"装入掌机，通过底座连接大屏，再选择控制席":"插入卡带，连接屏幕，再选择你的控制席");
        line(graphics, "选择控制席", left + 14, top + 124, panelWidth - 28, CREAM);
        line(graphics, Objects.requireNonNullElse(actions.status(), ""), left + 14, top + panelHeight - 55, panelWidth - 28, AMBER);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
