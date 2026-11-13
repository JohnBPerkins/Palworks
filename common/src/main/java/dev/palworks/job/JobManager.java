package dev.palworks.job;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.animation.WorkAnimator;
import dev.palworks.animation.WorkAspectSync;
import dev.palworks.crop.Watering;
import dev.palworks.debug.DebugManager;
import dev.palworks.debug.WorkerDebugSync;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.job.JobPool.JobKey;
import dev.palworks.job.WorkerRegistry.WorkerInfo;
import dev.palworks.reservation.ReservationId;
import dev.palworks.sleep.Sleep;
import dev.palworks.suitability.SuitabilityDetector;
import dev.palworks.worker.WorkActivitySync;
import dev.palworks.worker.WorkerEligibility;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import dev.palworks.worker.WorkerStatusSync;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Central job management system for all worker Pokémon in a level: one per level. Keeps the
 * registry of its workers, and each tick runs, in order, its pickup queue ({@link PickupQueue}),
 * the job scan ({@link JobScanner}) and assignment ({@link JobAssigner}), all sharing one
 * {@link JobPool}. WorkerAI reports back here as workers finish, drop or give back their jobs.
 *
 * <p>Designed for 50+ workers with chunk-based caching and performance optimization.
 */
public class JobManager {

    private static final int WORKER_STALE_TICKS = 5; // Unregister a worker this long after it stops ticking
    // (a little slack so a skipped tick isn't a despawn)
    private static final int FOLLOW_UP_RECHECK_INTERVAL = 20; // Ticks between checks that follow-up jobs still exist
    private static final long JOB_POOL_EXPIRY_TICKS = 5 * 60 * 20; // Drop jobs nobody has taken in 5 minutes

    // Singleton instances (one per ServerLevel)
    private static final Map<String, JobManager> instances = new ConcurrentHashMap<>();
    // Same instances keyed by the level itself, for the per-block-change hook (no string building).
    private static final Map<ServerLevel, JobManager> byLevel = new ConcurrentHashMap<>();

    private final ServerLevel level;
    private final WorkerRegistry workers = new WorkerRegistry();
    private final JobPool pool = new JobPool();
    private final JobScanner scanner;
    private final JobAssigner assigner;
    private final PickupQueue pickups;

    /**
     * Private constructor for singleton pattern.
     */
    private JobManager(ServerLevel level) {
        this.level = level;
        this.scanner = new JobScanner(pool);
        this.assigner = new JobAssigner(level, pool, scanner, workers);
        this.pickups = new PickupQueue(pool, workers, assigner);
    }

    /**
     * Get the JobManager instance for a given world.
     */
    public static JobManager getInstance(ServerLevel level) {
        String dimensionKey = level.dimension().location().toString();
        return instances.computeIfAbsent(dimensionKey, k -> {
            JobManager manager = new JobManager(level);
            byLevel.put(level, manager);
            return manager;
        });
    }

    /**
     * Called for every block change on the server (see the ServerLevel mixins). The scanner looks
     * at the block again (see JobScanner#onBlockChanged). If the block's collision changed (something became passable or blocked: a gate or door toggled, a block
     * broken or placed), workers nearby forget which jobs they couldn't reach, so they re-check
     * on their next assignment. Must stay cheap: returns early unless some worker has failures.
     */
    @SuppressWarnings("ReferenceEquality") // identity on purpose, see below
    public static void onBlockStateChange(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        JobManager manager = byLevel.get(level);
        if (manager == null) {
            return;
        }
        // The jobs it gives, if its chunk has been scanned, are looked at again next tick
        manager.scanner.onBlockChanged(pos);
        if (!manager.assigner.hasUnreachable()) {
            return;
        }
        // Collision shapes are cached and shared per state (e.g. every full block, every crop), so
        // identity is a cheap "did passability change" test. A false positive only costs a re-check.
        if (oldState.getCollisionShape(level, pos) == newState.getCollisionShape(level, pos)) {
            return;
        }
        manager.assigner.forgetUnreachableNear(pos);
    }

    /**
     * Called whenever a block entity's contents change (see BlockEntityChangedMixin): a chest or
     * furnace the scanner knows is looked at again (see JobScanner#onContainerChanged). Called very
     * often, so it stays a couple of lookups.
     */
    public static void onContainerChanged(ServerLevel level, BlockPos pos) {
        JobManager manager = byLevel.get(level);
        if (manager != null) {
            manager.scanner.onContainerChanged(pos);
        }
    }

