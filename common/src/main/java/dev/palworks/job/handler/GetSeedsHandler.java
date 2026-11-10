package dev.palworks.job.handler;

import dev.palworks.Palworks;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.job.JobManager;
import dev.palworks.station.ChestHelper;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityDetector;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class GetSeedsHandler implements JobHandler {
    private static final boolean DEBUG = Palworks.DEBUG;

    private final List<Step> steps = List.of(Step.of("take seeds", 20, Step.effect(this::work)));

    @Override
    public List<Step> steps() {
        return steps;
    }

    private void work(JobContext ctx) {
        BlockPos targetPos = ctx.pos();
        WorkerInventory inventory = ctx.inventory();

        if (DEBUG) {
            Palworks.LOGGER.info(
                    "{} starting GET_SEEDS at {} (inventory: {} items, has space: {})",
                    ctx.name(),
                    targetPos,
                    inventory.getTotalItemCount(),
                    inventory.hasSpace());
        }

        // Skip extraction if inventory is full, but still complete the job
        if (!inventory.hasSpace()) {
            if (DEBUG) {
                Palworks.LOGGER.info("{} skipping GET_SEEDS extraction - inventory already full", ctx.name());
            }
            return;
        }

        Set<Suitability> suitabilities = SuitabilityDetector.getSuitabilities(ctx.pokemon());
        if (suitabilities.contains(Suitability.PLANTING)) {
            // A seed the open farmland around it can take (see JobManager.seedToFetch)
            net.minecraft.world.item.Item seed = ctx.level() instanceof ServerLevel serverLevel
                    ? JobManager.getInstance(serverLevel)
                            .seedToFetch(targetPos, ctx.entity().blockPosition())
                    : null;
            ItemStack extracted = seed == null
                    ? ItemStack.EMPTY
                    : dev.palworks.station.ChestHelper.extractItems(ctx.level(), targetPos, seed, 64);
            if (!extracted.isEmpty()) {
                int leftover = inventory.addItem(extracted);
                ChestHelper.returnLeftover(ctx.level(), targetPos, extracted, leftover);
                if (DEBUG) {
                    Palworks.LOGGER.info(
                            "{} got {} {} seeds from chest at {} ({} leftover)",
                            ctx.name(),
                            extracted.getCount() - leftover,
                            extracted.getItem().toString(),
                            targetPos,
                            leftover);
                }
            } else if (DEBUG) {
                Palworks.LOGGER.warn(
                        "{} FAILED to get seeds from chest at {} - chest might be empty, treating as complete",
                        ctx.name(),
                        targetPos);
            }
        } else if (suitabilities.contains(Suitability.NETHER)) {
            ItemStack extracted = dev.palworks.station.ChestHelper.extractItems(
                    ctx.level(), targetPos, net.minecraft.world.item.Items.NETHER_WART, 64);
            if (!extracted.isEmpty()) {
                int leftover = inventory.addItem(extracted);
                ChestHelper.returnLeftover(ctx.level(), targetPos, extracted, leftover);
                if (DEBUG) {
                    Palworks.LOGGER.info(
                            "{} got {} nether wart from chest at {} ({} leftover)",
                            ctx.name(),
                            extracted.getCount() - leftover,
                            targetPos,
                            leftover);
                }
            } else if (DEBUG) {
                Palworks.LOGGER.warn(
                        "{} FAILED to get nether wart from chest at {} - chest might be empty, treating as complete",
                        ctx.name(),
                        targetPos);
            }
        } else if (DEBUG) {
            Palworks.LOGGER.warn(
                    "{} has GET_SEEDS job but lacks PLANTING/NETHER suitability! Suitabilities: {}",
                    ctx.name(),
                    suitabilities);
        }
    }
}
