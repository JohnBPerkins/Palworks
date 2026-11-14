package dev.palworks.job.handler;

import dev.palworks.Palworks;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.job.JobChecks;
import dev.palworks.job.JobManager;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

final class CollectDropsHandler implements JobHandler {
    /** How far round a worker the items it couldn't reach from there are (the ones it went for). */
    private static final double OUT_OF_REACH_AROUND = 2.0;

    private final List<Step> steps = List.of(Step.of("pick up", 20, Step.effect(this::work)));

    @Override
    public List<Step> steps() {
        return steps;
    }

    // Picks up what's within its reach (see JobChecks.itemsInReach), whatever fits (into stacks of
    // the same item first, then free slots). What doesn't fit, or lies further off, stays on the
    // ground, and JobManager gives it a pickup job of its own again.
    private void work(JobContext ctx) {
        BlockPos targetPos = ctx.pos();
        WorkerInventory inventory = ctx.inventory();

        List<ItemEntity> inReach = JobChecks.itemsInReach(ctx.level(), ctx.entity());
        if (inReach.isEmpty() && ctx.level() instanceof ServerLevel level) {
            // It got as near as it could, and they're still out of reach (on top of a fence, say)
            JobManager.onPickupOutOfReach(level, ctx.entity().getBoundingBox().inflate(OUT_OF_REACH_AROUND));
            return;
        }
        int pickedUp = 0;
        for (ItemEntity itemEntity : inReach) {
            ItemStack stack = itemEntity.getItem();
            int leftover = inventory.addItem(stack);
            if (leftover == stack.getCount()) {
                continue; // none of it fits
            }
            pickedUp += stack.getCount() - leftover;
            if (leftover == 0) {
                itemEntity.discard();
            } else {
                // Only part of it fit: leave the rest on the ground (it used to be deleted)
                itemEntity.setItem(stack.copyWithCount(leftover));
            }
        }

        if (pickedUp > 0) {
            ctx.didWork(1); // one trip, however much it carried back
            Palworks.LOGGER.debug("{} collected {} dropped items from ground at {}", ctx.name(), pickedUp, targetPos);
        }
    }
}
