package dev.palworks.job;

import dev.palworks.Palworks;
import dev.palworks.job.WorkerRegistry.WorkerInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * Pickup jobs waiting to be created: for items to pick up as they come into the world (see
 * TrackedDrops), once they've landed, wherever that is; or for the items round a spot (what a
 * pickup left behind). A job is made where the items are, one for the items within
 * {@link JobChecks#pickupRadius} of each other, unless a pickup job there already covers them.
 * Each is offered first to the worker that made the drops, then goes to the pool for any
 * transporter.
 */
final class PickupQueue {

    /** Ticks to wait for a new item to land (a log's drops fall from up the trunk). */
    static final int LANDING_DELAY = 20;
    /**
     * Decaying leaves drop a few items at a time for minutes: they're picked up in batches, every
     * this many ticks, so a transporter isn't sent off for every sapling (and kept from delivering
     * while they keep coming, see JobAssigner).
     */
    static final int DECAY_BATCH_TICKS = 600;

    private final JobPool pool;
    private final WorkerRegistry workers;
    private final JobAssigner assigner;

    // Ordered by due time, since process() stops at the first request not yet due
    private final Queue<PickupRequest> queue = new PriorityQueue<>(Comparator.comparingLong(PickupRequest::dueTime));

    PickupQueue(JobPool pool, WorkerRegistry workers, JobAssigner assigner) {
        this.pool = pool;
        this.workers = workers;
        this.assigner = assigner;
    }

    /**
     * Queue a job to pick up the items lying round {@code pos} (within {@link JobChecks#pickupRadius}),
     * created at {@code gameTime} if there are any then. {@code suggestedWorker}, if given, is
     * offered it first (still only if it's a transporter with room for them).
     */
    void add(BlockPos pos, @Nullable UUID suggestedWorker, long gameTime) {
        queue.add(new PickupRequest(pos.immutable(), null, suggestedWorker, gameTime));
    }

    /**
     * Queue a pickup for an item that's just come into the world, once it's landed (or, for one a
     * decaying leaf dropped, with the rest of its batch).
     */
    void track(ItemEntity item, @Nullable UUID suggestedWorker, boolean decay, long gameTime) {
        long due = decay ? (gameTime / DECAY_BATCH_TICKS + 1) * DECAY_BATCH_TICKS : gameTime + LANDING_DELAY;
        queue.add(new PickupRequest(null, item.getUUID(), suggestedWorker, due));
    }

    /** Create the queued pickup jobs that are due. */
    void process(ServerLevel level) {
        long currentTime = level.getGameTime();
        while (!queue.isEmpty() && queue.peek().dueTime() <= currentTime) {
            PickupRequest request = queue.poll();
            if (request.pos() != null) {
                createCollectDropsJob(level, request.pos(), request.suggestedWorker());
                continue;
            }
            Entity entity = level.getEntity(request.item());
            if (entity instanceof ItemEntity item && item.isAlive() && !covered(item)) {
                // Where it landed: the job there takes this item and any others round it
                createCollectDropsJob(level, item.blockPosition(), request.suggestedWorker());
            }
        }
    }

    /** Whether a pickup job (waiting, or a worker's) already takes this item. */
    private boolean covered(ItemEntity item) {
        double radius = JobChecks.pickupRadius();
        AABB bounds = item.getBoundingBox();
        for (JobRecord job : pool.available()) {
            if (job.type() == JobType.COLLECT_DROPS
                    && new AABB(job.position()).inflate(radius).intersects(bounds)) {
                return true;
            }
        }
        for (JobRecord job : pool.reserved()) {
            if (job.type() == JobType.COLLECT_DROPS
                    && new AABB(job.position()).inflate(radius).intersects(bounds)) {
                return true;
            }
        }
        return false;
    }

    /** Create a pickup job for the items round a spot, if there are any. */
    private void createCollectDropsJob(ServerLevel level, BlockPos pos, @Nullable UUID suggested) {
        List<ItemEntity> items = new ArrayList<>(JobChecks.itemsToPickUp(level, pos));
        if (items.isEmpty()) {
            Palworks.LOGGER.debug("No items found at {} for collect drops job", pos);
            return;
        }

        JobRecord collectJob = JobRecord.followUp(JobType.COLLECT_DROPS, pos, level.getGameTime(), suggested);

        // Offer it to the suggested worker first (same checks as any job: suitability, reachable);
        // otherwise it goes to the pool for anyone
        WorkerInfo suggestedWorker = suggested == null ? null : workers.get(suggested);
        if (suggestedWorker != null
                && suggestedWorker.idle
                && assigner.assignFirstReachable(suggestedWorker, List.of(collectJob)) != null) {
            Palworks.LOGGER.debug("Assigned collect drops job at {} to the worker that dropped them", pos);
        } else {
            pool.offer(collectJob);
            Palworks.LOGGER.debug("Added collect drops job to the pool at {} ({} items)", pos, items.size());
        }
    }

    /**
     * A pickup job waiting to be created, at game time {@code dueTime}: for the items round
     * {@code pos}, or for the item {@code item} wherever it is then (one or the other). Offered
     * first to {@code suggestedWorker}, if given.
     */
    private record PickupRequest(
            @Nullable BlockPos pos,
            @Nullable UUID item,
            @Nullable UUID suggestedWorker,
            long dueTime) {}
}
