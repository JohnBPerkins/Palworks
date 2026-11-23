package dev.palworks.job.handler;

import dev.palworks.Palworks;
import dev.palworks.animation.WorkAnimations;
import dev.palworks.boulder.BoulderPlan;
import dev.palworks.boulder.BoulderRise;
import dev.palworks.boulder.BoulderSites;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * An Unearthing worker raising a boulder (see dev.palworks.boulder): it stamps the ground (a ground
 * move, Bulldoze by default) and the boulder rises out of it where the job's site is (see
 * {@link BoulderRise}), then stands there as real blocks for miners. The boulder was rolled, and its
 * site found to fit it, as the job was handed out (JobAssigner): both are in the job's data, so a
 * worker that takes over raises the same one. If anything gets in the way meanwhile (a block put
 * there, someone standing on the spot), the job ends and nothing's placed: no block is ever
 * replaced.
 */
final class SummonBoulderHandler implements JobHandler {
    // Hunger and mood cost: a unit for every so many blocks raised
    private static final int BLOCKS_PER_UNIT = 10;

    private final List<Step> steps = List.of(
            Step.animated("summon", 20, WorkAnimations.SUMMON_BOULDER, this::summon)
                    .thereWhile(SummonBoulderHandler::siteStillOpen),
            Step.of("rise", BoulderRise.RISE_TICKS + 2, this::rise)
                    .thereWhile(ctx -> BoulderRise.isRising(ctx.pokemon().getUuid())));

    @Override
    public List<Step> steps() {
        return steps;
    }

    /** The ground move lands: the boulder starts rising, if there's still room for it. */
    private boolean summon(JobContext ctx) {
        BoulderPlan plan = plan(ctx);
        BlockPos corner = corner(ctx);
        if (!(ctx.level() instanceof ServerLevel level)
                || plan == null
                || corner == null
                || !BoulderSites.isOpen(level, corner, plan.shape())) {
            return false;
        }
        BoulderRise.start(level, ctx.pokemon().getUuid(), corner, plan);
        return true;
    }

    /** It's up: the real blocks take the place of what rose. */
    private boolean rise(JobContext ctx) {
        Map<BlockPos, BlockState> placed = BoulderRise.finish(ctx.pokemon().getUuid());
        if (placed.isEmpty()) {
            return false;
        }
        ctx.didWork(Math.max(1, (placed.size() + BLOCKS_PER_UNIT - 1) / BLOCKS_PER_UNIT));
        Palworks.LOGGER.debug("{} raised a boulder of {} blocks at {}", ctx.name(), placed.size(), ctx.pos());
        return true;
    }

    /** Whether the boulder still has room where it's to go (nothing put there, nobody on it). */
    private static boolean siteStillOpen(JobContext ctx) {
        BoulderPlan plan = plan(ctx);
        BlockPos corner = corner(ctx);
        return ctx.level() instanceof ServerLevel level
                && plan != null
                && corner != null
                && BoulderSites.isOpen(level, corner, plan.shape());
    }

    // The boulder and its site, in the job's data (see BoulderPlan.toJobData)
    @Nullable
    private static BoulderPlan plan(JobContext ctx) {
        return BoulderPlan.fromJobData(ctx.data());
    }

    @Nullable
    private static BlockPos corner(JobContext ctx) {
        return BoulderPlan.cornerIn(ctx.data());
    }
}
