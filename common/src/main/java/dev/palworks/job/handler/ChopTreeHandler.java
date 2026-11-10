package dev.palworks.job.handler;

import dev.palworks.Palworks;
import dev.palworks.ai.WorkPositions;
import dev.palworks.animation.WorkAnimations;
import dev.palworks.job.JobManager;
import dev.palworks.job.JobType;
import dev.palworks.tree.TreeDetector;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

final class ChopTreeHandler implements JobHandler {
    private static final boolean DEBUG = Palworks.DEBUG;

    // Clear the leaves in the way to the trunk (1s, skipped if there are none), chop (3s), then
    // replant (1s later). The replant spots and sapling are saved in the job's data at the
    // chop, so a paused job, or one another lumberjack takes over, still replants.
    private final List<Step> steps = List.of(
            Step.of("clear leaves", 20, Step.effect(ChopTreeHandler::clearLeaves))
                    .onlyIf(ChopTreeHandler::leavesInTheWay),
            Step.animated("chop", 60, WorkAnimations.CHOP, ChopTreeHandler::chop),
            // The tree's gone by now: it was just chopped
            Step.of("replant", 20, Step.effect(ChopTreeHandler::replant)).outlivesItsTarget());

    @Override
    public List<Step> steps() {
        return steps;
    }

    /** Where the worker stands by this tree, and the leaves in its way (see WorkPositions.treeStandFor). */
    @Nullable
    private static WorkPositions.TreeStand treeStand(JobContext ctx) {
        return WorkPositions.treeStandFor(
                ctx.level(), ctx.pos(), ctx.entity(), JobHandlerRegistry.stanceFor(JobType.CHOP_TREE, ctx.entity()));
    }

    /** Whether leaves stand between the worker and the trunk. */
    private static boolean leavesInTheWay(JobContext ctx) {
        WorkPositions.TreeStand stand = treeStand(ctx);
        return stand != null && stand.needsClearing();
    }

    /**
     * Break the leaves between the worker and the trunk (with their usual drops, as when they
     * decay), so it can walk in and chop right up against it. Its stand spot then moves in to the
     * trunk, and it walks there before the chop starts.
     */
    private static void clearLeaves(JobContext ctx) {
        WorkPositions.TreeStand stand = treeStand(ctx);
        if (stand == null) {
            return;
        }
        for (BlockPos leaf : stand.leavesToClear()) {
            ctx.level().destroyBlock(leaf, true, ctx.entity());
        }
        // It hasn't walked to its new spot yet: don't let "close enough" chop from out here
        ctx.state().setWalkedToJob(false);
    }

    /** Bring the tree down and note where to replant. Ends the job if there's no valid tree. */
    private static boolean chop(JobContext ctx) {
        Level level = ctx.level();
        BlockPos treePos = ctx.pos();
        TreeDetector.TreeValidationResult treeResult = TreeDetector.validateTree(level, treePos);

        if (!treeResult.isValid() || treeResult.getLogPositions().isEmpty()) {
            // Not a valid tree any more (partly chopped, or it's a player build). Leave it alone:
            // breaking "just this log" is how builds that fail tree detection got griefed.
            if (DEBUG) {
                Palworks.LOGGER.warn(
                        "{} found no valid tree at {} ({}), leaving it alone",
                        ctx.name(),
                        treePos,
                        treeResult.getReason());
            }
            return false;
        }

        // Get sapling type BEFORE destroying the tree (found by name from its logs or leaves;
        // null for a tree with no recognisable sapling, which is chopped but not replanted)
        net.minecraft.world.item.Item saplingItem = TreeDetector.getSaplingForTree(level, treeResult);
        BlockPos basePos = treeResult.getBasePosition();

        // Where to replant: a 2x2 trunk (dark oak, giant spruce/jungle) gets a 2x2 of saplings
        // in the same spots, anything else one at its base. Worked out while the trunk is
        // still there to look at.
        java.util.List<BlockPos> saplingSpots =
                TreeDetector.saplingPositions(level, treeResult.getLogPositions(), basePos);

        // Huge fungus canopies never decay, so they come down with the stem (found before
        // anything is broken, while the tree is still intact)
        java.util.Set<BlockPos> canopy = TreeDetector.isNetherStem(level.getBlockState(treePos))
                ? TreeDetector.findNetherCanopyToBreak(level, treeResult.getLogPositions(), treePos)
                : java.util.Set.of();

        int logsChopped = 0;
        for (BlockPos logPos : treeResult.getLogPositions()) {
            if (level.destroyBlock(logPos, true)) {
                logsChopped++;
            }
        }
        for (BlockPos canopyPos : canopy) {
            level.destroyBlock(canopyPos, true);
        }

        if (DEBUG) {
            Palworks.LOGGER.info(
                    "{} chopped tree at {} - {} logs and {} canopy blocks destroyed, base at {}, sapling: {}",
                    ctx.name(),
                    treePos,
                    logsChopped,
                    canopy.size(),
                    basePos,
                    saplingItem);
        }

        // For the replant step (saved with the job, so it survives a pause or a handover)
        if (saplingItem != null) {
            ctx.data()
                    .putString(
                            DATA_SAPLING,
                            net.minecraft.core.registries.BuiltInRegistries.ITEM
                                    .getKey(saplingItem)
                                    .toString());
            int[] spots = new int[saplingSpots.size() * 3];
            for (int i = 0; i < saplingSpots.size(); i++) {
                BlockPos spot = saplingSpots.get(i);
                spots[i * 3] = spot.getX();
                spots[i * 3 + 1] = spot.getY();
                spots[i * 3 + 2] = spot.getZ();
            }
            ctx.data().putIntArray(DATA_SPOTS, spots);
        }

        // Queue a job to collect the drops
        if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
            JobManager.getInstance(serverLevel).onTreeChopped(ctx.pokemon(), basePos, level);
        }
        // No sapling found: chopped, but nothing to replant
        return saplingItem != null;
    }

    /** Plant the saplings noted at the chop. The sapling is free (see onTreeChopped). */
    private static void replant(JobContext ctx) {
        net.minecraft.resources.ResourceLocation saplingId =
                net.minecraft.resources.ResourceLocation.tryParse(ctx.data().getString(DATA_SAPLING));
        net.minecraft.world.item.Item saplingItem = saplingId == null
                ? null
                : net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getOptional(saplingId)
                        .orElse(null);
        int[] spots = ctx.data().getIntArray(DATA_SPOTS);
        if (saplingItem == null || spots.length % 3 != 0) {
            return;
        }
        // The sapling is free: the leaves still drop their usual saplings as they decay, and
        // nothing had dropped yet at the chop to pay with
        int replanted = 0;
        for (int i = 0; i < spots.length; i += 3) {
            if (TreeDetector.plantSapling(
                    ctx.level(), new BlockPos(spots[i], spots[i + 1], spots[i + 2]), saplingItem)) {
                replanted++;
            }
        }
        if (DEBUG) {
            Palworks.LOGGER.info("{} replanted {} of {} {}", ctx.name(), replanted, spots.length / 3, saplingItem);
        }
    }

    private static final String DATA_SAPLING = "sapling";
    private static final String DATA_SPOTS = "spots";
}
