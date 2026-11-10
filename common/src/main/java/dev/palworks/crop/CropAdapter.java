package dev.palworks.crop;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter layer for crop detection and interaction.
 * Handles vanilla crops, stems, nether wart, cocoa, berries, etc.
 * Makes it easy to add modded crop support later.
 */
public class CropAdapter {
    // Cobblemon berry tag for modded berry support
    private static final TagKey<Block> COBBLEMON_BERRIES = TagKey.create(
        Registries.BLOCK,
        new ResourceLocation("cobblemon", "berries")
    );

    // Galarica nut bush - special berry that harvests at age 3 and resets to age 1
    private static final ResourceLocation GALARICA_NUT_BUSH_ID = new ResourceLocation("cobblemon", "galarica_nut_bush");

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
     * Check if a block is a berry that can be harvested by right-clicking.
     */
    private static boolean isBerry(BlockState state) {
        Block block = state.getBlock();

        // Vanilla berries
        if (block instanceof SweetBerryBushBlock ||
            block == Blocks.CAVE_VINES ||
            block == Blocks.CAVE_VINES_PLANT) {
            return true;
        }

        // Galarica nut bush
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
        if (GALARICA_NUT_BUSH_ID.equals(blockId)) {
            return true;
        }

        // Cobblemon berries (tagged)
        return state.is(COBBLEMON_BERRIES);
    }

