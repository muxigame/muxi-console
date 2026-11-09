package net.muxigame.console.client.ui;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.muxigame.console.client.ConsoleClient;
import net.muxigame.console.domain.Platform;
import net.muxigame.console.mc.ConsoleContent;
import net.muxigame.console.mc.WriterMenu;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/** Selecting a file invalidates the prior affirmative rights declaration. */
public final class WriterScreen extends AbstractContainerScreen<WriterMenu> {
    private static final ResourceLocation TEXTURE=ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");
    private final JsonObject context;
    private final ClientActions actions;
    private Path file;
    private final List<Path> dependencies=new ArrayList<>();
    private Platform selected;
    private boolean rights;
    private boolean choosing;
    private boolean releasedSession;
    private boolean cancelledWriter;
    private String localStatus = "";
    private Checkbox declaration;
    private Button upload;
    private Button platform;
    private Button addDependency,clearDependencies;

    public WriterScreen(WriterMenu menu,Inventory inventory,Component title) {
        this(menu,inventory,title,ConsoleClient.INSTANCE);
    }
    public WriterScreen(WriterMenu menu,Inventory inventory,Component title,ClientActions actions) {
        super(menu,inventory,title);
        imageWidth=176;imageHeight=222;inventoryLabelY=130;
        this.context = menu.context(); this.actions = Objects.requireNonNull(actions);
    }
    private Button button(int x,int y,int width,int height,Component label,Button.OnPress action){return addRenderableWidget(Button.builder(label,action).bounds(leftPos+x,topPos+y,width,height).build());}
    @Override protected void init() {
        super.init();
        if(!releasedSession){releasedSession=true;actions.leave();}
        button(8,20,64,18,Component.translatable("ui.muxi_console.choose_file"), ignored -> browse());
        platform = button(8,58,160,18,Component.empty(), ignored -> {
            selected = selected == null ? Platform.values()[0]
                    : selected.ordinal() + 1 == Platform.values().length ? null : Platform.values()[selected.ordinal() + 1];
            resetRights();
            refresh();
        });
        addDependency=button(8,40,32,16,Component.translatable("ui.muxi_console.writer.dependencies"),ignored->browseDependency());
        addDependency.setTooltip(Tooltip.create(Component.translatable("ui.muxi_console.writer.dependencies_hint")));
        clearDependencies=button(42,40,30,16,Component.translatable("ui.muxi_console.writer.clear"),ignored->clearDependencies());
        declaration = addRenderableWidget(Checkbox.builder(Component.translatable("ui.muxi_console.writer.rights_short"), font)
                .pos(leftPos+8,topPos+80).maxWidth(160).selected(rights)
                .onValueChange((checkbox, value) -> { rights = value; actions.selectionChanged(); refresh(); }).build());
        declaration.setTooltip(Tooltip.create(Component.translatable("ui.muxi_console.rights")));
        upload = button(8,110,160,18,Component.translatable("ui.muxi_console.writer.burn"), ignored -> {
            if (file != null && rights && Files.isRegularFile(file)&&menu.getSlot(0).getItem().is(ConsoleContent.BLANK.get())) {
                localStatus = "";
                actions.upload(context.deepCopy(), file, List.copyOf(dependencies), selected, rights);
            }
        });
        refresh();
    }
    private void refresh() {
        if (platform != null) platform.setMessage(Component.translatable("ui.muxi_console.platform").append(": ").append(selected == null
                ? Component.translatable("ui.muxi_console.writer.automatic") : Component.translatable("platform.muxi_console." + selected.id())));
        if (upload != null) upload.active = file != null && rights && Files.isRegularFile(file) && !choosing&&menu.getSlot(0).getItem().is(ConsoleContent.BLANK.get());
        if(addDependency!=null)addDependency.active=file!=null&&file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")&&(selected==null||selected==Platform.ARCADE)&&!choosing&&dependencies.size()<63;
        if(clearDependencies!=null)clearDependencies.active=!dependencies.isEmpty()&&!choosing;
    }
    public void choose(Path selectedFile) {
        Objects.requireNonNull(selectedFile);
        file = selectedFile.toAbsolutePath().normalize();
        dependencies.clear();
        resetRights();
        localStatus = Files.isRegularFile(file) ? "" : "请选择可读取的游戏文件";
        actions.selectionChanged();
        refresh();
    }
    private void resetRights(){
        rights = false;
        if (declaration != null && declaration.selected()) declaration.onPress();
    }
    public void addDependency(Path dependency){
        Objects.requireNonNull(dependency);Path chosen=dependency.toAbsolutePath().normalize();String name=chosen.getFileName().toString().toLowerCase(Locale.ROOT);
        if(file==null||!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")||!name.matches("[a-z0-9_]{1,32}\\.zip")||!Files.isRegularFile(chosen)||dependencies.size()>=63){localStatus="请选择街机父集或BIOS的原名ZIP";return;}
        if(name.equals(file.getFileName().toString().toLowerCase(Locale.ROOT))||dependencies.stream().anyMatch(p->p.getFileName().toString().equalsIgnoreCase(name))){localStatus="此ZIP名称已存在";return;}
        dependencies.add(chosen);resetRights();localStatus="已添加依赖，需重新确认本次授权";
        actions.selectionChanged();
        refresh();
    }
    public void clearDependencies(){if(dependencies.isEmpty())return;dependencies.clear();resetRights();localStatus="已清空街机依赖，需重新确认本次授权";actions.selectionChanged();refresh();}
    public List<Path> dependencies(){return List.copyOf(dependencies);}
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
    private void browseDependency(){if(choosing)return;choosing=true;refresh();CompletableFuture.supplyAsync(()->TinyFileDialogs.tinyfd_openFileDialog("选择有权使用的父集或BIOS ZIP",file==null?"":file.toString(),null,"MAME ZIP",false)).whenComplete((chosen,error)->minecraft.execute(()->{choosing=false;if(error!=null)localStatus="未能打开依赖选择器";else if(chosen!=null)addDependency(Path.of(chosen));refresh();}));}
    @Override public void onFilesDrop(List<Path> files) {
        if (files.size() == 1) choose(files.get(0));
        else localStatus = "一次选择一个游戏文件";
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        refresh();
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics,mouseX,mouseY);
        if(mouseX>=leftPos+80&&mouseX<leftPos+168&&mouseY>=topPos+20&&mouseY<topPos+34)
            graphics.renderTooltip(font,Component.literal(file==null?Component.translatable("ui.muxi_console.writer.drop_file").getString():file.getFileName().toString()),mouseX,mouseY);
        if(mouseX>=leftPos+8&&mouseX<leftPos+168&&mouseY>=topPos+98&&mouseY<topPos+109){String status=message();if(!status.isBlank())graphics.renderTooltip(font,Component.literal(status),mouseX,mouseY);}
    }
    private String message(){if(!localStatus.isBlank())return localStatus;if(!menu.getSlot(0).hasItem())return Component.translatable("ui.muxi_console.insert_blank").getString();return Objects.requireNonNullElse(actions.status(),"");}
    @Override protected void containerTick(){refresh();}
    private void cancelWriter(){if(!cancelledWriter){cancelledWriter=true;if(actions instanceof ConsoleClient client)client.cancelWriter(context);}}
    @Override public void onClose(){cancelWriter();super.onClose();}
    @Override public void removed(){cancelWriter();super.removed();}
    @Override protected void renderBg(GuiGraphics graphics,float partial,int mouseX,int mouseY){
        graphics.blit(TEXTURE,leftPos,topPos,0,0,imageWidth,imageHeight);
        // Retain the vanilla container and inventory texture; only replace the unused upper storage rows.
        graphics.fill(leftPos+7,topPos+17,leftPos+169,topPos+129,0xffc6c6c6);
        graphics.blit(TEXTURE,leftPos+79,topPos+35,7,17,18,18);
    }
    @Override protected void renderLabels(GuiGraphics graphics,int mouseX,int mouseY){
        super.renderLabels(graphics,mouseX,mouseY);
        String name=file==null?Component.translatable("ui.muxi_console.writer.drop_file").getString():file.getFileName().toString();
        graphics.drawString(font,font.plainSubstrByWidth(name,88),80,24,0x404040,false);
        graphics.drawString(font,Component.translatable(menu.getSlot(0).getItem().is(ConsoleContent.CARTRIDGE.get())?"ui.muxi_console.writer.result":"ui.muxi_console.writer.slot"),102,40,0x404040,false);
        graphics.drawString(font,font.plainSubstrByWidth(message(),160),8,99,0x404040,false);
    }
}
