package net.muxigame.console.mc;

import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

public final class HandheldItem extends Item {
    public HandheldItem(Properties p){super(p);}
    @Override public Component getName(ItemStack item){var body=HardwareCatalog.body(HardwareCatalog.model(item));return body==null?super.getName(item):Component.literal(body.name());}
    @Override public void appendHoverText(ItemStack item,TooltipContext context,java.util.List<Component> lines,TooltipFlag flag){super.appendHoverText(item,context,lines,flag);CardData.read(item).ifPresent(c->lines.add(Component.literal("卡带："+c.title())));lines.add(Component.literal("潜行使用：装入 / 取出卡带"));if(CardData.read(item).isEmpty())lines.add(Component.literal("使用：选择机型与按键"));}
    public InteractionResultHolder<ItemStack> use(Level world,Player player,InteractionHand hand){
        if(!world.isClientSide)ConsoleServer.handheld((ServerPlayer)player,hand);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),world.isClientSide);
    }
}
