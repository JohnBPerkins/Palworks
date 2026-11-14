package dev.palworks.job;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.crop.CropAdapter;
import dev.palworks.drops.TrackedDrops;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.job.WorkerRegistry.WorkerInfo;
import dev.palworks.suitability.Suitability;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Pickup jobs for the items to pick up (see TrackedDrops). Every such item in the level is kept
 * track of from when it comes into the world (spawned, or loaded with its chunk) till it's gone,
 * and every {@link #SWEEP_TICKS} one that's landed with no pickup job round it gets one, where it
 * is: so an item a job missed (it rolled off, the job went, the worker had no room) is never left
 * lying there. Also pickups for the items round a spot (what a pickup left behind). A job is
 * offered first to the worker that made the drops, then goes to the pool for any transporter.
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
    // How much further than the pickup radius a planter can be from seeds a player threw it
    private static final double THROWN_SEEDS_EXTRA_REACH = 2.0;
    /** How often (ticks) items with no pickup job round them are given one. */
    static final int SWEEP_TICKS = 20;
    // Items out of reach wait this long (ticks) for another pickup job, twice as long each time
    // after, up to the most
    private static final long OUT_OF_REACH_WAIT = 30 * 20;
    private static final long OUT_OF_REACH_MAX_WAIT = 5 * 60 * 20;

    private final JobPool pool;
    private final WorkerRegistry workers;
    private final JobAssigner assigner;
    private final SeedRequests seedRequests;

    // Ordered by due time, since process() stops at the first request not yet due
    private final Queue<PickupRequest> queue = new PriorityQueue<>(Comparator.comparingLong(PickupRequest::dueTime));
    // The items to pick up in the level (by entity UUID), till they're gone
    private final Map<UUID, Tracked> tracked = new LinkedHashMap<>();

    PickupQueue(JobPool pool, WorkerRegistry workers, JobAssigner assigner, SeedRequests seedRequests) {
        this.pool = pool;
        this.workers = workers;
        this.assigner = assigner;
        this.seedRequests = seedRequests;
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
     * Keep track of an item to pick up that's just come into the world: it gets a pickup job once
     * it's landed (or, for one a decaying leaf dropped, with the rest of its batch).
     */
    void track(
            ItemEntity item, @Nullable UUID suggestedWorker, @Nullable BlockPos source, boolean decay, long gameTime) {
        long due = decay ? (gameTime / DECAY_BATCH_TICKS + 1) * DECAY_BATCH_TICKS : gameTime + LANDING_DELAY;
        tracked.put(item.getUUID(), new Tracked(due, suggestedWorker, source));
    }

    /**
     * A pickup job at {@code pos} was out of reach (no worker could get to it): the items round it
     * wait a while for another, longer each time, so they're not path-checked over and over. They
     * stay tracked: once the way's clear (or they've been moved), they're picked up.
     */
    void outOfReach(BlockPos pos, long now) {
        AABB area = new AABB(pos).inflate(JobChecks.pickupRadius());
        for (Map.Entry<UUID, Tracked> entry : tracked.entrySet()) {
            Tracked item = entry.getValue();
            if (item.lastSpot != null && area.contains(Vec3.atCenterOf(item.lastSpot))) {
                item.wait = item.wait == 0 ? OUT_OF_REACH_WAIT : Math.min(item.wait * 2, OUT_OF_REACH_MAX_WAIT);
                item.due = now + item.wait;
            }
        }
    }

    /** Create the queued pickup jobs that are due, and jobs for items with none round them. */
    void process(ServerLevel level) {
        long currentTime = level.getGameTime();
        while (!queue.isEmpty() && queue.peek().dueTime() <= currentTime) {
            PickupRequest request = queue.poll();
            createCollectDropsJob(level, request.pos(), request.suggestedWorker());
        }
        if (currentTime % SWEEP_TICKS == 0 && !tracked.isEmpty()) {
            sweep(level, currentTime);
        }
    }

    /**
     * The planter seeds a player threw are for: the nearest one close by (within the pickup
     * radius and a little) that plants them (Planting for farmland seeds, Nether for wart), with
     * room in its pouch, that doesn't transport (one that does takes them as any pickup). Null if
     * they aren't a player's seeds, or no planter's close by.
     */
    @Nullable
    private WorkerInfo planterFor(ItemEntity item) {
        Item seed = item.getItem().getItem();
        if (!TrackedDrops.isThrownByPlayer(item) || !CropAdapter.isSeed(seed)) {
            return null;
        }
        Suitability skill = seed.equals(Items.NETHER_WART) ? Suitability.NETHER : Suitability.PLANTING;
        double reach = JobChecks.pickupRadius() + THROWN_SEEDS_EXTRA_REACH;
        WorkerInfo nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (WorkerInfo worker : workers.all()) {
            if (!worker.suitabilities.contains(skill) || worker.suitabilities.contains(Suitability.TRANSPORTING)) {
                continue;
            }
            double dx = Math.abs(worker.position.getX() + 0.5 - item.getX());
            double dz = Math.abs(worker.position.getZ() + 0.5 - item.getZ());
            double dy = Math.abs(worker.position.getY() - item.getY());
            if (dx > reach || dz > reach || dy > reach) {
                continue;
            }
            Pokemon pokemon = workers.pokemon(worker.pokemonUUID);
            if (pokemon == null || WorkerInventoryManager.getOrCreate(pokemon).roomFor(item.getItem()) == 0) {
                continue;
            }
            double distance = dx * dx + dz * dz + dy * dy;
            if (distance < nearestDistance) {
                nearest = worker;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    /**
     * Give every item to pick up that's landed, with no pickup job round it, one where it is (which
     * takes the others round it too). One that's gone (picked up, despawned, or its chunk unloaded:
     * it's tracked again if that loads) is let go.
     */
    private void sweep(ServerLevel level, long now) {
        List<AABB> jobs = new ArrayList<>();
        double radius = JobChecks.pickupRadius();
        for (JobRecord job : pool.available()) {
            if (job.type() == JobType.COLLECT_DROPS) {
                jobs.add(new AABB(job.position()).inflate(radius));
            }
        }
        for (JobRecord job : pool.reserved()) {
            if (job.type() == JobType.COLLECT_DROPS) {
                jobs.add(new AABB(job.position()).inflate(radius));
            }
        }
        for (Iterator<Map.Entry<UUID, Tracked>> it = tracked.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Tracked> entry = it.next();
            Tracked item = entry.getValue();
            if (item.due > now) {
                continue;
            }
            if (!(level.getEntity(entry.getKey()) instanceof ItemEntity entity) || !entity.isAlive()) {
                it.remove();
                continue;
            }
            if (TrackedDrops.handedTo(entity) != null) {
                continue; // left for a planter: its own to take (see SeedRequests)
            }
            // Seeds a player threw by a planter with room for them are its, to plant
            WorkerInfo planter = planterFor(entity);
            if (planter != null) {
                TrackedDrops.handOverTo(entity, planter.pokemonUUID);
                seedRequests.handOver(assigner, planter, entity.blockPosition(), now);
                continue;
            }
            if (covered(entity, jobs)) {
                continue;
            }
            BlockPos at = spotFor(entity, item.source, radius);
            item.lastSpot = entity.blockPosition();
            createCollectDropsJob(level, at, item.suggestedWorker);
            jobs.add(new AABB(at).inflate(radius));
            item.suggestedWorker = null; // only its first job is offered to the worker that dropped it
        }
    }

    /**
     * Where a pickup job for this item goes: where it was dropped (the crop harvested, the tree
     * chopped, the block mined), if it's landed within reach of that: where a worker can stand, as
     * the drops round a tree land on its leaves, say, out of reach themselves, but in reach of a
     * pickup at its base. Otherwise (it rolled off, or it was thrown) where it is.
     */
    private static BlockPos spotFor(ItemEntity item, @Nullable BlockPos source, double radius) {
        if (source != null && new AABB(source).inflate(radius).intersects(item.getBoundingBox())) {
            return source;
        }
        return spotOf(item);
    }

    /**
     * The spot a pickup job for this item goes at, where it is: the block it's lying in. Not the one under its
     * feet: an item lying on farmland (or a slab, a path) is within the top of that block, and a
     * job there is in a solid block, which nowhere to stand is found round, so it was never
     * picked up.
     */
    static BlockPos spotOf(ItemEntity item) {
        return BlockPos.containing(item.getX(), item.getY() + 0.5, item.getZ());
    }

    private static boolean covered(ItemEntity item, List<AABB> jobs) {
        AABB bounds = item.getBoundingBox();
        for (AABB job : jobs) {
            if (job.intersects(bounds)) {
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
     * A pickup job waiting to be created, at game time {@code dueTime}, for the items round
     * {@code pos}. Offered first to {@code suggestedWorker}, if given.
     */
    private record PickupRequest(BlockPos pos, @Nullable UUID suggestedWorker, long dueTime) {}

    /**
     * An item to pick up: it's given a pickup job from game time {@code due} on (once it's landed),
     * by {@code source}, where it was dropped, if given (see spotFor), the first offered to
     * {@code suggestedWorker}, the worker that dropped it, if given.
     */
    private static final class Tracked {
        long due;
        // How long it waited last after a pickup out of reach (0: none was); where it last was
        long wait;

        @Nullable
        BlockPos lastSpot;

        @Nullable
        UUID suggestedWorker;

        @Nullable
        final BlockPos source;

        Tracked(long due, @Nullable UUID suggestedWorker, @Nullable BlockPos source) {
            this.due = due;
            this.suggestedWorker = suggestedWorker;
            this.source = source;
        }
    }
}