    /**
     * Have the scanner look at the block at {@code pos} again at game time {@code at}, with nothing
     * changing to say so (a watered crop, wanting water again once its cooldown's over).
     */
    public static void recheckAt(ServerLevel level, BlockPos pos, long at) {
        JobManager manager = byLevel.get(level);
        if (manager != null) {
            manager.scanner.recheckAt(pos, at);
        }
    }

    /**
     * Drop all per-dimension JobManager instances. Call on server stop so stale instances
     * (and their references to old ServerLevels/entities) don't leak across world reloads.
     * Instances are keyed by dimension id, which collides between worlds in the same JVM.
     */
    public static void clearAll() {
        instances.clear();
        byLevel.clear();
        WorkActivitySync.clear();
        WorkerDebugSync.clear();
        WorkAnimator.clear();
        WorkAspectSync.clear();
        WorkerStatusSync.clear();
        Watering.clear();
    }

    /**
     * Main tick method - called once per server tick.
     * Handles all job management responsibilities.
     */
    public void tick(ServerLevel level) {
        // Phase 1: Update worker registry (register/unregister workers)
        updateWorkerRegistry(level);

        // Phase 2: Create the pickup jobs that are due (a harvest's or chop's drops)
        pickups.process(level);

        // Phase 3: Scan for new jobs (sparse, chunk-based)
        scanner.scan(level, workers.all());

        // Phase 4: Assign jobs to idle workers (proximity + suitability)
        assigner.assign(level);

        // Debug overlay for players who asked for it (/palworks debug)
        WorkerDebugSync.tick(level);

        // Phase 5: Clean up expired reservations and stale cache
        cleanupExpiredData(level);
    }

    // ============================================
    // Worker Registry Methods
    // ============================================

    /**
     * Register or update a worker in the active workers registry.
     */
    public void registerWorker(Pokemon pokemon, Mob entity, Level level) {
        UUID uuid = pokemon.getUuid();

        // Fainted (e.g. in a battle): it can't work until it's healed, so it isn't a worker until
        // then. Unregistering hands its job over, with any progress, for another worker to finish.
        if (WorkerEligibility.isFainted(pokemon)) {
            if (workers.contains(uuid)) {
                unregisterWorker(uuid);
            }
            return;
        }
        boolean isNewWorker = !WorkerStateManager.hasWorkerState(pokemon);
        WorkerState state = WorkerStateManager.getOrCreate(pokemon);

        if (isNewWorker && level instanceof ServerLevel serverLevel) {
            // Opt-in activity message (only players who ran /palworks debug see it)
            DebugManager.broadcastActivity(serverLevel, pokemon.getSpecies().getName() + " is now a worker");
        }

        // Disabled workers get no jobs. If one was registered before being disabled, drop it from
        // the registry and free its job, or assignment would keep handing it work.
        if (!state.isEnabled()) {
            WorkerInfo removed = workers.remove(uuid);
            if (removed != null && removed.currentReservation != null) {
                pool.release(JobKey.of(removed.currentReservation), level.getGameTime());
            }
            return;
        }

        // Create or update worker info
        boolean newlyActive = !workers.contains(uuid);
        WorkerInfo info = workers.getOrAdd(uuid, entity.blockPosition());

        // WorkerState (NBT) is the single source of truth for a worker's reservation.
        // If the worker has dropped its reservation (job complete, stuck, expired, or
        // corrupted) but JobManager still has the job reserved, release it back to the
        // available pool so it can be reassigned. Without this, abandoned jobs leak.
        ReservationId stateReservation = state.getCurrentReservation();
        if (info.currentReservation != null && stateReservation == null) {
            pool.release(JobKey.of(info.currentReservation), level.getGameTime());
            Palworks.LOGGER.debug(
                    "{} dropped its reservation; released job at {} back to the pool",
                    pokemon.getSpecies().getName(),
                    info.currentReservation.getStationPos());
        }

        BlockPos entityPos = entity.blockPosition();
        info.position = entityPos;
        // A party Pokémon works near its owner; one from a pasture works round its pasture
        info.fromParty = WorkerEligibility.isFromParty(pokemon);
        info.ownerPosition = info.fromParty ? WorkerEligibility.partyOwnerPosition(pokemon, level) : null;
        // A pastured one's home is its pasture block; a party one stays by its owner, so needs none
        info.home = info.fromParty ? null : WorkerEligibility.pastureOf(entity);
        if (newlyActive) {
            // Just sent out (or back in range): its first job comes from a fresh look round, not
            // from chunk scans up to 2s old that may have missed what's there now
            scanner.rescanAround(entityPos);
        }
        // From its species and types as they are now, so an evolution or form change counts at once
        info.suitabilities = SuitabilityDetector.getSuitabilities(pokemon);
        info.currentReservation = stateReservation;
        // A busy worker (in a battle, evolving) gets no new jobs; WorkerAI drops the one it had.
        info.idle = (stateReservation == null) && !WorkerEligibility.isBusy(entity);

        // Every live worker reports in here on its own tick; updateWorkerRegistry() drops the ones
        // that stop reporting (despawned, recalled, disabled, in a non-ticking chunk, changed dimension).
        info.lastSeenTick = level.getGameTime();
        if (entity instanceof PokemonEntity pokemonEntity) {
            workers.putEntity(uuid, pokemonEntity);
        }
    }

