package dev.palworks.crop;

import com.cobblemon.mod.common.block.ApricornBlock;
import com.cobblemon.mod.common.block.HeartyGrainsBlock;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

/**
 * Adapter layer for crop detection and interaction.
 * Handles vanilla crops, stems, nether wart, cocoa, berries, apricorns, etc.
 * Makes it easy to add modded crop support later.
 */
public class CropAdapter {
    // The property of a Cobblemon crop that says whether (and how) it has mutated
    private static final String MUTATION = "mutation";
    // Ages at which Cobblemon's galarica nut bushes and berry bushes are ripe
    private static final int GALARICA_RIPE_AGE = 3;
    private static final int BERRY_RIPE_AGE = 5;

    // Cobblemon berry tag for modded berry support
    private static final TagKey<Block> COBBLEMON_BERRIES =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("cobblemon", "berries"));

    // Galarica nut bush - special berry that harvests at age 3 and resets to age 1
    private static final ResourceLocation GALARICA_NUT_BUSH_ID =
            ResourceLocation.fromNamespaceAndPath("cobblemon", "galarica_nut_bush");

    /**
     * Check if a block is a plantable crop surface (e.g., farmland).
     */
    public static boolean isPlantableSurface(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        // Farmland for most crops (grass types with PLANTING)
        if (state.is(Blocks.FARMLAND)) {
            return true;
        }

        // Soul sand for nether wart (requires both PLANTING and NETHER suitabilities)
        if (state.is(Blocks.SOUL_SAND)) {
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
        if (block instanceof SweetBerryBushBlock || state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT)) {
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

    /** Whether a block is one of Cobblemon's apricorns (the fruit hanging off apricorn leaves). */
    public static boolean isApricorn(BlockState state) {
        return state.getBlock() instanceof ApricornBlock;
    }

    /**
     * A fruit hanging off the side of another block: a cocoa pod on a jungle log, an apricorn on
     * apricorn leaves. Both have a horizontal facing (towards what they hang from), so they aren't
     * stations to stand in front of, and they hang above the ground: up a trunk, or in a canopy.
     */
    public static boolean isHangingFruit(BlockState state) {
        return state.getBlock() instanceof CocoaBlock || isApricorn(state);
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

        // A two-block crop (hearty grains, a pitcher plant) is harvested from its lower half, which
        // takes the top with it. Its upper half is just as grown, and counted as a crop of its own it
        // got a second job: harvesting it as if it were a whole plant left a stub on a ripe base.
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return false;
        }

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

        // Apricorns (Cobblemon): ripe at their last stage. Their age is AGE_3 (0-3).
        if (isApricorn(state)) {
            return state.getValue(BlockStateProperties.AGE_3) >= BlockStateProperties.MAX_AGE_3;
        }

        // Sweet berry bush
        if (block instanceof SweetBerryBushBlock) {
            return state.getValue(SweetBerryBushBlock.AGE) >= 3;
        }

        // Glow berries (cave vines)
        if (state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT)) {
            return state.getValue(net.minecraft.world.level.block.CaveVines.BERRIES);
        }

        // Galarica nut bush - harvestable at age 3
        if (isGalaricaNutBush(block)) {
            IntegerProperty age = ageProperty(state);
            return age != null && state.getValue(age) >= GALARICA_RIPE_AGE;
        }

        // Cobblemon berries - harvestable at age 5
        // Cobblemon berry blocks use an AGE property (0-5)
        if (state.is(COBBLEMON_BERRIES)) {
            // Cobblemon berries use their own AGE property; no age property, not harvestable
            IntegerProperty age = ageProperty(state);
            return age != null && state.getValue(age) >= BERRY_RIPE_AGE;
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
            return state.getValue(PitcherCropBlock.HALF)
                            == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
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

        return block instanceof CropBlock
                || block instanceof StemBlock
                || block instanceof NetherWartBlock
                || block instanceof CocoaBlock
                || isApricorn(state)
                || block instanceof SweetBerryBushBlock
                || state.is(Blocks.CAVE_VINES)
                || state.is(Blocks.CAVE_VINES_PLANT)
                || state.is(COBBLEMON_BERRIES);
    }

    /**
     * Whether the block at {@code pos} is a crop still growing, which watering helps along (see
     * Watering): a field crop, a stem, a berry bush, a pitcher plant, by its own rule for whether bone
     * meal would grow it any more. A two-block crop is watered from its lower half. Not nether wart
     * (Nether work, and bone meal doesn't grow it), nor a fruit hanging in a tree (cocoa, apricorns),
     * nor glow berries.
     */
    public static boolean isGrowing(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        boolean watered = block instanceof CropBlock
                || block instanceof StemBlock
                || block instanceof SweetBerryBushBlock
                || block instanceof PitcherCropBlock
                || state.is(COBBLEMON_BERRIES);
        boolean upperHalf = state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
        if (!watered || upperHalf) {
            return false;
        }
        return block instanceof BonemealableBlock crop && crop.isValidBonemealTarget(level, pos, state);
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
     * Whether a seed can be planted on this surface (farmland, or soul sand for nether wart): it
     * plants a crop (see {@link #fieldCropOf}), and that crop can grow on top of it, by the crop's
     * own rules, the ones that stop a player planting it there: wheat or mint on farmland, not on
     * soul sand; nether wart only on soul sand; nothing in the dark; medicinal leek (which grows in
     * water) never on farmland.
     */
    public static boolean canPlantSeed(Item seed, Level level, BlockPos surfacePos) {
        Block crop = fieldCropOf(seed);
        if (crop == null || !isPlantableSurface(level, surfacePos)) {
            return false;
        }
        return crop.defaultBlockState().canSurvive(level, surfacePos.above());
    }

    /**
     * The crop a seed plants in a field (on farmland, or soul sand), or null if it doesn't plant
     * one: an item that places a crop block (wheat seeds, a carrot, pumpkin seeds, nether wart,
     * Cobblemon's mint and vivichoke seeds, revival herbs, hearty grains, and modded crops' seeds
     * too). Worked out from the item itself, so any mod's crop works without being listed.
     */
    @Nullable
    public static Block fieldCropOf(Item seed) {
        if (seed instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            if (block instanceof CropBlock || block instanceof StemBlock || block instanceof NetherWartBlock) {
                return block;
            }
        }
        return null;
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
        Block crop = fieldCropOf(seed);
        if (crop != null) return crop;
        if (seed.equals(Items.COCOA_BEANS)) return Blocks.COCOA;
        if (seed.equals(Items.SWEET_BERRIES)) return Blocks.SWEET_BERRY_BUSH;

        return null;
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

        // Apricorns: Cobblemon's own harvest, what picking one by hand does: its loot (the apricorn,
        // sometimes a seed) and back to its first stage, still hanging there. Not the generic path
        // below, which takes one "seed" back to pay for a replant: an apricorn's pick item is the
        // apricorn itself, so that left the worker nothing.
        if (block instanceof ApricornBlock apricorn) {
            apricorn.harvest(level, state, pos);
            level.playSound(
                    null,
                    pos,
                    net.minecraft.sounds.SoundEvents.ITEM_PICKUP,
                    net.minecraft.sounds.SoundSource.BLOCKS,
                    0.7f,
                    1.4f);
            return true;
        }

        // Berries (vanilla and Cobblemon): picked exactly as by hand, with the same yield, sound and
        // resulting state as the game's own right-click code
        if (isBerry(state)) {
            // Cobblemon berries: Cobblemon's own harvest. The bush's berries were worked out as it
            // grew (its block entity's growth points); harvest() hands them over and takes the bush
            // back to age 3 with fresh grow timers. (Lowering only the blockstate age left the block
            // entity mature, so Cobblemon regrew it next tick: infinite berries.) A bush that never
            // grew them (set ripe by a command or another mod) gives nothing, as it does by hand.
            net.minecraft.world.level.block.entity.BlockEntity berryBe = level.getBlockEntity(pos);
            if (berryBe instanceof com.cobblemon.mod.common.block.entity.BerryBlockEntity berryEntity) {
                for (ItemStack drop : berryEntity.harvest(level, state, pos)) {
                    if (!drop.isEmpty()) {
                        Block.popResource(level, pos, drop);
                    }
                }
                level.playSound(
                        null,
                        pos,
                        com.cobblemon.mod.common.CobblemonSounds.BERRY_HARVEST,
                        net.minecraft.sounds.SoundSource.BLOCKS,
                        1.0f,
                        1.0f);
                return true;
            }

            // Galarica nut bush: as NutBushBlock.useWithoutItem, 1-2 nuts, back to age 1
            if (isGalaricaNutBush(block)) {
                IntegerProperty age = ageProperty(state);
                Block.popResource(
                        level,
                        pos,
                        new ItemStack(
                                com.cobblemon.mod.common.CobblemonItems.GALARICA_NUTS, 1 + level.random.nextInt(2)));
                playPickSound(level, pos, net.minecraft.sounds.SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES);
                if (age != null) {
                    level.setBlock(pos, state.setValue(age, 1), 2);
                }
                return true;
            }

            // Sweet berries: as SweetBerryBushBlock.useWithoutItem on a ripe (age 3) bush, 2-3
            // berries, back to age 1
            if (block instanceof SweetBerryBushBlock) {
                int count = 1 + level.random.nextInt(2) + (state.getValue(SweetBerryBushBlock.AGE) == 3 ? 1 : 0);
                Block.popResource(level, pos, new ItemStack(Items.SWEET_BERRIES, count));
                playPickSound(level, pos, net.minecraft.sounds.SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES);
                level.setBlock(pos, state.setValue(SweetBerryBushBlock.AGE, 1), 2);
                return true;
            }

            // Glow berries: as CaveVines.use, one berry, the vine left without
            if (state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT)) {
                Block.popResource(level, pos, new ItemStack(Items.GLOW_BERRIES, 1));
                playPickSound(level, pos, net.minecraft.sounds.SoundEvents.CAVE_VINES_PICK_BERRIES);
                level.setBlock(pos, state.setValue(net.minecraft.world.level.block.CaveVines.BERRIES, false), 2);
                return true;
            }

            return true;
        }

        // Hearty grains: harvested as Cobblemon has it by hand, by cutting the top half (its grains,
        // with its own loot), leaving the lower half to grow it again. The lower half drops back to
        // its last one-block stage by itself once its top is gone; it's set there too in case it
        // had no top. (This used to drop a made-up 1-3 grains and break the top without its loot.)
        if (block instanceof HeartyGrainsBlock) {
            BlockPos top = pos.above();
            if (level.getBlockState(top).is(block)) {
                level.destroyBlock(top, true);
            }
            BlockState lower = level.getBlockState(pos);
            IntegerProperty age = ageProperty(lower);
            if (lower.is(block) && age != null && lower.getValue(age) > HeartyGrainsBlock.AGE_AFTER_HARVEST) {
                level.setBlock(pos, lower.setValue(age, HeartyGrainsBlock.AGE_AFTER_HARVEST), 3);
            }
            return true;
        }

        // Other crops - check if they have an age property
        IntegerProperty ageProperty = ageProperty(state);

        if (ageProperty != null) {
            // This crop has an age property - harvest and reset to age 0
            int currentAge = state.getValue(ageProperty);
            int maxAge = ageProperty.getPossibleValues().stream()
                    .max(Integer::compareTo)
                    .orElse(7);

            // Only harvest if at max age
            if (currentAge >= maxAge) {
                // Harvest in place, like right-click-harvest mods: drop the crop's real loot (what
                // breaking it by hand gives, e.g. wheat + 1-4 seeds), minus one seed that pays for
                // replanting, and reset it to age 0. This used to drop block.asItem() instead, which
                // for wheat and beetroot is the seed, so those fields never produced any crop.
                List<ItemStack> drops = level instanceof ServerLevel serverLevel
                        ? Block.getDrops(state, serverLevel, pos, level.getBlockEntity(pos))
                        : new ArrayList<>();

                // Every vanilla and Cobblemon crop drops at least one of its seed when mature (wheat
                // and beetroot 1-4 seeds, cocoa 3 beans, carrots, potatoes, wart, mint seeds...), so
                // there's one to take. A crop whose loot has none can't pay for its replant: it's
                // broken as by hand, with its normal drops, and the planters replant it. (It used to
                // be replanted for free, and to drop made-up items when its loot gave nothing.)
                Item seed = seedOf(level, pos, state);
                if (seed == null || !takeOne(drops, seed)) {
                    level.destroyBlock(pos, true);
                    return true;
                }
                for (ItemStack drop : drops) {
                    if (!drop.isEmpty()) {
                        Block.popResource(level, pos, drop);
                    }
                }

                level.setBlock(pos, asReplanted(state.setValue(ageProperty, 0)), 3);

                dev.palworks.Palworks.LOGGER.debug(
                        "Harvested crop at {}: was age {}, dropped {}, reset to age 0", pos, currentAge, drops);
                return true;
            }
        }

        // Default: break the block completely (for crops like wheat, carrots, etc.)
        level.destroyBlock(pos, true);
        return true;
    }

    /**
     * A crop reset to age 0 as if it had been replanted from its seed: a mutated revival herb
     * (which drops a power, mental, mirror or white herb) is back to a plain one, as one replanted
     * by hand would be. Kept mutated, it dropped its rare herb every time it regrew. Anything else
     * about it (which way it faces, whether it's wild) stays as it was.
     */
    private static BlockState asReplanted(BlockState reset) {
        for (Property<?> property : reset.getProperties()) {
            if (MUTATION.equals(property.getName())) {
                return withDefault(reset, property);
            }
        }
        return reset;
    }

    private static <T extends Comparable<T>> BlockState withDefault(BlockState state, Property<T> property) {
        return state.setValue(property, state.getBlock().defaultBlockState().getValue(property));
    }

    /** A block's integer "age" property, or null if it has none. */
    @Nullable
    private static IntegerProperty ageProperty(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if ("age".equals(property.getName()) && property instanceof IntegerProperty age) {
                return age;
            }
        }
        return null;
    }

    /** Remove one of {@code item} from a list of drops. Whether there was one to remove. */
    private static boolean takeOne(List<ItemStack> drops, Item item) {
        for (ItemStack drop : drops) {
            if (!drop.isEmpty() && drop.is(item)) {
                drop.shrink(1);
                return true;
            }
        }
        return false;
    }

    /** A picking sound as the game plays it: full volume, pitch 0.8-1.2. */
    private static void playPickSound(Level level, BlockPos pos, net.minecraft.sounds.SoundEvent sound) {
        level.playSound(
                null,
                pos,
                sound,
                net.minecraft.sounds.SoundSource.BLOCKS,
                1.0f,
                0.8f + level.random.nextFloat() * 0.4f);
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

        // Plant the crop (canPlantSeed only passes field crops, never cocoa or sweet berries). Its
        // default state is what planting it by hand gives: age 0, not wild, not mutated.
        level.setBlock(plantPos, fieldCropOf(seed).defaultBlockState(), 3);
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
