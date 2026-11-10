package dev.palworks.job.handler;

import dev.palworks.Palworks;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.station.ChestHelper;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityDetector;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

final class DeliverItemsHandler implements JobHandler {

    private final List<Step> steps = List.of(Step.of("deliver", 20, Step.effect(this::work)));

    @Override
    public List<Step> steps() {
        return steps;
    }

    private void work(JobContext ctx) {
        BlockPos chestPos = ctx.pos();
        WorkerInventory inventory = ctx.inventory();
        if (inventory.isEmpty()) {
            return;
        }

        // A worker that plants keeps its seeds to plant with; everything else goes in the chest
        Set<Suitability> suitabilities = SuitabilityDetector.getSuitabilities(ctx.pokemon());
        int totalDelivered = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (WorkerInventory.isCargo(stack, suitabilities)) {
                int count = stack.getCount();
                int left = ChestHelper.insertItems(ctx.level(), chestPos, stack);
                totalDelivered += count - left;
                // Keep whatever didn't fit (it used to be deleted when a stack only partly fit)
                inventory.setItem(i, left == 0 ? ItemStack.EMPTY : stack.copyWithCount(left));
            }
        }

        if (totalDelivered > 0) {
            Palworks.LOGGER.debug("{} delivered {} items to chest at {}", ctx.name(), totalDelivered, chestPos);
        } else {
            Palworks.LOGGER.debug("{} failed to deliver items to chest at {}", ctx.name(), chestPos);
        }
    }
}
