package dev.palworks.ai;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.job.JobChecks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The order a transporter goes round a pickup job's items in: planned once, from where it is when
 * it starts, each next the nearest to the one before, so it walks one short round rather than back
 * and forth. It sticks to the plan (its walk isn't replanned as it goes, see WorkPositions) and only
 * plans again when there's nothing left in it for it to take (all picked up, taken by someone else,
 * or more than it has room for), for any new ones that have landed meanwhile.
 *
 * <p>Server thread only, like the rest of the worker AI.
 */
final class PickupRoute {
    private PickupRoute() {}

    private record Route(BlockPos jobPos, List<ItemEntity> items) {}

    private static final Map<Mob, Route> ROUTES = new WeakHashMap<>();

    /**
     * The job's items still to pick up that the worker has room for, in the order it goes round
     * them: the rest of its plan, or a new plan if there's none of that (or it's for another job).
     * Ones it has no room for are passed by: going to one, it would find it couldn't take it, and
     * stand there. A plan with only those left is done with too: more of what it carries may have
     * landed since, which {@code JobChecks.canWorkerDo} counts, and with nowhere to go for it the
     * job was given up as one with nowhere to stand.
     */
    static List<ItemEntity> itemsInOrder(Level level, BlockPos jobPos, Mob worker) {
        if (!(worker instanceof PokemonEntity mon)) {
            return List.of();
        }
        WorkerInventory inventory = WorkerInventoryManager.getOrCreate(mon.getPokemon());
        Route route = ROUTES.get(worker);
        if (route != null && route.jobPos.equals(jobPos)) {
            List<ItemEntity> left = toTake(level, inventory, route.items);
            if (!left.isEmpty()) {
                ROUTES.put(worker, new Route(jobPos, left));
                return left;
            }
        }
        List<ItemEntity> planned =
                plan(toTake(level, inventory, JobChecks.itemsToPickUp(level, jobPos)), worker.position());
        ROUTES.put(worker, new Route(jobPos.immutable(), planned));
        return planned;
    }

    /** Those of these items that are still to pick up and that the worker has room for. */
    private static List<ItemEntity> toTake(Level level, WorkerInventory inventory, List<ItemEntity> items) {
        List<ItemEntity> toTake = new ArrayList<>(items.size());
        for (ItemEntity item : items) {
            if (JobChecks.isToPickUp(level, item) && inventory.roomFor(item.getItem()) > 0) {
                toTake.add(item);
            }
        }
        return toTake;
    }

    /** Whether there's an item within the worker's reach that it has room for. */
    static boolean anyToPickUpHere(Level level, Mob worker) {
        if (!(worker instanceof PokemonEntity mon)) {
            return false;
        }
        WorkerInventory inventory = WorkerInventoryManager.getOrCreate(mon.getPokemon());
        for (ItemEntity item : JobChecks.itemsInReach(level, worker)) {
            if (inventory.roomFor(item.getItem()) > 0) {
                return true;
            }
        }
        return false;
    }

    /** These items, each next the nearest to the one before, from {@code start}. */
    private static List<ItemEntity> plan(List<ItemEntity> items, Vec3 start) {
        List<ItemEntity> left = new ArrayList<>(items);
        List<ItemEntity> order = new ArrayList<>(items.size());
        Vec3 from = start;
        while (!left.isEmpty()) {
            int nearest = 0;
            for (int i = 1; i < left.size(); i++) {
                if (left.get(i).distanceToSqr(from) < left.get(nearest).distanceToSqr(from)) {
                    nearest = i;
                }
            }
            ItemEntity next = left.remove(nearest);
            order.add(next);
            from = next.position();
        }
        return order;
    }
}
