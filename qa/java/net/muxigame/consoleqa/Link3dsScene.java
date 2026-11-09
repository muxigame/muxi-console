package net.muxigame.consoleqa;

import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.*;
import net.muxigame.console.domain.*;
import net.muxigame.console.mc.*;

/** Cyan dye is the only supplied fixture. Every machine, ROM and saved file is produced through the normal product. */
final class Link3dsScene {
    private static JsonObject words(byte[] bytes,boolean relocation){ByteBuffer in=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);JsonObject row=new JsonObject();String[] keys=relocation?new String[]{"magic","writes","role"}:new String[]{"magic","role","stage","frame","sent","received","peerRole","peerSequence","peerChallenge","result","localNode","totalNodes"};for(String key:keys)row.addProperty(key,Integer.toUnsignedLong(in.getInt()));row.addProperty("sha256",Hashing.sha256(bytes));return row;}
    static JsonObject command(MinecraftServer server,JsonObject j,QaOwner owner){try{
        String action=j.get("action").getAsString();var world=ConsoleServer.state(server);
        if(action.equals("link3ds_fixture")){
            if(!world.rooms.isEmpty()||server.getPlayerList().getPlayerCount()!=2)throw new IllegalStateException("Fresh idle original pair required");
            for(String role:List.of("host","guest")){var p=server.getPlayerList().getPlayerByName(owner.playerName(role));if(p==null||!p.containerMenu.getCarried().isEmpty()||LinkPlayServer.isActorLocked(world,p.getUUID()))throw new IllegalStateException("Original idle player required");var core=p.getInventory().getItem(2);if(!core.is(ConsoleContent.HANDHELD.get())||HardwareCatalog.model(core)!=0||MachineData.read(core).isPresent()||CardData.hasInserted(core)||!p.getInventory().getItem(9).isEmpty())throw new IllegalStateException("Only original empty core and dye slot eligible");}
            for(String role:List.of("host","guest")){var p=server.getPlayerList().getPlayerByName(owner.playerName(role));p.getInventory().setItem(9,new ItemStack(Items.CYAN_DYE));p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);p.inventoryMenu.broadcastChanges();}
        }else if(!action.equals("link3ds_observe"))throw new IllegalArgumentException("Known 3DS fixture/read action required");
        JsonObject out=new JsonObject();JsonArray saved=MachineScene.saved(server,true);for(var value:saved){var row=value.getAsJsonObject();UUID machine=UUID.fromString(row.get("machine").getAsString());var key=new MachineSaveKey(machine,row.get("content").getAsString());var actual=world.states.latest(key).orElseThrow();row.addProperty("expectedFingerprint",LinkCoreIdentity.stateFingerprint("n3ds",machine));if(actual.profileGeneration()!=null)row.addProperty("profileGeneration",actual.profileGeneration().toString());}out.add("saved",saved);JsonArray profiles=new JsonArray();
        for(String role:List.of("host","guest")){var player=server.getPlayerList().getPlayerByName(owner.playerName(role));if(player==null)continue;var card=CardData.read(player.getMainHandItem());var machine=MachineData.read(player.getMainHandItem());if(card.isEmpty()||machine.isEmpty()||card.get().platform()!=Platform.N3DS)continue;var key=new MachineProfileVault.Key(machine.get(),Platform.N3DS);var profile=world.profiles.latest(key);if(profile.isEmpty())continue;var p=profile.get();JsonObject row=new Gson().toJsonTree(p).getAsJsonObject();row.addProperty("machine",machine.get().toString());if(p.size()>32L*1024*1024)throw new IOException("Original diagnostic profile exceeds fixture bound");ByteArrayOutputStream bytes=new ByteArrayOutputStream();for(long offset=0;offset<p.size();){byte[] block=world.profiles.read(key,p.generation(),offset,24576);if(block.length<1)throw new IOException("Profile read truncated");bytes.write(block);offset+=block.length;}byte[] archive=bytes.toByteArray();if(archive.length!=p.size()||!Hashing.sha256(archive).equals(p.hash()))throw new IOException("Actual native archive checksum differs");row.addProperty("readBytes",archive.length);row.addProperty("readSha256",Hashing.sha256(archive));long expanded=0;
            try(var zip=new ZipInputStream(new ByteArrayInputStream(archive))){for(ZipEntry entry;(entry=zip.getNextEntry())!=null;){boolean radio=entry.getName().equals("Azahar/sdmc/muxi-original-uds-evidence.bin"),writes=entry.getName().equals("Azahar/sdmc/muxi-original-relocation.bin");ByteArrayOutputStream record=new ByteArrayOutputStream();byte[] block=new byte[65536];for(int n;(n=zip.read(block))!=-1;){expanded+=n;if(expanded>32L*1024*1024)throw new IOException("Expanded diagnostic profile exceeds fixture bound");if(radio||writes){if(record.size()+n>(writes?12:48))throw new IOException("Original diagnostic record differs");record.write(block,0,n);}}if(radio||writes){byte[] raw=record.toByteArray();if(raw.length!=(writes?12:48))throw new IOException("Captured SD diagnostic was incomplete");row.add(writes?"save":"radio",words(raw,writes));}}}profiles.add(row);
        }out.add("profiles",profiles);Path nds=MachineScene.world(server).resolve("link/nds");long count=0;if(Files.isDirectory(nds))try(var walk=Files.walk(nds)){count=walk.filter(p->Files.isRegularFile(p)&&p.getFileName().toString().endsWith(".state")).count();}out.addProperty("ndsGroupStateFiles",count);out.addProperty("queueCheckpoint",false);return out;
    }catch(Exception failure){throw new IllegalStateException(failure);}}
    private Link3dsScene(){}
}
