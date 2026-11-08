package net.muxigame.console.mc;

import java.util.*;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.shapes.*;

/** New items occupy four tiles. Old zero/six-tile saves remain readable and migrate without replacing neighbours. */
public final class ScreenBlock extends DeviceBlock {
    public static final IntegerProperty PART=IntegerProperty.create("part",0,ScreenLayout.LAST_PART);
    private static final Map<Level,Set<UUID>> REMOVING=new IdentityHashMap<>();
    // The first entity constructed by our own fresh placement identifies that write even if setBlock throws afterwards.
    private static final ThreadLocal<MigrationWrite> WRITING=new ThreadLocal<>();
    public ScreenBlock(Properties properties){super(Kind.SCREEN,properties);registerDefaultState(defaultBlockState().setValue(PART,0));}
    @Override public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(BlockPos pos,BlockState state){
        var made=super.newBlockEntity(pos,state);MigrationWrite write=WRITING.get();
        if(write!=null&&write.before.position.equals(pos)&&write.target==state&&write.owned==null&&made instanceof DeviceEntity device)write.owned=device;
        return made;
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return simpleCodec(ScreenBlock::new);}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){super.createBlockStateDefinition(builder);builder.add(PART);}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){
        int part=state.getValue(PART);Direction facing=state.getValue(FACING);
        if(ScreenLayout.legacyPart(part))return switch(facing){case EAST,WEST->Block.box(7,0,0,9,16,16);default->Block.box(0,0,7,16,16,9);};
        double[][] pieces=part==0?new double[][]{{.4,5.7,6.2,15.6,15.4,8.4},{7.0,.8,8.25,9.0,6.2,10.1},{4.3,0,2.8,11.7,1,13.2}}:
            new double[][]{{-15.5,12,6.2,15.5,31,8.4},{-1.4,1,8.25,1.4,12.8,10.2},{-7,0,2.8,7,1.4,13.2},{-3,11.5,8.2,3,16,10.0}};
        int index=part==0?0:ScreenLayout.index(part),column=index%ScreenLayout.WIDTH,row=index/ScreenLayout.WIDTH;
        VoxelShape shape=Shapes.empty();
        for(double[] b:pieces){
            double x0=part==0?b[0]:Math.max(b[0],-16*column)+16*column,x1=part==0?b[3]:Math.min(b[3],16-16*column)+16*column;
            double y0=part==0?b[1]:Math.max(b[1],16*row)-16*row,y1=part==0?b[4]:Math.min(b[4],16+16*row)-16*row,z0=b[2],z1=b[5];
            if(x0>=x1||y0>=y1)continue;
            double a=x0,c=x1;switch(facing){case EAST->{x0=16-z1;z0=a;x1=16-b[2];z1=c;}case SOUTH->{x0=16-c;z0=16-b[5];x1=16-a;z1=16-b[2];}case WEST->{x0=b[2];z0=16-c;x1=b[5];z1=16-a;}default->{}}
            shape=Shapes.or(shape,Block.box(x0,y0,z0,x1,y1,z1));
        }
        return shape;
    }
    @Override protected List<ItemStack> getDrops(BlockState state,LootParams.Builder params){return List.of(new ItemStack(ConsoleContent.SCREEN_ITEM.get()));}
    @Override protected void onPlace(BlockState state,Level level,BlockPos pos,BlockState old,boolean moving){super.onPlace(state,level,pos,old,moving);if(!level.isClientSide)level.scheduleTick(pos,this,1);}
    @Override protected void tick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random){
        if(!(level.getBlockEntity(pos) instanceof DeviceEntity device))return;
        if(state.getValue(PART)==0){if(!upgradeSingle(level,device))level.scheduleTick(pos,this,100);return;}
        if(!device.largeScreen())return;
        Direction facing=state.getValue(FACING);boolean legacy=ScreenLayout.legacyPart(state.getValue(PART));
        List<BlockPos> parts=ScreenLayout.parts(device.screenAnchor(),facing,legacy);
        for(BlockPos member:parts)if(!level.hasChunkAt(member)){level.scheduleTick(pos,this,20);return;}
        for(int index=0;index<parts.size();index++){
            int expected=legacy?index+1:ScreenLayout.newPart(index);
            if(!(level.getBlockEntity(parts.get(index)) instanceof DeviceEntity other)||other.kind()!=Kind.SCREEN||!other.identity.equals(device.identity)||!other.screenAnchor().equals(device.screenAnchor())||other.getBlockState().getValue(FACING)!=facing||other.getBlockState().getValue(PART)!=expected){level.removeBlock(pos,false);return;}
        }
        if(legacy&&device.screenPrimary()&&!upgradeLegacy(level,device,parts))level.scheduleTick(pos,this,100);
    }
    private record Saved(BlockPos position,BlockState state,DeviceEntity entity,CompoundTag data){}
    private static final class MigrationWrite {
        final Saved before;final BlockState target;DeviceEntity owned;
        MigrationWrite(Saved before,BlockState target){this.before=before;this.target=target;owned=before.entity;}
    }
    private static Saved save(ServerLevel level,BlockPos pos){
        var entity=level.getBlockEntity(pos);
        if(entity!=null&&!(entity instanceof DeviceEntity))throw new IllegalStateException("A foreign block entity cannot enter screen migration");
        DeviceEntity device=(DeviceEntity)entity;
        return new Saved(pos,level.getBlockState(pos),device,device==null?null:device.saveWithoutMetadata(level.registryAccess()));
    }
    private static boolean unchanged(ServerLevel level,Saved before){
        if(level.getBlockState(before.position)!=before.state||level.getBlockEntity(before.position)!=before.entity)return false;
        return before.entity==null||before.data.equals(before.entity.saveWithoutMetadata(level.registryAccess()));
    }
    private static boolean writable(ServerLevel level,Saved before,BlockState target){
        return level.hasChunkAt(before.position)&&!level.isOutsideBuildHeight(before.position)&&level.getWorldBorder().isWithinBounds(before.position)
            &&unchanged(level,before)&&(target.isAir()||level.isUnobstructed(target,before.position,CollisionContext.empty()));
    }
    private static boolean write(ServerLevel level,MigrationWrite step){
        MigrationWrite outer=WRITING.get();WRITING.set(step);
        try{return level.setBlock(step.before.position,step.target,3);}
        finally{if(outer==null)WRITING.remove();else WRITING.set(outer);}
    }
    private static boolean owned(ServerLevel level,MigrationWrite step){
        if(level.getBlockState(step.before.position)!=step.target)return false;
        MigrationWrite outer=WRITING.get();WRITING.set(step);
        try{
            // getBlockEntity can complete delayed entity creation after a partially successful setBlock.
            var current=level.getBlockEntity(step.before.position);
            return step.target.isAir()?current==null:current==step.owned;
        }finally{if(outer==null)WRITING.remove();else WRITING.set(outer);}
    }
    private static void restore(ServerLevel level,Saved before){
        // Rollback sends state/client updates but does not synchronously mutate another saved target through neighbours.
        if(level.getBlockState(before.position)!=before.state&&!level.setBlock(before.position,before.state,2))throw new IllegalStateException("Screen migration rollback refused a saved state");
        if(before.entity!=null){
            if(!(level.getBlockEntity(before.position) instanceof DeviceEntity restored))throw new IllegalStateException("Screen migration rollback lost its saved entity");
            restored.loadAdditional(before.data.copy(),level.registryAccess());restored.changed();
        }else if(level.getBlockEntity(before.position)!=null)throw new IllegalStateException("Screen migration rollback found an unexpected entity");
    }
    private static void rollback(ServerLevel level,List<MigrationWrite> touched,Throwable failure){
        Throwable rollbackFailure=null;
        for(int index=touched.size()-1;index>=0;index--){
            MigrationWrite step=touched.get(index);
            try{
                if(unchanged(level,step.before))continue;
                // A neighbour callback may have placed a different block/entity after our write. It is not ours to erase.
                if(owned(level,step))restore(level,step.before);
            }catch(RuntimeException|Error error){if(rollbackFailure==null)rollbackFailure=error;else rollbackFailure.addSuppressed(error);}
        }
        if(rollbackFailure!=null){
            if(failure!=null)failure.addSuppressed(rollbackFailure);
            else if(rollbackFailure instanceof RuntimeException error)throw error;
            else if(rollbackFailure instanceof Error error)throw error;
        }
    }
    private boolean upgradeSingle(ServerLevel level,DeviceEntity original){
        BlockPos anchor=original.getBlockPos();Direction facing=original.getBlockState().getValue(FACING);List<BlockPos> targets=ScreenLayout.parts(anchor,facing);
        List<Saved> saved=new ArrayList<>();
        for(int index=0;index<targets.size();index++){
            BlockPos target=targets.get(index);BlockState tile=original.getBlockState().setValue(PART,ScreenLayout.newPart(index));
            if(!level.hasChunkAt(target)||level.isOutsideBuildHeight(target)||!level.getWorldBorder().isWithinBounds(target)||index>0&&(!level.getBlockState(target).isAir()||level.getBlockEntity(target)!=null)||!level.isUnobstructed(tile,target,CollisionContext.empty()))return false;
            saved.add(save(level,target));
        }
        UUID identity=original.identity;Set<UUID> active=REMOVING.computeIfAbsent(level,key->new HashSet<>());if(!active.add(identity))return false;
        List<MigrationWrite> touched=new ArrayList<>();boolean success=false;Throwable failure=null;
        try{
            for(int index=0;index<saved.size();index++){
                Saved before=saved.get(index);BlockState tile=saved.get(0).state.setValue(PART,ScreenLayout.newPart(index));
                // Earlier flags=3 neighbour callbacks may have occupied a later tile after the first preflight.
                if(!writable(level,before,tile))return false;
                MigrationWrite step=new MigrationWrite(before,tile);touched.add(step);
                if(!write(level,step)||!owned(level,step)||!(level.getBlockEntity(before.position) instanceof DeviceEntity member))return false;
                member.identity=identity;member.screenOrigin=anchor;member.changed();
            }
            for(MigrationWrite step:touched)if(!owned(level,step))return false;
            success=true;return true;
        }catch(RuntimeException|Error error){failure=error;throw error;}
        finally{try{if(!success)rollback(level,touched,failure);}finally{active.remove(identity);if(active.isEmpty())REMOVING.remove(level);}}
    }
    private boolean upgradeLegacy(ServerLevel level,DeviceEntity original,List<BlockPos> legacy){
        // Obsolete follower tiles normally hold no items. Keep unusual data intact rather than silently discarding it.
        for(int index:new int[]{2,5})if(level.getBlockEntity(legacy.get(index)) instanceof DeviceEntity extra&&(!extra.card.isEmpty()||!extra.dockedHandheld.isEmpty()||extra.ports!=0))return false;
        List<Saved> saved=legacy.stream().map(pos->save(level,pos)).toList();UUID identity=original.identity;BlockPos anchor=original.screenAnchor();
        Set<UUID> active=REMOVING.computeIfAbsent(level,key->new HashSet<>());if(!active.add(identity))return false;
        List<MigrationWrite> touched=new ArrayList<>();boolean success=false;Throwable failure=null;
        try{
            int[] retained={0,1,3,4};
            for(int index=0;index<retained.length;index++){
                Saved before=saved.get(retained[index]);BlockState tile=before.state.setValue(PART,ScreenLayout.newPart(index));
                if(!writable(level,before,tile))return false;
                MigrationWrite step=new MigrationWrite(before,tile);touched.add(step);
                if(!write(level,step)||!owned(level,step)||!(level.getBlockEntity(before.position) instanceof DeviceEntity member))return false;
                member.screenOrigin=anchor;member.changed();
            }
            for(int index:new int[]{2,5}){
                Saved before=saved.get(index);BlockState air=Blocks.AIR.defaultBlockState();
                if(!writable(level,before,air))return false;
                MigrationWrite step=new MigrationWrite(before,air);touched.add(step);
                if(!write(level,step)||!owned(level,step))return false;
            }
            for(MigrationWrite step:touched)if(!owned(level,step))return false;
            success=true;return true;
        }catch(RuntimeException|Error error){failure=error;throw error;}
        finally{try{if(!success)rollback(level,touched,failure);}finally{active.remove(identity);if(active.isEmpty())REMOVING.remove(level);}}
    }
    @Override protected void onRemove(BlockState old,Level level,BlockPos pos,BlockState next,boolean moving){
        if(!old.is(next.getBlock())&&!level.isClientSide&&level.getBlockEntity(pos) instanceof DeviceEntity device&&device.largeScreen()){
            Set<UUID> active=REMOVING.computeIfAbsent(level,key->new HashSet<>());
            if(active.add(device.identity))try{
                for(BlockPos other:ScreenLayout.parts(device.screenAnchor(),old.getValue(FACING),ScreenLayout.legacyPart(old.getValue(PART)))){
                    if(other.equals(pos)||!level.hasChunkAt(other))continue;
                    if(level.getBlockEntity(other) instanceof DeviceEntity member&&member.kind()==Kind.SCREEN&&member.identity.equals(device.identity)&&member.screenAnchor().equals(device.screenAnchor()))level.removeBlock(other,false);
                }
            }finally{active.remove(device.identity);if(active.isEmpty())REMOVING.remove(level);}
        }
        super.onRemove(old,level,pos,next,moving);
    }
}
