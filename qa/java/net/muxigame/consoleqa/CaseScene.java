package net.muxigame.consoleqa;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.muxigame.console.mc.*;

/** Empty ordinary fixtures only; all recorded cartridges must come from the real writer. */
final class CaseScene {
    static final BlockPos POSITION = new BlockPos(22, 90, 22);
    private final QaOwner owner;
    private boolean prepared;
    private final JsonObject fixtureSlots = new JsonObject();
    private final JsonObject iconFixtures = new JsonObject();
    private boolean iconFixturesGiven;
    CaseScene(QaOwner owner) { this.owner = owner; }
    private ServerPlayer host(MinecraftServer server) throws Exception {
        ServerPlayer player = server.getPlayerList().getPlayerByName(owner.playerName("host"));
        if (player == null) throw new IllegalStateException("Real fixture host missing"); return player;
    }
    JsonObject command(MinecraftServer server, JsonObject input) {
        try {
            String action = input.get("action").getAsString();
            if (action.equals("case_existing")) {
                if (prepared || server.getPlayerList().getPlayerCount() != 2 || !ConsoleServer.state(server).rooms.isEmpty()) throw new IllegalStateException("Only an idle copied two-player case fixture may be adopted");
                if (server.getPlayerList().getPlayerByName(owner.playerName("guest")) == null) throw new IllegalStateException("Real fixture guest missing");
                ServerPlayer player = host(server);
                if (player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL || !player.containerMenu.getCarried().isEmpty()) throw new IllegalStateException("Existing survival player with empty real cursor required");
                if (!server.overworld().getBlockState(POSITION).isAir() || server.overworld().getBlockState(POSITION.below()).isAir()) throw new IllegalStateException("Original empty case plate with existing support required");
                int found = -1;
                for (int slot = 0; slot < 36; slot++) {
                    ItemStack item = player.getInventory().getItem(slot);
                    if (!item.is(ConsoleContent.CASE_ITEM.get()) || !item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getString("qa_case_sentinel").equals("original-empty-case")) continue;
                    if (found >= 0 || item.getCount() != 1) throw new IllegalStateException("Exactly one original complete fixture case is required");
                    found = slot;
                }
                if (found < 0) throw new IllegalStateException("Original case is absent from the actual host inventory");
                fixtureSlots.addProperty("case", found); prepared = true;
            } else if (action.equals("case_icon_fixtures")) {
                if (!prepared || iconFixturesGiven || server.getPlayerList().getPlayerCount() != 2 || server.getPlayerList().getPlayerByName(owner.playerName("guest")) == null || !ConsoleServer.state(server).rooms.isEmpty()) throw new IllegalStateException("Adopted idle two-player fixture required once for ordinary icon devices");
                ServerPlayer player = host(server);
                if (player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL || !player.containerMenu.getCarried().isEmpty()) throw new IllegalStateException("Existing survival player and empty real cursor required");
                int originals = 0;
                for (ItemStack item : player.getInventory().items) if (item.is(ConsoleContent.CASE_ITEM.get()) && item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getString("qa_case_sentinel").equals("original-empty-case")) { if (item.getCount() != 1) throw new IllegalStateException("Original case count changed"); originals++; }
                if (originals != 1) throw new IllegalStateException("Exactly one original case required before supplying icon-only ordinary devices");
                List<ItemStack> additions = List.of(new ItemStack(ConsoleContent.WRITER_ITEM.get()), new ItemStack(ConsoleContent.DOCK_ITEM.get()));
                String[] names = {"writer", "dock"}; int[] destinations = {-1, -1}; boolean[] added = {true, true};
                for (int i = 0; i < additions.size(); i++) {
                    for (int slot = 0; slot < 36; slot++) if (player.getInventory().getItem(slot).is(additions.get(i).getItem())) { destinations[i] = slot; added[i] = false; break; }
                    if (destinations[i] >= 0) continue;
                    for (int slot = 9; slot < 36; slot++) if (player.getInventory().getItem(slot).isEmpty() && (i == 0 || destinations[0] != slot)) { destinations[i] = slot; break; }
                    if (destinations[i] < 0) throw new IllegalStateException("Not enough empty main inventory slots for ordinary icon devices");
                }
                // Both destinations were checked before any actual item is supplied.
                for (int i = 0; i < additions.size(); i++) {
                    if (added[i]) player.getInventory().setItem(destinations[i], additions.get(i));
                    JsonObject row = stack(player, player.getInventory().getItem(destinations[i])); row.addProperty("slot", destinations[i]); row.addProperty("given", added[i]); iconFixtures.add(names[i], row);
                }
                iconFixturesGiven = true; player.inventoryMenu.broadcastChanges();
            } else if (action.equals("case_camera")) {
                if (!prepared) throw new IllegalStateException("Adopt the actual existing case first");
                double x = input.get("x").getAsDouble(), y = input.has("y") ? input.get("y").getAsDouble() : 90, z = input.get("z").getAsDouble();
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || x < 3 || x > 25 || y < 90 || y > 94 || z < 4 || z > 25) throw new IllegalArgumentException("Camera outside isolated original platform");
                host(server).connection.teleport(x, y, z, input.get("yaw").getAsFloat(), input.get("pitch").getAsFloat());
            } else if (action.equals("case_prepare")) {
                if (prepared || server.overworld().getGameTime() > 12000 || server.getPlayerList().getPlayerCount() != 2
                        || !ConsoleServer.state(server).rooms.isEmpty()) throw new IllegalStateException("Fresh idle two-player fixture required");
                if (!server.overworld().getBlockState(POSITION).isAir() || server.overworld().getBlockState(POSITION.below()).isAir()) throw new IllegalStateException("Prepare the isolated dock platform first; case position must be empty");
                ServerPlayer player = host(server);
                if (!player.containerMenu.getCarried().isEmpty()) throw new IllegalStateException("Empty real cursor required");
                for (ItemStack item : player.getInventory().items) if (CardData.read(item).isPresent()) throw new IllegalStateException("Prepare empty fixtures before issuing real cards");
                ItemStack box = new ItemStack(ConsoleContent.CASE_ITEM.get());
                CompoundTag marker = new CompoundTag(); marker.putString("qa_case_sentinel", "original-empty-case"); marker.putIntArray("values", new int[]{7, 31, 1024, -9});
                box.set(DataComponents.CUSTOM_DATA, CustomData.of(marker)); box.set(DataComponents.CUSTOM_NAME, Component.literal("QA卡带收纳盒"));
                give(player, "case", box, true);
                give(player, "spareCase", new ItemStack(ConsoleContent.CASE_ITEM.get()), false);
                give(player, "blank", new ItemStack(ConsoleContent.BLANK.get(), 64), false);
                give(player, "stone", new ItemStack(Items.STONE, 8), false);
                give(player, "handheld", HardwareCatalog.variant(new ItemStack(ConsoleContent.HANDHELD.get()), 5), false);
                player.setGameMode(GameType.SURVIVAL); player.getAbilities().flying = false; player.onUpdateAbilities();
                player.inventoryMenu.broadcastChanges(); prepared = true;
            } else if (!action.equals("case_observe") || !prepared) throw new IllegalArgumentException("Prepare or adopt the case fixture before observing");
            return observe(server);
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private void give(ServerPlayer player, String key, ItemStack item, boolean hotbar) {
        int first = hotbar ? 0 : 9, end = hotbar ? 9 : 36;
        for (int slot = first; slot < end; slot++) if (player.getInventory().getItem(slot).isEmpty()) {
            player.getInventory().setItem(slot, item); fixtureSlots.addProperty(key, slot); return;
        }
        throw new IllegalStateException("No empty ordinary fixture inventory slot for " + key);
    }
    private static JsonObject stack(ServerPlayer player, ItemStack item) {
        JsonObject row = new JsonObject(); row.addProperty("empty", item.isEmpty());
        if (item.isEmpty()) return row;
        row.addProperty("item", BuiltInRegistries.ITEM.getKey(item.getItem()).toString()); row.addProperty("count", item.getCount());
        row.addProperty("nbt", item.saveOptional(player.registryAccess()).toString());
        row.addProperty("data", item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().toString());
        CardData.read(item).ifPresent(card -> { row.addProperty("instance", card.instanceId().toString()); row.addProperty("hash", card.sha256()); row.addProperty("platform", card.platform().id()); });
        if (item.is(ConsoleContent.CASE_ITEM.get())) {
            JsonArray contents = new JsonArray(); var container = item.get(DataComponents.CONTAINER);
            if (container != null) for (ItemStack entry : container.nonEmptyItemsCopy()) contents.add(stack(player, entry));
            row.add("contents", contents);
        }
        return row;
    }
    JsonObject observe(MinecraftServer server) {
        JsonObject out = new JsonObject(); out.addProperty("prepared", prepared); if (!prepared) return out;
        try {
            ServerPlayer player = host(server); out.add("fixtureSlots", fixtureSlots.deepCopy());
            out.add("iconFixtures", iconFixtures.deepCopy());
            out.addProperty("block", BuiltInRegistries.BLOCK.getKey(server.overworld().getBlockState(POSITION).getBlock()).toString());
            out.addProperty("serverTick", server.getTickCount());
            JsonArray boxes = new JsonArray(), drops = new JsonArray();
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack item = player.getInventory().getItem(slot); if (!item.is(ConsoleContent.CASE_ITEM.get())) continue;
                JsonObject row = stack(player, item); row.addProperty("location", "host/" + slot); boxes.add(row);
            }
            if (server.overworld().getBlockEntity(POSITION) instanceof CartridgeCaseEntity box) {
                out.add("placed", stack(player, box.packed()));
                JsonArray contents = new JsonArray(); for (int i = 0; i < box.getContainerSize(); i++) { JsonObject row = stack(player, box.getItem(i)); row.addProperty("slot", i); contents.add(row); }
                out.add("contents", contents); out.addProperty("facing", box.getBlockState().getValue(CartridgeCaseBlock.FACING).getName());
                out.addProperty("blockNbt", box.saveWithFullMetadata(server.overworld().registryAccess()).toString());
            }
            for (ItemEntity entity : server.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(20, 89, 20, 25, 94, 25))) {
                JsonObject row = stack(player, entity.getItem()); row.addProperty("entity", entity.getUUID().toString()); row.addProperty("x", entity.getX()); row.addProperty("y", entity.getY()); row.addProperty("z", entity.getZ()); drops.add(row);
            }
            out.add("boxes", boxes); out.add("drops", drops); return out;
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
