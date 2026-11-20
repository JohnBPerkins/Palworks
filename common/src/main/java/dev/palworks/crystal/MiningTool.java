package dev.palworks.crystal;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * The pickaxe a miner's Mining level stands for, which what it mines drops as if broken with:
 * level 1 a wooden pickaxe, 2 a diamond one, 3 to 5 a diamond one with Fortune I to III. The game's
 * own loot for that tool, so a worker still gets only what a player could.
 *
 * <p>Wood or diamond decides what it can break, not what a crystal drops (any pickaxe gets a
 * crystal's full loot): it counts once workers mine ores.
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
}
