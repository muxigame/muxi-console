package net.muxigame.console.client.ui;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.network.chat.Component;
import net.muxigame.console.domain.Platform;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/** Selecting a file invalidates the prior affirmative rights declaration. */
public final class WriterScreen extends ConsoleScreen {
    private final JsonObject context;
    private final ClientActions actions;
    private Path file;
    private Platform selected;
    private boolean rights;
    private boolean choosing;
    private String localStatus = "";
    private Checkbox declaration;
    private ConsoleButton upload;
    private ConsoleButton platform;

    public WriterScreen(JsonObject context, ClientActions actions) {
        super(Component.translatable("ui.muxi_console.write"));
        this.context = Objects.requireNonNull(context).deepCopy(); this.actions = Objects.requireNonNull(actions);
    }
    @Override protected void init() {
        layout(436, 250);
        button(left + 14, top + 58, 108, "选择文件", ignored -> browse());
        platform = button(left + 14, top + 95, panelWidth - 28, "", ignored -> {
            selected = selected == null ? Platform.values()[0]
                    : selected.ordinal() + 1 == Platform.values().length ? null : Platform.values()[selected.ordinal() + 1];
            refresh();
        });
        declaration = addRenderableWidget(Checkbox.builder(Component.translatable("ui.muxi_console.rights"), font)
                .pos(left + 14, top + 125).maxWidth(panelWidth - 28).selected(rights)
                .onValueChange((checkbox, value) -> { rights = value; actions.selectionChanged(); refresh(); }).build());
        upload = button(left + panelWidth - 124, top + panelHeight - 38, 110, "上传并刻录", ignored -> {
            if (file != null && rights && Files.isRegularFile(file)) {
                localStatus = "正在准备上传…";
                actions.upload(context.deepCopy(), file, selected, rights);
            }
        });
        button(left + 14, top + panelHeight - 38, 70, "关闭", ignored -> onClose());
        refresh();
    }
    private void refresh() {
        if (platform != null) platform.setMessage(Component.literal("平台：").append(selected == null
                ? Component.literal("自动识别") : Component.translatable("platform.muxi_console." + selected.id())));
        if (upload != null) upload.active = file != null && rights && Files.isRegularFile(file) && !choosing;
    }
    public void choose(Path selectedFile) {
        Objects.requireNonNull(selectedFile);
        file = selectedFile.toAbsolutePath().normalize();
        rights = false;
        if (declaration != null && declaration.selected()) declaration.onPress();
        localStatus = Files.isRegularFile(file) ? "" : "请选择可读取的游戏文件";
        actions.selectionChanged();
        refresh();
    }
    public boolean confirmed() { return rights; }
    public Path selectedFile() { return file; }
    public Platform selectedPlatform() { return selected; }
    public void status(String text) { localStatus = Objects.requireNonNullElse(text, ""); }
    private void browse() {
        if (choosing) return;
        choosing = true; refresh();
        CompletableFuture.supplyAsync(() -> TinyFileDialogs.tinyfd_openFileDialog(
                "选择你的游戏文件", file == null ? "" : file.toString(), null, "游戏文件", false))
                .whenComplete((chosen, error) -> minecraft.execute(() -> {
                    choosing = false;
                    if (error != null) localStatus = "未能打开文件选择器，可以把文件拖入这里";
                    else if (chosen != null) choose(Path.of(chosen));
                    refresh();
                }));
    }
    @Override public void onFilesDrop(List<Path> files) {
        if (files.size() == 1) choose(files.get(0));
        else localStatus = "一次选择一个游戏文件";
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        panel(graphics, "物品栏准备空白卡带 → 选择文件 → 确认刻录");
        line(graphics, file == null ? "也可以把游戏文件拖到这个窗口" : file.getFileName().toString(),
                left + 132, top + 64, panelWidth - 148, CREAM);
        line(graphics, "选择新文件后需要重新确认", left + 14, top + 150, panelWidth - 28, EDGE);
        String remote = Objects.requireNonNullElse(actions.status(), "");
        line(graphics, remote.isBlank() ? localStatus : remote, left + 14, top + panelHeight - 56, panelWidth - 28, AMBER);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
