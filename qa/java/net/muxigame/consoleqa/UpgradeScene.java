package net.muxigame.consoleqa;

import com.google.gson.*;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.*;
import net.muxigame.console.mc.*;

/** Ordinary empty cores/materials only; cards are still issued exclusively by the normal writer. */
final class UpgradeScene {
    private final QaOwner owner;private boolean prepared;private final JsonObject fixtures=new JsonObject();private int propSlot=-1;
    UpgradeScene(QaOwner owner){this.owner=owner;}
    private ServerPlayer host(MinecraftServer server)throws Exception{var p=server.getPlayerList().getPlayerByName(owner.playerName("host"));if(p==null)throw new IllegalStateException("Actual host required");return p;}
    JsonObject command(MinecraftServer server,JsonObject j){try{
        ServerPlayer p=host(server);String action=j.get("action").getAsString();
        if(action.equals("upgrade_prepare")){
            if(prepared||server.getPlayerList().getPlayerCount()!=2||!ConsoleServer.state(server).rooms.isEmpty()||!p.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Fresh idle two-client fixture required");
            var core=p.getInventory().getItem(2);if(!core.is(ConsoleContent.HANDHELD.get())||core.getCount()!=1||HardwareCatalog.model(core)!=0||CardData.hasInserted(core)||MachineData.read(core).isPresent())throw new IllegalStateException("Use the original ordinary scene core, never replace a real machine");
            CompoundTag data=core.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();data.putString("qa_upgrade_core","preserve-complete-components");core.set(DataComponents.CUSTOM_DATA,CustomData.of(data));core.set(DataComponents.CUSTOM_NAME,Component.literal("Original named upgrade core"));core.set(DataComponents.LORE,new ItemLore(List.of(Component.literal("Keep original custom lore"))));
            List<ItemStack> supplies=List.of(new ItemStack(ConsoleContent.HANDHELD.get()),new ItemStack(ConsoleContent.HANDHELD.get()),new ItemStack(ConsoleContent.DESK_ITEM.get()),new ItemStack(Items.WHITE_DYE,4),new ItemStack(Items.PURPLE_DYE,4),new ItemStack(Items.LIME_DYE,4),new ItemStack(Items.BLACK_DYE),new ItemStack(Items.CYAN_DYE),new ItemStack(Items.LIGHT_BLUE_DYE));
            String[] names={"ndsCore","n3dsCore","wiiCore","white","purple","lime","black","cyan","lightBlue"};
            List<Integer> empty=new ArrayList<>();for(int i=9;i<36;i++)if(p.getInventory().getItem(i).isEmpty())empty.add(i);if(empty.size()<supplies.size()+1||!p.getInventory().getItem(3).isEmpty()||!p.getOffhandItem().isEmpty())throw new IllegalStateException("Preflight all empty fixture destinations");
            p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);var guest=server.getPlayerList().getPlayerByName(owner.playerName("guest"));if(guest==null)throw new IllegalStateException("Actual guest required");guest.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            for(int i=0;i<supplies.size();i++){p.getInventory().setItem(empty.get(i),supplies.get(i));fixtures.addProperty(names[i],empty.get(i));}propSlot=3;fixtures.addProperty("core",2);fixtures.addProperty("prop",propSlot);prepared=true;p.inventoryMenu.broadcastChanges();
        }else if(action.equals("upgrade_card_sentinel")){
            String id=j.get("instance").getAsString();ItemStack card=null;for(ItemStack item:p.getInventory().items)if(item.is(ConsoleContent.CARTRIDGE.get())&&CardData.read(item).map(c->c.instanceId().toString().equals(id)).orElse(false)){if(card!=null)throw new IllegalStateException("Duplicated physical issued card");card=item;}
            if(!prepared||card==null)throw new IllegalStateException("An actually issued original inventory card is required");CompoundTag tag=card.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();tag.putString("qa_upgrade_card","preserve-original-issued-card");card.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));card.set(DataComponents.LORE,new ItemLore(List.of(Component.literal("Original cartridge lore"))));p.inventoryMenu.broadcastChanges();
        }else if(action.equals("upgrade_prop")){
            if(!prepared||!ConsoleServer.state(server).rooms.isEmpty()||!p.containerMenu.getCarried().isEmpty()||!p.getOffhandItem().isEmpty())throw new IllegalStateException("Idle prop fixture with empty cursor/offhand required");
            ItemStack prior=p.getInventory().getItem(propSlot);if(!prior.isEmpty()&&(!prior.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().contains("qa_upgrade_prop")||CardData.read(prior).isPresent()||MachineData.read(prior).isPresent()))throw new IllegalStateException("Refuse to replace an actual card or machine");
            String name=j.get("kind").getAsString();ItemStack prop=switch(name){case "writer"->new ItemStack(ConsoleContent.WRITER_ITEM.get());case "dock"->new ItemStack(ConsoleContent.DOCK_ITEM.get());case "case"->new ItemStack(ConsoleContent.CASE_ITEM.get());case "controller"->new ItemStack(ConsoleContent.CONTROLLER.get());case "blank"->new ItemStack(ConsoleContent.BLANK.get());case "card_nes"->HardwareCatalog.variant(new ItemStack(ConsoleContent.CARTRIDGE.get()),1);case "card_gba"->HardwareCatalog.variant(new ItemStack(ConsoleContent.CARTRIDGE.get()),5);case "card_wii"->HardwareCatalog.variant(new ItemStack(ConsoleContent.CARTRIDGE.get()),25);default->throw new IllegalArgumentException("Bounded unissued appearance props only");};CompoundTag tag=new CompoundTag();tag.putString("qa_upgrade_prop",name);prop.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));p.getInventory().setItem(propSlot,prop);p.inventoryMenu.broadcastChanges();
        }else if(!action.equals("upgrade_observe"))throw new IllegalArgumentException("Unknown upgrade scene action");
        JsonObject out=new JsonObject();out.addProperty("prepared",prepared);out.add("fixtures",fixtures.deepCopy());out.addProperty("cloudConfigured",ConsoleServer.state(server).cloud!=null);out.add("saved",MachineScene.saved(server,true));return out;
    }catch(Exception error){throw new IllegalStateException(error);}}
}
