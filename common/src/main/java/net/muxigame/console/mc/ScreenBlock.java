package net.muxigame.console.mc;

import java.util.*;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.shapes.*;

/** Part zero preserves old single-block screens; new items place parts one through six. */
public final class ScreenBlock extends DeviceBlock {
    public static final IntegerProperty PART=IntegerProperty.create("part",0,ScreenLayout.PARTS);
    private static final Map<Level,Set<UUID>> REMOVING=new IdentityHashMap<>();
    public ScreenBlock(Properties properties){super(Kind.SCREEN,properties);registerDefaultState(defaultBlockState().setValue(PART,0));}
    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return simpleCodec(ScreenBlock::new);}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){super.createBlockStateDefinition(builder);builder.add(PART);}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){
        if(state.getValue(PART)==0)return super.getShape(state,level,pos,context);
        return switch(state.getValue(FACING)){case EAST,WEST->Block.box(7,0,0,9,16,16);default->Block.box(0,0,7,16,16,9);};
    }
    @Override protected List<ItemStack> getDrops(BlockState state,LootParams.Builder params){return List.of(new ItemStack(ConsoleContent.SCREEN_ITEM.get()));}
    @Override protected void onPlace(BlockState state,Level level,BlockPos pos,BlockState old,boolean moving){super.onPlace(state,level,pos,old,moving);if(!level.isClientSide&&state.getValue(PART)>0)level.scheduleTick(pos,this,1);}
    @Override protected void tick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random){
        if(!(level.getBlockEntity(pos) instanceof DeviceEntity device)||!device.largeScreen())return;
        var facing=state.getValue(FACING);List<BlockPos> parts=ScreenLayout.parts(device.screenAnchor(),facing);
        for(BlockPos member:parts)if(!level.hasChunkAt(member)){level.scheduleTick(pos,this,20);return;}
        for(int index=0;index<parts.size();index++){
            if(!(level.getBlockEntity(parts.get(index)) instanceof DeviceEntity other)||other.kind()!=Kind.SCREEN||!other.identity.equals(device.identity)||!other.screenAnchor().equals(device.screenAnchor())||other.getBlockState().getValue(FACING)!=facing||other.getBlockState().getValue(PART)!=index+1){level.removeBlock(pos,false);return;}
        }
    }
    @Override protected void onRemove(BlockState old,Level level,BlockPos pos,BlockState next,boolean moving){
        if(!old.is(next.getBlock())&&!level.isClientSide&&level.getBlockEntity(pos) instanceof DeviceEntity device&&device.largeScreen()){
            Set<UUID> active=REMOVING.computeIfAbsent(level,key->new HashSet<>());
            if(active.add(device.identity))try{
                for(BlockPos other:ScreenLayout.parts(device.screenAnchor(),old.getValue(FACING))){
                    if(other.equals(pos)||!level.hasChunkAt(other))continue;
                    if(level.getBlockEntity(other) instanceof DeviceEntity member&&member.kind()==Kind.SCREEN&&member.identity.equals(device.identity)&&member.screenAnchor().equals(device.screenAnchor()))level.removeBlock(other,false);
                }
            }finally{active.remove(device.identity);if(active.isEmpty())REMOVING.remove(level);}
        }
        super.onRemove(old,level,pos,next,moving);
    }
}
