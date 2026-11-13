package dev.palworks.job.handler;

import dev.palworks.Palworks;
import dev.palworks.drops.TrackedDrops;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.job.JobChecks;
import dev.palworks.job.JobManager;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * A planter picks up the seeds left for it (by a transporter that brought them, or a player that
 * threw them by it: see SeedRequests), into its pouch. Only the ones left for it.
 */
final class TakeSeedsHandler implements JobHandler {

    private final List<Step> steps = List.of(Step.of("take seeds", 10, Step.effect(TakeSeedsHandler::take)));

    @Override
    public List<Step> steps() {
        return steps;
    }

    private static void take(JobContext ctx) {
        WorkerInventory pouch = ctx.inventory();
        int taken = 0;
        for (ItemEntity item :
                JobChecks.seedsHandedTo(ctx.level(), ctx.pos(), ctx.pokemon().getUuid())) {
            ItemStack stack = item.getItem();
            int leftover = pouch.addItem(stack);
            taken += stack.getCount() - leftover;
            if (leftover == 0) {
                item.discard();
                continue;
            }
            if (leftover < stack.getCount()) {
                item.setItem(stack.copyWithCount(leftover));
            }
            // What its pouch has no room for is an ordinary pickup, for a transporter to put away
            TrackedDrops.stopHandingOver(item);
            if (ctx.level() instanceof ServerLevel serverLevel) {
                JobManager.getInstance(serverLevel).queuePickup(ctx.pos(), null, serverLevel.getGameTime());
            }
        }
        if (taken > 0) {
            ctx.didWork(1);
        }
        if (ctx.level() instanceof ServerLevel serverLevel) {
            JobManager.getInstance(serverLevel).onSeedsTaken(ctx.pokemon().getUuid());
        }
        Palworks.LOGGER.debug("{} took {} seeds left for it at {}", ctx.name(), taken, ctx.pos());
    }
}
