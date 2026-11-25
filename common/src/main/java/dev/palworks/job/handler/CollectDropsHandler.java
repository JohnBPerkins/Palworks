package dev.palworks.job.handler;

import dev.palworks.inventory.WorkerInventory;
import dev.palworks.job.JobChecks;
import dev.palworks.job.JobManager;
import dev.palworks.job.JobType;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

final class CollectDropsHandler implements JobHandler {
    /** How far round a worker the items it couldn't reach from there are (the ones it went for). */
    private static final double OUT_OF_REACH_AROUND = 2.0;

    // A moment's stop at each item (or bunch of them) it goes up to, as a player picks them up
    private final List<Step> steps = List.of(Step.of("pick up", 2, Step.effect(this::work)));

    @Override
    public List<Step> steps() {
        return steps;
    }

    /**
     * On to the next item, while there are more round the job's spot that it has room for: one job
     * for all of them, walking from one to the next (see WorkPositions), not one each.
     */
    @Override
    public boolean repeatAfterComplete(JobContext ctx) {
        return JobChecks.canWorkerDo(ctx.level(), ctx.pokemon(), JobType.COLLECT_DROPS, ctx.pos());
    }

    /** It picks up what it passes on the way, as a player does. */
    @Override
    public void onTheWay(JobContext ctx) {
        pickUp(ctx, JobChecks.itemsInReach(ctx.level(), ctx.entity()));
    }

    // Picks up what's within its reach (see JobChecks.itemsInReach). What doesn't fit stays on the
    // ground, and JobManager gives it a pickup job of its own again, for a transporter with room.
    private void work(JobContext ctx) {
        List<ItemEntity> inReach = JobChecks.itemsInReach(ctx.level(), ctx.entity());
        if (inReach.isEmpty() && ctx.level() instanceof ServerLevel level) {
            // It got as near as it could, and they're still out of reach (on top of a fence, say)
            JobManager.onPickupOutOfReach(level, ctx.entity().getBoundingBox().inflate(OUT_OF_REACH_AROUND));
        }
        pickUp(ctx, inReach);
        if (!repeatAfterComplete(ctx) && !ctx.inventory().isEmpty()) {
            // One trip, however many items it went round, counted as it's done (if it got any)
            ctx.didWork(1);
        }
    }

    // Whatever of these fits (into stacks of the same item first, then free slots)
    private static void pickUp(JobContext ctx, List<ItemEntity> items) {
        WorkerInventory inventory = ctx.inventory();
        for (ItemEntity itemEntity : items) {
            ItemStack stack = itemEntity.getItem();
            int leftover = inventory.addItem(stack);
            if (leftover == stack.getCount()) {
                continue; // none of it fits
            }
            if (leftover == 0) {
                itemEntity.discard();
            } else {
                // Only part of it fit: leave the rest on the ground (it used to be deleted)
                itemEntity.setItem(stack.copyWithCount(leftover));
            }
        }
    }
}
