package dev.palworks.job.handler;

import dev.palworks.Palworks;
import dev.palworks.hunger.TroughFood;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.station.ChestHelper;
import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A transporter fetches food from a chest for a trough that's running low (see JobAssigner): a
 * stack of the first trough food it finds there. It's then carrying it, and its delivery takes it
 * to the trough, as harvested food is (troughs before chests); any that doesn't fit goes back to a
 * chest.
 */
final class FetchFoodHandler implements JobHandler {
    /** How much it takes at once: a stack. */
    private static final int FETCH = 64;

    private final List<Step> steps = List.of(Step.of("take food", 20, Step.effect(FetchFoodHandler::fetch)));

    @Override
    public List<Step> steps() {
        return steps;
    }

    private static void fetch(JobContext ctx) {
        WorkerInventory inventory = ctx.inventory();
        Item food = ChestHelper.itemsMatching(ctx.level(), ctx.pos(), TroughFood::accepts).stream()
                .findFirst()
                .orElse(null);
        if (food == null || !inventory.hasSpace()) {
            return;
        }
        ItemStack taken = ChestHelper.extractItems(ctx.level(), ctx.pos(), food, FETCH);
        if (taken.isEmpty()) {
            return;
        }
        int leftover = inventory.addItem(taken);
        ChestHelper.returnLeftover(ctx.level(), ctx.pos(), taken, leftover);
        ctx.didWork(1);
        Palworks.LOGGER.debug(
                "{} fetched {} {} from the chest at {} for a trough",
                ctx.name(),
                taken.getCount() - leftover,
                food,
                ctx.pos());
    }
}
