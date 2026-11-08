package net.muxigame.console.mc;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import java.util.List;

/** A portable media box; secondary use places the same box and its complete contents. */
public final class CartridgeCaseItem extends BlockItem {
    public CartridgeCaseItem(Block block, Properties properties) { super(block, properties); }

    public static boolean readable(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).getSlots() <= CartridgeCaseMenu.CAPACITY;
    }

    private static void open(Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer server)) return;
        ItemStack box = player.getItemInHand(hand);
        if (box.getCount() != 1 || !readable(box)) {
            player.displayClientMessage(Component.literal("收纳盒数据无效，原物品已保留"), true); return;
        }
        int source = hand == InteractionHand.OFF_HAND ? 40 : player.getInventory().selected;
        SimpleContainer contents = new SimpleContainer(CartridgeCaseMenu.CAPACITY) {
            @Override public boolean canPlaceItem(int slot, ItemStack stack) { return CartridgeCaseMenu.accepts(stack); }
            @Override public boolean stillValid(Player actor) {
                return actor == player && actor.getInventory().getItem(source) == box && box.getCount() == 1;
            }
            @Override public void setChanged() {
                super.setChanged();
                // Commit each native slot operation; closing the menu is not the persistence boundary.
                box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(getItems()));
                player.getInventory().setChanged();
            }
        };
        box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(contents.getItems());
        server.openMenu(new SimpleMenuProvider((id, inventory, actor) -> new CartridgeCaseMenu(id, inventory, contents, source), box.getHoverName()),
                data -> data.writeVarInt(source));
    }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        open(player, hand);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (player.isSecondaryUseActive()) {
            if (!readable(context.getItemInHand())) return InteractionResult.FAIL;
            return super.useOn(context);
        }
        open(player, context.getHand());
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override public boolean canFitInsideContainerItems() { return false; }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, lines, flag);
        long used = stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyStream().count();
        lines.add(Component.translatable("tooltip.muxi_console.cartridge_case", used, CartridgeCaseMenu.CAPACITY));
    }
}
