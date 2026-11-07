package net.muxigame.console.mc;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.server.level.ServerPlayer;

public final class DeviceBlock extends BaseEntityBlock {
    public enum Kind {DESK,WRITER,SCREEN}
    public static final DirectionProperty FACING=BlockStateProperties.HORIZONTAL_FACING;
    public final Kind kind;
    public DeviceBlock(Kind kind,Properties properties){super(properties);this.kind=kind;registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));}
    protected MapCodec<? extends BaseEntityBlock> codec(){return simpleCodec(p->new DeviceBlock(kind,p));}
    protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(FACING);}
    public BlockState getStateForPlacement(BlockPlaceContext c){return defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite());}
    public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new DeviceEntity(p,s);}
    protected RenderShape getRenderShape(BlockState s){return RenderShape.MODEL;}
    protected ItemInteractionResult useItemOn(ItemStack stack,BlockState state,Level level,BlockPos pos,Player player,InteractionHand hand,BlockHitResult hit){
        if(level.isClientSide)return ItemInteractionResult.SUCCESS;
        ConsoleServer.interact((ServerPlayer)player,pos,hand);return ItemInteractionResult.CONSUME;
    }
    protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        if(!level.isClientSide)ConsoleServer.interact((ServerPlayer)player,pos,InteractionHand.MAIN_HAND);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    protected void onRemove(BlockState old,Level level,BlockPos pos,BlockState next,boolean moving){
        if(!old.is(next.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof DeviceEntity d){
            ConsoleServer.removed(d);if(!d.card.isEmpty())Containers.dropItemStack(level,pos.getX()+.5,pos.getY()+.4,pos.getZ()+.5,d.card.copy());
            for(int i=0;i<d.ports;i++)Containers.dropItemStack(level,pos.getX()+.5,pos.getY()+.4,pos.getZ()+.5,new ItemStack(ConsoleContent.CONTROLLER.get()));
        }
        super.onRemove(old,level,pos,next,moving);
    }
}