    /**
     * Unregister a worker and release its job reservation.
     */
    public void unregisterWorker(UUID pokemonUUID) {
        WorkerInfo removed = workers.remove(pokemonUUID);
        if (removed != null) {
            // Free up the reserved job
            if (removed.currentReservation != null) {
                // The worker is gone (recalled, despawned, unloaded, disabled): hand its job over,
                // with any progress (e.g. a chopped tree that still needs replanting), so another
                // worker carries on from there. It drops the job itself, so it doesn't redo it
                // when it comes back.
                PokemonEntity entity = workers.lastEntity(pokemonUUID);
                Pokemon pokemon = entity != null ? entity.getPokemon() : null;
                WorkerState state = pokemon != null ? WorkerStateManager.get(pokemon) : null;
                if (state != null) {
                    pool.captureProgress(state, JobKey.of(removed.currentReservation));
                    state.clearReservation();
                    WorkerStateManager.save(pokemon, state);
                }
                pool.release(JobKey.of(removed.currentReservation), level.getGameTime());

                Palworks.LOGGER.debug(
                        "Unregistered worker {} and released job at {}",
                        pokemonUUID,
                        removed.currentReservation.getStationPos());
            }
        }
        // Recalled (or knocked out) in its sleep: it wakes, and isn't sent back out asleep
        PokemonEntity gone = workers.lastEntity(pokemonUUID);
        if (gone != null
                && (gone.getRemovalReason() == Entity.RemovalReason.DISCARDED
                        || gone.getRemovalReason() == Entity.RemovalReason.KILLED)) {
            WorkerState state = WorkerStateManager.get(gone.getPokemon());
            if (state != null && (state.isAsleep() || state.isInBed())) {
                state.setAsleep(false);
                state.setInBed(false);
                state.setWokenUntil(level.getGameTime() + Sleep.WOKEN_TICKS);
                WorkerStateManager.save(gone.getPokemon(), state);
            }
        }
        // Drop per-worker caches so they don't leak after the worker is gone.
        workers.forgetEntity(pokemonUUID);
        assigner.forget(pokemonUUID);
        WorkerInventoryManager.unload(pokemonUUID);
        WorkerStateManager.unload(pokemonUUID);
        WorkActivitySync.forget(pokemonUUID);
    }

    /**
     * Unregister workers that have stopped reporting in through {@link #registerWorker}.
     * Costs O(workers), not O(entities in the level).
     */
    private void updateWorkerRegistry(ServerLevel level) {
        long now = level.getGameTime();
        List<UUID> toRemove = new ArrayList<>();
        for (WorkerInfo worker : workers.all()) {
            if (now - worker.lastSeenTick > WORKER_STALE_TICKS) {
                toRemove.add(worker.pokemonUUID);
            }
        }

        for (UUID uuid : toRemove) {
            unregisterWorker(uuid);
        }
    }

    // ============================================
    // Reports from workers
    // ============================================

