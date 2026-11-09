package net.muxigame.consoleqa;

import com.google.gson.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.muxigame.console.domain.*;
import net.muxigame.console.mc.*;

/** Ordinary black dye supplies only. Group evidence comes from the actual world's committed vaults. */
final class LinkDsScene {
    static JsonObject command(MinecraftServer server,JsonObject j,QaOwner owner){try{
        String action=j.get("action").getAsString();var world=ConsoleServer.state(server);
        if(action.equals("linkds_fixture")){
            if(!world.rooms.isEmpty()||server.getPlayerList().getPlayerCount()!=2)throw new IllegalStateException("Fresh idle actual pair required");
            for(String role:List.of("host","guest")){var p=server.getPlayerList().getPlayerByName(owner.playerName(role));if(p==null||!p.containerMenu.getCarried().isEmpty()||LinkPlayServer.isActorLocked(world,p.getUUID()))throw new IllegalStateException("Idle original player required");var core=p.getInventory().getItem(2);if(!core.is(ConsoleContent.HANDHELD.get())||HardwareCatalog.model(core)!=0||MachineData.read(core).isPresent()||CardData.hasInserted(core)||!p.getInventory().getItem(9).isEmpty())throw new IllegalStateException("Only an original empty core and empty dye slot are eligible");}
            for(String role:List.of("host","guest")){var p=server.getPlayerList().getPlayerByName(owner.playerName(role));p.getInventory().setItem(9,new ItemStack(Items.BLACK_DYE));p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);p.inventoryMenu.broadcastChanges();}
        }else if(!Set.of("linkds_observe","linkds_verify_group").contains(action))throw new IllegalArgumentException("Known DS fixture/read action required");
        JsonObject out=new JsonObject();out.add("saved",MachineScene.saved(server,true));for(var value:out.getAsJsonArray("saved")){var row=value.getAsJsonObject();row.addProperty("expectedFingerprint",LinkCoreIdentity.stateFingerprint("nds",UUID.fromString(row.get("machine").getAsString())));}List<LinkLobby.Endpoint> endpoints=new ArrayList<>();JsonArray profiles=new JsonArray();
        for(String role:List.of("host","guest")){var p=server.getPlayerList().getPlayerByName(owner.playerName(role));if(p==null)continue;var item=p.getMainHandItem();var card=CardData.read(item);var machine=MachineData.read(item);if(card.isEmpty()||machine.isEmpty()||card.get().platform()!=Platform.NDS)continue;endpoints.add(new LinkLobby.Endpoint(p.getUUID(),machine.get(),card.get().instanceId(),card.get().sha256(),"nds"));var profile=world.profiles.latest(new MachineProfileVault.Key(machine.get(),Platform.NDS));if(profile.isPresent()){var row=new JsonObject();row.addProperty("machine",machine.get().toString());row.addProperty("generation",profile.get().generation().toString());row.addProperty("hash",profile.get().hash());row.addProperty("size",profile.get().size());profiles.add(row);}}
        out.add("profiles",profiles);if(endpoints.size()==2){var key=new LinkDsGroupVault.Key(endpoints.stream().map(LinkLobby.Endpoint::key).toList(),LinkCoreIdentity.bundled("nds").bridge());var group=world.dsLinks.latest(key,world.states,world.profiles);if(group.isPresent()){var stored=group.get();JsonObject row=new Gson().toJsonTree(stored).getAsJsonObject();
            if(action.equals("linkds_verify_group")){var digest=MessageDigest.getInstance("SHA-256");long offset=0;int magic=0;while(offset<stored.size()){byte[] bytes=world.dsLinks.read(key,stored.generation(),offset,24576);if(bytes.length<1)throw new IOException("Real group read truncated");if(offset==0){if(bytes.length<4)throw new IOException("Group header missing");magic=(bytes[0]&255)<<24|(bytes[1]&255)<<16|(bytes[2]&255)<<8|(bytes[3]&255);}digest.update(bytes);offset+=bytes.length;}row.addProperty("readBytes",offset);row.addProperty("readSha256",HexFormat.of().formatHex(digest.digest()));row.addProperty("magic",Integer.toHexString(magic));}
            out.add("group",row);}}
        return out;
    }catch(Exception failure){throw new IllegalStateException(failure);}}
    private LinkDsScene(){}
}
