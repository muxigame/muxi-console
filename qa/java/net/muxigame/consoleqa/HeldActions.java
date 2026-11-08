package net.muxigame.consoleqa;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.inventory.ClickType;
import net.muxigame.console.client.ConsoleClient;
import net.muxigame.console.client.input.InputProfiles;
import net.muxigame.console.client.ui.GameScreen;
import net.muxigame.console.domain.Platform;
import net.muxigame.console.runtime.PortInput;

/** Ordinary callbacks and public read-only feedback. No fake ROM, controller mask or render state. */
final class HeldActions {
    private static final Gson JSON=new Gson();
    private String pressedWorld="";

    JsonObject command(Minecraft client,JsonObject j){
        if(client.player==null||client.gameMode==null)throw new IllegalStateException("Actual joined client required");
        String action=j.get("action").getAsString();JsonObject result=new JsonObject();
        switch(action){
            case "hand_arm"->{HumanoidArm arm=HumanoidArm.valueOf(j.get("arm").getAsString());client.options.mainHand().set(arm);client.options.broadcastOptions();}
            case "hand_offhand_toggle"->{
                if(client.screen!=null)throw new IllegalStateException("Release input before the normal inventory swap");
                // A fixture stack may contain two single-stack controllers. Vanilla moves one and leaves one behind;
                // returning the offhand must therefore find an actually empty slot rather than swapping controller for controller.
                int target=-1;boolean returning=!client.player.getOffhandItem().isEmpty();
                if(returning&&!client.player.getOffhandItem().is(net.muxigame.console.mc.ConsoleContent.CONTROLLER.get()))throw new IllegalStateException("Only the ordinary fixture controller may be moved back");
                for(int slot=0;slot<9;slot++){
                    if(slot==client.player.getInventory().selected)continue;var stack=client.player.getInventory().getItem(slot);
                    if(returning?stack.isEmpty():stack.is(net.muxigame.console.mc.ConsoleContent.CONTROLLER.get())){target=slot;break;}
                }
                if(target<0)throw new IllegalStateException("No safe ordinary inventory swap target");
                result.addProperty("inventorySwapTarget",target);result.addProperty("returningController",returning);
                client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId,45,target,ClickType.SWAP,client.player);
            }
            case "hand_key"->{
                if(client.screen==null)throw new IllegalStateException("An actual normal input/settings screen is required");
                int key=j.get("key").getAsInt();boolean pressed=j.get("pressed").getAsBoolean();
                boolean consumed=pressed?client.screen.keyPressed(key,0,0):client.screen.keyReleased(key,0,0);result.addProperty("consumed",consumed);
            }
            case "hand_mouse"->{if(client.screen==null)throw new IllegalStateException("Actual input screen required");result.addProperty("consumed",client.screen.mouseClicked(0,client.screen.height/2.0,j.get("button").getAsInt()));}
            case "hand_button_prefix"->{
                if(client.screen==null)throw new IllegalStateException("An actual settings screen is required");
                String prefix=j.get("prefix").getAsString();
                Button found=null;for(var child:client.screen.children())if(child instanceof Button b&&b.visible&&b.active&&b.getMessage().getString().startsWith(prefix)){if(found!=null)throw new IllegalStateException("Ambiguous button prefix");found=b;}
                if(found==null)throw new IllegalStateException("Visible settings button missing: "+prefix);result.addProperty("button",found.getMessage().getString());found.onPress();
            }
            case "hand_sample_callbacks"->{
                if(!(client.screen instanceof GameScreen))throw new IllegalStateException("Real focused game screen required");
                Platform platform=Platform.fromId(j.get("platform").getAsString());
                // A first-party frontend source polls the real screen's callback key-state through the real profile mapper.
                // This is needed for hidden test windows; it is not an OS-input acceptance claim.
                ConsoleClient.INSTANCE.setInputSource(()->client.screen instanceof GameScreen s?InputProfiles.get().sampleKeys(platform,s.inputKeys()):PortInput.IDLE);
            }
            case "hand_world_key"->{
                String name=j.get("name").getAsString();boolean pressed=j.get("pressed").getAsBoolean();
                KeyMapping mapping=switch(name){case "forward"->client.options.keyUp;case "attack"->client.options.keyAttack;case "drop"->client.options.keyDrop;case "swap"->client.options.keySwapOffhand;case "hotbar"->client.options.keyHotbarSlots[0];default->throw new IllegalArgumentException("Bounded ordinary world keys only");};
                mapping.setDown(pressed);if(pressed&&!name.equals("forward"))KeyMapping.click(mapping.getKey());pressedWorld=pressed?name:"";
            }
            case "hand_release_keys"->{
                KeyMapping.releaseAll();pressedWorld="";ConsoleClient.INSTANCE.setInputSource(null);
                if(client.screen instanceof GameScreen screen)for(int key=32;key<=348;key++)screen.keyReleased(key,0,0);
            }
            case "hand_profile"->{ }
            default->throw new IllegalArgumentException("Unknown held review callback");
        }
        result.add("feedback",feedback());result.add("profile",profile(client,j));return result;
    }

    static JsonObject feedback(){
        try{return JSON.toJsonTree(ConsoleClient.class.getMethod("inputFeedback").invoke(ConsoleClient.INSTANCE)).getAsJsonObject();}
        catch(ReflectiveOperationException e){throw new IllegalStateException("Current product public input feedback is required",e);}
    }
    private static JsonObject profile(Minecraft client,JsonObject j){
        JsonObject out=new JsonObject();Platform platform=j.has("platform")?Platform.fromId(j.get("platform").getAsString()):Platform.GB;var p=InputProfiles.get().profile(platform);
        out.addProperty("platform",platform.id());out.addProperty("aKey",p.keyBinding(8));out.addProperty("bKey",p.keyBinding(0));out.addProperty("upKey",p.keyBinding(4));out.addProperty("focusKey",InputProfiles.get().focusKey());out.addProperty("file",InputProfiles.get().path().toString());
        // Independently instantiate the ordinary persisted profile reader. No configuration content is written here.
        var reader=new InputProfiles(client.gameDirectory);var disk=reader.profile(platform);out.addProperty("diskAKey",disk.keyBinding(8));out.addProperty("diskBKey",disk.keyBinding(0));out.addProperty("diskFocusKey",reader.focusKey());return out;
    }
    JsonObject observe(Minecraft client){
        JsonObject out=new JsonObject();out.add("input",feedback());out.addProperty("pressedWorld",pressedWorld);
        try{out.add("render",(JsonObject)Class.forName("net.muxigame.console.client.HandheldPresentation").getMethod("snapshot").invoke(null));}
        catch(ReflectiveOperationException e){throw new IllegalStateException("Current product's public render observation required",e);}
        JsonArray buttons=new JsonArray();if(client.screen!=null)for(var child:client.screen.children())if(child instanceof Button b&&b.visible&&b.active)buttons.add(b.getMessage().getString());out.add("buttons",buttons);
        if(client.player!=null){out.addProperty("mainArm",client.player.getMainArm().name());out.addProperty("mainArmOption",client.options.mainHand().get().name());out.addProperty("selected",client.player.getInventory().selected);out.addProperty("offhandEmpty",client.player.getOffhandItem().isEmpty());out.addProperty("mainhandEmpty",client.player.getMainHandItem().isEmpty());out.addProperty("swinging",client.player.swinging);}
        if(client.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit&&client.level!=null){out.addProperty("hitBlock",hit.getBlockPos().toShortString());out.addProperty("hitState",client.level.getBlockState(hit.getBlockPos()).toString());}
        return out;
    }
}
