package net.muxigame.consoleqa;

import com.google.gson.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.muxigame.console.domain.*;
import net.muxigame.console.mc.*;

/** Ordinary empty hardware fixture and read-only world vault metadata; never issues a card or progress. */
final class ExpansionScene {
    private final QaOwner owner;private boolean prepared;
    private final Map<Platform,UUID> observedMachines=new EnumMap<>(Platform.class);
    ExpansionScene(QaOwner owner){this.owner=owner;}
    JsonObject command(MinecraftServer server,JsonObject j){try{
        if(j.get("action").getAsString().equals("expansion_prepare")){
            ServerPlayer host=server.getPlayerList().getPlayerByName(owner.playerName("host"));
            if(prepared||host==null||server.getPlayerList().getPlayerCount()!=2||!ConsoleServer.state(server).rooms.isEmpty()||!host.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Fresh idle two-client fixture required");
            for(int slot:new int[]{9,10,11})if(!host.getInventory().getItem(slot).isEmpty())throw new IllegalStateException("Preflight all empty supply slots");
            if(!(server.overworld().getBlockEntity(new BlockPos(8,90,8))instanceof DeviceEntity)||!server.overworld().getBlockState(new BlockPos(5,90,6)).isAir())throw new IllegalStateException("Use the normally prepared original writer and empty fixture platform");
            host.getInventory().setItem(9,HardwareCatalog.variant(new ItemStack(ConsoleContent.HANDHELD.get()),23));host.getInventory().setItem(10,HardwareCatalog.variant(new ItemStack(ConsoleContent.HANDHELD.get()),24));host.getInventory().setItem(11,HardwareCatalog.variant(new ItemStack(ConsoleContent.DESK_ITEM.get()),25));
            for(String role:List.of("host","guest")){var player=server.getPlayerList().getPlayerByName(owner.playerName(role));if(player==null)throw new IllegalStateException("Actual player missing");player.setGameMode(GameType.SURVIVAL);player.inventoryMenu.broadcastChanges();}prepared=true;
        }else if(j.get("action").getAsString().equals("expansion_screen_supply")){
            ServerPlayer host=server.getPlayerList().getPlayerByName(owner.playerName("host"));
            if(!prepared||host==null||!host.getInventory().getItem(12).isEmpty())throw new IllegalStateException("Prepared fixture with an empty supply slot required");
            host.getInventory().setItem(12,new ItemStack(ConsoleContent.SCREEN_ITEM.get()));host.inventoryMenu.broadcastChanges();
        }else if(!j.get("action").getAsString().equals("expansion_observe"))throw new IllegalArgumentException("Unknown expansion scene action");
        return observe(server);
    }catch(Exception error){throw new IllegalStateException(error);}}
    JsonObject observe(MinecraftServer server){try{
        JsonObject out=new JsonObject();out.addProperty("prepared",prepared);
        for(var room:ConsoleServer.state(server).rooms.values())if(Set.of(Platform.NDS,Platform.N3DS,Platform.WII).contains(room.card.platform())){
            UUID prior=observedMachines.putIfAbsent(room.card.platform(),room.saveKey.machineUUID());if(prior!=null&&!prior.equals(room.saveKey.machineUUID()))throw new IllegalStateException("Unexpected second machine in a fixed expansion fixture");
        }
        JsonObject machines=new JsonObject();for(var entry:observedMachines.entrySet()){
            JsonObject row=new JsonObject();row.addProperty("machine",entry.getValue().toString());var key=new MachineProfileVault.Key(entry.getValue(),entry.getKey());var latest=ConsoleServer.state(server).profiles.latest(key);
            if(latest.isPresent()){var profile=latest.get();JsonObject p=new JsonObject();p.addProperty("generation",profile.generation().toString());p.addProperty("hash",profile.hash());p.addProperty("size",profile.size());p.addProperty("frame",profile.frame());row.add("profile",p);}machines.add(entry.getKey().id(),row);
        }out.add("machines",machines);var saved=MachineScene.saved(server,true);for(var value:saved){var row=value.getAsJsonObject();var latest=ConsoleServer.state(server).states.latest(new MachineSaveKey(UUID.fromString(row.get("machine").getAsString()),row.get("content").getAsString())).orElseThrow();if(latest.profileGeneration()!=null)row.addProperty("profileGeneration",latest.profileGeneration().toString());}out.add("saved",saved);
        var entity=server.overworld().getBlockEntity(new BlockPos(5,90,6));if(entity instanceof DeviceEntity device){out.add("wiiDevice",ConsoleServer.view(device));var tag=device.getUpdateTag(server.registryAccess());if(tag.hasUUID("machine_identity"))out.addProperty("wiiMachine",tag.getUUID("machine_identity").toString());}
        JsonArray screens=new JsonArray();for(BlockPos pos:BlockPos.betweenClosed(new BlockPos(0,89,0),new BlockPos(12,93,12)))if(server.overworld().getBlockEntity(pos)instanceof DeviceEntity screen&&screen.kind()==DeviceBlock.Kind.SCREEN){JsonObject row=new JsonObject();row.addProperty("position",pos.toShortString());row.addProperty("source",screen.linkedConsole==null?"":screen.linkedConsole.toShortString());screens.add(row);}out.add("screens",screens);
        return out;
    }catch(Exception error){throw new IllegalStateException(error);}}
}
