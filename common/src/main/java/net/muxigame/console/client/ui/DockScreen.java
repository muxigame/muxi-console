package net.muxigame.console.client.ui;

import com.google.gson.JsonObject;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.muxigame.console.client.ConsoleClient;
import net.muxigame.console.domain.Platform;
import net.muxigame.console.mc.DockMenu;

/** One persistent machine slot and ordinary inventory controls, backed by the dock's real menu. */
public final class DockScreen extends AbstractContainerScreen<DockMenu> {
    private static final ResourceLocation TEXTURE=ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");
    private final ClientActions actions;
    private boolean prepared;
    private Button power,leave;
    private final Button[] seats=new Button[4];

    public DockScreen(DockMenu menu,Inventory inventory,Component title){this(menu,inventory,title,ConsoleClient.INSTANCE);}
    public DockScreen(DockMenu menu,Inventory inventory,Component title,ClientActions actions){
        super(menu,inventory,title);
        this.actions=Objects.requireNonNull(actions);
        imageWidth=176;imageHeight=222;inventoryLabelY=130;
    }
    private Button button(int x,int y,int width,Component label,Button.OnPress onPress){
        return addRenderableWidget(Button.builder(label,onPress).bounds(leftPos+x,topPos+y,width,18).build());
    }
    @Override protected void init(){
        super.init();
        // Reopening this menu from its own running game must keep that control seat alive.
        if(!prepared){prepared=true;if(actions instanceof ConsoleClient client)client.prepareDockMenu();}
        power=button(8,58,76,Component.literal("开机"),ignored->act("power",0));
        button(92,58,76,Component.literal("连接屏幕"),ignored->act("link",0));
        for(int port=0;port<seats.length;port++){
            int seat=port;
            seats[port]=button(8+41*port,82,37,Component.literal("P"+(port+1)),ignored->act("join",seat));
        }
        leave=button(8,106,160,Component.literal("保存并离开游戏"),ignored->{if(currentSession())actions.leave();});
        refresh();
    }
    private void act(String action,int port){actions.device(menu.context(),action,port);}
    private boolean currentSession(){return actions instanceof ConsoleClient client&&client.screenConsole()==menu.position().asLong();}
    private Platform platform(JsonObject context){
        try{return Platform.fromId(context.getAsJsonObject("card").get("platform").getAsString());}
        catch(RuntimeException absent){return Platform.NES;}
    }
    private void refresh(){
        JsonObject context=menu.context();
        if(power!=null){power.setMessage(Component.literal(menu.powered()?"关机":"开机"));power.active=menu.getSlot(0).hasItem()&&!menu.locked();}
        for(int port=0;port<seats.length;port++)if(seats[port]!=null)seats[port].active=context.has("card")&&menu.powered()&&port<Math.min(menu.capacity(),platform(context).controllerPorts());
        if(leave!=null)leave.active=currentSession();
    }
    private String message(){
        if(menu.locked())return currentSession()?"保存离开后可取出掌机":"正在游玩或保存，请稍候";
        if(!menu.getSlot(0).hasItem())return "将掌机放入上方插槽";
        if(!menu.context().has("card"))return "掌机尚未装入卡带";
        return menu.powered()?"选择控制席开始游戏":"开机后选择控制席";
    }
    @Override protected void containerTick(){refresh();}
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){
        refresh();super.render(graphics,mouseX,mouseY,partialTick);renderTooltip(graphics,mouseX,mouseY);
    }
    @Override protected void renderBg(GuiGraphics graphics,float partialTick,int mouseX,int mouseY){
        graphics.blit(TEXTURE,leftPos,topPos,0,0,imageWidth,imageHeight);
        graphics.fill(leftPos+7,topPos+17,leftPos+169,topPos+129,0xffc6c6c6);
        graphics.blit(TEXTURE,leftPos+79,topPos+27,7,17,18,18);
    }
    @Override protected void renderLabels(GuiGraphics graphics,int mouseX,int mouseY){
        super.renderLabels(graphics,mouseX,mouseY);
        graphics.drawString(font,"掌机",102,32,0x404040,false);
        graphics.drawString(font,font.plainSubstrByWidth(message(),160),8,47,0x404040,false);
    }
}
