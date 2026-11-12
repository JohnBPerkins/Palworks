package dev.palworks.block;

import com.mojang.serialization.MapCodec;
import dev.architectury.event.EventResult;
import dev.palworks.hunger.TroughFood;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * A trough: a low wooden box of feed (see {@link TroughBlockEntity}), one for each wood (see
 * {@link ModBlocks#WOODS}). Right-clicking it with food puts the food in, as feed; workers that are
 * hungry walk to one and eat from it (see EatFoodHandler). Troughs placed side by side join into
 * one long trough (the side between them is left open, as fences join), whatever their woods, and
 * share their feed evenly, so it always shows one level along them.
 */
public class TroughBlock extends BaseEntityBlock {
    public static final MapCodec<TroughBlock> CODEC = simpleCodec(TroughBlock::new);

    /** Whether the trough joins the one on that side (and so has no wall there). */
    public static final BooleanProperty NORTH = PipeBlock.NORTH;

    public static final BooleanProperty EAST = PipeBlock.EAST;
    public static final BooleanProperty SOUTH = PipeBlock.SOUTH;
    public static final BooleanProperty WEST = PipeBlock.WEST;

    /**
     * How full it looks: 0 (empty, the bare wooden bottom) to {@link TroughFeed#MAX_FILL}, just under
     * its rim, a pixel higher a level. Set from the feed of it and the troughs joined to it as that
     * changes (see {@link TroughFeed#fillLevel}).
     */
    public static final IntegerProperty FILL = IntegerProperty.create("fill", 0, TroughFeed.MAX_FILL);

    private static final Map<Direction, BooleanProperty> SIDES =
            Map.of(Direction.NORTH, NORTH, Direction.EAST, EAST, Direction.SOUTH, SOUTH, Direction.WEST, WEST);

    // Half a block high: low enough for a Pokémon to reach in, and to step up onto
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 8, 16);

    public TroughBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition
                .any()
                .setValue(NORTH, false)
                .setValue(EAST, false)
                .setValue(SOUTH, false)
                .setValue(WEST, false)
                .setValue(FILL, 0));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, FILL);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        for (Map.Entry<Direction, BooleanProperty> side : SIDES.entrySet()) {
            BlockState neighbour =
                    context.getLevel().getBlockState(context.getClickedPos().relative(side.getKey()));
            state = state.setValue(side.getValue(), joins(neighbour));
        }
        return state;
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            Direction direction,
            BlockState neighbourState,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighbourPos) {
        BooleanProperty side = SIDES.get(direction);
        return side == null ? state : state.setValue(side, joins(neighbourState));
    }

    private static boolean joins(BlockState neighbour) {
        return neighbour.getBlock() instanceof TroughBlock;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /**
     * Right-clicking with food puts one in, as feed (shift-right-clicking puts the whole stack in:
     * see {@link #onShiftRightClick}). Anything else shows how much feed it has.
     */
    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit) {
        if (!TroughFood.accepts(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        putIn(level, pos, player, stack, 1);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Shift-right-clicking a trough with food puts the whole stack in, as far as there's room along
     * it and the troughs joined to it. Through the interaction event, not {@link #useItemOn}:
     * Minecraft doesn't ask a block what a right-click does while its player sneaks with something
     * in hand. Registered in PalworksCommon.
     */
    public static EventResult onShiftRightClick(Player player, InteractionHand hand, BlockPos pos, Direction face) {
        Level level = player.level();
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isSecondaryUseActive()
                || !(level.getBlockState(pos).getBlock() instanceof TroughBlock)
                || !TroughFood.accepts(stack)) {
            return EventResult.pass();
        }
        putIn(level, pos, player, stack, stack.getCount());
        return EventResult.interruptTrue();
    }

    /** Put up to {@code count} of the food in hand in the trough (on the server), and say how much feed it has. */
    private static void putIn(Level level, BlockPos pos, Player player, ItemStack stack, int count) {
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof TroughBlockEntity trough)) {
            return;
        }
        int put = count - trough.addFood(stack.copyWithCount(count));
        if (put > 0) {
            if (!player.getAbilities().instabuild) {
                stack.shrink(put);
            }
            level.playSound(null, pos, SoundEvents.COMPOSTER_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        showFeed(player, trough);
    }

    /** With anything but food in hand: how much feed this trough and those joined to it have. */
    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof TroughBlockEntity trough) {
            showFeed(player, trough);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** "Feed: 23/64", over the player's hotbar. */
    private static void showFeed(Player player, TroughBlockEntity trough) {
        TroughBlockEntity.Stock stock = trough.stock();
        player.displayClientMessage(
                Component.translatable("palworks.trough.feed", stock.feed(), stock.capacity()), true);
    }

    /** A trough placed next to others takes its share of their feed (a tick later, once it's all there). */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!oldState.is(this)) {
            level.scheduleTick(pos, this, 1);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof TroughBlockEntity trough) {
            trough.shareOut();
        }
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof TroughBlockEntity trough
                ? TroughFeed.signal(trough.feed(), TroughFeed.CAPACITY)
                : 0;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TroughBlockEntity(pos, state);
    }
}
