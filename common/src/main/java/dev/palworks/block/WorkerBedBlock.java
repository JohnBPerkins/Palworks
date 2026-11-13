package dev.palworks.block;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * A worker bed, where a pastured worker sleeps (see dev.palworks.sleep.Sleep): a low wooden frame
 * round a cushion, one worker to a bed. Two {@link Tier}s, straw and the nicer wool (in every
 * colour), which sleep the same for now. Three sizes, square: 1 block, 2 by 2 and 3 by 3, for
 * Pokémon up to as wide as that (see dev.palworks.sleep.Beds). A bigger one is placed whole, like a
 * vanilla bed, out from where it's placed, forward and to the right; each of its blocks knows where
 * it is in it ({@link #PART_X}, {@link #PART_Z}), and breaking any breaks it all, dropping the bed.
 *
 * <p>It has no collision, as a carpet nearly has none, so a worker walks onto it and lies down in the
 * straw, in the middle of the bed.
 */
public class WorkerBedBlock extends Block {
    /**
     * What a bed's cushion is: straw, or the nicer wool. Both sleep the same for now; it's here so
     * mood can make a nicer bed count for more.
     */
    public enum Tier implements StringRepresentable {
        STRAW("straw"),
        WOOL("wool");

        public static final Codec<Tier> CODEC = StringRepresentable.fromEnum(Tier::values);

        private final String name;

        Tier(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /** The biggest bed there is, a side. */
    public static final int MAX_SIZE = 3;

    /** Where this block is in its bed, from its north-west corner (0 to its size, less one). */
    public static final IntegerProperty PART_X = IntegerProperty.create("part_x", 0, MAX_SIZE - 1);

    public static final IntegerProperty PART_Z = IntegerProperty.create("part_z", 0, MAX_SIZE - 1);

    public static final MapCodec<WorkerBedBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Tier.CODEC.fieldOf("tier").forGetter(WorkerBedBlock::tier),
                    Codec.intRange(1, MAX_SIZE).fieldOf("size").forGetter(WorkerBedBlock::size),
                    propertiesCodec())
            .apply(instance, WorkerBedBlock::new));

    // What it looks like and is picked by: the frame and cushion, a quarter of a block high
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 4, 16);

    private final Tier tier;
    private final int size;

    public WorkerBedBlock(Tier tier, int size, Properties properties) {
        super(properties);
        this.tier = tier;
        this.size = size;
        registerDefaultState(stateDefinition.any().setValue(PART_X, 0).setValue(PART_Z, 0));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /** What its cushion is. */
    public Tier tier() {
        return tier;
    }

    /** How many blocks a side the bed is. */
    public int size() {
        return size;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PART_X, PART_Z);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    // ---- Where a bed is ----

    /** The north-west corner block of the bed this block is part of: where its sleeper's job is. */
    public static BlockPos origin(BlockPos pos, BlockState state) {
        return pos.offset(-state.getValue(PART_X), 0, -state.getValue(PART_Z));
    }

    /** Whether this is a bed's north-west corner block, which stands for the whole bed. */
    public static boolean isOrigin(BlockState state) {
        return state.getValue(PART_X) == 0 && state.getValue(PART_Z) == 0;
    }

    /** Where a worker lies in the bed whose corner block is {@code origin}: its middle, on the floor. */
    public Vec3 center(BlockPos origin) {
        return new Vec3(origin.getX() + size / 2.0, origin.getY(), origin.getZ() + size / 2.0);
    }

    // ---- Placing it whole, and breaking it whole ----

    /**
     * Out from the block it's placed on, forward (the way the player faces) and to their right; if
     * any of that is taken, it isn't placed.
     */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos clicked = context.getClickedPos();
        BlockPos origin = originFor(clicked, context.getHorizontalDirection());
        Level level = context.getLevel();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                BlockPos cell = origin.offset(x, 0, z);
                if (!level.getBlockState(cell).canBeReplaced(context)
                        || !level.getWorldBorder().isWithinBounds(cell)) {
                    return null;
                }
            }
        }
        return part(clicked.getX() - origin.getX(), clicked.getZ() - origin.getZ());
    }

    /** The bed's corner, for one placed at {@code clicked} by a player facing {@code facing}. */
    private BlockPos originFor(BlockPos clicked, Direction facing) {
        Direction right = facing.getClockWise();
        BlockPos far = clicked.relative(facing, size - 1).relative(right, size - 1);
        return new BlockPos(Math.min(clicked.getX(), far.getX()), clicked.getY(), Math.min(clicked.getZ(), far.getZ()));
    }

    private BlockState part(int x, int z) {
        return defaultBlockState().setValue(PART_X, x).setValue(PART_Z, z);
    }

    /** The rest of a bigger bed, once the block it was placed at is. */
    @Override
    public void setPlacedBy(
            Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) {
            return;
        }
        BlockPos origin = origin(pos, state);
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                BlockPos cell = origin.offset(x, 0, z);
                if (!cell.equals(pos)) {
                    level.setBlock(cell, part(x, z), Block.UPDATE_ALL);
                }
            }
        }
    }

    /** A block of a bed whose next block over (within the bed) has gone goes too. */
    @Override
    protected BlockState updateShape(
            BlockState state,
            Direction direction,
            BlockState neighbourState,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighbourPos) {
        if (direction.getAxis().isHorizontal()) {
            int x = state.getValue(PART_X) + direction.getStepX();
            int z = state.getValue(PART_Z) + direction.getStepZ();
            boolean inBed = x >= 0 && x < size && z >= 0 && z < size;
            if (inBed
                    && !(neighbourState.is(this)
                            && neighbourState.getValue(PART_X) == x
                            && neighbourState.getValue(PART_Z) == z)) {
                return Blocks.AIR.defaultBlockState();
            }
        }
        return state;
    }

    /**
     * Broken by a player: only its corner block drops the bed (see its loot table), so breaking any
     * other block of it breaks the corner too, dropping it there, as a vanilla bed's foot does its head.
     * In creative it drops nothing, whichever block is broken: the corner goes first, without its drop,
     * as it otherwise would with it when the rest of the bed falls away.
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && !isOrigin(state)) {
            BlockPos origin = origin(pos, state);
            BlockState corner = level.getBlockState(origin);
            if (corner.is(this) && isOrigin(corner)) {
                level.destroyBlock(origin, !player.isCreative(), player);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
