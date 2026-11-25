package dev.palworks.job.handler;

import dev.palworks.Palworks;
import dev.palworks.ai.WorkStance;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.station.ChestHelper;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityDetector;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

final class DeliverItemsHandler implements JobHandler {

    private final List<Step> steps = List.of(Step.of("deliver", 20, Step.effect(this::work)));

    @Override
    public List<Step> steps() {
        return steps;
    }

    /** Right up against the chest (or trough) it fills, as a player stands to open one. */
    @Override
    public WorkStance stance(@Nullable String speciesId) {
        return WorkStance.AGAINST;
    }

    private void work(JobContext ctx) {
        BlockPos chestPos = ctx.pos();
        WorkerInventory inventory = ctx.inventory();
        if (inventory.isEmpty()) {
            return;
        }

        // A worker that plants keeps its seeds to plant with (while there's somewhere to plant
        // them); everything else goes in the chest
        Set<Suitability> suitabilities = SuitabilityDetector.getSuitabilities(ctx.pokemon());
        int totalDelivered = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (inventory.carriesAsCargo(stack, suitabilities)) {
                int count = stack.getCount();
                int left = ChestHelper.insertItems(ctx.level(), chestPos, stack);
                totalDelivered += count - left;
                // Keep whatever didn't fit (it used to be deleted when a stack only partly fit)
                inventory.setItem(i, left == 0 ? ItemStack.EMPTY : stack.copyWithCount(left));
            }
        }

        if (totalDelivered > 0) {
            ctx.didWork(1); // one trip, however much it delivered
        } else {
            Palworks.LOGGER.debug("{} failed to deliver items to chest at {}", ctx.name(), chestPos);
        }
    }
}