    /**
     * Called when a worker completes a job.
     * Creates consecutive jobs for harvest chains.
     */
    public void onJobCompleted(Pokemon pokemon, ReservationId completedReservation, Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        BlockPos jobPos = completedReservation.getStationPos();
        UUID workerUUID = pokemon.getUuid();

        Palworks.LOGGER.debug(
                "{} completed {} job at {}", pokemon.getSpecies().getName(), completedReservation.getJobType(), jobPos);

        // Handle consecutive jobs for harvesting
        if (completedReservation.getJobType() == JobType.HARVEST_CROPS) {
            long currentTime = level.getGameTime();

            // No replant job: harvesting resets the crop in place (paid for with one of its seeds),
            // and berries regrow on their own. See CropAdapter.harvestCrop.
            Palworks.LOGGER.debug("Queuing pickup job for harvest at {}", jobPos);
            DebugManager.broadcastActivity(serverLevel, "  §7→ Queued §eCOLLECT_DROPS§7");

            // Queue 1: Collect dropped items (delay 2 ticks for items to spawn)
            pickups.add(jobPos, workerUUID, currentTime + PickupQueue.DROPS_SPAWN_DELAY);
        }

        // A pickup that left items behind (they didn't fit): they get a job of their own again, for
        // any transporter with room for them
        if (completedReservation.getJobType() == JobType.COLLECT_DROPS) {
            pickups.add(jobPos, null, level.getGameTime());
        }

        // IMPORTANT: Remove completed job entirely (don't put back in available pool!)
        pool.remove(JobKey.of(completedReservation)); // from available too, in case it's there

        // Mark worker as idle so they can receive new assignments
        workers.markIdle(workerUUID);
    }

    /**
     * Called when a worker gives up on a job it couldn't reach (stuck or timed out).
     * Blacklists that job position for this worker for a while so it isn't immediately
     * reassigned the same unreachable job. The reservation itself is released via the
     * registerWorker reconciliation on the next tick.
     */
    public void onJobAbandoned(Pokemon pokemon, ReservationId reservation, Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        UUID workerUUID = pokemon.getUuid();
        BlockPos pos = reservation.getStationPos();
        WorkerInfo info = workers.get(workerUUID);

        assigner.rememberUnreachable(workerUUID, info != null ? info.position : null, pos, serverLevel.getGameTime());

        // If it had already done part of the job, hand that progress over with it
        WorkerState state = WorkerStateManager.get(pokemon);
        if (state != null) {
            pool.captureProgress(state, JobKey.of(reservation));
        }

        Palworks.LOGGER.debug(
                "{} abandoned {} at {}; won't retry it until terrain changes or it moves (max {} ticks)",
                pokemon.getSpecies().getName(),
                reservation.getJobType(),
                pos,
                JobAssigner.JOB_FAILURE_BLACKLIST_TICKS);
    }

    /**
     * Called when a worker finds its reserved job no longer exists — the station block was broken,
     * replaced, or emptied while it was walking there. Removes the job from both pools (like a
     * completion) so it isn't re-offered, and marks the worker idle. Clearing the worker's
     * {@code currentReservation} here prevents {@link #registerWorker} from re-releasing the job
     * back into {@code availableJobs} on the next tick. Unlike {@link #onJobAbandoned} it does NOT
     * blacklist the position: nothing is wrong with the spot, so if real work reappears there (e.g.
     * the replacement furnace gets a smeltable input) it should be assignable again immediately.
     */
    public void onJobInvalidated(Pokemon pokemon, ReservationId reservation, Level level) {
        if (!(level instanceof ServerLevel)) return;

        BlockPos pos = reservation.getStationPos();
        pool.remove(JobKey.of(reservation));
        workers.markIdle(pokemon.getUuid());

        Palworks.LOGGER.debug(
                "{}'s {} at {} no longer exists - removed from pool",
                pokemon.getSpecies().getName(),
                reservation.getJobType(),
                pos);
    }

    /**
     * Called when a worker gives its job back because it can't do it any more (see
     * {@link JobChecks#canWorkerDo}: its seeds were taken, its inventory filled up). The job goes back in the
     * pool, with any progress (see {@link JobPool#captureProgress}), for a worker that can do it: this one
     * isn't offered it again until it can, since jobs are handed out by the same check. Not
     * remembered as unreachable: nothing's wrong with the spot.
     */
    public void onJobGivenBack(Pokemon pokemon, ReservationId reservation) {
        JobKey key = JobKey.of(reservation);
        WorkerState state = WorkerStateManager.get(pokemon);
        if (state != null) {
            pool.captureProgress(state, key);
        }
        pool.release(key, level.getGameTime());
        workers.markIdle(pokemon.getUuid());

        Palworks.LOGGER.debug(
                "{} can't do its {} at {} any more - given back to the pool",
                pokemon.getSpecies().getName(),
                reservation.getJobType(),
                reservation.getStationPos());
    }

