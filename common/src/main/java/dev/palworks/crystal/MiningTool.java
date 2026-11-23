package dev.palworks.crystal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The pickaxe a miner's Mining level stands for, which what it mines drops as if broken with:
 * level 1 a wooden pickaxe, 2 a diamond one, 3 to 5 a diamond one with Fortune I to III. The game's
 * own loot for that tool, so a worker still gets only what a player could.
 *
 * <p>Wood or diamond decides what it gets anything from: a crystal gives its full loot to any
 * pickaxe, but a wooden one gets nothing from most ores (iron, gold, diamond...), so a level 1 miner
 * leaves those in a boulder for a better one (see {@link #levelToHarvest}).
 */
public final class MiningTool {
    private static final int MAX_FORTUNE = 3;

    private MiningTool() {}

    /** The Fortune level a miner of this Mining level mines with: 0 below level 3, then 1 to 3. */
    public static int fortune(int miningLevel) {
        return Math.clamp(miningLevel - 2, 0, MAX_FORTUNE);
    }

    /** Whether a miner of this Mining level mines with a diamond pickaxe (else a wooden one). */
    public static boolean isDiamond(int miningLevel) {
        return miningLevel >= 2;
    }

    /** The pickaxe for this Mining level, enchanted from the world's registries. */
    public static ItemStack forLevel(HolderLookup.Provider registries, int miningLevel) {
        ItemStack pickaxe = new ItemStack(isDiamond(miningLevel) ? Items.DIAMOND_PICKAXE : Items.WOODEN_PICKAXE);
        int fortune = fortune(miningLevel);
        if (fortune > 0) {
            pickaxe.enchant(registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FORTUNE), fortune);
        }
        return pickaxe;
    }

    /**
     * The lowest Mining level that gets this block's drops: 1 if a wooden pickaxe does (stone, coal
     * ore, any block that needs no particular tool), else 2, the diamond pickaxe, which gets every
     * block's.
     */
    public static int levelToHarvest(BlockState state) {
        return !state.requiresCorrectToolForDrops() || Wooden.PICKAXE.isCorrectToolForDrops(state) ? 1 : 2;
    }

    /**
     * Break the block at {@code pos} as {@code miner}, as a player breaking it with {@code tool}
     * would: it goes (its particles, sound and game event), then its loot is rolled with the tool in
     * hand, with the experience an ore gives.
     */
    public static void breakWith(Level level, BlockPos pos, @Nullable Entity miner, ItemStack tool) {
        BlockState state = level.getBlockState(pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        level.destroyBlock(pos, false, miner);
        if (level instanceof ServerLevel serverLevel) {
            Block.dropResources(state, serverLevel, pos, blockEntity, miner, tool);
        }
    }

    // A wooden pickaxe, never changed, so shared. In a class of its own so it's only made when it's
    // first asked for: making an item stack needs the game's registries, which unit tests don't have
    private static final class Wooden {
        static final ItemStack PICKAXE = new ItemStack(Items.WOODEN_PICKAXE);
    }
}
