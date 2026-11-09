package net.muxigame.consoleqa;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.muxigame.console.domain.*;
import net.muxigame.console.mc.*;

/** Ordinary guest dye fixture and read-only actual machine exports/barrier. Never writes identities or states. */
final class LinkScene {
    static JsonObject command(MinecraftServer server,JsonObject j,QaOwner owner){try{
        if(j.get("action").getAsString().equals("linkqa_fixture")){
            boolean gba=j.has("platform")&&j.get("platform").getAsString().equals("gba");
            if(j.has("platform")&&!Set.of("gba","gb").contains(j.get("platform").getAsString()))throw new IllegalArgumentException("Known original serial diagnostic platform required");
            if(!ConsoleServer.state(server).rooms.isEmpty())throw new IllegalStateException("Actual idle players required");
            var roles=gba?List.of("host","guest"):List.of("guest");
            for(String role:roles){var player=server.getPlayerList().getPlayerByName(owner.playerName(role));if(player==null||!player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Actual idle fixture player required");var core=player.getInventory().getItem(2);if(!core.is(ConsoleContent.HANDHELD.get())||HardwareCatalog.model(core)!=0||MachineData.read(core).isPresent()||CardData.hasInserted(core)||!player.getInventory().getItem(9).isEmpty())throw new IllegalStateException("Only the original empty core and empty dye destination are allowed");}
            for(String role:roles){var player=server.getPlayerList().getPlayerByName(owner.playerName(role));player.getInventory().setItem(9,new ItemStack(gba?Items.PURPLE_DYE:Items.WHITE_DYE));player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);player.inventoryMenu.broadcastChanges();}
        }else if(!j.get("action").getAsString().equals("linkqa_observe"))throw new IllegalArgumentException("Known link fixture/read only action required");
        return observe(server,owner);
    }catch(Exception failure){throw new IllegalStateException(failure);}}
    private static JsonArray sram(byte[] state)throws IOException{
        if(state.length<16||state.length>LinkStateStore.MAX_BYTES)throw new IOException("Bounded actual GB state required");var in=new DataInputStream(new ByteArrayInputStream(state));if(in.readInt()!=0x4d585331)throw new IOException("Actual uncompressed cartridge MXS1 export required");
        int core=in.readInt();if(core<1||core>2*1024*1024||core>in.available())throw new IOException("Invalid actual core state field");in.skipNBytes(core);int count=in.readInt();if(count<4||count>512*1024||count>in.available())throw new IOException("Expected actual cartridge SRAM");JsonArray result=new JsonArray();for(int n=0;n<4;n++)result.add(in.readUnsignedByte());return result;
    }
    static JsonObject observe(MinecraftServer server,QaOwner owner)throws Exception{
        var world=ConsoleServer.state(server);JsonObject out=new JsonObject();out.addProperty("guestDye",9);out.add("saved",MachineScene.saved(server,true));JsonArray exports=new JsonArray();
        for(JsonElement value:out.getAsJsonArray("saved")){JsonObject row=value.getAsJsonObject();var key=new MachineSaveKey(UUID.fromString(row.get("machine").getAsString()),row.get("content").getAsString());var saved=world.states.latest(key).orElseThrow();if(saved.size()>LinkStateStore.MAX_BYTES)continue;
            try{ByteArrayOutputStream bytes=new ByteArrayOutputStream((int)saved.size());for(long offset=0;offset<saved.size();){byte[] piece=world.states.read(key,saved.generationID(),offset,24576);if(piece.length<1)throw new IOException("Truncated actual vault state");bytes.write(piece);offset+=piece.length;}
                byte[] state=bytes.toByteArray();if(!Hashing.sha256(state).equals(saved.hash()))throw new IOException("Actual vault payload SHA differs");JsonObject record=row.deepCopy();record.add("sram",sram(state));exports.add(record);
            }catch(IOException notGb){/* Non-MXS1 or unsupported payload remains in saved but cannot prove SRAM bytes. */}
        }out.add("exports",exports);
        List<LinkLobby.Endpoint> endpoints=new ArrayList<>();for(String role:List.of("host","guest")){var player=server.getPlayerList().getPlayerByName(owner.playerName(role));if(player==null)continue;var item=player.getMainHandItem();var card=CardData.read(item);var machine=MachineData.read(item);if(card.isPresent()&&machine.isPresent()&&Set.of(Platform.GB,Platform.GBC,Platform.GBA).contains(card.get().platform()))endpoints.add(new LinkLobby.Endpoint(player.getUUID(),machine.get(),card.get().instanceId(),card.get().sha256(),card.get().platform().id()));}
        endpoints.sort(Comparator.comparing(e->e.machine().toString()));Path root=MachineScene.world(server).resolve("link");
        if(endpoints.size()==2&&Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)){var group=new LinkStateStore(root).latest(endpoints,LinkCoreIdentity.bundled(endpoints.getFirst().platform()).bridge(),world.states);if(group.isPresent()){JsonObject barrier=new JsonObject();barrier.addProperty("hash",group.get().hash());barrier.addProperty("frame",group.get().frame());JsonArray keys=new JsonArray();for(var endpoint:endpoints){JsonObject key=new JsonObject();key.addProperty("machine",endpoint.machine().toString());key.addProperty("content",endpoint.content());keys.add(key);}barrier.add("keys",keys);out.add("group",barrier);}}
        return out;
    }
}
