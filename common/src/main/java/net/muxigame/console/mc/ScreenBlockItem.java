package net.muxigame.console.mc;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Preflights the full footprint before consuming the single screen item. */
public final class ScreenBlockItem extends BlockItem {
    public ScreenBlockItem(Block block,Properties properties){super(block,properties);}
    @Override protected BlockState getPlacementState(BlockPlaceContext context){
        BlockState state=super.getPlacementState(context);if(state==null)return null;state=state.setValue(ScreenBlock.PART,1);
        return canPlaceAll(context,state)?state:null;
    }
    private boolean canPlaceAll(BlockPlaceContext context,BlockState state){
        Level level=context.getLevel();Player player=context.getPlayer();Direction facing=state.getValue(DeviceBlock.FACING);BlockPos anchor=context.getClickedPos();
        for(int index=0;index<ScreenLayout.PARTS;index++){
            BlockPos target=ScreenLayout.part(anchor,facing,index);BlockState tile=state.setValue(ScreenBlock.PART,index+1);
            if(!level.hasChunkAt(target)||level.isOutsideBuildHeight(target)||!level.getWorldBorder().isWithinBounds(target)||!level.getBlockState(target).canBeReplaced(context)||level.getBlockEntity(target)!=null)return false;
            if(player!=null&&(!level.mayInteract(player,target)||!player.mayUseItemAt(target,context.getClickedFace(),context.getItemInHand())))return false;
            if(!tile.canSurvive(level,target)||!level.isUnobstructed(tile,target,player==null?CollisionContext.empty():CollisionContext.of(player)))return false;
        }
        return true;
    }
    @Override protected boolean placeBlock(BlockPlaceContext context,BlockState state){
        if(!canPlaceAll(context,state))return false;
        Level level=context.getLevel();BlockPos anchor=context.getClickedPos();Direction facing=state.getValue(DeviceBlock.FACING);UUID identity=UUID.randomUUID();Map<BlockPos,BlockState> previous=new LinkedHashMap<>();
        for(BlockPos target:ScreenLayout.parts(anchor,facing))previous.put(target,level.getBlockState(target));
        for(int index=0;index<ScreenLayout.PARTS;index++){
            BlockPos target=ScreenLayout.part(anchor,facing,index);
            if(!level.setBlock(target,state.setValue(ScreenBlock.PART,index+1),3)||!(level.getBlockEntity(target) instanceof DeviceEntity device)){
                for(var rollback:previous.entrySet())level.setBlock(rollback.getKey(),rollback.getValue(),3);return false;
            }
            device.identity=identity;device.screenOrigin=anchor;device.changed();
        }
        return true;
    }
    @Override protected boolean updateCustomBlockEntityTag(BlockPos pos,Level level,Player player,ItemStack stack,BlockState state){return false;}
}
