package net.muxigame.consoleqa;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.muxigame.console.mc.CartridgeCaseMenu;
import net.muxigame.console.mc.ConsoleContent;

/** Native menu input and read-only stack observations. Never changes product containers directly. */
final class CaseActions {
    static void command(Minecraft client, JsonObject input) {
        if (client.player == null || client.gameMode == null) throw new IllegalStateException("Real joined player required");
        switch (input.get("action").getAsString()) {
            case "case_use" -> {
                if (client.screen != null) throw new IllegalStateException("Release the actual screen before using the held case");
                client.gameMode.useItem(client.player, InteractionHand.valueOf(input.has("hand") ? input.get("hand").getAsString() : "MAIN_HAND"));
            }
            case "case_click", "case_inventory_click" -> {
                boolean inventory = input.get("action").getAsString().equals("case_inventory_click");
                if (inventory ? !(client.screen instanceof InventoryScreen) || client.player.containerMenu != client.player.inventoryMenu
                        : !(client.player.containerMenu instanceof CartridgeCaseMenu)) throw new IllegalStateException("Requested actual menu is not open");
                ClickType type = ClickType.valueOf(input.has("type") ? input.get("type").getAsString() : "PICKUP");
                if (type == ClickType.CLONE || type == ClickType.THROW) throw new IllegalArgumentException("No cloning or throwing in the case review");
                int slot = input.get("slot").getAsInt(), button = input.has("button") ? input.get("button").getAsInt() : 0;
                if (inventory) slot = inventorySlot(slot);
                if (slot != -999 && (slot < 0 || slot >= client.player.containerMenu.slots.size())) throw new IllegalArgumentException("Actual menu slot required");
                if (type == ClickType.SWAP && !(button >= 0 && button <= 8 || button == 40)) throw new IllegalArgumentException("Real number-key or offhand swap only");
                client.gameMode.handleInventoryMouseClick(client.player.containerMenu.containerId, slot, button, type, client.player);
            }
            default -> throw new IllegalArgumentException("Unsupported case client action");
        }
    }

    private static int inventorySlot(int slot) {
        if (slot >= 0 && slot <= 8) return slot + 36;
        if (slot >= 9 && slot <= 35) return slot;
        if (slot == 40) return 45;
        throw new IllegalArgumentException("Actual player inventory slot required");
    }

    static JsonObject stack(Minecraft client, ItemStack item) {
        JsonObject row = CartridgeActions.stack(client, item);
        if (!item.isEmpty() && item.is(ConsoleContent.CASE_ITEM.get())) {
            JsonArray contents = new JsonArray();
            var container = item.get(DataComponents.CONTAINER);
            if (container != null) {
                var copies = container.nonEmptyItemsCopy();
                for (ItemStack entry : copies) contents.add(CartridgeActions.stack(client, entry));
                row.addProperty("storedSlots", container.getSlots());
            } else row.addProperty("storedSlots", 0);
            row.add("contents", contents);
        }
        return row;
    }

    static JsonObject observe(Minecraft client) {
        JsonObject out = new JsonObject();
        if (client.player == null || client.level == null) return out;
        out.addProperty("menuId", client.player.containerMenu.containerId);
        out.addProperty("caseMenu", client.player.containerMenu instanceof CartridgeCaseMenu);
        if (client.player.containerMenu instanceof CartridgeCaseMenu menu) out.addProperty("sourceSlot", menu.sourceSlot());
        out.add("cursor", stack(client, client.player.containerMenu.getCarried()));
        JsonArray items = new JsonArray(), slots = new JsonArray();
        for (int i = 0; i < client.player.getInventory().getContainerSize(); i++) {
            JsonObject row = stack(client, client.player.getInventory().getItem(i)); row.addProperty("slot", i); items.add(row);
        }
        for (int i = 0; i < client.player.containerMenu.slots.size(); i++) {
            JsonObject row = stack(client, client.player.containerMenu.getSlot(i).getItem()); row.addProperty("slot", i); slots.add(row);
        }
        out.add("items", items); out.add("slots", slots); return out;
    }
}
