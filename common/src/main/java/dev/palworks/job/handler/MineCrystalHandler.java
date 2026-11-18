package dev.palworks.job.handler;

import dev.palworks.Palworks;
import dev.palworks.animation.WorkAnimations;
import dev.palworks.crystal.CrystalAdapter;
import dev.palworks.crystal.MiningTool;
import dev.palworks.drops.TrackedDrops;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityDetector;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/** Mining a grown crystal (amethyst, tumblestone, a type gem): see {@link CrystalAdapter}. */
final class MineCrystalHandler implements JobHandler {

    private final List<Step> steps = List.of(Step.animated("mine", 20, WorkAnimations.MINE, Step.effect(this::work)));

    @Override
    public List<Step> steps() {
        return steps;
    }

    private void work(JobContext ctx) {
        BlockPos pos = ctx.pos();
        // Mined as with the pickaxe its Mining level stands for (more Fortune the higher it is)
        int miningLevel = SuitabilityDetector.getSuitabilityLevel(ctx.pokemon(), Suitability.MINING);
        ItemStack tool = MiningTool.forLevel(ctx.level().registryAccess(), miningLevel);
        // What it drops is the worker's, for transporters to pick up (see TrackedDrops)
        boolean mined = TrackedDrops.breaking(
                ctx.pokemon().getUuid(), pos, () -> CrystalAdapter.harvest(ctx.level(), pos, ctx.entity(), tool));
        if (mined) {
            ctx.didWork(1);
            Palworks.LOGGER.debug("{} mined the crystal at {}", ctx.name(), pos);
        }
    }
}
