package net.muxigame.console.mc;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.List;

public final class CartridgeCaseBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public CartridgeCaseBlock(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return simpleCodec(CartridgeCaseBlock::new); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()); }
    @Override public BlockEntity newBlockEntity(BlockPos position, BlockState state) { return new CartridgeCaseEntity(position, state); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos position, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.X ? box(3.5, 0, 1, 12.5, 1.25, 15) : box(1, 0, 3.5, 15, 1.25, 12.5);
    }
    private static void open(Level level, BlockPos position, Player player) {
        if (player instanceof ServerPlayer server && level.getBlockEntity(position) instanceof CartridgeCaseEntity box)
            server.openMenu(box, data -> data.writeVarInt(-1));
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.isSecondaryUseActive()) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        open(level, pos, player); return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        open(level, pos, player); return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder parameters) {
        BlockEntity entity = parameters.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        return List.of(entity instanceof CartridgeCaseEntity box ? box.packed() : new ItemStack(ConsoleContent.CASE_ITEM.get()));
    }
    @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative() && level.getBlockEntity(pos) instanceof CartridgeCaseEntity box && !box.isEmpty())
            popResource(level, pos, box.packed());
        return super.playerWillDestroy(level, pos, state, player);
    }
    @Override public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return level.getBlockEntity(pos) instanceof CartridgeCaseEntity box ? box.packed() : new ItemStack(ConsoleContent.CASE_ITEM.get());
    }
    @Override protected void onRemove(BlockState old, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!old.is(next.getBlock())) level.updateNeighbourForOutputSignal(pos, this);
        super.onRemove(old, level, pos, next, moving);
    }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
}
