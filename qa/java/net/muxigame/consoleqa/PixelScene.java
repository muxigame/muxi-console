package net.muxigame.consoleqa;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.muxigame.console.mc.*;

/** First-party visual fixtures only: no cartridge identity, ROM output or save is synthesized. */
final class PixelScene {
    private final QaOwner owner;
    private boolean props,live;
    PixelScene(QaOwner owner){this.owner=owner;}
    private ServerPlayer host(MinecraftServer server){try{ServerPlayer player=server.getPlayerList().getPlayerByName(owner.playerName("host"));if(player==null)throw new IllegalStateException("Actual host player missing");return player;}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    JsonObject command(MinecraftServer server,JsonObject j){
        return switch(j.get("action").getAsString()){
            case "pixel_world"->{JsonObject out=new JsonObject();out.addProperty("gameTime",server.overworld().getGameTime());yield out;}
            case "pixel_props_prepare"->prepareProps(server);
            case "pixel_props_camera"->{if(!props)throw new IllegalStateException("Prepare original props first");ServerPlayer player=host(server);if(j.get("group").getAsString().equals("media"))ReviewScene.teleportEye(player,46.5,97,23,46.5,90.5,30.5);else ReviewScene.teleportEye(player,46.5,93.8,20,46.5,90.5,25.5);JsonObject out=new JsonObject();out.addProperty("group",j.get("group").getAsString());out.addProperty("mediaCount",21);yield out;}
            default->throw new IllegalArgumentException("Unsupported pixel fixture action");
        };
    }
    private JsonObject prepareProps(MinecraftServer server){
        if(props||server.getPlayerList().getPlayerCount()!=2||!(server.overworld().getBlockEntity(new BlockPos(32,90,0))instanceof DeviceEntity first)||first.getBlockState().getValue(DeviceBlock.MODEL)!=1)throw new IllegalStateException("Fresh current-candidate model gallery required");
        var level=server.overworld();JsonArray media=new JsonArray();
        for(int id=1;id<=21;id++){
            int column=(id-1)%7,row=(id-1)/7;double x=40.5+column*2,z=28.5+row*2;
            ItemStack item=HardwareCatalog.variant(new ItemStack(ConsoleContent.CARTRIDGE.get()),id);display(server,item,x,90.45,z);
            JsonObject entry=new JsonObject();entry.addProperty("cmd",id);entry.addProperty("platform",HardwareCatalog.body(id).platform().id());entry.addProperty("x",x);entry.addProperty("y",90.45);entry.addProperty("z",z);media.add(entry);
        }
        display(server,new ItemStack(ConsoleContent.BLANK.get()),42.5,90.5,25.5);
        display(server,new ItemStack(ConsoleContent.CONTROLLER.get()),44.5,90.5,25.5);
        level.setBlock(new BlockPos(47,90,25),ConsoleContent.DOCK.get().defaultBlockState().setValue(DeviceBlock.FACING,Direction.NORTH),3);
        level.setBlock(new BlockPos(49,90,25),ConsoleContent.WRITER.get().defaultBlockState().setValue(DeviceBlock.FACING,Direction.NORTH),3);
        props=true;JsonObject out=new JsonObject();out.addProperty("mediaCount",21);out.add("media",media);out.addProperty("displayPropsHaveNoCardData",true);out.addProperty("largeScreenUnchanged",true);return out;
    }
    private static void display(MinecraftServer server,ItemStack item,double x,double y,double z){
        if(CardData.read(item).isPresent())throw new IllegalArgumentException("Visual props must never contain an issued cartridge identity");
        Display.ItemDisplay entity=new Display.ItemDisplay(EntityType.ITEM_DISPLAY,server.overworld());CompoundTag tag=new CompoundTag();tag.put("item",item.save(server.registryAccess()));tag.putString("item_display","fixed");entity.load(tag);entity.moveTo(x,y,z,180,0);server.overworld().addFreshEntity(entity);
    }
    JsonObject prepareLive(MinecraftServer server){
        if(live)throw new IllegalStateException("Pixel live fixture already configured");ServerPlayer player=host(server);
        for(int slot:new int[]{2,7,40})if(CardData.read(player.getInventory().getItem(slot)).isPresent())throw new IllegalStateException("Refusing to replace any real cartridge-bearing item");
        player.getInventory().setItem(2,handheld(3,"pixel-review-gb-shell"));player.getInventory().setItem(7,handheld(5,"pixel-review-gba-shell"));player.getInventory().setItem(40,ItemStack.EMPTY);player.getInventory().selected=8;
        player.getAbilities().flying=false;player.onUpdateAbilities();player.inventoryMenu.broadcastChanges();live=true;
        JsonObject out=new JsonObject();out.addProperty("emptyGbSlot",2);out.addProperty("emptyGbaSlot",7);out.addProperty("mintedCartridges",0);out.addProperty("largeScreenScenarioSkipped",true);return out;
    }
    private static ItemStack handheld(int model,String marker){ItemStack item=HardwareCatalog.variant(new ItemStack(ConsoleContent.HANDHELD.get()),model);CompoundTag tag=new CompoundTag();tag.putString("qa_preserve",marker);item.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));return item;}
}
