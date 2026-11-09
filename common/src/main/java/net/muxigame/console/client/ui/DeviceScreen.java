package net.muxigame.console.client.ui;

import com.google.gson.JsonObject;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.muxigame.console.domain.Platform;

public final class DeviceScreen extends ConsoleScreen {
    private final JsonObject context;
    private final ClientActions actions;
    private Button leave;
    public DeviceScreen(JsonObject context, ClientActions actions) {
        super(Component.translatable("ui.muxi_console.title"));
        this.context = Objects.requireNonNull(context).deepCopy(); this.actions = Objects.requireNonNull(actions);
    }
    @Override protected void init() {
        layout(426, 252);
        int gap = 8, w = (panelWidth - 36) / 2;
        button(left + 14, top + 58, w, "启动 / 停止", ignored -> act("power", 0));
        button(left + 22 + w, top + 58, w, "取出卡带", ignored -> act("eject", 0));
        button(left + 14, top + 86, w, "连接附近屏幕", ignored -> act("link", 0));
        button(left + 22 + w, top + 86, w, "按键与手柄", ignored -> net.muxigame.console.client.input.NativeControls.openSettings(this));
        if(context.has("card")){leave=button(left+14,top+172,panelWidth-28,"保存并离开游戏",ignored->{if(currentSession())actions.leave();});leave.active=currentSession();}
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
    private boolean currentSession(){return actions instanceof net.muxigame.console.client.ConsoleClient client&&client.screenConsole()==context.get("position").getAsLong();}
    private Platform currentPlatform() {
        try { return Platform.fromId(context.getAsJsonObject("card").get("platform").getAsString()); }
        catch (RuntimeException error) { return Platform.NES; }
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if(leave!=null)leave.active=currentSession();
        panel(graphics, "插入卡带，连接屏幕，再选择你的控制席");
        line(graphics, "选择控制席", left + 14, top + 124, panelWidth - 28, CREAM);
        if(!context.has("card")&&context.has("model")&&context.get("model").getAsInt()==0)line(graphics,"游戏机核心：取回后在工作台与染料合成机型",left+14,top+180,panelWidth-28,AMBER);
        line(graphics, Objects.requireNonNullElse(actions.status(), ""), left + 14, top + panelHeight - 55, panelWidth - 28, AMBER);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
