package net.muxigame.console.mc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class CartridgeCaseEntity extends BaseContainerBlockEntity {
    private NonNullList<ItemStack> items = NonNullList.withSize(CartridgeCaseMenu.CAPACITY, ItemStack.EMPTY);

    public CartridgeCaseEntity(BlockPos position, BlockState state) { super(ConsoleContent.CASE_ENTITY.get(), position, state); }
    @Override public int getContainerSize() { return CartridgeCaseMenu.CAPACITY; }
    @Override protected NonNullList<ItemStack> getItems() { return items; }
    @Override protected void setItems(NonNullList<ItemStack> items) { this.items = items; }
    @Override protected Component getDefaultName() { return Component.translatable("block.muxi_console.cartridge_case"); }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) { return CartridgeCaseMenu.accepts(stack); }
    @Override protected AbstractContainerMenu createMenu(int id, Inventory inventory) { return new CartridgeCaseMenu(id, inventory, this, -1); }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
        super.saveAdditional(tag, lookup); ContainerHelper.saveAllItems(tag, items, lookup);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
        super.loadAdditional(tag, lookup);
        items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY); ContainerHelper.loadAllItems(tag, items, lookup);
    }
    @Override protected void applyImplicitComponents(DataComponentInput components) {
        super.applyImplicitComponents(components);
        components.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items);
    }
    @Override protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components); components.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
    }
    @Override public void removeComponentsFromTag(CompoundTag tag) { super.removeComponentsFromTag(tag); tag.remove("Items"); }

    /** One whole box, including complete card ItemStacks, names, and other item components. */
    public ItemStack packed() {
        ItemStack box = new ItemStack(ConsoleContent.CASE_ITEM.get());
        if (level == null) throw new IllegalStateException("Cannot pack an unattached cartridge case");
        saveToItem(box, level.registryAccess()); return box;
    }
}
