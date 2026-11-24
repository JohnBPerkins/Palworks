package dev.palworks.job.handler;

import dev.palworks.Palworks;
import dev.palworks.animation.WorkAnimations;
import dev.palworks.boulder.Boulders;
import dev.palworks.crystal.MiningTool;
import dev.palworks.drops.TrackedDrops;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityDetector;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * Mining one block of a boulder (see {@link Boulders}): broken as with the pickaxe the miner's
 * Mining level stands for (see {@link MiningTool}), so it drops what that pickaxe gets from it:
 * cobblestone from stone, ore with Fortune from level 3, and ice (with Silk Touch: see
 * {@link MiningTool#forBoulderBlock}) itself. A block a wooden pickaxe gets nothing from
 * is never handed to a level 1 miner (its job's minimum level, set by the scan).
 */
final class MineBoulderHandler implements JobHandler {

    private final List<Step> steps = List.of(Step.animated("mine", 20, WorkAnimations.MINE, Step.effect(this::work)));

    @Override
    public List<Step> steps() {
        return steps;
    }

    private void work(JobContext ctx) {
        BlockPos pos = ctx.pos();
        if (!(ctx.level() instanceof ServerLevel level)
                || !Boulders.get(level).isMinable(pos, level.getBlockState(pos))) {
            return;
        }
        int miningLevel = SuitabilityDetector.getSuitabilityLevel(ctx.pokemon(), Suitability.MINING);
        ItemStack tool = MiningTool.forBoulderBlock(level.registryAccess(), miningLevel, level.getBlockState(pos));
        // What it drops is the worker's, for transporters to pick up (see TrackedDrops)
        TrackedDrops.breaking(ctx.pokemon().getUuid(), pos, () -> MiningTool.breakWith(level, pos, ctx.entity(), tool));
        // What was on it drops a block (a tall column worked from below)
        Boulders.get(level).settleAbove(level, pos);
        ctx.didWork(1);
        Palworks.LOGGER.debug("{} mined a boulder's block at {}", ctx.name(), pos);
    }
}
