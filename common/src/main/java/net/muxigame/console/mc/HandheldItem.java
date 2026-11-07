package net.muxigame.console.mc;

import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;

public final class HandheldItem extends Item {
    public HandheldItem(Properties p){super(p);}
    public InteractionResultHolder<ItemStack> use(Level world,Player player,InteractionHand hand){
        if(!world.isClientSide)ConsoleServer.handheld((ServerPlayer)player,hand);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),world.isClientSide);
    }
}
