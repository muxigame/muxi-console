package net.muxigame.consoleqa;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.muxigame.console.mc.CardData;
import net.muxigame.console.mc.ConsoleContent;
import net.muxigame.console.mc.ConsoleServer;
import net.muxigame.console.mc.DeviceEntity;
import net.muxigame.console.mc.DeviceBlock;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Scene/inventory setup only. It never creates a cartridge or invokes an upload on behalf of a client. */
@Mod(ServerQaMod.ID)
public final class ServerQaMod {
    public static final String ID = "muxi_console_qa_server";
    private final QaOwner owner = new QaOwner(ID, "server");
    private final ReviewScene review = new ReviewScene(owner);
    private final DockScene dockReview=new DockScene(owner);
    private final PixelScene pixelReview=new PixelScene(owner);
    private final PolishScene polishReview=new PolishScene();
    private final CartridgeScene cartridgeReview=new CartridgeScene(owner);
    private final MachineScene machineReview=new MachineScene(owner);
    private final CaseScene caseReview=new CaseScene(owner);
    private final UpgradeScene upgradeReview=new UpgradeScene(owner);
    private final ExpansionScene expansionReview=new ExpansionScene(owner);
    private final Set<UUID> prepared = new HashSet<>();
    private boolean built;
    private final java.util.ArrayDeque<JsonObject> roomHistory=new java.util.ArrayDeque<>();
    private String lastRooms="";

    public ServerQaMod() { NeoForge.EVENT_BUS.addListener(this::tick); }
    private void tick(ServerTickEvent.Post event) { owner.poll(command -> dispatch(event.getServer(), command)); sampleRooms(event.getServer()); }

    private JsonArray rooms(MinecraftServer server)throws java.io.IOException {
        JsonArray rows=new JsonArray();for(var room:ConsoleServer.state(server).rooms.values()){
            JsonObject row=new JsonObject();row.addProperty("key",room.key);row.addProperty("epoch",room.session.epoch());row.addProperty("frame",room.frame);row.addProperty("card",room.card.instanceId().toString());row.addProperty("host",room.session.host().toString());row.addProperty("ready",room.ready.size());row.addProperty("controllers",room.session.controllers().size());row.addProperty("fingerprint",room.fingerprint);row.addProperty("pendingJoin",room.pendingJoin==null?"":room.pendingJoin.toString());row.addProperty("pendingLeave",room.pendingLeave==null?"":room.pendingLeave.toString());JsonArray leaves=new JsonArray();room.requestedLeaves.forEach(id->leaves.add(id.toString()));row.add("requestedLeaves",leaves);rows.add(row);
        }return rows;
    }
    private void sampleRooms(MinecraftServer server){try{JsonArray current=rooms(server);String key=current.toString();if(!key.equals(lastRooms)){lastRooms=key;JsonObject sample=new JsonObject();sample.addProperty("serverTick",server.getTickCount());sample.add("rooms",current);roomHistory.add(sample);while(roomHistory.size()>128)roomHistory.removeFirst();}}catch(java.io.IOException error){throw new IllegalStateException(error);}}

