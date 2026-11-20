package dev.palworks.crop;

import dev.palworks.job.JobType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Plants that grow a block at a time from one that stays put: sugar cane, cactus, bamboo and kelp
 * up from the bottom, twisting vines up, weeping vines down from the top. They're farmed as players
 * farm them: everything but that first block (the base) is cut, and it grows again. A plant is ripe
 * once its second block grows, and its job sits on that block: the first one cut, and one in reach
 * of a worker standing beside it, where the base often isn't (a weeping vine's is on the ceiling,
 * kelp's on the seabed). One job per plant: only its second block is ripe.
 *
 * <p>Each kind is its own job type (JobType), so it can get a skill of its own later.
 */
public final class TallPlants {
    // Long enough for any plant (bamboo stops at 16, kelp and vines at 25 more than they began)
    private static final int MAX_LENGTH = 64;

    /** A kind of tall plant: the job that harvests it, and the way it grows. */
    private enum Plant {
        SUGAR_CANE(JobType.HARVEST_SUGAR_CANE, Direction.UP),
        CACTUS(JobType.HARVEST_CACTUS, Direction.UP),
        BAMBOO(JobType.HARVEST_BAMBOO, Direction.UP),
        KELP(JobType.HARVEST_KELP, Direction.UP),
        WEEPING_VINES(JobType.HARVEST_NETHER_VINES, Direction.DOWN),
        TWISTING_VINES(JobType.HARVEST_NETHER_VINES, Direction.UP);

        final JobType job;
        final Direction growth;

        Plant(JobType job, Direction growth) {
            this.job = job;
            this.growth = growth;
        }

        /** The kind of plant this block is part of (any part of it), or null. */
        @Nullable
        static Plant of(BlockState state) {
            if (state.is(Blocks.SUGAR_CANE)) {
                return SUGAR_CANE;
            } else if (state.is(Blocks.CACTUS)) {
                return CACTUS;
            } else if (state.is(Blocks.BAMBOO) || state.is(Blocks.BAMBOO_SAPLING)) {
                // A shoot turns to bamboo as it grows its second block
                return BAMBOO;
            } else if (state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT)) {
                // The tip is kelp, the rest kelp_plant; the same for the vines
                return KELP;
            } else if (state.is(Blocks.WEEPING_VINES) || state.is(Blocks.WEEPING_VINES_PLANT)) {
                return WEEPING_VINES;
            } else if (state.is(Blocks.TWISTING_VINES) || state.is(Blocks.TWISTING_VINES_PLANT)) {
                return TWISTING_VINES;
            }
            return null;
        }
    }

    private TallPlants() {}

    /** The job that harvests this kind of plant, whatever its state, or null if it isn't one. */
    @Nullable
    public static JobType jobFor(BlockState state) {
        Plant plant = Plant.of(state);
        return plant == null ? null : plant.job;
    }

    /** The job at this block: its plant's, if it's that plant's second block; else null. */
    @Nullable
    public static JobType ripeJobAt(Level level, BlockPos pos) {
        Plant plant = Plant.of(level.getBlockState(pos));
        if (plant == null) {
            return null;
        }
        // The block it grew from is the same plant, and that one didn't grow from another (the base)
        Direction back = plant.growth.getOpposite();
        BlockPos base = pos.relative(back);
        boolean second = plant == Plant.of(level.getBlockState(base))
                && plant != Plant.of(level.getBlockState(base.relative(back)));
        return second ? plant.job : null;
    }

    /**
     * Harvest the plant whose second block is at {@code pos}: break it and everything grown beyond
     * it, from the tip back, as {@code breaker} breaking them by hand (each block's own loot: a
     * weeping vine drops itself only a third of the time). The base stays, and grows again; kelp
     * leaves water where it was. Broken one by one, tip first, so each drops now, as this break's
     * (a block left without support would only break a tick later). Returns how many it broke.
     */
    public static int harvest(Level level, BlockPos pos, @Nullable Entity breaker) {
        Plant plant = Plant.of(level.getBlockState(pos));
        if (plant == null) {
            return 0;
        }
        BlockPos tip = pos;
        for (int i = 0; i < MAX_LENGTH && plant == Plant.of(level.getBlockState(tip.relative(plant.growth))); i++) {
            tip = tip.relative(plant.growth);
        }
        int broken = 0;
        BlockPos at = tip;
        while (true) {
            if (level.destroyBlock(at, true, breaker)) {
                broken++;
            }
            if (at.equals(pos)) {
                return broken;
            }
            at = at.relative(plant.growth.getOpposite());
        }
    }
}
