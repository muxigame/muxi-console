package net.muxigame.console.mc;

import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.SlotAccess;

public final class HandheldItem extends Item {
    public HandheldItem(Properties p){super(p);}
    @Override public Component getName(ItemStack item){var body=HardwareCatalog.body(HardwareCatalog.model(item));return body==null?Component.translatable("item.muxi_console.handheld"):Component.literal(body.name());}
    @Override public void appendHoverText(ItemStack item,TooltipContext context,java.util.List<Component> lines,TooltipFlag flag){
        super.appendHoverText(item,context,lines,flag);
        if(HardwareCatalog.model(item)==0){lines.add(Component.translatable("tooltip.muxi_console.core"));lines.add(Component.translatable("tooltip.muxi_console.reshape"));}
        else lines.add(Component.translatable("tooltip.muxi_console.inventory_slot"));
    }
    @Override public boolean overrideOtherStackedOnMe(ItemStack handheld,ItemStack carried,Slot slot,ClickAction action,Player player,SlotAccess cursor){return HandheldInventory.onHandheld(handheld,carried,slot,action,player,cursor);}
    @Override public boolean overrideStackedOnOther(ItemStack handheld,Slot slot,ClickAction action,Player player){return HandheldInventory.onOther(handheld,slot,action,player);}
    public InteractionResultHolder<ItemStack> use(Level world,Player player,InteractionHand hand){
        if(!world.isClientSide)ConsoleServer.handheld((ServerPlayer)player,hand);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),world.isClientSide);
    }
}
