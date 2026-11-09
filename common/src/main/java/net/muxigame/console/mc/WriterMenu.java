package net.muxigame.console.mc;

import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.muxigame.console.domain.DomainException;

/** One actual cartridge slot owned by this viewer; all native inventory operations stay authoritative. */
public final class WriterMenu extends AbstractContainerMenu {
    private final SimpleContainer media = new SimpleContainer(1);
    private final BlockPos position;
    private final UUID token;
    public WriterMenu(int id, Inventory inventory, FriendlyByteBuf data) {
        this(id, inventory, data.readBlockPos(), UUID.fromString(data.readUtf(36)));
    }
    public WriterMenu(int id, Inventory inventory, BlockPos position, UUID token) {
        super(ConsoleContent.WRITER_MENU.get(), id);
        this.position=position.immutable(); this.token=token;
        addSlot(new Slot(media,0,80,36) {
            @Override public boolean mayPlace(ItemStack item) { return item.is(ConsoleContent.BLANK.get()); }
            @Override public int getMaxStackSize() { return 1; }
        });
        for(int row=0;row<3;row++)for(int col=0;col<9;col++)addSlot(new Slot(inventory,9+row*9+col,8+18*col,140+18*row));
        for(int col=0;col<9;col++)addSlot(new Slot(inventory,col,8+18*col,198));
    }
    public JsonObject context() { JsonObject j=new JsonObject();j.addProperty("position",position.asLong());j.addProperty("token",token.toString());return j; }
    public boolean matches(UUID candidate) { return token.equals(candidate); }
    public boolean hasBlank() { return media.getItem(0).is(ConsoleContent.BLANK.get())&&media.getItem(0).getCount()==1; }
    public void burn(ItemStack cartridge) {
        if(!hasBlank())throw new DomainException("blank","请先在刻录槽放入一张空白卡带");
        if(!cartridge.is(ConsoleContent.CARTRIDGE.get())||cartridge.getCount()!=1)throw new IllegalArgumentException("Invalid burned cartridge");
        media.setItem(0,cartridge);broadcastChanges();
    }
    @Override public boolean stillValid(Player player) {
        return player.level().hasChunkAt(position)&&player.level().getBlockState(position).is(ConsoleContent.WRITER.get())&&player.distanceToSqr(position.getCenter())<=64;
    }
    @Override public void removed(Player player) { super.removed(player);if(player instanceof net.minecraft.server.level.ServerPlayer server)ConsoleServer.writerClosed(server,token);if(!player.level().isClientSide)clearContainer(player,media); }
    @Override public ItemStack quickMoveStack(Player player,int index) {
        if(index<0||index>=slots.size()||!stillValid(player))return ItemStack.EMPTY;
        Slot slot=slots.get(index);if(!slot.hasItem())return ItemStack.EMPTY;
        ItemStack item=slot.getItem(),copy=item.copy();
        if(index==0){if(!moveItemStackTo(item,1,37,true))return ItemStack.EMPTY;}
        else if(item.is(ConsoleContent.BLANK.get())){if(!moveItemStackTo(item,0,1,false))return ItemStack.EMPTY;}
        else if(index<28){if(!moveItemStackTo(item,28,37,false))return ItemStack.EMPTY;}
        else if(!moveItemStackTo(item,1,28,false))return ItemStack.EMPTY;
        if(item.isEmpty())slot.setByPlayer(ItemStack.EMPTY);else slot.setChanged();
        slot.onTake(player,item);return copy;
    }
}
