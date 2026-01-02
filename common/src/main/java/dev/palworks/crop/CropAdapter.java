package dev.palworks.crop;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter layer for crop detection and interaction.
 * Handles vanilla crops, stems, nether wart, cocoa, berries, etc.
 * Makes it easy to add modded crop support later.
 */
public class CropAdapter {

    /**
     * Check if a block is a plantable crop surface (e.g., farmland).
     */
    public static boolean isPlantableSurface(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        // Farmland for most crops (grass types with PLANTING)
        if (block == Blocks.FARMLAND) {
            return true;
        }

        // Soul sand for nether wart (requires both PLANTING and NETHER suitabilities)
        if (block == Blocks.SOUL_SAND) {
            return true;
        }

        // Note: Cocoa beans plant on jungle logs (checked separately)
        return false;
    }

    /**
     * Check if a block is a fully grown crop ready for harvest.
     */
    public static boolean isFullyGrown(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        // Standard crops with age property
        if (block instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }

        // Stems (pumpkin/melon stems)
        if (block instanceof StemBlock) {
            return state.getValue(StemBlock.AGE) >= StemBlock.MAX_AGE;
        }

        // Nether wart
        if (block instanceof NetherWartBlock) {
            return state.getValue(NetherWartBlock.AGE) >= 3;
        }

        // Cocoa beans
        if (block instanceof CocoaBlock) {
            return state.getValue(CocoaBlock.AGE) >= 2;
        }

        // Sweet berry bush
        if (block instanceof SweetBerryBushBlock) {
            return state.getValue(SweetBerryBushBlock.AGE) >= 3;
        }

        // Glow berries (cave vines)
        if (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT) {
            return state.getValue(net.minecraft.world.level.block.CaveVines.BERRIES);
        }

        return false;
    }

    /**
     * Check if a block is a crop (not necessarily fully grown).
     */
    public static boolean isCrop(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        return block instanceof CropBlock ||
               block instanceof StemBlock ||
               block instanceof NetherWartBlock ||
               block instanceof CocoaBlock ||
               block instanceof SweetBerryBushBlock ||
               block == Blocks.CAVE_VINES ||
               block == Blocks.CAVE_VINES_PLANT;
    }

    /**
     * Get the seed/item needed to plant this crop type.
     * Returns null if the block at pos is not a plantable surface.
     */
    @Nullable
    public static Item getSeedForSurface(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        // Most seeds are planted on farmland
        if (block == Blocks.FARMLAND) {
            // Default to wheat seeds - specific seed type would be
            // determined by what's in the worker's inventory
            return Items.WHEAT_SEEDS;
        }

        // Nether wart on soul sand
        if (block == Blocks.SOUL_SAND) {
            return Items.NETHER_WART;
        }

        return null;
    }

    /**
     * Check if a seed item can be planted on this surface.
     */
    public static boolean canPlantSeed(Item seed, Level level, BlockPos surfacePos) {
        BlockState state = level.getBlockState(surfacePos);
        Block block = state.getBlock();

        // Farmland crops
        if (block == Blocks.FARMLAND) {
            return seed == Items.WHEAT_SEEDS ||
                   seed == Items.CARROT ||
                   seed == Items.POTATO ||
                   seed == Items.BEETROOT_SEEDS ||
                   seed == Items.PUMPKIN_SEEDS ||
                   seed == Items.MELON_SEEDS;
        }

        // Nether wart on soul sand
        if (block == Blocks.SOUL_SAND) {
            return seed == Items.NETHER_WART;
        }

        // Cocoa on jungle logs (checked at planting time)
        return false;
    }

    /**
     * Get the block that should be placed when planting a seed.
     * Returns null if the seed cannot be planted.
     */
    @Nullable
    public static Block getPlantedBlock(Item seed) {
        if (seed == Items.WHEAT_SEEDS) return Blocks.WHEAT;
        if (seed == Items.CARROT) return Blocks.CARROTS;
        if (seed == Items.POTATO) return Blocks.POTATOES;
        if (seed == Items.BEETROOT_SEEDS) return Blocks.BEETROOTS;
        if (seed == Items.PUMPKIN_SEEDS) return Blocks.PUMPKIN_STEM;
        if (seed == Items.MELON_SEEDS) return Blocks.MELON_STEM;
        if (seed == Items.NETHER_WART) return Blocks.NETHER_WART;
        if (seed == Items.COCOA_BEANS) return Blocks.COCOA;
        if (seed == Items.SWEET_BERRIES) return Blocks.SWEET_BERRY_BUSH;

        return null;
    }

