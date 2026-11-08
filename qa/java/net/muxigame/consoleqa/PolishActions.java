package net.muxigame.consoleqa;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import net.muxigame.console.client.ConsoleClient;
import net.muxigame.console.client.input.NativeControls;
import net.muxigame.console.client.ui.GameScreen;
import net.muxigame.console.domain.Platform;
import net.muxigame.console.runtime.PortInput;

/** Current normal UI and vanilla key-entry callbacks, never writes a fake controller or presentation state. */
final class PolishActions {
    JsonObject command(Minecraft mc,JsonObject j)throws Exception{
        if(mc.player==null)throw new IllegalStateException("Actual connected client required");
        JsonObject out=new JsonObject();String action=j.get("action").getAsString();
        switch(action){
            case "polish_pause"->{if(mc.screen!=null)throw new IllegalStateException("Return to world before normal options");mc.setScreen(new PauseScreen(true));}
            case "polish_button_translation"->{String key=j.get("key").getAsString();if(!List.of("menu.options","options.controls","controls.keybinds","gui.done").contains(key))throw new IllegalArgumentException("Only ordinary settings navigation");String label=Component.translatable(key).getString();Button b=find(mc.screen.children(),label);if(b==null)throw new IllegalStateException("Visible native settings button missing: "+label);b.onPress();out.addProperty("button",label);}
            case "polish_native_scroll","polish_native_select","polish_native_reset"->{
                if(!(mc.screen instanceof KeyBindsScreen screen))throw new IllegalStateException("Actual Minecraft KeyBindsScreen required");
                Platform platform=Platform.fromId(j.get("platform").getAsString());KeyMapping mapping=NativeControls.key(platform,j.get("bit").getAsInt());
                KeyBindsList list=null;for(var child:screen.children())if(child instanceof KeyBindsList found)list=found;
                if(list==null)throw new IllegalStateException("Native key list missing");
                KeyMapping[] sorted=mc.options.keyMappings.clone();Arrays.sort(sorted);String category=null;int index=0;boolean found=false;
                for(KeyMapping item:sorted){if(!item.getCategory().equals(category)){category=item.getCategory();index++;}if(item==mapping){found=true;break;}index++;}
                if(!found||index>=list.children().size()||!(list.children().get(index)instanceof KeyBindsList.KeyEntry entry))throw new IllegalStateException("Vanilla sorted key-entry identity differs");
                list.setScrollAmount(Math.max(0,index*20-list.getHeight()/2.0));out.addProperty("mapping",mapping.getName());out.addProperty("entry",index);
                if(!action.equals("polish_native_scroll")){
                    var children=entry.children();if(children.size()!=2||!(children.get(0)instanceof Button change)||!(children.get(1)instanceof Button reset))throw new IllegalStateException("Native key-entry controls differ");
                    if(action.equals("polish_native_select")){change.onPress();if(screen.selectedKey!=mapping)throw new IllegalStateException("Real vanilla button selected the wrong mapping");}
                    else reset.onPress();
                }
            }
            case "polish_key"->{if(mc.screen==null)throw new IllegalStateException("Normal input screen required");boolean pressed=j.get("pressed").getAsBoolean();int key=j.get("key").getAsInt();out.addProperty("consumed",pressed?mc.screen.keyPressed(key,0,0):mc.screen.keyReleased(key,0,0));}
            case "polish_mouse"->{if(mc.screen==null)throw new IllegalStateException("Normal input screen required");boolean pressed=!j.has("pressed")||j.get("pressed").getAsBoolean();int button=j.get("button").getAsInt();out.addProperty("consumed",pressed?mc.screen.mouseClicked(0,mc.screen.height/2.0,button):mc.screen.mouseReleased(0,mc.screen.height/2.0,button));}
            case "polish_callbacks"->{Platform platform=Platform.fromId(j.get("platform").getAsString());if(!(mc.screen instanceof GameScreen))throw new IllegalStateException("Actual model/screen fullscreen required");ConsoleClient.INSTANCE.setInputSource(()->mc.screen instanceof GameScreen?NativeControls.sample(platform):PortInput.IDLE);}
            case "polish_reload_options"->{mc.options.load();KeyMapping.resetMapping();}
            case "polish_save_options"->mc.options.save();
            case "polish_native_profile"->{ }
            case "polish_empty_slot8"->{var stack=mc.player.getInventory().getItem(8);if(net.muxigame.console.mc.CardData.read(stack).isPresent())throw new IllegalStateException("Never move a genuine cartridge for fixture space");if(!stack.isEmpty())mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId,44,0,net.minecraft.world.inventory.ClickType.QUICK_MOVE,mc.player);}
            case "polish_release_keys"->{NativeControls.clear();KeyMapping.releaseAll();ConsoleClient.INSTANCE.setInputSource(null);}
            default->throw new IllegalArgumentException("Unknown polish frontend action");
        }
        out.add("bindings",bindings(mc,j.has("platform")?Platform.fromId(j.get("platform").getAsString()):Platform.GB));return out;
    }
    private static Button find(List<? extends GuiEventListener> children,String label){
        for(var child:children){if(child instanceof Button b&&b.visible&&b.active&&b.getMessage().getString().equals(label))return b;if(child instanceof ContainerEventHandler parent){Button nested=find(parent.children(),label);if(nested!=null)return nested;}}return null;
    }
    private static JsonObject bindings(Minecraft mc,Platform platform)throws Exception{
        JsonObject out=new JsonObject();out.addProperty("platform",platform.id());JsonArray mappings=new JsonArray();var file=mc.gameDirectory.toPath().resolve("options.txt");List<String> lines=Files.exists(file)?Files.readAllLines(file,StandardCharsets.UTF_8):List.of();
        for(int bit=0;bit<16;bit++){KeyMapping key=NativeControls.key(platform,bit);JsonObject row=new JsonObject();row.addProperty("bit",bit);row.addProperty("name",key.getName());row.addProperty("key",key.getKey().getValue());row.addProperty("binding",key.saveString());String prefix="key_"+key.getName()+":";row.addProperty("persisted",lines.stream().filter(s->s.startsWith(prefix)).findFirst().orElse(""));mappings.add(row);}out.add("mappings",mappings);return out;
    }
    static JsonObject observe(Minecraft mc){
        JsonObject out=new JsonObject();
        try{out.add("feedbackAudit",new com.google.gson.Gson().toJsonTree(Class.forName("net.muxigame.console.client.HandheldFeedback").getMethod("audit").invoke(null)));}catch(ReflectiveOperationException error){throw new IllegalStateException("Actual baked-quad feedback audit required",error);}
        if(mc.screen instanceof GameScreen screen)try{out.add("presentation",(JsonObject)screen.getClass().getMethod("presentationSnapshot").invoke(screen));}catch(ReflectiveOperationException error){throw new IllegalStateException("Actual fullscreen render observation required",error);}
        try{out.add("hint",(JsonObject)Class.forName("net.muxigame.console.client.HandheldHint").getMethod("snapshot").invoke(null));}catch(ReflectiveOperationException error){throw new IllegalStateException("Actual transient hint observation required",error);}
        JsonArray widgets=new JsonArray();if(mc.screen!=null)for(var child:mc.screen.children())if(child instanceof Button b&&b.visible)widgets.add(b.getMessage().getString());out.add("visibleTopLevelButtons",widgets);return out;
    }
}
