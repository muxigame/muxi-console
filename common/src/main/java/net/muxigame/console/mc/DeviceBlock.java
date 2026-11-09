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

public class DeviceBlock extends BaseEntityBlock {
    public enum Kind {DESK,WRITER,SCREEN,DOCK}
    public static final DirectionProperty FACING=BlockStateProperties.HORIZONTAL_FACING;
    public static final IntegerProperty MODEL=IntegerProperty.create("model",0,25);
    public final Kind kind;
    public DeviceBlock(Kind kind,Properties properties){super(properties);this.kind=kind;registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH).setValue(MODEL,0));}
    protected MapCodec<? extends BaseEntityBlock> codec(){return simpleCodec(p->new DeviceBlock(kind,p));}
    protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(FACING,MODEL);}
    public BlockState getStateForPlacement(BlockPlaceContext c){return defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite());}
    public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new DeviceEntity(p,s);}
    @Override public void setPlacedBy(Level level,BlockPos pos,BlockState state,net.minecraft.world.entity.LivingEntity owner,ItemStack item){
        super.setPlacedBy(level,pos,state,owner,item);
        if(!level.isClientSide&&kind==Kind.DESK&&level.getBlockEntity(pos) instanceof DeviceEntity device){device.adoptMachine(item.copyWithCount(1));device.changed();}
    }
    protected RenderShape getRenderShape(BlockState s){return RenderShape.MODEL;}
    @Override protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState s,BlockGetter level,BlockPos pos,net.minecraft.world.phys.shapes.CollisionContext context){
        HardwareCatalog.Body body=kind==Kind.DESK?HardwareCatalog.body(s.getValue(MODEL)):null;
        if(body==null){if(kind==Kind.DESK)return Block.box(1,0,2,15,6,14);if(kind==Kind.DOCK)return s.getValue(FACING).getAxis()==Direction.Axis.X?Block.box(2,0,1,14,3,15):Block.box(1,0,2,15,3,14);return super.getShape(s,level,pos,context);}
        float[] b=body.bounds();double x0=b[0],z0=b[2],x1=b[3],z1=b[5];
        switch(s.getValue(FACING)){case EAST->{x0=16-b[5];z0=b[0];x1=16-b[2];z1=b[3];}case SOUTH->{x0=16-b[3];z0=16-b[5];x1=16-b[0];z1=16-b[2];}case WEST->{x0=b[2];z0=16-b[3];x1=b[5];z1=16-b[0];}default->{}}
        return Block.box(x0,b[1],z0,x1,b[4],z1);
    }
    @Override protected java.util.List<ItemStack> getDrops(BlockState state,net.minecraft.world.level.storage.loot.LootParams.Builder params){
        java.util.List<ItemStack> drops=super.getDrops(state,params);if(kind==Kind.DESK)for(ItemStack item:drops)if(item.is(ConsoleContent.DESK_ITEM.get())){HardwareCatalog.variant(item,state.getValue(MODEL));if(params.getOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY) instanceof DeviceEntity device)MachineData.write(item,device.machineId());}return drops;
    }
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
            if(!d.dockedHandheld.isEmpty())Containers.dropItemStack(level,pos.getX()+.5,pos.getY()+.4,pos.getZ()+.5,d.dockedHandheld.copy());
            for(int i=0;i<d.ports;i++)Containers.dropItemStack(level,pos.getX()+.5,pos.getY()+.4,pos.getZ()+.5,new ItemStack(ConsoleContent.CONTROLLER.get()));
        }
        super.onRemove(old,level,pos,next,moving);
    }
}