    /**
     * Check if a block is a galarica nut bush.
     */
    private static boolean isGalaricaNutBush(Block block) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
        return GALARICA_NUT_BUSH_ID.equals(blockId);
    }

    /**
     * Check if a block is a fully grown crop ready for harvest.
     */
    public static boolean isFullyGrown(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        // Torchflowers and pitcher plants: harvested, but not replanted (see isPickedNotReplanted)
        if (isPickedNotReplanted(level, pos, state)) {
            return true;
        }

        // Standard crops with age property
        if (block instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }

        // Pumpkin/melon stems are never harvested themselves, even fully grown: their fruit is (see
        // attachedFruitPos / isAttachedFruit)
        if (block instanceof StemBlock) {
            return false;
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

        // Galarica nut bush - harvestable at age 3
        if (isGalaricaNutBush(block)) {
            for (Property<?> property : state.getProperties()) {
                if (property.getName().equals("age") && property instanceof IntegerProperty) {
                    IntegerProperty ageProperty = (IntegerProperty) property;
                    int age = state.getValue(ageProperty);
                    return age >= 3; // Galarica is harvestable at age 3
                }
            }
            return false;
        }

        // Cobblemon berries - harvestable at age 5
        // Cobblemon berry blocks use an AGE property (0-5)
        if (state.is(COBBLEMON_BERRIES)) {
            // Find the AGE property dynamically (Cobblemon berries use their own AGE property)
            for (Property<?> property : state.getProperties()) {
                if (property.getName().equals("age") && property instanceof IntegerProperty) {
                    IntegerProperty ageProperty = (IntegerProperty) property;
                    int age = state.getValue(ageProperty);
                    return age >= 5; // Cobblemon berries are harvestable at age 5
                }
            }
            // No age property found - not harvestable
            return false;
        }

        return false;
    }

    /**
     * A grown torchflower or pitcher plant on farmland. These are harvested by picking them (the
     * flower, or the pitcher plant, is what they drop), leaving the farmland empty instead of
     * replanting: they don't drop their seed back (torchflower seeds and pitcher pods come from
     * sniffers), so there's no seed to pay for a replant. A torchflower crop turns into a plain
     * torchflower when it's grown, so the flower counts only on farmland, not in a flower bed. A
     * pitcher crop is two blocks tall; its lower half is the one harvested.
     */
    public static boolean isPickedNotReplanted(Level level, BlockPos pos, BlockState state) {
        if (state.is(Blocks.TORCHFLOWER)) {
            return level.getBlockState(pos.below()).is(Blocks.FARMLAND);
        }
        if (state.is(Blocks.PITCHER_CROP)) {
            return state.getValue(PitcherCropBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                && state.getValue(PitcherCropBlock.AGE) >= PitcherCropBlock.MAX_AGE;
        }
        return false;
    }

    /**
     * For an attached stem (a pumpkin or melon stem, or a modded gourd's, that has grown its fruit),
     * the position of that fruit; otherwise null. Harvest jobs for gourds are found from the stem so
     * the chunk scan doesn't have to check every block's neighbours.
     */
    @Nullable
    public static BlockPos attachedFruitPos(BlockState state, BlockPos stemPos) {
        if (state.getBlock() instanceof AttachedStemBlock) {
            return stemPos.relative(state.getValue(AttachedStemBlock.FACING));
        }
        return null;
    }

    /** Whether the block at {@code pos} is the fruit of an attached stem next to it. */
    public static boolean isAttachedFruit(Level level, BlockPos pos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockState neighbor = level.getBlockState(pos.relative(dir));
            if (neighbor.getBlock() instanceof AttachedStemBlock
                    && neighbor.getValue(AttachedStemBlock.FACING) == dir.getOpposite()) {
                return true;
            }
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
               block == Blocks.CAVE_VINES_PLANT ||
               state.is(COBBLEMON_BERRIES);
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
     * The item that plants the crop at {@code pos}: its pick-block item (wheat seeds for wheat, a
     * carrot for carrots, cocoa beans for cocoa, nether wart for nether wart; modded crops' own
     * seeds). Null if the block has none.
     */
    @Nullable
    private static Item seedOf(Level level, BlockPos pos, BlockState state) {
        ItemStack clone = state.getBlock().getCloneItemStack(level, pos, state);
        return clone.isEmpty() ? null : clone.getItem();
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
     * Whether an item is a seed or other plantable item (what a planter's seed pouch may hold).
     */
    public static boolean isSeed(Item item) {
        return getPlantedBlock(item) != null;
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
        // A pumpkin, melon (or modded gourd) grown by a stem next to it: break it like a player
        // would, with its normal drops. The stem detaches by itself and grows another.
        if (isAttachedFruit(level, pos)) {
            level.destroyBlock(pos, true);
            return true;
        }

        if (!isFullyGrown(level, pos)) {
            return false;
        }

        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        // Torchflowers and pitcher plants: picked with their normal drops, not replanted (breaking a
        // pitcher's lower half takes the upper half with it)
        if (isPickedNotReplanted(level, pos, state)) {
            level.destroyBlock(pos, true);
            return true;
        }

        // Berries (vanilla and Cobblemon) are harvested and reset their age
        if (isBerry(state)) {
            // Cobblemon berry blocks (incl. Galarica nut bush) carry a BerryBlockEntity. Delegate to
            // Cobblemon's own harvest(level, state, pos): it computes the correct yield AND resets the
            // growth timers + age stage. Previously we only lowered the blockstate `age` and left the
            // block entity mature, so Cobblemon re-grew it to max next tick == infinite berries.
            net.minecraft.world.level.block.entity.BlockEntity berryBe = level.getBlockEntity(pos);
            if (berryBe instanceof com.cobblemon.mod.common.block.entity.BerryBlockEntity berryEntity) {
                int dropped = 0;
                for (ItemStack drop : berryEntity.harvest(level, state, pos)) {
                    if (!drop.isEmpty()) {
                        Block.popResource(level, pos, drop);
                        dropped += drop.getCount();
                    }
                }

                // Safety net: if Cobblemon's harvest left the berry still fully grown, reset its grow
                // timers so it can't instantly re-mature (the infinite-berry bug).
                if (isFullyGrown(level, pos)) {
                    berryEntity.resetGrowTimers(pos, level.getBlockState(pos));
                }

                // Some berries' harvest() yields nothing (e.g. no growth points generated); make sure
                // the worker still gets a berry so harvesting never silently does nothing. (A player
                // right-clicking these would also get nothing, so this is strictly more forgiving.)
                if (dropped == 0) {
                    Block.popResource(level, pos, new ItemStack(block.asItem(), 1 + level.random.nextInt(3)));
                }

                // Match the right-click harvest's sound for parity.
                level.playSound(null, pos, com.cobblemon.mod.common.CobblemonSounds.BERRY_HARVEST,
                    net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f);

                dev.palworks.Palworks.LOGGER.info("Harvested Cobblemon berry at {} ({} dropped from Cobblemon harvest)", pos, dropped);
                return true;
            }

            // Galarica nut bush - drop berries and reset to age 1
            if (isGalaricaNutBush(block)) {
                Item berryItem = block.asItem();
                int berryCount = 1 + level.random.nextInt(3); // 1-3 berries
                Block.popResource(level, pos, new ItemStack(berryItem, berryCount));

                // Find AGE property and reset to 1
                IntegerProperty ageProperty = null;
                for (Property<?> property : state.getProperties()) {
                    if (property.getName().equals("age") && property instanceof IntegerProperty) {
                        ageProperty = (IntegerProperty) property;
                        break;
                    }
                }

                if (ageProperty != null) {
                    int currentAge = state.getValue(ageProperty);
                    level.setBlock(pos, state.setValue(ageProperty, 1), 3);

                    dev.palworks.Palworks.LOGGER.info("Harvested Galarica nut at {}: was age {}, dropped {} x{}, reset to age 1",
                        pos, currentAge, berryItem, berryCount);
                }

                return true;
            }

            // Vanilla sweet berries - drop berries and reset to age 1
            if (block instanceof SweetBerryBushBlock) {
                int berryCount = 1 + level.random.nextInt(2); // 1-2 berries
                Block.popResource(level, pos, new ItemStack(Items.SWEET_BERRIES, berryCount));
                level.setBlock(pos, state.setValue(SweetBerryBushBlock.AGE, 1), 2);
                dev.palworks.Palworks.LOGGER.info("Harvested sweet berries, dropped {}, reset to age 1 at {}", berryCount, pos);
                return true;
            }

            // Glow berries - drop berries and remove berries from vine
            if (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT) {
                Block.popResource(level, pos, new ItemStack(Items.GLOW_BERRIES, 1));
                level.setBlock(pos, state.setValue(net.minecraft.world.level.block.CaveVines.BERRIES, false), 2);
                dev.palworks.Palworks.LOGGER.info("Harvested glow berries at {}", pos);
                return true;
            }

            return true;
        }

        // Check if this is hearty grains (multi-block crop)
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
        boolean isHeartyGrains = blockId.toString().contains("hearty_grain");

        if (isHeartyGrains) {
            // Hearty grains - two-block crop, reset bottom to age 3 and break top
            IntegerProperty ageProperty = null;
            for (Property<?> property : state.getProperties()) {
                if (property.getName().equals("age") && property instanceof IntegerProperty) {
                    ageProperty = (IntegerProperty) property;
                    break;
                }
            }

            if (ageProperty != null) {
                int currentAge = state.getValue(ageProperty);

                // Get the crop item and drop it
                Item cropItem = block.asItem();
                int dropCount = 1 + level.random.nextInt(3);
                Block.popResource(level, pos, new ItemStack(cropItem, dropCount));

                // Reset bottom block to age 3
                level.setBlock(pos, state.setValue(ageProperty, 3), 3);

                // Break the top block (one block above)
                BlockPos topPos = pos.above();
                BlockState topState = level.getBlockState(topPos);
                if (topState.getBlock() == block) {
                    level.destroyBlock(topPos, false); // Don't drop items from top block
                }

                dev.palworks.Palworks.LOGGER.info("Harvested hearty grains at {}: was age {}, dropped {} x{}, reset to age 3, broke top block",
                    pos, currentAge, cropItem, dropCount);
                return true;
            }
        }

        // Other crops - check if they have an age property
        IntegerProperty ageProperty = null;
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals("age") && property instanceof IntegerProperty) {
                ageProperty = (IntegerProperty) property;
                break;
            }
        }

        if (ageProperty != null) {
            // This crop has an age property - harvest and reset to age 0
            int currentAge = state.getValue(ageProperty);
            int maxAge = ageProperty.getPossibleValues().stream().max(Integer::compareTo).orElse(7);

            // Only harvest if at max age
            if (currentAge >= maxAge) {
                // Harvest in place, like right-click-harvest mods: drop the crop's real loot (what
                // breaking it by hand gives, e.g. wheat + 1-4 seeds), minus one seed that pays for
                // replanting, and reset it to age 0. This used to drop block.asItem() instead, which
                // for wheat and beetroot is the seed, so those fields never produced any crop.
                List<ItemStack> drops = level instanceof ServerLevel serverLevel
                    ? Block.getDrops(state, serverLevel, pos, level.getBlockEntity(pos))
                    : new ArrayList<>();
                if (drops.isEmpty()) {
                    // No loot table result (some modded crops): keep the old behaviour
                    drops = new ArrayList<>(List.of(new ItemStack(block.asItem(), 1 + level.random.nextInt(3))));
                }

                // Every vanilla crop drops at least one of its seed when mature, so there's always
                // one to take (wheat/beetroot 1-4 seeds, cocoa 3 beans, carrots, potatoes, wart)
                Item seed = seedOf(level, pos, state);
                if (seed != null) {
                    takeOne(drops, seed);
                }
                for (ItemStack drop : drops) {
                    if (!drop.isEmpty()) {
                        Block.popResource(level, pos, drop);
                    }
                }

                level.setBlock(pos, state.setValue(ageProperty, 0), 3);

                dev.palworks.Palworks.LOGGER.debug("Harvested crop at {}: was age {}, dropped {}, reset to age 0",
                    pos, currentAge, drops);
                return true;
            }
        }

        // Default: break the block completely (for crops like wheat, carrots, etc.)
        level.destroyBlock(pos, true);
        return true;
    }

    /** Remove one of {@code item} from a list of drops, if there is one. */
    private static void takeOne(List<ItemStack> drops, Item item) {
        for (ItemStack drop : drops) {
            if (!drop.isEmpty() && drop.is(item)) {
                drop.shrink(1);
                return;
            }
        }
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
