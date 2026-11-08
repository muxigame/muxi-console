package net.muxigame.consoleqa;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.muxigame.console.domain.*;
import net.muxigame.console.mc.*;

/** Empty physical hardware supply and read-only machine/game storage evidence. No identity or state injection. */
final class MachineScene {
    private final QaOwner owner;private boolean prepared;
    MachineScene(QaOwner owner){this.owner=owner;}
    private ServerPlayer player(MinecraftServer s,String role)throws IOException{ServerPlayer p=s.getPlayerList().getPlayerByName(owner.playerName(role));if(p==null)throw new IllegalStateException("Actual network player missing");return p;}
    JsonObject command(MinecraftServer s,JsonObject j){try{
        if(j.get("action").getAsString().equals("machine_prepare")){
            if(prepared||!ConsoleServer.state(s).rooms.isEmpty())throw new IllegalStateException("New idle machine fixture required");
            empty(player(s,"host"),2,"machine-a");empty(player(s,"guest"),7,"machine-b");
            ServerPlayer p=player(s,"host");for(ItemStack item:List.of(HardwareCatalog.variant(new ItemStack(ConsoleContent.DESK_ITEM.get()),1),new ItemStack(Items.DIAMOND_PICKAXE),new ItemStack(ConsoleContent.DOCK_ITEM.get())))if(p.getInventory().items.stream().noneMatch(i->i.is(item.getItem()))&&!p.getInventory().add(item))throw new IllegalStateException("Ordinary fixture inventory is full");
            p.inventoryMenu.broadcastChanges();prepared=true;
        }else if(!j.get("action").getAsString().equals("machine_observe"))throw new IllegalArgumentException("Unknown machine fixture command");
        return observe(s);
    }catch(Exception e){throw new IllegalStateException(e);}}
    private static void empty(ServerPlayer p,int slot,String marker){ItemStack prior=p.getInventory().getItem(slot);if(prior.is(ConsoleContent.HANDHELD.get())&&!CardData.hasInserted(prior)&&HardwareCatalog.model(prior)==5)return;if(!prior.isEmpty()&&(!prior.is(ConsoleContent.HANDHELD.get())||CardData.hasInserted(prior)||MachineData.read(prior).isPresent()))throw new IllegalStateException("Refuse to replace a real machine or card");ItemStack item=HardwareCatalog.variant(new ItemStack(ConsoleContent.HANDHELD.get()),5);CompoundTag tag=new CompoundTag();tag.putString("qa_preserve",marker);item.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));p.getInventory().setItem(slot,item);p.inventoryMenu.broadcastChanges();}
    static Path world(MinecraftServer s){return s.getWorldPath(LevelResource.ROOT).resolve("muxi-console").toAbsolutePath().normalize();}
    static JsonArray saved(MinecraftServer s,boolean disk)throws IOException{
        JsonArray rows=new JsonArray();Path root=world(s).resolve("states/machines");if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))return rows;
        try(var machines=Files.list(root)){for(Path machine:machines.filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).sorted().toList()){
            UUID id=UUID.fromString(machine.getFileName().toString());try(var games=Files.list(machine)){for(Path game:games.filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).sorted().toList()){
                MachineSaveKey key=new MachineSaveKey(id,game.getFileName().toString());var latest=ConsoleServer.state(s).states.latest(key);if(latest.isEmpty())continue;var v=latest.get();JsonObject row=new JsonObject();row.addProperty("machine",v.machineUUID().toString());row.addProperty("content",v.contentSHA());row.addProperty("hash",v.hash());row.addProperty("generation",v.generationID().toString());row.addProperty("fingerprint",v.fingerprint());row.addProperty("frame",v.frame());row.addProperty("size",v.size());
                Path state=game.resolve("cards").resolve(id.toString()).resolve(v.generationID()+".state");row.addProperty("path",state.toString());if(disk){row.addProperty("diskBytes",Files.size(state));row.addProperty("diskSha256",Hashing.sha256(state));}rows.add(row);
            }}
        }}return rows;
    }
    private static JsonObject item(ServerPlayer p,ItemStack item,String location){JsonObject row=new JsonObject();row.addProperty("location",location);row.addProperty("item",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).toString());row.addProperty("count",item.getCount());row.addProperty("model",HardwareCatalog.model(item));row.addProperty("nbt",item.saveOptional(p.registryAccess()).toString());MachineData.read(item).ifPresent(id->row.addProperty("machine",id.toString()));CardData.read(item).ifPresent(c->{row.addProperty("card",c.instanceId().toString());row.addProperty("content",c.sha256());});if(item.is(ConsoleContent.HANDHELD.get()))row.addProperty("insertedNbt",CardData.inserted(item).saveOptional(p.registryAccess()).toString());return row;}
    JsonObject observe(MinecraftServer s)throws IOException{
        JsonObject out=new JsonObject();out.addProperty("prepared",prepared);out.addProperty("worldStorage",world(s).toString());out.add("saved",saved(s,true));JsonArray items=new JsonArray(),devices=new JsonArray(),drops=new JsonArray();
        for(String role:List.of("host","guest")){ServerPlayer p=player(s,role);for(int i=0;i<p.getInventory().getContainerSize();i++){ItemStack item=p.getInventory().getItem(i);if(!item.isEmpty())items.add(item(p,item,role+"/"+i));}if(!p.inventoryMenu.getCarried().isEmpty())items.add(item(p,p.inventoryMenu.getCarried(),role+"/cursor"));}
        ServerPlayer host=player(s,"host");for(BlockPos pos:List.of(new BlockPos(16,90,18),new BlockPos(12,90,6)))if(s.overworld().getBlockEntity(pos)instanceof DeviceEntity d){JsonObject row=ConsoleServer.view(d);row.addProperty("blockPosition",pos.toShortString());if(d.kind()==DeviceBlock.Kind.DESK){var tag=d.getUpdateTag(s.registryAccess());if(tag.hasUUID("machine_identity"))row.addProperty("machineRead",tag.getUUID("machine_identity").toString());}else if(d.kind()==DeviceBlock.Kind.DOCK)MachineData.read(d.dockedHandheld).ifPresent(id->row.addProperty("machineRead",id.toString()));devices.add(row);if(!d.media().isEmpty())items.add(item(host,d.media(),"device/"+pos.toShortString()));}
        for(ItemEntity e:s.overworld().getEntitiesOfClass(ItemEntity.class,new AABB(3,89,4,26,96,26))){JsonObject row=item(host,e.getItem(),"world");row.addProperty("entity",e.getUUID().toString());row.addProperty("x",e.getX());row.addProperty("y",e.getY());row.addProperty("z",e.getZ());drops.add(row);}
        out.add("items",items);out.add("devices",devices);out.add("drops",drops);return out;
    }
}