    /**
     * Called when a worker completes chopping a tree (which it also replants, as part of the chop).
     * Queues a job to collect the drops.
     */
    public void onTreeChopped(Pokemon pokemon, BlockPos basePos, Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        UUID workerUUID = pokemon.getUuid();
        long currentTime = level.getGameTime();

        Palworks.LOGGER.debug("{} chopped tree at base {}", pokemon.getSpecies().getName(), basePos);

        DebugManager.broadcastActivity(
                serverLevel,
                String.format(
                        "§a%s§f chopped tree, queuing §eCOLLECT_DROPS§f",
                        pokemon.getSpecies().getName()));

        // Queue 1: Collect dropped items (delay 2 ticks for items to spawn)
        pickups.add(basePos, workerUUID, currentTime + PickupQueue.DROPS_SPAWN_DELAY);
    }

    /**
     * Clean up expired data (old reservations, stale caches).
     */
    private void cleanupExpiredData(ServerLevel level) {
        long currentTime = level.getGameTime();

        if (currentTime % FOLLOW_UP_RECHECK_INTERVAL == 0) {
            pool.dropGoneFollowUps(level);
            Watering.forgetExpired(level);
        }
        scanner.dropUnloaded(level);
        pool.expire(currentTime, JOB_POOL_EXPIRY_TICKS);
        assigner.expireUnreachable(currentTime);

        // Release orphaned reservations (jobs reserved by workers that no longer exist)
        for (JobRecord job : pool.orphaned(workers)) {
            Palworks.LOGGER.debug(
                    "Releasing orphaned {} job at {} - worker {} no longer exists",
                    job.type(),
                    job.position(),
                    job.reservedBy());
            pool.release(JobKey.of(job), currentTime);
        }
    }

    // ============================================
    // Used by WorkerAI, debug tools and tests
    // ============================================

    /**
     * Have the chunks covering a box be scanned again as soon as possible, after lots of blocks in
     * it changed at once (e.g. /palworks testarea). Until they have, no worker near them is given a
     * job, so none takes a far one before the nearer ones are found.
     */
    public void rescanArea(BlockPos min, BlockPos max) {
        scanner.rescanArea(min, max);
    }

    /**
     * Queue a job to pick up the items lying round {@code pos} (within
     * {@link JobChecks#pickupRadius}), created at {@code gameTime} if there are any then.
     * {@code suggestedWorker}, if given, is offered it first.
     */
    public void queuePickup(BlockPos pos, @Nullable UUID suggestedWorker, long gameTime) {
        pickups.add(pos, suggestedWorker, gameTime);
    }

    /**
     * The seed a planter at {@code workerPos} should take from the chest at {@code chestPos}, or
     * null if none of its seeds can go anywhere open in range (see JobAssigner).
     */
    @Nullable
    public Item seedToFetch(BlockPos chestPos, @Nullable BlockPos workerPos) {
        return assigner.seedToFetch(chestPos, workerPos);
    }

    /** The spots this worker currently remembers it couldn't reach (for the debug overlay). */
    public List<BlockPos> unreachableFor(UUID workerUUID, long now) {
        return assigner.unreachableFor(workerUUID, now);
    }

    /** Whether a job of this type at {@code pos} is waiting for a worker (tests, debugging). */
    public boolean isAvailable(JobType type, BlockPos pos) {
        return pool.isAvailable(new JobKey(pos, type));
    }

    /** Where the jobs of this type waiting for a worker are (tests, debugging). */
    public List<BlockPos> availablePositions(JobType type) {
        return pool.available().stream()
                .filter(job -> job.type() == type)
                .map(JobRecord::position)
                .toList();
    }

    /** Whether the chunk {@code pos} is in has been scanned and is indexed (tests, debugging). */
    public boolean isIndexed(BlockPos pos) {
        return scanner.isIndexed(pos);
    }

    /** Whether the scanner knows the block at {@code pos} as a chest, trough, bed or furnace (tests). */
    public boolean isKnownPlace(BlockPos pos) {
        return scanner.isKnownPlace(pos);
    }

    /** The {@code /palworks jobs} report: lines of formatted text. */
    public List<String> getJobsDebugInfo(ServerLevel level) {
        return JobsDebugReport.build(level, pool, workers, assigner);
    }
}
