package dev.palworks.crop;

import dev.palworks.config.PalworksConfig;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Watering crops, Water types' work: which crops want it, and what it does. Watering a crop
 * hydrates the farmland it grows in, and may fertilize it, just as bone meal would: the higher the
 * waterer's Watering level, the likelier ({@link #fertilizeChance}). A crop that's been watered
 * isn't watered again until the cooldown in {@code config/palworks.json} has passed. When each crop
 * was watered is kept here, per level, only for as long as that or until the crop is broken or
 * replaced, and forgotten when the server stops: after a restart every growing crop can be watered
 * straight away.
 */
public final class Watering {
    private static final int TICKS_PER_SECOND = 20;
    // The data bone meal's particles-and-sound event is played with (BoneMealItem.useOn)
    private static final int BONE_MEAL_EVENT_DATA = 15;

    // Game time each crop was last watered, by level. Only the server thread touches it.
    private static final Map<Level, Map<BlockPos, Long>> WATERED_AT = new ConcurrentHashMap<>();

    private Watering() {}

    /** Whether the block at {@code pos} is a crop that wants watering: still growing, and not watered lately. */
    public static boolean needsWater(Level level, BlockPos pos) {
        return CropAdapter.isGrowing(level, pos) && !recentlyWatered(level, pos);
    }

    private static boolean recentlyWatered(Level level, BlockPos pos) {
        Map<BlockPos, Long> watered = WATERED_AT.get(level);
        Long at = watered == null ? null : watered.get(pos);
        return at != null && level.getGameTime() - at < cooldownTicks();
    }

    private static long cooldownTicks() {
        return (long) PalworksConfig.current().wateringCooldownSeconds() * TICKS_PER_SECOND;
    }

    /** The chance a waterer at this Watering level fertilizes the crop it waters, 0 to 1. */
    public static double fertilizeChance(int wateringLevel) {
        return Math.min(1.0, wateringLevel * PalworksConfig.current().fertilizeChancePerLevel());
    }

    /**
     * Water the crop at {@code pos}: hydrate the farmland under it, and with {@code fertilizeChance}
     * (0 to 1) fertilize it too. It isn't watered again until the cooldown has passed. Returns
     * whether it was fertilized.
     */
    public static boolean water(ServerLevel level, BlockPos pos, double fertilizeChance) {
        WATERED_AT.computeIfAbsent(level, l -> new ConcurrentHashMap<>()).put(pos.immutable(), level.getGameTime());
        hydrate(level, pos.below());
        return level.getRandom().nextDouble() < fertilizeChance && fertilize(level, pos);
    }

    /** Farmland made as wet as water next to it makes it. Anything else is left alone. */
    private static void hydrate(ServerLevel level, BlockPos soilPos) {
        BlockState soil = level.getBlockState(soilPos);
        if (soil.is(Blocks.FARMLAND) && soil.getValue(FarmBlock.MOISTURE) < FarmBlock.MAX_MOISTURE) {
            level.setBlock(soilPos, soil.setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE), Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Fertilize the crop at {@code pos} exactly as a player's bone meal would: by the game's own
     * code for it, so each crop grows by its own rule (a stage or a few, or by chance, as it would
     * by hand; on NeoForge other mods hear of it as bone meal), with bone meal's particles and
     * sound. Returns whether bone meal could do anything there.
     */
    public static boolean fertilize(ServerLevel level, BlockPos pos) {
        if (!BoneMealItem.growCrop(new ItemStack(Items.BONE_MEAL), level, pos)) {
            return false;
        }
        level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_PLANT_GROWTH, pos, BONE_MEAL_EVENT_DATA);
        return true;
    }

    /**
     * Forget that the crop at {@code pos} was watered, as it's gone: broken, or replaced by another
     * block. A crop planted there later can be watered straight away.
     */
    public static void forget(Level level, BlockPos pos) {
        Map<BlockPos, Long> watered = WATERED_AT.get(level);
        if (watered != null) {
            watered.remove(pos);
        }
    }

    /** Forget the crops in {@code level} watered longer ago than the cooldown: they can be watered again. */
    public static void forgetExpired(Level level) {
        Map<BlockPos, Long> watered = WATERED_AT.get(level);
        if (watered != null) {
            long now = level.getGameTime();
            long cooldown = cooldownTicks();
            watered.values().removeIf(at -> now - at >= cooldown);
        }
    }

    /** Forget every watered crop (on server stop, so nothing holds on to old levels). */
    public static void clear() {
        WATERED_AT.clear();
    }
}
