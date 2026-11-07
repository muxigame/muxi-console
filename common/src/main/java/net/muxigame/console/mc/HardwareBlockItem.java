package net.muxigame.console.mc;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class HardwareBlockItem extends BlockItem {
    public HardwareBlockItem(Block block,Properties properties){super(block,properties);}
    @Override protected BlockState getPlacementState(BlockPlaceContext context){
        BlockState state=super.getPlacementState(context);int model=HardwareCatalog.model(context.getItemInHand());
        return state==null?null:state.setValue(DeviceBlock.MODEL,HardwareCatalog.body(model)==null?0:model);
    }
    @Override public Component getName(ItemStack item){HardwareCatalog.Body body=HardwareCatalog.body(HardwareCatalog.model(item));return body==null?super.getName(item):Component.literal(body.name());}
}
