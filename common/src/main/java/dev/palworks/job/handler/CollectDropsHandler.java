package dev.palworks.job.handler;

import dev.palworks.Palworks;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.job.JobChecks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

final class CollectDropsHandler implements JobHandler {

    private final List<Step> steps = List.of(Step.of("pick up", 20, Step.effect(this::work)));

    @Override
    public List<Step> steps() {
        return steps;
    }

    // Picks up whatever fits (into stacks of the same item first, then free slots). What doesn't
    // fit stays on the ground, and JobManager gives it a pickup job of its own again.
    private void work(JobContext ctx) {
        BlockPos targetPos = ctx.pos();
        WorkerInventory inventory = ctx.inventory();

        int pickedUp = 0;
        for (net.minecraft.world.entity.item.ItemEntity itemEntity : JobChecks.itemsToPickUp(ctx.level(), targetPos)) {
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
