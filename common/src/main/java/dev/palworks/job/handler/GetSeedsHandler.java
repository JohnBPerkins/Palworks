package dev.palworks.job.handler;

import dev.palworks.Palworks;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.job.JobManager;
import dev.palworks.station.ChestHelper;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

final class GetSeedsHandler implements JobHandler {

    private final List<Step> steps = List.of(Step.of("take seeds", 20, Step.effect(this::work)));

    @Override
    public List<Step> steps() {
        return steps;
    }

    private void work(JobContext ctx) {
        BlockPos targetPos = ctx.pos();
        WorkerInventory inventory = ctx.inventory();

        Palworks.LOGGER.debug(
                "{} starting GET_SEEDS at {} (inventory: {} items, has space: {})",
                ctx.name(),
                targetPos,
                inventory.getTotalItemCount(),
                inventory.hasSpace());

        // Skip extraction if inventory is full, but still complete the job
        if (!inventory.hasSpace()) {
            Palworks.LOGGER.debug("{} skipping GET_SEEDS extraction - inventory already full", ctx.name());
            return;
        }

        // What it's out of, if it plants too, or what the planter it's bringing seeds to is out
        // of; only what the open spots there can take (see JobManager.seedToFetch)
        Item seed = ctx.level() instanceof ServerLevel serverLevel
                ? JobManager.getInstance(serverLevel).seedToFetch(ctx.pokemon().getUuid(), targetPos, inventory)
                : null;
        ItemStack extracted =
                seed == null ? ItemStack.EMPTY : ChestHelper.extractItems(ctx.level(), targetPos, seed, 64);
        if (!extracted.isEmpty()) {
            int leftover = inventory.addItem(extracted);
            ChestHelper.returnLeftover(ctx.level(), targetPos, extracted, leftover);
            ctx.didWork(1);
            Palworks.LOGGER.debug(
                    "{} got {} {} from chest at {} ({} leftover)",
                    ctx.name(),
                    extracted.getCount() - leftover,
                    extracted.getItem(),
                    targetPos,
                    leftover);
        } else {
            Palworks.LOGGER.debug(
                    "{} got nothing from chest at {} (none it needs left), treating as complete",
                    ctx.name(),
                    targetPos);
        }
    }
}
