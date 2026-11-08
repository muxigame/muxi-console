package net.muxigame.consoleqa;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.muxigame.console.domain.Hashing;
import net.muxigame.console.mc.*;

/** Ordinary empty fixture items and sentinels on already issued cards; never creates a card identity or save. */
final class CartridgeScene {
    private final QaOwner owner;private boolean prepared;
    private final Map<UUID,Map<Integer,ItemStack>> fillers=new HashMap<>();
    CartridgeScene(QaOwner owner){this.owner=owner;}
    private ServerPlayer player(MinecraftServer server,String role)throws Exception{if(!Set.of("host","guest").contains(role))throw new IllegalArgumentException("Real roles only");ServerPlayer p=server.getPlayerList().getPlayerByName(owner.playerName(role));if(p==null)throw new IllegalStateException("Real player missing");return p;}
    JsonObject command(MinecraftServer server,JsonObject j){try{
        String action=j.get("action").getAsString(),role=j.has("role")?j.get("role").getAsString():"host";ServerPlayer p=player(server,role);
        if(action.equals("cartridge_prepare")){
            if(prepared||server.overworld().getGameTime()>12000||server.getPlayerList().getPlayerCount()!=2||!ConsoleServer.state(server).rooms.isEmpty())throw new IllegalStateException("Fresh idle fixture required");
            for(String who:List.of("host","guest")){ServerPlayer person=player(server,who);for(ItemStack item:person.getInventory().items)if(CardData.read(item).isPresent())throw new IllegalStateException("Never replace an issued card fixture");
                person.getInventory().setItem(0,new ItemStack(ConsoleContent.BLANK.get(),8));person.getInventory().setItem(2,shell(3,who+"-gb-shell"));person.getInventory().setItem(7,shell(5,who+"-gba-shell"));person.getInventory().setItem(3,new ItemStack(Items.STONE,64));person.setGameMode(GameType.SURVIVAL);person.getInventory().selected=8;person.getAbilities().flying=false;person.onUpdateAbilities();person.inventoryMenu.broadcastChanges();}
            for(int i=0;i<2;i++){BlockPos pos=new BlockPos(i==0?8:14,90,20);if(!server.overworld().getBlockState(pos).isAir())throw new IllegalStateException("New empty desktop fixture occupied");server.overworld().setBlock(pos,ConsoleContent.DESK.get().defaultBlockState().setValue(DeviceBlock.FACING,Direction.SOUTH).setValue(DeviceBlock.MODEL,i==0?1:11),3);}
            prepared=true;
        }else{
            if(!prepared)throw new IllegalStateException("Prepare cartridge fixture first");
            switch(action){
                case "cartridge_legacy"->{UUID id=UUID.fromString(j.get("instance").getAsString());int slot=j.get("slot").getAsInt();if(slot!=2)throw new IllegalArgumentException("Only the ordinary empty GB fixture may represent the legacy save");ItemStack shell=p.getInventory().getItem(slot);if(!shell.is(ConsoleContent.HANDHELD.get())||CardData.hasInserted(shell)||shell.has(DataComponents.CONTAINER))throw new IllegalStateException("Empty unconverted shell required");List<ItemStack> found=p.getInventory().items.stream().filter(s->s.is(ConsoleContent.CARTRIDGE.get())&&CardData.read(s).map(c->c.instanceId().equals(id)).orElse(false)).toList();if(found.size()!=1||found.getFirst().getCount()!=1)throw new IllegalStateException("One actual writer-issued card is required");ItemStack card=found.getFirst();CompoundTag source=card.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();if(source.contains("qa_card_sentinel")||CardData.read(card).orElseThrow().platform()!=net.muxigame.console.domain.Platform.GB)throw new IllegalStateException("Only the explicit unmodified legacy GB fixture card is permitted");ConsoleServer.requireCardIdle(p,id);var data=CardData.read(card).orElseThrow();if(!ConsoleServer.state(server).content.has(data.sha256()))throw new IllegalStateException("Actual content missing");CompoundTag shellTag=shell.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();shellTag.put("muxi_console",source.getCompound("muxi_console").copy());shell.set(DataComponents.CUSTOM_DATA,CustomData.of(shellTag));card.shrink(1);p.inventoryMenu.broadcastChanges();}
                case "cartridge_sentinel"->{UUID id=UUID.fromString(j.get("instance").getAsString());List<ItemStack> found=p.getInventory().items.stream().filter(s->s.is(ConsoleContent.CARTRIDGE.get())&&CardData.read(s).map(c->c.instanceId().equals(id)).orElse(false)).toList();if(found.size()!=1||found.getFirst().getCount()!=1)throw new IllegalStateException("Exactly one real issued raw card is required");ItemStack card=found.getFirst();var c=CardData.read(card).orElseThrow();ConsoleServer.requireCardIdle(p,id);var admitted=ConsoleServer.state(server).content.describe(c.sha256());if(admitted.size()!=c.size()||admitted.platform()!=c.platform())throw new IllegalStateException("Card content is not actually admitted");CompoundTag tag=card.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();CompoundTag sentinel=new CompoundTag();sentinel.putString("source","normal-writer-issued");sentinel.putIntArray("values",new int[]{7,31,1024,-9});sentinel.putString("card",id.toString());tag.put("qa_card_sentinel",sentinel);card.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));card.set(DataComponents.CUSTOM_NAME,Component.literal("QA完整卡 · "+c.platform().id()));p.inventoryMenu.broadcastChanges();}
                case "cartridge_fill"->{if(!ConsoleServer.state(server).rooms.isEmpty()||fillers.containsKey(p.getUUID())||!p.inventoryMenu.getCarried().isEmpty())throw new IllegalStateException("Idle empty cursor required for ordinary fillers");Map<Integer,ItemStack> added=new HashMap<>();for(int slot=0;slot<36;slot++)if(p.getInventory().getItem(slot).isEmpty()){ItemStack item=new ItemStack(Items.STONE,64);CompoundTag tag=new CompoundTag();tag.putBoolean("qa_cartridge_filler",true);item.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));p.getInventory().setItem(slot,item);added.put(slot,item.copy());}fillers.put(p.getUUID(),added);p.inventoryMenu.broadcastChanges();}
                case "cartridge_clear_fill"->{Map<Integer,ItemStack> added=fillers.get(p.getUUID());if(added==null)throw new IllegalStateException("No own filler fixture");for(var e:added.entrySet())if(!ItemStack.matches(e.getValue(),p.getInventory().getItem(e.getKey())))throw new IllegalStateException("A filler changed; refuse cleanup");for(int slot:added.keySet())p.getInventory().setItem(slot,ItemStack.EMPTY);fillers.remove(p.getUUID());p.inventoryMenu.broadcastChanges();}
                case "cartridge_observe"->{ }
                default->throw new IllegalArgumentException("Unknown cartridge fixture action");
            }
        }
        return observe(server);
    }catch(Exception e){throw new IllegalStateException(e);}}
    private static ItemStack shell(int model,String marker){ItemStack item=HardwareCatalog.variant(new ItemStack(ConsoleContent.HANDHELD.get()),model);CompoundTag tag=new CompoundTag();tag.putString("qa_preserve",marker);item.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));return item;}
    private static JsonObject stack(ServerPlayer p,ItemStack item,String location){JsonObject out=new JsonObject();out.addProperty("location",location);out.addProperty("item",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).toString());out.addProperty("count",item.getCount());out.addProperty("model",HardwareCatalog.model(item));out.addProperty("nbt",item.saveOptional(p.registryAccess()).toString());CardData.read(item).ifPresent(c->{out.addProperty("instance",c.instanceId().toString());out.addProperty("hash",c.sha256());out.addProperty("platform",c.platform().id());});if(item.is(ConsoleContent.HANDHELD.get()))out.addProperty("insertedNbt",CardData.inserted(item).saveOptional(p.registryAccess()).toString());return out;}
    JsonObject observe(MinecraftServer server){JsonObject out=new JsonObject();out.addProperty("prepared",prepared);if(!prepared)return out;try{
        JsonArray items=new JsonArray(),disk=new JsonArray(),desks=new JsonArray();Map<UUID,String> ids=new HashMap<>();
        for(String role:List.of("host","guest")){ServerPlayer p=player(server,role);for(int slot=0;slot<p.getInventory().getContainerSize();slot++){ItemStack item=p.getInventory().getItem(slot);if(!item.isEmpty())items.add(stack(p,item,role+"/"+slot));CardData.read(item).ifPresent(c->ids.put(c.instanceId(),c.sha256()));}ItemStack cursor=p.inventoryMenu.getCarried();if(!cursor.isEmpty())items.add(stack(p,cursor,role+"/cursor"));CardData.read(cursor).ifPresent(c->ids.put(c.instanceId(),c.sha256()));}
        ServerPlayer host=player(server,"host");for(BlockPos pos:List.of(new BlockPos(8,90,20),new BlockPos(14,90,20),new BlockPos(12,90,6)))if(server.overworld().getBlockEntity(pos)instanceof DeviceEntity device){desks.add(ConsoleServer.view(device));ItemStack media=device.media();if(!media.isEmpty())items.add(stack(host,media,"device/"+pos.toShortString()));CardData.read(media).ifPresent(c->ids.put(c.instanceId(),c.sha256()));}
        Path root=server.getWorldPath(LevelResource.ROOT).resolve("muxi-console").toAbsolutePath().normalize();out.addProperty("worldStorage",root.toString());
        for(var e:ids.entrySet()){JsonObject row=new JsonObject();row.addProperty("card",e.getKey().toString());Path content=root.resolve("content").resolve(e.getValue().substring(0,2)).resolve(e.getValue()+".rom");row.addProperty("contentPath",content.toString());row.addProperty("contentBytes",Files.size(content));row.addProperty("contentSha256",Hashing.sha256(content));disk.add(row);}out.add("machineStates",MachineScene.saved(server,true));out.addProperty("storageScope","machine+content");
        JsonArray monitors=new JsonArray();for(BlockPos pos:List.of(new BlockPos(12,90,8),new BlockPos(16,90,6)))if(server.overworld().getBlockEntity(pos)instanceof DeviceEntity screen&&screen.kind()==DeviceBlock.Kind.SCREEN){JsonObject row=ConsoleServer.view(screen);row.addProperty("blockPosition",pos.toShortString());row.addProperty("source",screen.linkedConsole==null?"":screen.linkedConsole.toShortString());row.addProperty("mediaEmpty",screen.media().isEmpty());monitors.add(row);}
        out.add("items",items);out.add("disk",disk);out.add("devices",desks);out.add("monitors",monitors);return out;
    }catch(Exception e){throw new IllegalStateException(e);}}
}
