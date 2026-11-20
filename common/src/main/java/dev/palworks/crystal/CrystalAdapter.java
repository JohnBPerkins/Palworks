package dev.palworks.crystal;

import com.cobblemon.mod.common.CobblemonSounds;
import com.cobblemon.mod.common.block.GrowableStoneBlock;
import com.cobblemon.mod.common.block.TumblestoneBlock;
import com.cobblemon.mod.common.block.TypeGemClusterBlock;
import com.cobblemon.mod.common.block.TypeGemCoreBlock;
import com.cobblemon.mod.common.item.TumblestoneItem;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.jetbrains.annotations.Nullable;

/**
 * Crystals that grow back where they're broken, for Mining workers: amethyst clusters, tumblestone
 * and Cobblemon's type gem clusters. Only the fully grown crystal is work; what it grows from
 * (budding amethyst, the block a tumblestone sits on, gem blocks and the deepslate crystal core) is
 * never touched, so a crystal farm keeps growing more. Harvesting gives what breaking it by hand
 * does:
 * <ul>
 *   <li>Amethyst cluster: broken, its loot (2 shards by hand). Budding amethyst grows another.</li>
 *   <li>Tumblestone cluster (the last of its four stages, any colour): broken, its loot (4
 *       tumblestone by hand), and a new one put back on the same face from one of them, as a
 *       player would with the item (see {@link TumblestoneItem#useOn}).</li>
 *   <li>Type gem cluster at its last stage (3): broken, its loot (2-4 gems by hand). Gem blocks
 *       joined to a crystal core grow new clusters on their open faces. Left alone, a grown cluster
 *       turns into a gem block itself; harvested, it doesn't, so a tended formation stays its size.</li>
 * </ul>
 * Buds and smaller stages drop nothing (amethyst) or less (gems), so they're left to grow.
 */
public final class CrystalAdapter {
    private CrystalAdapter() {}

    /** How many stages up a tumblestone's growth to follow (each colour has four). */
    private static final int MAX_STAGES = 8;

