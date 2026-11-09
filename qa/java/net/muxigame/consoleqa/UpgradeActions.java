package net.muxigame.consoleqa;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.muxigame.console.client.ConsoleClient;
import net.muxigame.console.client.input.NativeControls;
import net.muxigame.console.client.ui.GameScreen;
import net.muxigame.console.mc.WriterMenu;
import net.muxigame.console.mc.DockMenu;
import org.lwjgl.glfw.GLFW;

/** Actual menu packets and normal screen callbacks only. No product inventory, card or save setters. */
final class UpgradeActions {
    static void command(Minecraft mc,JsonObject j)throws Exception{
        if(mc.player==null||mc.gameMode==null)throw new IllegalStateException("Connected real player required");
        String action=j.get("action").getAsString();
        switch(action){
            case "upgrade_inventory"->{if(mc.screen!=null)throw new IllegalStateException("Return to world first");mc.setScreen(new InventoryScreen(mc.player));}
            case "upgrade_click"->{menu(mc);int slot=j.get("slot").getAsInt(),button=j.has("button")?j.get("button").getAsInt():0;ClickType kind=ClickType.valueOf(j.has("type")?j.get("type").getAsString():"PICKUP");
                if(slot<0||slot>=mc.player.containerMenu.slots.size()||!(kind==ClickType.PICKUP&&button>=0&&button<=1||kind==ClickType.SWAP&&(button>=0&&button<=8||button==40)||kind==ClickType.QUICK_MOVE&&button==0))throw new IllegalArgumentException("Bounded vanilla menu click required");
                mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId,slot,button,kind,mc.player);}
            case "upgrade_hover"->{menu(mc);var screen=(AbstractContainerScreen<?>)mc.screen;int slot=j.get("slot").getAsInt();if(slot<0||slot>=mc.player.containerMenu.slots.size())throw new IllegalArgumentException("Actual slot required");var target=mc.player.containerMenu.getSlot(slot);
                double x=screen.getGuiLeft()+target.x+8,y=screen.getGuiTop()+target.y+8;
                // Hidden GLFW windows need not dispatch a cursor warp. Exercise the actual
                // vanilla input callback; rendering must independently report the hovered slot.
                var move=net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove",long.class,double.class,double.class);move.setAccessible(true);
                move.invoke(mc.mouseHandler,mc.getWindow().getWindow(),x*mc.getWindow().getScreenWidth()/screen.width,y*mc.getWindow().getScreenHeight()/screen.height);}
            case "upgrade_controls"->{if(mc.screen!=null||!ConsoleClient.INSTANCE.snapshot().room().isEmpty())throw new IllegalStateException("Idle world required");mc.setScreen(new ControlsScreen(null,mc.options));}
            case "upgrade_button"->{if(mc.screen==null)throw new IllegalStateException("Actual settings screen required");List<Button> buttons=new ArrayList<>();buttons(mc.screen,j.get("text").getAsString(),buttons);if(buttons.size()!=1||!buttons.getFirst().active)throw new IllegalStateException("Exactly one active native widget required");buttons.getFirst().onPress();}
            case "upgrade_platform"->{if(!(mc.screen instanceof net.muxigame.console.client.ui.ConsoleBindingsScreen))throw new IllegalStateException("Real platform binding page required");var target=net.muxigame.console.domain.Platform.fromId(j.get("platform").getAsString());for(int attempt=0;attempt<25;attempt++){var cycle=mc.screen.children().stream().filter(CycleButton.class::isInstance).map(CycleButton.class::cast).filter(value->value.getValue() instanceof net.muxigame.console.domain.Platform).findFirst().orElseThrow();if(cycle.getValue()==target)break;if(attempt==24)throw new IllegalStateException("Target platform is absent from the actual selected family");cycle.onPress();}}
            case "upgrade_device"->{if(!(mc.screen instanceof net.muxigame.console.client.ui.ConsoleBindingsScreen))throw new IllegalStateException("Real Wii binding page required");int target=j.get("device").getAsInt();for(int attempt=0;attempt<7;attempt++){var cycle=mc.screen.children().stream().filter(CycleButton.class::isInstance).map(CycleButton.class::cast).filter(value->value.getValue() instanceof Integer&&value.getMessage().getString().contains(net.minecraft.network.chat.Component.translatable("ui.muxi_console.bindings.device").getString())).findFirst().orElseThrow();if(cycle.getValue().equals(target))break;if(attempt==6)throw new IllegalStateException("Unknown real Wii device choice");cycle.onPress();}}
            case "upgrade_key"->{if(mc.screen==null)throw new IllegalStateException("Actual screen required");int key=j.get("key").getAsInt(),mods=j.has("modifiers")?j.get("modifiers").getAsInt():0;if(j.get("pressed").getAsBoolean())mc.screen.keyPressed(key,0,mods);else mc.screen.keyReleased(key,0,mods);}
            case "upgrade_reload_options"->{if(mc.screen!=null||!ConsoleClient.INSTANCE.snapshot().room().isEmpty())throw new IllegalStateException("Reload only while idle");mc.options.load();net.muxigame.console.client.input.InputProfiles.get().load();}
            case "upgrade_callback_source"->{if(!(mc.screen instanceof GameScreen))throw new IllegalStateException("Focused real game screen required");ConsoleClient.INSTANCE.setInputSource(()->NativeControls.sample(ConsoleClient.INSTANCE.inputFeedback().platform()));}
            default->throw new IllegalArgumentException("Unknown upgrade frontend action");
        }
    }
    private static void menu(Minecraft mc){if(!(mc.screen instanceof AbstractContainerScreen<?>)||!(mc.player.containerMenu instanceof WriterMenu)&&!(mc.player.containerMenu instanceof DockMenu)&&mc.player.containerMenu!=mc.player.inventoryMenu)throw new IllegalStateException("Only the actual writer, dock or vanilla player inventory is supported");}
    private static void buttons(GuiEventListener listener,String text,List<Button> found){if(listener instanceof Button button&&button.getMessage().getString().equals(text))found.add(button);if(listener instanceof ContainerEventHandler container)for(var child:container.children())buttons(child,text,found);}
    private static JsonObject stack(Minecraft mc,ItemStack item){JsonObject out=CartridgeActions.stack(mc,item);if(!item.isEmpty()){ItemStack copy=item.copy();copy.remove(DataComponents.CUSTOM_MODEL_DATA);out.addProperty("withoutModel",copy.saveOptional(mc.level.registryAccess()).toString());}return out;}
    static JsonObject observe(Minecraft mc){
        JsonObject out=new JsonObject();if(mc.player==null||mc.level==null)return out;
        var menu=mc.player.containerMenu;out.addProperty("menu",menu.getClass().getName());out.addProperty("containerId",menu.containerId);out.add("cursor",stack(mc,menu.getCarried()));
        JsonArray slots=new JsonArray();for(int i=0;i<menu.slots.size();i++){var slot=menu.getSlot(i);JsonObject row=stack(mc,slot.getItem());row.addProperty("index",i);row.addProperty("x",slot.x);row.addProperty("y",slot.y);slots.add(row);}out.add("slots",slots);
        JsonArray inventory=new JsonArray();for(int i=0;i<mc.player.getInventory().getContainerSize();i++){JsonObject row=stack(mc,mc.player.getInventory().getItem(i));row.addProperty("slot",i);inventory.add(row);}out.add("inventory",inventory);
        if(menu instanceof WriterMenu writer)out.add("writer",writer.context());
        if(menu instanceof DockMenu dock){JsonObject context=dock.context();context.addProperty("locked",dock.locked());out.add("dock",context);}
        JsonArray widgets=new JsonArray();if(mc.screen!=null)for(var child:mc.screen.children())if(child instanceof Button button){JsonObject row=new JsonObject();row.addProperty("text",button.getMessage().getString());row.addProperty("active",button.active);widgets.add(row);}out.add("widgets",widgets);
        if(mc.screen instanceof AbstractContainerScreen<?> screen){
            var hovered=screen.getSlotUnderMouse();out.addProperty("hoveredSlot",hovered==null?-1:menu.slots.indexOf(hovered));
            if(hovered!=null)out.add("hoveredStack",stack(mc,hovered.getItem()));
            out.addProperty("mouseGuiX",mc.mouseHandler.xpos()*screen.width/mc.getWindow().getScreenWidth());out.addProperty("mouseGuiY",mc.mouseHandler.ypos()*screen.height/mc.getWindow().getScreenHeight());
        }
        JsonObject keys=new JsonObject();for(var platform:net.muxigame.console.domain.Platform.values()){JsonObject row=new JsonObject();for(int bit=0;bit<16;bit++){row.addProperty(Integer.toString(bit),NativeControls.key(platform,bit).saveString());row.addProperty("label."+bit,NativeControls.key(platform,bit).getTranslatedKeyMessage().getString());}row.addProperty("device",net.muxigame.console.client.input.InputProfiles.get().profile(platform).controllerDevice());keys.add(platform.id(),row);}out.add("keys",keys);
        return out;
    }
}
