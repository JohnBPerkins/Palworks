package dev.palworks.job.handler;

import dev.palworks.Palworks;
import dev.palworks.animation.WorkAnimations;
import dev.palworks.inventory.WorkerInventory;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

final class PlantSeedsHandler implements JobHandler {

    private final List<Step> steps = List.of(Step.animated("plant", 20, WorkAnimations.PLANT, Step.effect(this::work)));

    @Override
    public List<Step> steps() {
        return steps;
    }

    private void work(JobContext ctx) {
        BlockPos plantPos = ctx.pos();
        WorkerInventory inventory = ctx.inventory();

        // Plant a carried seed: the first held item that can be planted on this block (not just
        // the first item, which may be something else, like a harvested crop).
        ItemStack seedStack = ItemStack.EMPTY;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()
                    && dev.palworks.crop.CropAdapter.canPlantSeed(stack.getItem(), ctx.level(), plantPos.below())) {
                seedStack = stack;
                break;
            }
        }
        if (seedStack.isEmpty()) {
            Palworks.LOGGER.debug("{} has no seeds to plant at {}", ctx.name(), plantPos);
            return;
        }

        net.minecraft.world.item.Item seed = seedStack.getItem();
        boolean planted = dev.palworks.crop.CropAdapter.plantSeed(seed, ctx.level(), plantPos.below());
        if (planted) {
            seedStack.shrink(1);
            inventory.setChanged();
            ctx.didWork(1);
            Palworks.LOGGER.debug(
                    "{} planted {} at {} ({} items remaining)",
                    ctx.name(),
                    seed.toString(),
                    plantPos,
                    inventory.getTotalItemCount());
        } else {
            Palworks.LOGGER.debug("{} failed to plant {} at {}", ctx.name(), seed.toString(), plantPos);
        }
    }
}
