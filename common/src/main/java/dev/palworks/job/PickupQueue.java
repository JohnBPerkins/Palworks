package dev.palworks.job;

import dev.palworks.Palworks;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Pickup jobs waiting to be created: for a harvest's or a chop's drops, once they've had a tick
 * or two to spawn, or for items a pickup left behind. Each is offered first to the worker that
 * made the drops, then goes to the pool for any transporter.
 */
final class PickupQueue {
    private static final boolean DEBUG = Palworks.DEBUG;

    /** Ticks to wait after a harvest or chop for its items to spawn. */
    static final int DROPS_SPAWN_DELAY = 2;

    private final JobPool pool;
    private final WorkerRegistry workers;
    private final JobAssigner assigner;

    // Ordered by due time, since process() stops at the first request not yet due (a FIFO would
    // only be correct while every request uses the same delay).
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
        queue.add(new PickupRequest(pos.immutable(), suggestedWorker, gameTime));
    }

    /**
     * Create the queued pickup jobs that are due (their items have had time to spawn).
     */
    void process(ServerLevel level) {
        long currentTime = level.getGameTime();

        if (DEBUG && !queue.isEmpty()) {
            Palworks.LOGGER.info(
                    "Processing consecutive job queue ({} jobs queued, current time: {})", queue.size(), currentTime);
        }

        // Process all ready requests
        while (!queue.isEmpty()) {
            PickupRequest request = queue.peek();

            // Check if ready to process (delay elapsed)
            if (currentTime < request.dueTime()) {
                if (DEBUG) {
                    Palworks.LOGGER.info(
                            "Next pickup not ready yet - {} ticks remaining", request.dueTime() - currentTime);
                }
                break; // Not ready yet, and queue is ordered
            }

            queue.poll();

            if (DEBUG) {
                Palworks.LOGGER.info("Creating pickup job at {}", request.pos());
            }
            createCollectDropsJob(level, request);
        }
    }

    /**
     * Create a collect drops job for items at a location.
     */
    private void createCollectDropsJob(ServerLevel level, PickupRequest request) {
        // Scan for items at harvest location
        List<ItemEntity> items = JobChecks.itemsToPickUp(level, request.pos());

        if (items.isEmpty()) {
            if (DEBUG) {
                Palworks.LOGGER.debug("No items found at {} for collect drops job", request.pos());
            }
            return;
        }

        // Create collect drops job
        JobRecord collectJob = JobRecord.followUp(
                JobType.COLLECT_DROPS, request.pos(), level.getGameTime(), request.suggestedWorker());

        // Offer it to the suggested worker first (same checks as any job: suitability, reachable);
        // otherwise it goes to the pool for anyone
        WorkerInfo suggestedWorker = request.suggestedWorker() == null ? null : workers.get(request.suggestedWorker());
        if (suggestedWorker != null
                && suggestedWorker.idle
                && assigner.assignFirstReachable(suggestedWorker, List.of(collectJob)) != null) {
            if (DEBUG) {
                Palworks.LOGGER.debug(
                        "Assigned consecutive collect drops job to original harvester at {}", request.pos());
            }
        } else {
            // Otherwise add to available jobs
            pool.offer(collectJob);

            if (DEBUG) {
                Palworks.LOGGER.debug(
                        "Added consecutive collect drops job to available pool at {} ({} items)",
                        request.pos(),
                        items.size());
            }
        }
    }
}