    /**
     * Get drops from harvesting a crop at the given position.
     * This simulates what would drop when the crop is broken.
     */
    public static List<ItemStack> getHarvestDrops(Level level, BlockPos pos) {
        List<ItemStack> drops = new ArrayList<>();
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        if (!isFullyGrown(level, pos)) {
            return drops; // No drops if not fully grown
        }

        // Standard crops - simplified (real implementation would use loot tables)
        if (block == Blocks.WHEAT) {
            drops.add(new ItemStack(Items.WHEAT, 1));
            drops.add(new ItemStack(Items.WHEAT_SEEDS, level.random.nextInt(3) + 1));
        } else if (block == Blocks.CARROTS) {
            drops.add(new ItemStack(Items.CARROT, level.random.nextInt(3) + 2));
        } else if (block == Blocks.POTATOES) {
            drops.add(new ItemStack(Items.POTATO, level.random.nextInt(3) + 2));
        } else if (block == Blocks.BEETROOTS) {
            drops.add(new ItemStack(Items.BEETROOT, 1));
            drops.add(new ItemStack(Items.BEETROOT_SEEDS, level.random.nextInt(3) + 1));
        } else if (block == Blocks.NETHER_WART) {
            drops.add(new ItemStack(Items.NETHER_WART, level.random.nextInt(3) + 2));
        } else if (block == Blocks.COCOA) {
            drops.add(new ItemStack(Items.COCOA_BEANS, 3));
        } else if (block == Blocks.SWEET_BERRY_BUSH) {
            drops.add(new ItemStack(Items.SWEET_BERRIES, level.random.nextInt(2) + 2));
        }

        return drops;
    }

    /**
     * Harvest a crop at the given position.
     * Returns true if successfully harvested.
     */
    public static boolean harvestCrop(Level level, BlockPos pos) {
        if (!isFullyGrown(level, pos)) {
            return false;
        }

        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        // Sweet berry bushes reset to age 1 when harvested, don't break
        if (block instanceof SweetBerryBushBlock) {
            // Drop sweet berries
            int berryCount = 1 + level.random.nextInt(2); // 1-2 berries
            net.minecraft.world.level.block.Block.popResource(level, pos, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SWEET_BERRIES, berryCount));
            level.setBlock(pos, state.setValue(SweetBerryBushBlock.AGE, 1), 2);
            return true;
        }

        // Glow berries - just remove the berries, don't break the vine
        if (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT) {
            // Drop the glow berry
            net.minecraft.world.level.block.Block.popResource(level, pos, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GLOW_BERRIES, 1));
            // Remove berries from the vine
            level.setBlock(pos, state.setValue(net.minecraft.world.level.block.CaveVines.BERRIES, false), 2);
            return true;
        }

        // Other crops break completely
        level.destroyBlock(pos, true);
        return true;
    }

    /**
     * Plant a seed at the given position above the surface.
     * Returns true if successfully planted.
     */
    public static boolean plantSeed(Item seed, Level level, BlockPos surfacePos) {
        BlockPos plantPos = surfacePos.above();

        // Check if space is clear
        if (!level.getBlockState(plantPos).isAir()) {
            return false;
        }

        // Check if surface is valid
        if (!canPlantSeed(seed, level, surfacePos)) {
            return false;
        }

        // Get the block to plant
        Block plantBlock = getPlantedBlock(seed);
        if (plantBlock == null) {
            return false;
        }

        // Special case: cocoa beans need jungle log
        if (seed == Items.COCOA_BEANS) {
            // Check adjacent blocks for jungle logs
            // Simplified - real implementation would check all directions
            return false; // TODO: Implement cocoa planting logic
        }

        // Plant the crop
        level.setBlock(plantPos, plantBlock.defaultBlockState(), 3);
        return true;
    }

    /**
     * Check if light level is sufficient for crop growth.
     * Most crops need light level >= 9.
     */
    public static boolean hasValidLightLevel(Level level, BlockPos pos) {
        return level.getRawBrightness(pos, 0) >= 9;
    }
}
