package dev.palworks.job;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.advancement.WorkerAdvancements;
import dev.palworks.ai.StandMemo;
import dev.palworks.ai.path.WorkerNavigator;
import dev.palworks.animation.WorkAnimator;
import dev.palworks.animation.WorkAspectSync;
import dev.palworks.boulder.Boulders;
import dev.palworks.crop.Watering;
import dev.palworks.debug.DebugManager;
import dev.palworks.debug.WorkerDebugSync;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.job.JobPool.JobKey;
import dev.palworks.job.WorkerRegistry.WorkerInfo;
import dev.palworks.reservation.ReservationId;
import dev.palworks.sleep.Sleep;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityDetector;
import dev.palworks.tree.TreeDetector;
import dev.palworks.tree.TreeSize;
import dev.palworks.worker.WorkActivitySync;
import dev.palworks.worker.WorkerEligibility;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import dev.palworks.worker.WorkerStatusSync;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
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

    // Pokémon out in the world that report in but aren't working (their work is off, or they've no
    // work skill left), and the game time each last did. They aren't in the worker registry, so
    // nothing unregistered them when they were recalled, and their state and inventory stayed in
    // memory until the server stopped.
    private final Map<UUID, Long> notWorking = new HashMap<>();
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
    private final SeedRequests seedRequests;
    private final NonWorkerBeds nonWorkerBeds = new NonWorkerBeds(pool);

    /**
     * Private constructor for singleton pattern.
     */
    private JobManager(ServerLevel level) {
        this.level = level;
        this.scanner = new JobScanner(pool);
        this.seedRequests = new SeedRequests(pool, workers);
        this.assigner = new JobAssigner(level, pool, scanner, workers, nonWorkerBeds, seedRequests);
        this.pickups = new PickupQueue(pool, workers, assigner, seedRequests);
        assigner.onUnreachablePickup(area -> pickups.outOfReach(level, area, level.getGameTime()));
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
     * Called for every block change on the server (see the ServerLevel mixins). A watered crop that's
     * broken or replaced by another block loses its watering cooldown (growing, a change of age,
     * keeps it). Workers standing by it have where they stand worked out again (see StandMemo). The
     * scanner looks at the block again (see JobScanner#onBlockChanged). If the
     * block's collision changed (something became passable or blocked: a gate or door toggled, a block
     * broken or placed), workers nearby forget which jobs they couldn't reach, so they re-check
     * on their next assignment. Must stay cheap: returns early unless some worker has failures.
     */
    @SuppressWarnings("ReferenceEquality") // identity on purpose, see below
    public static void onBlockStateChange(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (oldState.getBlock() != newState.getBlock()) {
            Watering.forget(level, pos);
        }
        // Where the workers by it stand is worked out again (it's remembered while nothing changes)
        StandMemo.onBlockChanged(level, pos);
        // A boulder's block that's gone (mined, broken, blown up) isn't the boulder's any more,
        // whether or not there are workers here now. Only on the server thread: the level's saved
        // data isn't to be touched from others (a mod's change there is still never mined, as a
        // boulder block is only work while it's the block placed: Boulders.isMinable)
        List<BlockPos> boulderLeft =
                level.getServer().isSameThread() ? Boulders.get(level).onBlockChanged(pos, newState) : List.of();
        JobManager manager = byLevel.get(level);
        if (manager == null) {
            return;
        }
        // The jobs it gives, if its chunk has been scanned, are looked at again next tick, and, if
        // it was the last of a boulder's top layer, the rest of that boulder too (a layer further
        // down is work now)
        manager.scanner.onBlockChanged(pos);
        boulderLeft.forEach(manager.scanner::onBlockChanged);
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
    // Non-workers' beds (see NonWorkerBeds, sleep.NonWorkerSleep)
    // ============================================

    /** A bed a non-worker has, the game time it got it, and whether it's lying in it yet. */
    public record NonWorkerBed(BlockPos pos, long since, boolean inBed) {}

    /** The bed this non-worker has, if any. Ask every tick it has one: one that stops asking loses it. */
    @Nullable
    public NonWorkerBed nonWorkerBed(UUID pokemon) {
        NonWorkerBeds.Claim claim = nonWorkerBeds.claimOf(pokemon, level.getGameTime());
        return claim == null ? null : new NonWorkerBed(claim.bed(), claim.since(), claim.inBed());
    }

    /** This non-worker wants a bed: it takes a free one, if there is one (see NonWorkerBeds). */
    @Nullable
    public NonWorkerBed claimNonWorkerBed(PokemonEntity entity) {
        NonWorkerBeds.Claim claim = nonWorkerBeds.claim(level, entity);
        return claim == null ? null : new NonWorkerBed(claim.bed(), claim.since(), claim.inBed());
    }

    /** This non-worker has got to its bed, and lies in it. */
    public void settleNonWorkerInBed(UUID pokemon) {
        nonWorkerBeds.settle(pokemon);
    }

    /** This non-worker is done with its bed. */
    public void releaseNonWorkerBed(UUID pokemon) {
        nonWorkerBeds.release(pokemon);
    }

    /** This non-worker couldn't get to its bed: it lets it go, and doesn't ask again for a while. */
    public void giveUpNonWorkerBed(UUID pokemon) {
        nonWorkerBeds.giveUp(pokemon, level.getGameTime());
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

        // Not a worker now: its work is off, or it has no work it can do (it lost its last work
        // skill). If it was registered, drop it from the registry and free its job, or assignment
        // would keep handing it work; and it lets its own job go.
        if (!WorkerEligibility.works(pokemon, state)) {
            WorkerInfo removed = workers.remove(uuid);
            if (removed != null) {
                // It was working until now: it stops where it is, rather than walk on to the job
                // it's just dropped, and what's remembered of it as a worker goes
                entity.getNavigation().stop();
                WorkerNavigator.forget(entity);
                assigner.forget(uuid);
                workers.forgetEntity(uuid);
                state.setCantReach(false);
                state.setCantDeliver(false);
            }
            if (removed != null && removed.currentReservation != null) {
                pool.release(JobKey.of(removed.currentReservation), level.getGameTime());
            }
            if (state.getCurrentReservation() != null) {
                state.clearReservation();
                WorkerStateManager.save(pokemon, state);
            }
            notWorking.put(uuid, level.getGameTime());
            return;
        }
        notWorking.remove(uuid);

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
        }

        // A job it has that the registry doesn't (saved with it, before a restart or its chunk
        // unloading) is still its own: claim it in the pool, or the scan offers it again and
        // another worker is given it too. If another worker has it already, that one keeps it.
        if (stateReservation != null
                && !stateReservation.equals(info.currentReservation)
                && !pool.adopt(stateReservation, uuid, asFound(stateReservation, level))) {
            state.clearReservation();
            WorkerStateManager.save(pokemon, state);
            stateReservation = null;
        }

        BlockPos entityPos = entity.blockPosition();
        info.position = entityPos;
        // A party Pokémon works near its owner; one from a pasture works round its pasture
        info.fromParty = WorkerEligibility.isFromParty(pokemon);
        info.ownerPosition = info.fromParty ? WorkerEligibility.partyOwnerPosition(pokemon, level) : null;
        // A pastured one's home is its pasture block; a party one stays by its owner, so needs none
        info.home = info.fromParty ? null : WorkerEligibility.pastureOf(entity);
        info.roam = info.fromParty ? null : WorkerEligibility.roamOf(entity);
        if (newlyActive) {
            // Just sent out (or back in range): its first job comes from a fresh look round, not
            // from chunk scans up to 2s old that may have missed what's there now
            scanner.rescanAround(entityPos);
        }
        // From its species and types as they are now, so an evolution or form change counts at once
        info.suitabilities = SuitabilityDetector.getSuitabilities(pokemon);
        readLevels(pokemon, info);
        info.currentReservation = stateReservation;
        // A busy worker (in a battle, evolving) gets no new jobs; WorkerAI drops the one it had.
        info.idle = (stateReservation == null) && !WorkerEligibility.isBusy(entity);

        // Every live worker reports in here on its own tick; updateWorkerRegistry() drops the ones
        // that stop reporting (despawned, recalled, disabled, in a non-ticking chunk, changed dimension).
        info.lastSeenTick = level.getGameTime();
        if (entity instanceof PokemonEntity pokemonEntity) {
            workers.putEntity(uuid, pokemonEntity);
        }
        if (newlyActive && level instanceof ServerLevel serverLevel) {
            WorkerAdvancements.startedWorking(pokemon, serverLevel.getServer());
        }
    }

    /** How many of this player's Pokémon are working in this level now. */
    public int workersOwnedBy(UUID owner) {
        int count = 0;
        for (WorkerInfo worker : workers.all()) {
            Pokemon pokemon = workers.pokemon(worker.pokemonUUID);
            if (pokemon != null && owner.equals(pokemon.getOwnerUUID())) {
                count++;
            }
        }
        return count;
    }

    /**
     * The worker's level in each of its skills. Its own method, not a loop in registerWorker: there
     * the loop's iterator shared a local slot with the PokemonEntity below it, and the Architectury
     * transformer (NeoForge dev runs), recomputing the frames, loads PokemonEntity to merge the two,
     * which crashes against vanilla's final LivingEntity methods.
     */
    private static void readLevels(Pokemon pokemon, WorkerInfo info) {
        info.levels.clear();
        for (Suitability skill : info.suitabilities) {
            info.levels.put(skill, SuitabilityDetector.getSuitabilityLevel(pokemon, skill));
        }
    }

    /**
     * The job a worker still has from before a restart, as it would be found now: with the skills
     * it takes (see JobChecks.skillsFor), and as a follow-up if it's one that only comes as one (a
     * pickup). Built from its type alone, nether wart it let go of went back in the pool as any
     * crop, for a Grass type to harvest, and a pickup as a job no rescan or clean-up ever removes.
     */
    private static JobRecord asFound(ReservationId reservation, Level level) {
        JobType type = reservation.getJobType();
        BlockPos pos = reservation.getStationPos();
        Set<Suitability> skills = JobChecks.skillsFor(level, type, pos);
        if (type == JobType.COLLECT_DROPS || type == JobType.TAKE_SEEDS) {
            return JobRecord.followUp(type, pos, level.getGameTime(), null, skills);
        }
        // A tree by its size, as the scan does (one already chopped, its replant left, is anyone's)
        int minLevel = type == JobType.CHOP_TREE
                ? TreeSize.levelNeeded(
                        TreeDetector.validateTree(level, pos).getLogPositions().size())
                : 1;
        return JobRecord.of(type, pos, level.getGameTime(), skills, minLevel);
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
        forgetCaches(pokemonUUID);
    }

    /** Drop what's kept in memory for a Pokémon that's gone, so it doesn't build up. */
    private void forgetCaches(UUID pokemonUUID) {
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

        // Those out but not working stop reporting in too, once they're recalled: what's kept in
        // memory for them goes then, as a worker's does
        notWorking.entrySet().removeIf(entry -> {
            if (now - entry.getValue() <= WORKER_STALE_TICKS) {
                return false;
            }
            forgetCaches(entry.getKey());
            return true;
        });
    }

    // ============================================
    // Reports from workers
    // ============================================

    /**
     * Called when a worker completes a job. A pickup that left items behind gets a job again.
     */
    public void onJobCompleted(Pokemon pokemon, ReservationId completedReservation, Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        BlockPos jobPos = completedReservation.getStationPos();
        UUID workerUUID = pokemon.getUuid();

        // Handle consecutive jobs for harvesting
        if (completedReservation.getJobType() == JobType.HARVEST_CROPS) {
            // No replant job: harvesting resets the crop in place (paid for with one of its seeds),
            // and berries regrow on their own. See CropAdapter.harvestCrop. Its drops get their
            // pickup jobs as they land (see onTrackedItemAdded).
            DebugManager.broadcastActivity(serverLevel, "  §7→ Drops to §eCOLLECT_DROPS§7");
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
    }

    /**
     * Called when a worker finds its reserved job no longer exists — the station block was broken,
     * replaced, or emptied while it was walking there. Removes the job from the pool (like a
     * completion) so it isn't re-offered, and marks the worker idle, so {@link #registerWorker}
     * doesn't give the job back to the pool on the next tick. Unlike {@link #onJobAbandoned} it does NOT
     * blacklist the position: nothing is wrong with the spot, so if real work reappears there (e.g.
     * the replacement furnace gets a smeltable input) it should be assignable again immediately.
     */
    public void onJobInvalidated(Pokemon pokemon, ReservationId reservation, Level level) {
        if (!(level instanceof ServerLevel)) return;

        pool.remove(JobKey.of(reservation));
        workers.markIdle(pokemon.getUuid());
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
    }

    /**
     * Called when a worker completes chopping a tree (which it also replants, as part of the chop).
     * Its drops get their pickup jobs as they land (see onTrackedItemAdded).
     */
    public void onTreeChopped(Pokemon pokemon, BlockPos basePos, Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        DebugManager.broadcastActivity(
                serverLevel,
                String.format(
                        "§a%s§f chopped tree, drops to §eCOLLECT_DROPS§f",
                        pokemon.getSpecies().getName()));
    }

    /**
     * Clean up expired data (old reservations, stale caches).
     */
    private void cleanupExpiredData(ServerLevel level) {
        long currentTime = level.getGameTime();

        if (currentTime % FOLLOW_UP_RECHECK_INTERVAL == 0) {
            pool.dropGoneFollowUps(level);
            seedRequests.cleanUp(level, pickups, assigner);
            Watering.forgetExpired(level);
        }
        scanner.dropUnloaded(level);
        nonWorkerBeds.dropStale(currentTime);
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
     * An item to pick up has come into the level (see TrackedDrops): spawned by a worker's work
     * or a player, or loaded back in with its chunk. It gets a pickup job once it's landed (one
     * dropped by decaying leaves, with the rest of its batch): at {@code source}, where it was
     * dropped (the crop, the tree), if it's landed near it, else where it is. Offered first to
     * {@code suggestedWorker}, the worker that dropped it, if given.
     */
    public static void onTrackedItemAdded(
            ServerLevel level,
            ItemEntity item,
            @Nullable UUID suggestedWorker,
            @Nullable BlockPos source,
            boolean decay) {
        getInstance(level).pickups.track(item, suggestedWorker, source, decay, level.getGameTime());
    }

    /**
     * The items round a worker were out of its reach where it got to (lying on top of something,
     * say): they wait a while before they're picked up (see PickupQueue#outOfReach).
     */
    public static void onPickupOutOfReach(ServerLevel level, AABB area) {
        getInstance(level).pickups.outOfReach(level, area, level.getGameTime());
    }

    /** Whether this item waits to be picked up after being out of reach (see PickupQueue#outOfReach). */
    static boolean isWaitingForPickup(Level level, ItemEntity item) {
        JobManager manager = level instanceof ServerLevel serverLevel ? byLevel.get(serverLevel) : null;
        return manager != null && manager.pickups.isWaiting(item.getUUID(), level.getGameTime());
    }

    /**
     * The seed (or nether wart) this transporter, carrying {@code inventory}, should take from the
     * chest at {@code chestPos}: to plant itself, or for the planter it's bringing seeds to. Null
     * if none (see JobAssigner).
     */
    @Nullable
    public Item seedToFetch(UUID transporter, BlockPos chestPos, WorkerInventory inventory) {
        return assigner.seedToFetchAs(transporter, chestPos, inventory);
    }

    // ============================================
    // Seeds for planters (see SeedRequests)
    // ============================================

    /** The planter this transporter is bringing seeds to, if it's out in the world. */
    @Nullable
    public PokemonEntity seedsRecipient(UUID transporter) {
        SeedRequests.Request request = seedRequests.takenOnBy(transporter);
        return request == null ? null : workers.entity(request.planter);
    }

    /** Whether this transporter carries seeds for the planter it's bringing them to. */
    public boolean hasSeedsToBring(Pokemon transporter) {
        SeedRequests.Request request = seedRequests.takenOnBy(transporter.getUuid());
        WorkerInfo planter = request == null ? null : workers.get(request.planter);
        return planter != null && assigner.seedSlotFor(planter, WorkerInventoryManager.getOrCreate(transporter)) >= 0;
    }

    // A seed handover is thrown as a player throws an item (Player.drop): from this far below its
    // eyes, with this lift, at most this fast, waiting as long before it can be picked up
    private static final double THROW_BELOW_EYES = 0.3;
    private static final double THROW_LIFT = 0.1;
    private static final double THROW_MAX_SPEED = 0.3;
    private static final int THROW_PICKUP_DELAY = 40;
    // How fast (blocks a tick) to throw it for each block it's to go: about what lands it there
    private static final double THROW_SPEED_PER_BLOCK = 0.1;

    /**
     * A transporter has brought seeds to the planter that asked for them: it throws them to the
     * planter, as a player gives another an item, and the planter gets a job to pick them up where
     * they land, once a player could (see SeedRequests). Only that planter takes them;
     * nobody else's pickups do.
     */
    public void handOverSeeds(Pokemon transporter) {
        SeedRequests.Request request = seedRequests.takenOnBy(transporter.getUuid());
        WorkerInfo planter = request == null ? null : workers.get(request.planter);
        PokemonEntity planterEntity = request == null ? null : workers.entity(request.planter);
        if (planter == null || planterEntity == null) {
            return;
        }
        WorkerInventory inventory = WorkerInventoryManager.getOrCreate(transporter);
        int slot = assigner.seedSlotFor(planter, inventory);
        if (slot < 0) {
            return;
        }
        net.minecraft.world.item.ItemStack seeds = inventory.getItem(slot);
        inventory.setItem(slot, net.minecraft.world.item.ItemStack.EMPTY);
        inventory.setChanged();
        PokemonEntity from = transporter.getEntity();
        double startX = from == null ? planterEntity.getX() : from.getX();
        double startY = from == null ? planterEntity.getY() : from.getEyeY() - THROW_BELOW_EYES;
        double startZ = from == null ? planterEntity.getZ() : from.getZ();
        double dx = planterEntity.getX() - startX;
        double dz = planterEntity.getZ() - startZ;
        double across = Math.sqrt(dx * dx + dz * dz);
        double speed = Math.min(across * THROW_SPEED_PER_BLOCK, THROW_MAX_SPEED);
        double velocityX = across > 0 ? dx / across * speed : 0;
        double velocityZ = across > 0 ? dz / across * speed : 0;
        ItemEntity item = new ItemEntity(level, startX, startY, startZ, seeds, velocityX, THROW_LIFT, velocityZ);
        item.setPickUpDelay(THROW_PICKUP_DELAY);
        level.addFreshEntity(item);
        // Marked once it's in: an item that comes into the world already marked (as one loaded with
        // its chunk does) was left for a planter that's long forgotten (see TrackedDrops)
        dev.palworks.drops.TrackedDrops.handOverTo(item, planter.pokemonUUID);
        seedRequests.handOver(assigner, planter, item, level.getGameTime());
        Palworks.LOGGER.debug(
                "{} left {} for {} at {}",
                transporter.getSpecies().getName(),
                seeds,
                planterEntity.getPokemon().getSpecies().getName(),
                item.blockPosition());
    }

    /** A planter has picked up the seeds left for it. */
    public void onSeedsTaken(UUID planter) {
        seedRequests.taken(planter);
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