    private JsonObject dispatch(MinecraftServer server, JsonObject command) {
        if(command.get("action").getAsString().startsWith("expansion_"))return expansionReview.command(server,command);
        if(command.get("action").getAsString().startsWith("linkqa_"))return LinkScene.command(server,command,owner);
        if(command.get("action").getAsString().startsWith("linkds_"))return LinkDsScene.command(server,command,owner);
        if(command.get("action").getAsString().startsWith("link3ds_"))return Link3dsScene.command(server,command,owner);
        if(command.get("action").getAsString().startsWith("upgrade_"))return upgradeReview.command(server,command);
        if(command.get("action").getAsString().startsWith("case_"))return caseReview.command(server,command);
        if(command.get("action").getAsString().startsWith("machine_"))return machineReview.command(server,command);
        if(command.get("action").getAsString().startsWith("cartridge_"))return cartridgeReview.command(server,command);
        if(command.get("action").getAsString().startsWith("polish_"))return polishReview.command(server,command);
        if(command.get("action").getAsString().equals("pixel_live_prepare")){JsonObject setup=new JsonObject();setup.addProperty("action","dock_prepare");dockReview.command(server,setup);return pixelReview.prepareLive(server);}
        if(command.get("action").getAsString().startsWith("pixel_"))return pixelReview.command(server,command);
        if(command.get("action").getAsString().startsWith("dock_"))return dockReview.command(server,command);
        return switch (command.get("action").getAsString()) {
            case "prepare" -> prepare(server);
            case "observe" -> observe(server);
            case "position" -> position(server, command);
            case "adopt_scene" -> adopt(server);
            case "supply_controllers" -> supplyControllers(server);
            default -> review.command(server, command);
        };
    }
    private JsonObject supplyControllers(MinecraftServer server){try{if(!built||!ConsoleServer.state(server).rooms.isEmpty())throw new IllegalStateException("Only an idle adopted fixture may receive ordinary controllers");for(String role:new String[]{"host","guest"}){ServerPlayer p=server.getPlayerList().getPlayerByName(owner.playerName(role));if(p==null||!p.getInventory().add(new ItemStack(ConsoleContent.CONTROLLER.get(),2)))throw new IllegalStateException("No room for ordinary fixture controllers");p.inventoryMenu.broadcastChanges();}return observe(server);}catch(java.io.IOException error){throw new IllegalStateException(error);}}
    private JsonObject adopt(MinecraftServer server){
        if(built||server.getPlayerList().getPlayerCount()!=2)throw new IllegalStateException("Only a newly launched two-real-client copied fixture may be adopted");
        try{JsonObject marker=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(owner.validatedRoot().resolve("local-mc-owner.json"))).getAsJsonObject();if(!marker.getAsJsonObject("releaseProfile").getAsJsonArray("fixtureOverrides").toString().contains("copied fixture world"))throw new IllegalStateException("Unified runner must declare a copied fixture world");
            for(int x:new int[]{8,10,12})if(!(server.overworld().getBlockEntity(new BlockPos(x,90,8)) instanceof DeviceEntity))throw new IllegalStateException("Existing physical console scene missing");
            for(String role:new String[]{"host","guest"}){ServerPlayer player=server.getPlayerList().getPlayerByName(owner.playerName(role));if(player==null)throw new IllegalStateException("Real copied-fixture player missing");prepared.add(player.getUUID());}
            JsonObject result=review.adopt(server);built=true;return result;
        }catch(java.io.IOException error){throw new IllegalStateException(error);}
    }

    private JsonObject prepare(MinecraftServer server) {
        var level = server.overworld();
        if (!built) {
            if (level.getGameTime() > 12000 || server.getPlayerList().getPlayerCount() != 2)
                throw new IllegalStateException("Scene setup requires a fresh two-real-player world");
            if (level.getBlockEntity(new BlockPos(8, 90, 8)) != null || level.getBlockEntity(new BlockPos(10, 90, 8)) != null)
                throw new IllegalStateException("Refusing to replace an existing device");
            for (int x = 3; x <= 17; x++) for (int z = 4; z <= 15; z++) {
                level.setBlock(new BlockPos(x, 89, z), x == 3 || x == 17 || z == 4 || z == 15 ? Blocks.DEEPSLATE_TILES.defaultBlockState() : Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                for (int y = 90; y <= 94; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
            level.setBlock(new BlockPos(8,90,8), ConsoleContent.WRITER.get().defaultBlockState().setValue(DeviceBlock.FACING, Direction.SOUTH), 3);
            level.setBlock(new BlockPos(10,90,8), ConsoleContent.DESK.get().defaultBlockState().setValue(DeviceBlock.FACING, Direction.SOUTH), 3);
            level.setBlock(new BlockPos(12,90,8), ConsoleContent.SCREEN.get().defaultBlockState().setValue(DeviceBlock.FACING, Direction.SOUTH), 3);
            level.setDefaultSpawnPos(new BlockPos(10,90,12), 180);
            level.setDayTime(6000); server.setDifficulty(Difficulty.PEACEFUL, true);
            built = true;
        }
        for (String role : new String[]{"host", "guest"}) {
            ServerPlayer player;
            try { player = server.getPlayerList().getPlayerByName(owner.playerName(role)); }
            catch (java.io.IOException error) { throw new IllegalStateException(error); }
            if (player == null) throw new IllegalStateException("The real runner player is missing: " + role);
            if (!prepared.add(player.getUUID())) continue;
            player.setGameMode(GameType.CREATIVE);
            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(ConsoleContent.BLANK.get(), 4));
            player.getInventory().setItem(1, new ItemStack(ConsoleContent.CONTROLLER.get(), 2));
            player.getInventory().setItem(2, new ItemStack(ConsoleContent.HANDHELD.get()));
            player.getInventory().selected = 8;
            player.connection.teleport(role.equals("host") ? 8.5 : 10.5, 90, 11.5, 180, 18);
            player.inventoryMenu.broadcastChanges();
        }
        JsonObject result = observe(server); result.addProperty("scenePrepared", true);
        result.addProperty("syntheticServerPlayers", false); result.addProperty("cartridgesMintedByQa", 0);
        return result;
    }

    private JsonObject position(MinecraftServer server, JsonObject command) {
        if (!built) throw new IllegalStateException("Prepare the isolated platform first");
        ServerPlayer player = server.getPlayerList().getPlayerByName(command.get("player").getAsString());
        double x = command.get("x").getAsDouble(), z = command.get("z").getAsDouble();
        if (player == null || !prepared.contains(player.getUUID()) || x < 3.5 || x > 16.5 || z < 4.5 || z > 14.5)
            throw new IllegalArgumentException("Fixture positioning is limited to its two real players and platform");
        player.connection.teleport(x, 90, z, 180, 18);
        return observe(server);
    }

    private JsonObject observe(MinecraftServer server) {
        JsonObject result = new JsonObject(); result.addProperty("serverTick", server.getTickCount());
        result.add("browser",review.browserObserve(server));
        result.add("hardware",review.hardwareObserve(server));
        result.add("shared",review.sharedObserve(server));
        result.add("dock",dockReview.observe(server));
        result.add("polish",polishReview.observe(server));result.add("qaChannel",owner.replyStatus());
        result.add("case",caseReview.observe(server));
        JsonArray players = new JsonArray();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            JsonObject row = new JsonObject(); row.addProperty("uuid", player.getUUID().toString()); row.addProperty("name", player.getGameProfile().getName());
            row.addProperty("x", player.getX()); row.addProperty("y", player.getY()); row.addProperty("z", player.getZ());
            JsonArray inventory = new JsonArray();
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack item = player.getInventory().getItem(slot); if (item.isEmpty()) continue;
                JsonObject entry = new JsonObject(); entry.addProperty("slot", slot); entry.addProperty("item", BuiltInRegistries.ITEM.getKey(item.getItem()).toString()); entry.addProperty("count", item.getCount());
                CardData.read(item).ifPresent(card -> { entry.addProperty("instance", card.instanceId().toString()); entry.addProperty("hash", card.sha256()); entry.addProperty("platform", card.platform().id()); });
                inventory.add(entry);
            }
            row.add("inventory", inventory); players.add(row);
        }
        result.add("players", players);
        JsonArray devices = new JsonArray();
        for (int x : new int[]{8,10,12}) if (server.overworld().getBlockEntity(new BlockPos(x,90,8)) instanceof DeviceEntity device)
            devices.add(ConsoleServer.view(device));
        result.add("devices", devices);
        try {
            var state = ConsoleServer.state(server); result.addProperty("contentBytes", state.content.usedBytes()); result.addProperty("pendingUploads", state.uploads.activeCount());
            result.addProperty("cloudStorageConfigured",state.cloud!=null);result.addProperty("localUploadServerRunning",state.localMedia!=null);
            result.add("rooms", rooms(server));JsonArray history=new JsonArray();roomHistory.forEach(history::add);result.add("roomHistory",history);
            JsonArray snapshots = new JsonArray(); Set<UUID> currentCards = new HashSet<>();
            for (int x : new int[]{8,10,12}) if (server.overworld().getBlockEntity(new BlockPos(x,90,8)) instanceof DeviceEntity device)
                CardData.read(device.card).ifPresent(card -> currentCards.add(card.instanceId()));
            for(int x:new int[]{8,14})if(server.overworld().getBlockEntity(new BlockPos(x,90,20)) instanceof DeviceEntity device)CardData.read(device.card).ifPresent(card->currentCards.add(card.instanceId()));
            for (var room : state.rooms.values()) currentCards.add(room.card.instanceId());
            for (ServerPlayer player : server.getPlayerList().getPlayers())
                for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
                    CardData.read(player.getInventory().getItem(slot)).ifPresent(card -> currentCards.add(card.instanceId()));
            result.add("states", MachineScene.saved(server,false));result.addProperty("storageScope","machine+content");
        } catch (Exception error) { throw new IllegalStateException(error); }
        return result;
    }
}