    // Cobblemon's gem blocks, which gem clusters grow on. By id: its tag fields differ across 1.8.x.
    private static final TagKey<Block> TYPE_GEM_BLOCKS =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("cobblemon", "type_gem_blocks"));

    /**
     * Whether a block is a kind of crystal that's mined once it's grown (see {@link #isRipe}): an
     * amethyst cluster, a tumblestone, a type gem cluster. Nothing else ever is, so it's what work
     * this kind of block takes whatever its state (the "Worked by" tooltip).
     */
    public static boolean isCrystal(BlockState state) {
        Block block = state.getBlock();
        return state.is(Blocks.AMETHYST_CLUSTER)
                || block instanceof TumblestoneBlock
                || block instanceof TypeGemClusterBlock;
    }

    /**
     * Whether the crystal at {@code pos} is one to mine: fully grown, and where another grows in
     * its place. An amethyst cluster only on budding amethyst, a gem cluster only on a gem block or
     * a crystal core (what Cobblemon grows them on); a tumblestone cluster anywhere, since it's put
     * back from its own drops. One put somewhere else to look at (a cluster on a wall) is left
     * alone: mined, it'd be gone for good.
     */
    public static boolean isRipe(BlockGetter level, BlockPos pos, BlockState state) {
        if (!isCrystal(state)) {
            return false;
        }
        Block block = state.getBlock();
        if (state.is(Blocks.AMETHYST_CLUSTER)) {
            return supportOf(level, pos, state).is(Blocks.BUDDING_AMETHYST);
        }
        if (block instanceof TumblestoneBlock tumblestone) {
            return tumblestone.getNextStage() == null; // the cluster: it grows no further
        }
        if (block instanceof TypeGemClusterBlock) {
            IntegerProperty stage = TypeGemClusterBlock.Companion.getSTAGE();
            if (!state.hasProperty(stage)
                    || state.getValue(stage).intValue() != Collections.max(stage.getPossibleValues())) {
                return false;
            }
            BlockState support = supportOf(level, pos, state);
            return support.is(TYPE_GEM_BLOCKS) || support.getBlock() instanceof TypeGemCoreBlock;
        }
        return false;
    }

    /** The block a crystal grows out of: the one behind the face it's on. */
    private static BlockState supportOf(BlockGetter level, BlockPos pos, BlockState crystal) {
        return level.getBlockState(
                pos.relative(crystal.getValue(DirectionalBlock.FACING).getOpposite()));
    }

    /**
     * Mine the crystal at {@code pos} as {@code miner}, as a player breaking it with {@code tool}
     * would (see {@link MiningTool}): its own loot for that tool, and a tumblestone put back.
     * Returns whether there was a ripe crystal there.
     */
    public static boolean harvest(Level level, BlockPos pos, @Nullable Entity miner, ItemStack tool) {
        BlockState state = level.getBlockState(pos);
        if (!isRipe(level, pos, state)) {
            return false;
        }
        if (state.getBlock() instanceof TumblestoneBlock
                && level instanceof ServerLevel serverLevel
                && replantTumblestone(serverLevel, pos, state, miner, tool)) {
            return true;
        }
        // As a player's break: the block goes (its particles, sound and game event), then its loot
        // is rolled with the tool in hand
        BlockEntity blockEntity = level.getBlockEntity(pos);
        level.destroyBlock(pos, false, miner);
        if (level instanceof ServerLevel serverLevel) {
            Block.dropResources(state, serverLevel, pos, blockEntity, miner, tool);
        }
        return true;
    }

    /**
     * Break a tumblestone cluster and put a new one back from its drops, as a player breaking it
     * and using one of the tumblestone on the same face would. False (nothing done) where a player
     * couldn't put one back: under water (the cluster leaves water, and the item needs air), on a
     * face no longer sturdy, or with no tumblestone of its colour in the loot.
     */
    private static boolean replantTumblestone(
            ServerLevel level, BlockPos pos, BlockState cluster, @Nullable Entity miner, ItemStack tool) {
        Direction facing = cluster.getValue(DirectionalBlock.FACING);
        BlockPos support = pos.relative(facing.getOpposite());
        if (cluster.getValue(BlockStateProperties.WATERLOGGED)
                || !level.getBlockState(support).isFaceSturdy(level, support, facing)) {
            return false;
        }
        List<ItemStack> drops = Block.getDrops(cluster, level, pos, level.getBlockEntity(pos), miner, tool);
        Block seedling = takeTumblestoneFor(drops, cluster.getBlock());
        if (seedling == null) {
            return false;
        }
        // The item places its block's default state on the face: not waterlogged, facing out
        BlockState regrown = seedling.defaultBlockState().setValue(DirectionalBlock.FACING, facing);
        level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(cluster));
        for (ItemStack drop : drops) {
            if (!drop.isEmpty()) {
                Block.popResource(level, pos, drop);
            }
        }
        level.setBlock(pos, regrown, Block.UPDATE_ALL);
        level.playSound(null, support, CobblemonSounds.TUMBLESTONE_PLACE, SoundSource.BLOCKS);
        return true;
    }

    /**
     * Take one tumblestone out of the drops that grows into this cluster (the same colour), and
     * return the block it places; null if there's none.
     */
    @Nullable
    private static Block takeTumblestoneFor(List<ItemStack> drops, Block cluster) {
        for (ItemStack drop : drops) {
            if (!drop.isEmpty()
                    && drop.getItem() instanceof TumblestoneItem item
                    && growsInto(item.getBlock(), cluster)) {
                drop.shrink(1);
                return item.getBlock();
            }
        }
        return null;
    }

    /** Whether a tumblestone placed as {@code start} grows, stage by stage, into {@code cluster}. */
    private static boolean growsInto(@Nullable Block start, Block cluster) {
        Block block = start;
        for (int i = 0; i < MAX_STAGES && block instanceof GrowableStoneBlock stone; i++) {
            if (block.equals(cluster)) {
                return true;
            }
            block = stone.getNextStage();
        }
        return false;
    }
}
