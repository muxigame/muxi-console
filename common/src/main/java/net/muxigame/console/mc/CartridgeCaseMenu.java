package net.muxigame.console.mc;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Vanilla chest layout with server-side media restrictions and a locked portable source. */
public final class CartridgeCaseMenu extends ChestMenu {
    public static final int CAPACITY = 27;
    private final int sourceSlot;
    public int sourceSlot() { return sourceSlot; }

    public CartridgeCaseMenu(int id, Inventory inventory, int sourceSlot) {
        this(id, inventory, new SimpleContainer(CAPACITY), sourceSlot);
    }

    public CartridgeCaseMenu(int id, Inventory inventory, Container contents, int sourceSlot) {
        super(ConsoleContent.CASE_MENU.get(), id, inventory, contents, 3);
        if (sourceSlot != -1 && sourceSlot != 40 && (sourceSlot < 0 || sourceSlot > 35))
            throw new IllegalArgumentException("Invalid cartridge case source slot");
        this.sourceSlot = sourceSlot;
        for (int i = 0; i < slots.size(); i++) {
            Slot old = slots.get(i);
            boolean media = i < CAPACITY;
            boolean locked = !media && old.container == inventory && old.getContainerSlot() == sourceSlot;
            Slot slot = new Slot(old.container, old.getContainerSlot(), old.x, old.y) {
                @Override public boolean mayPlace(ItemStack stack) {
                    return !locked && (!media || accepts(stack));
                }
                @Override public boolean mayPickup(Player player) { return !locked; }
            };
            slot.index = i;
            slots.set(i, slot);
        }
    }

    public static boolean accepts(ItemStack stack) {
        return stack.is(ConsoleContent.CARTRIDGE.get()) || stack.is(ConsoleContent.BLANK.get());
    }

    private boolean locked(Slot slot) {
        return sourceSlot >= 0 && slot.container instanceof Inventory && slot.getContainerSlot() == sourceSlot;
    }

    @Override public void clicked(int index, int button, ClickType type, Player player) {
        if (!stillValid(player)) { player.closeContainer(); return; }
        if (index >= 0 && index < slots.size() && locked(slots.get(index))) return;
        // Number keys and offhand swaps access Inventory directly rather than the source Slot.
        if (sourceSlot >= 0 && type == ClickType.SWAP && button == sourceSlot) return;
        super.clicked(index, button, type, player);
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (!stillValid(player) || index < 0 || index >= slots.size() || locked(slots.get(index))) return ItemStack.EMPTY;
        if (index >= CAPACITY && !accepts(slots.get(index).getItem())) return ItemStack.EMPTY;
        return super.quickMoveStack(player, index);
    }

    @Override public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return !locked(slot) && super.canTakeItemForPickAll(stack, slot);
    }

    @Override public boolean canDragTo(Slot slot) { return !locked(slot) && super.canDragTo(slot); }
}
