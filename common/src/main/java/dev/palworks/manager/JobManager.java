package dev.palworks.manager;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.ai.WorkPositions;
import dev.palworks.ai.path.WorkerNavigator;
import dev.palworks.ai.path.WorkerPathfinder;
import dev.palworks.ai.path.WorkerPathfinder.Reach;
import dev.palworks.animation.WorkAnimator;
import dev.palworks.animation.WorkAspectSync;
import dev.palworks.crop.CropAdapter;
import dev.palworks.debug.DebugManager;
import dev.palworks.debug.WorkerDebugSync;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.job.JobBlockTags;
import dev.palworks.job.JobType;
import dev.palworks.job.WorkRange;
import dev.palworks.reservation.ReservationId;
import dev.palworks.station.ChestHelper;
import dev.palworks.station.FurnaceHelper;
import dev.palworks.station.StationDetector;
import dev.palworks.station.StationState;
import dev.palworks.station.StationStateManager;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityDetector;
import dev.palworks.tree.TreeDetector;
import dev.palworks.worker.WorkActivitySync;
import dev.palworks.worker.WorkerEligibility;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * Central job management system for all worker Pokemon in a world.
 * Handles job discovery, assignment, and consecutive job chains.
 *
 * Designed for 50+ workers with chunk-based caching and performance optimization.
 */
public class JobManager {
    // Job positions listed per job type by /palworks jobs
    private static final int JOB_POSITIONS_SHOWN = 3;

    private static final boolean DEBUG = Palworks.DEBUG;

    // Performance tuning constants
    // Soft cap on blocks scanned per tick. Checked between chunks (a started chunk always
    // finishes), so a tick may overshoot by up to one chunk's band. ~2 chunk-bands at 32 vertical.
    private static final int MAX_BLOCKS_SCANNED_PER_TICK = 32768;
    private static final int JOB_CACHE_LIFETIME_TICKS = 40; // 2s: max staleness of a chunk's job scan
    // (also bounds furnace/crop discovery latency)
    private static final int ASSIGNMENT_BATCH_SIZE = 10; // Workers assigned per tick
    private static final int WORKER_SCAN_RADIUS = WorkRange.HORIZONTAL; // Block radius to scan around workers
    private static final int VERTICAL_SCAN_RADIUS = WorkRange.VERTICAL; // Y range above/below a worker to scan
    private static final int MAX_PATH_CHECKS_PER_WORKER = 8; // Cap expensive createPath() calls per assignment
    private static final int DELIVERY_SEARCH_RADIUS = 32; // Horizontal reach for picking a delivery chest
    private static final int DELIVERY_SEARCH_VERTICAL = 4; // Y reach for picking a delivery chest
    // A worker won't retry a job it couldn't reach until terrain near it changes, it moves
    // UNREACHABLE_FORGET_MOVE_DIST blocks, or this backstop runs out (see recentlyFailedJobs).
    private static final int JOB_FAILURE_BLACKLIST_TICKS = 600; // 30s backstop
    private static final int UNREACHABLE_FORGET_MOVE_DIST = 8; // Blocks a worker moves before retrying jobs
    private static final int WORKER_STALE_TICKS = 5; // Unregister a worker this long after it stops ticking
    // (a little slack so a skipped tick isn't a despawn)
    private static final int CONSECUTIVE_JOB_PICKUP_DELAY = 2; // Ticks to wait for items to spawn
    private static final int FOLLOW_UP_RECHECK_INTERVAL = 20; // Ticks between checks that follow-up jobs still exist
    private static final int HIGH_PRIORITY = 9; // Priority for consecutive jobs
    /** How far round a pickup job's spot its items are looked for (and picked up by the worker). */
    public static final double PICKUP_RADIUS = 3.0;

    private static final boolean MINING_JOBS_ENABLED = false; // Flip on once MINE_RESOURCE breaks ores
    private static final long JOB_POOL_EXPIRY_TICKS = 5 * 60 * 20; // Drop jobs nobody has taken in 5 minutes

    // Singleton instances (one per ServerLevel)
    private static final Map<String, JobManager> instances = new ConcurrentHashMap<>();
    // Same instances keyed by the level itself, for the per-block-change hook (no string building).
    private static final Map<ServerLevel, JobManager> byLevel = new ConcurrentHashMap<>();

    // Core registries
    private final Map<UUID, WorkerInfo> activeWorkers; // All active workers
    // Keyed by position AND job type: several jobs can share a block, e.g. chopping a tree and
    // picking up its drops both happen at its base. Keyed by position alone, one overwrote the
    // other (a fainted lumberjack's half-done chop, still needing its replant, was lost that way).
    private final Map<JobKey, JobRecord> availableJobs; // Discovered jobs
    private final Map<JobKey, JobRecord> reservedJobs; // Currently assigned

    // Chunk-based job cache (performance optimization)
    private final Map<ChunkPos, ChunkJobCache> chunkCaches;

    // Per-tick cache of live worker entities (Pokemon UUID -> entity), rebuilt each
    // updateWorkerRegistry() so UUID lookups are O(1) instead of scanning all entities.
    private final Map<UUID, PokemonEntity> entityCache = new HashMap<>();

    // Per-worker memory of job positions the worker couldn't reach, either because the path check
    // failed at assignment or it got stuck walking there (Pokemon UUID -> job position -> game tick
    // the entry expires). Stops idle workers re-pathfinding the same unreachable jobs every tick.
    // Cleared early when collision changes near the worker (onBlockStateChange: a gate opened, a
    // block broken) or when the worker moves away from failureAnchors, so it reacts right away
    // when a player clears the way.
    private final Map<UUID, Map<BlockPos, Long>> recentlyFailedJobs = new ConcurrentHashMap<>();
    // Where each worker was when its current failures were recorded (reachability is from there).
    private final Map<UUID, BlockPos> failureAnchors = new ConcurrentHashMap<>();

    // Blocks of trees already validated during the current chunk scan, so a tree with several base
    // blocks only gets one tree search and one job. Cleared at the start of each chunk scan.
    private final Set<BlockPos> treeBlocksSeenThisScan = new HashSet<>();

    // Consecutive job queue (harvest → pickup + replant)
    private final Queue<ConsecutiveJobRequest> consecutiveJobQueue;

    // World reference
    private final ServerLevel level;

    /**
     * Private constructor for singleton pattern.
     */
    private JobManager(ServerLevel level) {
        this.level = level;
        this.activeWorkers = new ConcurrentHashMap<>();
        this.availableJobs = new ConcurrentHashMap<>();
        this.reservedJobs = new ConcurrentHashMap<>();
        this.chunkCaches = new ConcurrentHashMap<>();
        // Ordered by due time, since processConsecutiveJobs stops at the first request not yet due
        // (a FIFO would only be correct while every request uses the same delay).
        this.consecutiveJobQueue = new PriorityQueue<>(Comparator.comparingLong(r -> r.creationTime));
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
     * Called for every block change on the server (see the ServerLevel mixins). If the block's
     * collision changed (something became passable or blocked: a gate or door toggled, a block
     * broken or placed), workers nearby forget which jobs they couldn't reach, so they re-check
     * on their next assignment. Must stay cheap: returns early unless some worker has failures.
     */
    @SuppressWarnings("ReferenceEquality") // identity on purpose, see below
    public static void onBlockStateChange(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        JobManager manager = byLevel.get(level);
        if (manager == null || manager.recentlyFailedJobs.isEmpty()) {
            return;
        }
        // Collision shapes are cached and shared per state (e.g. every full block, every crop), so
        // identity is a cheap "did passability change" test. A false positive only costs a re-check.
        if (oldState.getCollisionShape(level, pos) == newState.getCollisionShape(level, pos)) {
            return;
        }
        manager.forgetUnreachableNear(pos);
    }

    private void forgetUnreachableNear(BlockPos pos) {
        for (WorkerInfo worker : activeWorkers.values()) {
            if (!WorkRange.contains(worker.position, pos)) {
                continue;
            }
            forgetUnreachable(worker.pokemonUUID);
        }
    }

    private void forgetUnreachable(UUID workerUUID) {
        recentlyFailedJobs.remove(workerUUID);
        failureAnchors.remove(workerUUID);
    }

    private void rememberUnreachable(UUID workerUUID, @Nullable BlockPos workerPos, BlockPos jobPos, long now) {
        recentlyFailedJobs
                .computeIfAbsent(workerUUID, k -> new ConcurrentHashMap<>())
                .put(jobPos, now + JOB_FAILURE_BLACKLIST_TICKS);
        if (workerPos != null) {
            failureAnchors.putIfAbsent(workerUUID, workerPos);
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
    }

    /**
     * Main tick method - called once per server tick.
     * Handles all job management responsibilities.
     */
    public void tick(ServerLevel level) {
        // Phase 1: Update worker registry (register/unregister workers)
        updateWorkerRegistry(level);

        // Phase 2: Process consecutive job queue (harvest → pickup + replant chains)
        processConsecutiveJobs(level);

        // Phase 3: Scan for new jobs (sparse, chunk-based)
        scanForNewJobs(level);

        // Phase 4: Assign jobs to idle workers (proximity + suitability)
        assignJobsToWorkers(level);

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
            if (activeWorkers.containsKey(uuid)) {
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
            WorkerInfo removed = activeWorkers.remove(uuid);
            if (removed != null && removed.currentReservation != null) {
                releaseJobReservation(JobKey.of(removed.currentReservation));
            }
            return;
        }

        // Create or update worker info
        boolean newlyActive = !activeWorkers.containsKey(uuid);
        WorkerInfo info = activeWorkers.computeIfAbsent(uuid, k -> new WorkerInfo());

        // WorkerState (NBT) is the single source of truth for a worker's reservation.
        // If the worker has dropped its reservation (job complete, stuck, expired, or
        // corrupted) but JobManager still has the job reserved, release it back to the
        // available pool so it can be reassigned. Without this, abandoned jobs leak.
        ReservationId stateReservation = state.getCurrentReservation();
        if (info.currentReservation != null && stateReservation == null) {
            releaseJobReservation(JobKey.of(info.currentReservation));
            if (DEBUG) {
                Palworks.LOGGER.debug(
                        "{} dropped its reservation; released job at {} back to the pool",
                        pokemon.getSpecies().getName(),
                        info.currentReservation.getStationPos());
            }
        }

        info.pokemonUUID = uuid;
        BlockPos entityPos = entity.blockPosition();
        info.position = entityPos;
        // A party Pokémon works near its owner; one from a pasture works round its pasture
        info.fromParty = WorkerEligibility.isFromParty(pokemon);
        info.ownerPosition = info.fromParty ? WorkerEligibility.partyOwnerPosition(pokemon, level) : null;
        if (newlyActive) {
            // Just sent out (or back in range): its first job comes from a fresh look round, not
            // from chunk scans up to 2s old that may have missed what's there now
            rescanAround(entityPos);
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
            entityCache.put(uuid, pokemonEntity);
        }
    }

    /**
     * Unregister a worker and release its job reservation.
     */
    public void unregisterWorker(UUID pokemonUUID) {
        WorkerInfo removed = activeWorkers.remove(pokemonUUID);
        if (removed != null) {
            // Free up the reserved job
            if (removed.currentReservation != null) {
                // The worker is gone (recalled, despawned, unloaded, disabled): hand its job over,
                // with any progress (e.g. a chopped tree that still needs replanting), so another
                // worker carries on from there. It drops the job itself, so it doesn't redo it
                // when it comes back.
                PokemonEntity entity = entityCache.get(pokemonUUID);
                Pokemon pokemon = entity != null ? entity.getPokemon() : null;
                WorkerState state = pokemon != null ? WorkerStateManager.get(pokemon) : null;
                if (state != null) {
                    captureProgress(state, JobKey.of(removed.currentReservation));
                    state.clearReservation();
                    WorkerStateManager.save(pokemon, state);
                }
                releaseJobReservation(JobKey.of(removed.currentReservation));

                if (DEBUG) {
                    Palworks.LOGGER.debug(
                            "Unregistered worker {} and released job at {}",
                            pokemonUUID,
                            removed.currentReservation.getStationPos());
                }
            }
        }
        // Drop per-worker caches so they don't leak after the worker is gone.
        entityCache.remove(pokemonUUID);
        forgetUnreachable(pokemonUUID);
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
        for (Map.Entry<UUID, WorkerInfo> entry : activeWorkers.entrySet()) {
            if (now - entry.getValue().lastSeenTick > WORKER_STALE_TICKS) {
                toRemove.add(entry.getKey());
            }
        }

        for (UUID uuid : toRemove) {
            unregisterWorker(uuid);
        }
    }

    // ============================================
    // Job Discovery Methods
    // ============================================

    /**
     * Scan for new jobs using chunk-based caching.
     */
    private void scanForNewJobs(ServerLevel level) {
        int blocksScannedThisTick = 0;
        long currentTime = level.getGameTime();

        // Get chunks near active workers, each with the Y band to scan (around worker height).
        Map<ChunkPos, int[]> scanRanges = getChunkScanRangesNearWorkers(level);

        for (Map.Entry<ChunkPos, int[]> entry : scanRanges.entrySet()) {
            // Block budget is checked between chunks; a chunk we start always finishes.
            if (blocksScannedThisTick >= MAX_BLOCKS_SCANNED_PER_TICK) {
                break;
            }

            ChunkPos chunk = entry.getKey();
            ChunkJobCache cache = chunkCaches.get(chunk);

            // Skip if cache is fresh and not dirty
            if (cache != null && !cache.isDirty && (currentTime - cache.lastScanTime < JOB_CACHE_LIFETIME_TICKS)) {
                continue;
            }

            // Scan this chunk's worker-height band for jobs
            int[] range = entry.getValue();
            blocksScannedThisTick += scanChunkForJobs(level, chunk, range[0], range[1]);
        }

        if (DEBUG && blocksScannedThisTick > 0) {
            Palworks.LOGGER.debug(
                    "Scanned {} blocks across {} chunks ({} jobs available)",
                    blocksScannedThisTick,
                    scanRanges.size(),
                    availableJobs.size());
        }
    }

    /**
     * Get chunks near active workers, each mapped to the [minY, maxY) band to scan.
     * The band is bounded to a vertical window around the contributing worker(s) so the
     * scan can cover every layer (no Y gaps) without sweeping the entire build height.
     */
    private Map<ChunkPos, int[]> getChunkScanRangesNearWorkers(ServerLevel level) {
        Map<ChunkPos, int[]> ranges = new HashMap<>();
        int worldMinY = level.getMinBuildHeight();
        int worldMaxY = level.getMaxBuildHeight();
        int chunkRadius = WORKER_SCAN_RADIUS / 16; // Convert block radius to chunks

        for (WorkerInfo worker : activeWorkers.values()) {
            BlockPos workerPos = worker.position;
            int loY = Math.max(worldMinY, workerPos.getY() - VERTICAL_SCAN_RADIUS);
            int hiY = Math.min(worldMaxY, workerPos.getY() + VERTICAL_SCAN_RADIUS + 1); // exclusive
            int baseChunkX = workerPos.getX() >> 4;
            int baseChunkZ = workerPos.getZ() >> 4;

            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    ChunkPos chunk = new ChunkPos(baseChunkX + dx, baseChunkZ + dz);
                    int[] range = ranges.get(chunk);
                    if (range == null) {
                        ranges.put(chunk, new int[] {loY, hiY});
                    } else {
                        range[0] = Math.min(range[0], loY);
                        range[1] = Math.max(range[1], hiY);
                    }
                }
            }
        }

        return ranges;
    }

    /**
     * Scan a single chunk for job opportunities.
     */
    private int scanChunkForJobs(ServerLevel level, ChunkPos chunkPos, int scanMinY, int scanMaxY) {
        ChunkJobCache previous = chunkCaches.get(chunkPos);
        ChunkJobCache cache = new ChunkJobCache();
        treeBlocksSeenThisScan.clear();
        cache.chunk = chunkPos;
        cache.lastScanTime = level.getGameTime();
        cache.isDirty = false;

        int blocksScanned = 0;

        // Scan every block in the worker-height band (16x16 x-z, every Y layer - no gaps).
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        if (DEBUG) {
            Palworks.LOGGER.info(
                    "Scanning chunk {} - Y band: {} to {}, chunk coords: {} to {}, {} to {}",
                    chunkPos,
                    scanMinY,
                    scanMaxY,
                    chunkPos.getMinBlockX(),
                    chunkPos.getMaxBlockX(),
                    chunkPos.getMinBlockZ(),
                    chunkPos.getMaxBlockZ());
        }

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = scanMinY; y < scanMaxY; y++) {
                    int worldX = chunkPos.getMinBlockX() + x;
                    int worldZ = chunkPos.getMinBlockZ() + z;
                    pos.set(worldX, y, worldZ);
                    blocksScanned++;

                    BlockState state = level.getBlockState(pos);

                    // Remember chests so delivery can pick one without its own block search
                    if (isChest(state)) {
                        cache.chests.add(pos.immutable());
                    }

                    // Check for job opportunities at this position
                    JobRecord job = scanPositionForJob(level, pos, state);
                    if (job != null && !reservedJobs.containsKey(JobKey.of(job))) {
                        if (DEBUG) {
                            Palworks.LOGGER.info(
                                    "Chunk scan: Found {} at {} (scanned pos was {}, job pos is {})",
                                    job.type,
                                    job.position,
                                    new BlockPos(worldX, y, worldZ),
                                    job.position);
                        }
                        availableJobs.put(JobKey.of(job), job);
                        cache.cachedJobs
                                .computeIfAbsent(job.type, k -> new ArrayList<>())
                                .add(job.position);
                    }
                }
            }
        }

        // Jobs the last scan of this chunk found but this one didn't are gone (a player harvested the
        // crop, chopped the tree, planted the farmland, emptied the chest...). Without this they
        // stayed up to 5 minutes and workers kept walking to them.
        if (previous != null) {
            removeJobsNotFoundAgain(previous, cache);
        }

        chunkCaches.put(chunkPos, cache);
        return blocksScanned;
    }

    /** Remove the available jobs an earlier scan found that a newer scan of the same chunk didn't. */
    private void removeJobsNotFoundAgain(ChunkJobCache previous, ChunkJobCache current) {
        previous.cachedJobs.forEach((type, positions) -> {
            List<BlockPos> stillThere = current.cachedJobs.getOrDefault(type, List.of());
            for (BlockPos pos : positions) {
                if (!stillThere.contains(pos)) {
                    removeScannedJob(new JobKey(pos, type));
                }
            }
        });
    }

    /** Remove all of a chunk cache's available jobs (when the cache itself is dropped). */
    private void removeJobsOf(ChunkJobCache cache) {
        cache.cachedJobs.forEach(
                (type, positions) -> positions.forEach(pos -> removeScannedJob(new JobKey(pos, type))));
    }

    /**
     * Remove an available job that came from a chunk scan. Reserved jobs are left to their worker,
     * and follow-up jobs (e.g. picking up drops after a harvest) don't come from the scan, so a
     * rescan not finding them says nothing about them.
     */
    private void removeScannedJob(JobKey key) {
        JobRecord job = availableJobs.get(key);
        if (job != null && !job.isConsecutiveJob && !job.isHandedOver()) {
            availableJobs.remove(key);
        }
    }

    /**
     * Whether a job still exists in the world: the same checks the chunk scan uses to find it.
     * Every step of a job checks it's still there with this, by default (see StepRunner): workers
     * check it on their way to a job, so they drop it instead of finishing the walk when it's gone
     * (crop already harvested, tree chopped, chest emptied...), and as they work, so they stop
     * when it goes, and a step's effect never lands on a job that isn't there. A job changes some
     * things itself as it goes, so a few checks are looser than the scan's: a furnace the worker
     * has lit is still its job (see FurnaceHelper.canUseHeat).
     */
    public static boolean isJobStillThere(Level level, JobType type, BlockPos pos) {
        return switch (type) {
            case FUEL_FURNACE -> FurnaceHelper.canUseHeat(level, pos);
            case HARVEST_CROPS -> CropAdapter.isFullyGrown(level, pos) || CropAdapter.isAttachedFruit(level, pos);
            case PLANT_SEEDS -> level.getBlockState(pos).isAir() && CropAdapter.isPlantableSurface(level, pos.below());
            case CHOP_TREE -> TreeDetector.isTreeBlock(level.getBlockState(pos));
            case GET_SEEDS ->
                isChest(level.getBlockState(pos))
                        && (hasPlantableSeeds(level, pos) || ChestHelper.hasItem(level, pos, Items.NETHER_WART));
            case DELIVER_ITEMS -> level.getBlockEntity(pos) instanceof net.minecraft.world.Container;
            case COLLECT_DROPS -> !itemsToPickUp(level, pos).isEmpty();
            default -> true;
        };
    }

    /**
     * Whether this worker can do this job: it has what the job takes (a seed for the soil, room for
     * the drops...). The worker's side of a job, as {@link #isJobStillThere} is the world's. The one
     * check of it, so the two places it's asked agree: when jobs are handed out (a worker is only
     * offered jobs it can do), and while a worker has one (on its way and as it works, see
     * StepRunner), so a worker that can't do its job any more gives it back for one that can,
     * instead of finishing it for nothing. A job type that needs something of its worker goes here.
     *
     * <p>Suitabilities aren't asked here: a job's (which can depend on its block: nether wart is
     * Nether work) are on its record, checked as it's handed out (see {@link #canDoJob}).
     */
    public static boolean canWorkerDo(Level level, Pokemon pokemon, JobType type, BlockPos pos) {
        WorkerInventory inventory = WorkerInventoryManager.getOrCreate(pokemon);
        return switch (type) {
            // A seed it carries that can be planted there (wart on soul sand, the rest on farmland)
            case PLANT_SEEDS -> carriesSeedFor(level, inventory, pos);
            // Room for some of what's lying there
            case COLLECT_DROPS -> hasRoomForAny(inventory, itemsToPickUp(level, pos));
            // Room for the seeds it fetches
            case GET_SEEDS -> inventory.hasSpace();
            // Something to deliver
            case DELIVER_ITEMS -> inventory.hasCargo(SuitabilityDetector.getSuitabilities(pokemon));
            default -> true;
        };
    }

    private static boolean hasRoomForAny(WorkerInventory inventory, List<ItemEntity> items) {
        for (ItemEntity item : items) {
            if (inventory.roomFor(item.getItem()) > 0) {
                return true;
            }
        }
        return false;
    }

    /** {@link #canWorkerDo} for a worker being offered a job. One that isn't loaded can't do any. */
    private boolean canWorkerDo(WorkerInfo worker, JobRecord job) {
        Pokemon pokemon = getPokemonByUUID(worker.pokemonUUID);
        return pokemon != null && canWorkerDo(level, pokemon, job.type, job.position);
    }

    /**
     * Detects the job opportunity (if any) at a single world position.
     */
    @Nullable
    private JobRecord scanPositionForJob(Level level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();

        // Check for station-based jobs (furnaces, etc.)
        JobType stationJob = StationDetector.getJobType(block);
        if (stationJob != null) {
            // Special handling for furnaces (Kindling jobs)
            if (stationJob == JobType.FUEL_FURNACE) {
                if (FurnaceHelper.needsFuel(level, pos)) {
                    JobRecord job = new JobRecord();
                    job.position = pos.immutable(); // CRITICAL FIX: Create immutable copy
                    job.type = JobType.FUEL_FURNACE;
                    job.requiredSuitabilities = EnumSet.of(Suitability.KINDLING);
                    job.discoveryTime = level.getGameTime();
                    job.priority = 7;
                    return job;
                }
            } else {
                // Other stations use the station state system
                StationState station = StationStateManager.get(level, pos);
                if (station != null && station.isEnabled() && !station.isReserved()) {
                    if (station.hasQueuedJobs() || station.hasCurrentJob()) {
                        JobRecord job = new JobRecord();
                        job.position = pos.immutable(); // CRITICAL FIX: Create immutable copy
                        job.type = stationJob;
                        job.requiredSuitabilities = EnumSet.of(stationJob.getRequiredSuitability());
                        job.discoveryTime = level.getGameTime();
                        job.priority = 6;
                        return job;
                    }
                }
            }
        }

        // Pumpkins and melons: a stem that has grown its fruit is "attached" to it. The harvest job
        // goes on the fruit (the stem itself is never harvested).
        BlockPos fruitPos = CropAdapter.attachedFruitPos(state, pos);
        if (fruitPos != null) {
            JobRecord job = new JobRecord();
            job.position = fruitPos.immutable();
            job.type = JobType.HARVEST_CROPS;
            job.requiredSuitabilities = EnumSet.of(Suitability.GATHERING);
            job.discoveryTime = level.getGameTime();
            job.priority = 8; // Same as ripe crops
            return job;
        }

        // Check for gathering jobs (harvestable crops)
        if (CropAdapter.isFullyGrown(level, pos)) {
            JobRecord job = new JobRecord();
            job.position = pos.immutable(); // CRITICAL FIX: Create immutable copy
            job.type = JobType.HARVEST_CROPS;
            // Nether wart is Nether work (Ghost types), like planting it and fetching it from
            // chests: Gathering alone let any Grass type harvest it
            job.requiredSuitabilities =
                    state.is(Blocks.NETHER_WART) ? EnumSet.of(Suitability.NETHER) : EnumSet.of(Suitability.GATHERING);
            job.discoveryTime = level.getGameTime();
            job.priority = 8; // High priority for ripe crops
            return job;
        }

        // Check for planting jobs (empty farmland)
        if (CropAdapter.isPlantableSurface(level, pos)) {
            // Exclude soul sand from PLANTING - that requires NETHER suitability
            if (state.is(Blocks.SOUL_SAND)) {
                BlockPos abovePos = pos.above();
                if (level.getBlockState(abovePos).isAir()) {
                    if (DEBUG) {
                        Palworks.LOGGER.info(
                                "JobManager: Found NETHER planting job - farmland at {}, planting at {}",
                                pos,
                                abovePos);
                    }
                    JobRecord job = new JobRecord();
                    job.position = abovePos;
                    job.type = JobType.PLANT_SEEDS;
                    job.requiredSuitabilities = EnumSet.of(Suitability.NETHER);
                    job.discoveryTime = level.getGameTime();
                    job.priority = 5;
                    return job;
                }
            } else {
                BlockPos abovePos = pos.above();
                if (level.getBlockState(abovePos).isAir()) {
                    if (DEBUG) {
                        Palworks.LOGGER.info(
                                "JobManager: Found PLANTING job - farmland at {}, planting at {}", pos, abovePos);
                    }
                    JobRecord job = new JobRecord();
                    job.position = abovePos;
                    job.type = JobType.PLANT_SEEDS;
                    job.requiredSuitabilities = EnumSet.of(Suitability.PLANTING);
                    job.discoveryTime = level.getGameTime();
                    job.priority = 5;
                    return job;
                }
            }
        }

        // Check for get seeds jobs (chests with seeds)
        if (isChest(state)) {
            if (hasPlantableSeeds(level, pos)) {
                JobRecord job = new JobRecord();
                job.position = pos.immutable(); // CRITICAL FIX: Create immutable copy
                job.type = JobType.GET_SEEDS;
                job.requiredSuitabilities = EnumSet.of(Suitability.PLANTING);
                job.discoveryTime = level.getGameTime();
                job.priority = 6;
                return job;
            }
            if (ChestHelper.hasItem(level, pos, Items.NETHER_WART)) {
                JobRecord job = new JobRecord();
                job.position = pos.immutable(); // CRITICAL FIX: Create immutable copy
                job.type = JobType.GET_SEEDS;
                job.requiredSuitabilities = EnumSet.of(Suitability.NETHER);
                job.discoveryTime = level.getGameTime();
                job.priority = 6;
                return job;
            }
        }

        // Check for lumbering jobs (trees). One job per tree, at a base block (a log, or a mangrove
        // root, standing on valid ground), and only if it's a valid natural tree. Validating here
        // instead of only at chop time stops workers repeatedly walking to builds they'll refuse to
        // chop. Checking for a base first, and skipping bases of a tree already validated this scan
        // (2x2 trunks, a mangrove's many root columns), keeps it to one tree search per tree.
        // validateTree() still runs again at chop time.
        if (TreeDetector.isTreeBlock(state)
                && TreeDetector.standsOnGround(level, pos, state)
                && !treeBlocksSeenThisScan.contains(pos)) {
            TreeDetector.TreeValidationResult tree = TreeDetector.validateTree(level, pos);
            if (!tree.isValid()) {
                return null;
            }
            treeBlocksSeenThisScan.addAll(tree.getLogPositions());
            JobRecord job = new JobRecord();
            // The same spot for the tree whichever base this scan found it from, so a tree seen
            // from two chunks (or by scans of different heights) is one job, not one per base
            job.position = TreeDetector.chopPosition(level, tree).immutable();
            job.type = JobType.CHOP_TREE;
            job.requiredSuitabilities = EnumSet.of(Suitability.LUMBERING);
            if (TreeDetector.isNetherStem(state)) {
                job.requiredSuitabilities.add(Suitability.NETHER); // nether trees need Lumbering + Nether
            }
            job.discoveryTime = level.getGameTime();
            job.priority = 4;
            return job;
        }

        // Check for mining jobs (ores). Off until mining is implemented (MINE_RESOURCE's handler
        // doesn't break the ore yet), so workers don't walk to ores and stand there forever.
        if (MINING_JOBS_ENABLED && state.is(JobBlockTags.ORES)) {
            JobRecord job = new JobRecord();
            job.position = pos.immutable(); // CRITICAL FIX: Create immutable copy
            job.type = JobType.MINE_RESOURCE;
            job.requiredSuitabilities = EnumSet.of(Suitability.MINING);
            job.discoveryTime = level.getGameTime();
            job.priority = 7; // High priority for ores
            return job;
        }

        return null;
    }

    // Helper methods for job detection
    private static boolean isChest(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST);
    }

    private static boolean hasPlantableSeeds(Level level, BlockPos pos) {
        return !ChestHelper.itemsMatching(level, pos, JobManager::isFarmlandSeed)
                .isEmpty();
    }

    /**
     * A seed a planter (Planting) plants on farmland: any item that plants a field crop (see
     * CropAdapter.fieldCropOf), vanilla or modded, except nether wart, which is Nether work.
     */
    private static boolean isFarmlandSeed(Item item) {
        return !item.equals(Items.NETHER_WART) && CropAdapter.fieldCropOf(item) != null;
    }

    /**
     * The open farmland planting spots within work range of {@code workerPos} (anywhere, if null):
     * where seeds are wanted right now.
     */
    private List<BlockPos> openFarmlandSpots(@Nullable BlockPos workerPos) {
        List<BlockPos> spots = new ArrayList<>();
        for (JobRecord job : availableJobs.values()) {
            if (job.type == JobType.PLANT_SEEDS
                    && job.requiredSuitabilities.contains(Suitability.PLANTING)
                    && (workerPos == null || WorkRange.contains(workerPos, job.position))) {
                spots.add(job.position);
            }
        }
        return spots;
    }

    /** Whether {@code seed} can be planted on one of {@code spots} (planting spots, above farmland). */
    private boolean plantableOnAny(Item seed, List<BlockPos> spots) {
        for (BlockPos spot : spots) {
            if (CropAdapter.canPlantSeed(seed, level, spot.below())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The seed a planter at {@code workerPos} should take from the chest at {@code chestPos}: the
     * first farmland seed in it that one of the open spots in its range can take, or null if none
     * can. Seeds that grow somewhere else (medicinal leek grows in water), or that nothing in range
     * is waiting for, are left in the chest: carried, they'd count as the seeds it has, and it
     * would never go back for ones it can plant.
     */
    @Nullable
    public Item seedToFetch(BlockPos chestPos, @Nullable BlockPos workerPos) {
        return seedToFetch(chestPos, openFarmlandSpots(workerPos));
    }

    @Nullable
    private Item seedToFetch(BlockPos chestPos, List<BlockPos> openSpots) {
        if (openSpots.isEmpty()) {
            return null;
        }
        for (Item seed : ChestHelper.itemsMatching(level, chestPos, JobManager::isFarmlandSeed)) {
            if (plantableOnAny(seed, openSpots)) {
                return seed;
            }
        }
        return null;
    }

    /** Whether the worker carries a seed one of {@code openSpots} can take. */
    private boolean carriesSeedForAny(WorkerInventory inventory, List<BlockPos> openSpots) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && plantableOnAny(stack.getItem(), openSpots)) {
                return true;
            }
        }
        return false;
    }

    // Ores are matched via the #palworks:ores block tag (see JobBlockTags + the datapack
    // tag file); logs via TreeDetector.isLog(). Both are data-driven / single-sourced now.

    // ============================================
    // Job Assignment Methods
    // ============================================

    /**
     * Assign jobs to idle workers.
     */
    private void assignJobsToWorkers(ServerLevel level) {
        List<WorkerInfo> idleWorkers = activeWorkers.values().stream()
                .filter(w -> w.idle && w.currentReservation == null)
                .filter(this::hasScannedAround)
                .limit(ASSIGNMENT_BATCH_SIZE)
                .collect(Collectors.toList());

        if (idleWorkers.isEmpty()) {
            return;
        }

        int assignedCount = 0;
        for (WorkerInfo worker : idleWorkers) {
            Pokemon pokemon = getPokemonByUUID(worker.pokemonUUID);

            // A transporter carrying cargo (anything but a planter's seeds) fills up before it
            // delivers: it takes pickups it has room for, nearest first (a free slot takes anything;
            // once every slot is used, only items matching what it carries), and goes to a chest
            // only when none are left. Its other jobs wait until it's delivered.
            if (pokemon != null
                    && worker.suitabilities.contains(Suitability.TRANSPORTING)
                    && WorkerInventoryManager.getOrCreate(pokemon).hasCargo(worker.suitabilities)) {
                List<JobRecord> pickups = poolCandidates(worker).stream()
                        .filter(job -> job.type == JobType.COLLECT_DROPS)
                        .collect(Collectors.toList());
                if (!pickups.isEmpty()) {
                    // Pickups left that it has room for. If none can be reached right now, they're
                    // remembered as unreachable and drop out of the list, and it delivers once
                    // they're all gone; until then it doesn't go to a chest half full.
                    if (assignFirstReachable(worker, pickups) != null) {
                        assignedCount++;
                    }
                    continue;
                }

                // Nothing left to pick up: deliver. Nearest chests with room first; the first one it
                // can actually reach wins
                if (assignFirstReachable(worker, deliveryCandidates(level, worker, pokemon)) != null) {
                    assignedCount++;
                    continue;
                }
                // No chest it can use: fall through to its other jobs
            }

            // Best pool jobs first; the first one it can actually reach wins
            if (assignFirstReachable(worker, poolCandidates(worker)) != null) {
                assignedCount++;
            }
        }

        if (DEBUG && assignedCount > 0) {
            Palworks.LOGGER.debug(
                    "Assigned {} jobs to idle workers ({} idle workers available)", assignedCount, idleWorkers.size());
        }
    }

    /**
     * Whether every chunk in a worker's range has been scanned for jobs. The scan gets through a
     * couple of chunks a tick, so a worker just sent out (or arrived somewhere new) waits the few
     * ticks until it's done: assigning it straight away handed it the best of the jobs found so
     * far, which could be the far side of its range with nearer ones not found yet.
     */
    private boolean hasScannedAround(WorkerInfo worker) {
        int chunkRadius = WORKER_SCAN_RADIUS / 16; // same chunks as getChunkScanRangesNearWorkers
        int baseChunkX = worker.position.getX() >> 4;
        int baseChunkZ = worker.position.getZ() >> 4;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                ChunkJobCache cache = chunkCaches.get(new ChunkPos(baseChunkX + dx, baseChunkZ + dz));
                if (cache == null || cache.isDirty) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Have the chunks in a worker's range round {@code center} scanned again as soon as possible. */
    private void rescanAround(BlockPos center) {
        int chunkRadius = WORKER_SCAN_RADIUS / 16;
        ChunkPos chunk = new ChunkPos(center);
        markForRescan(chunk.x - chunkRadius, chunk.z - chunkRadius, chunk.x + chunkRadius, chunk.z + chunkRadius);
    }

    /**
     * Have the chunks covering a box be scanned again as soon as possible, after lots of blocks in
     * it changed at once (e.g. /palworks testarea). Until they have, no worker near them is given a
     * job, so none takes a far one before the nearer ones are found.
     */
    public void rescanArea(BlockPos min, BlockPos max) {
        markForRescan(min.getX() >> 4, min.getZ() >> 4, max.getX() >> 4, max.getZ() >> 4);
    }

    private void markForRescan(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                ChunkJobCache cache = chunkCaches.get(new ChunkPos(x, z));
                if (cache != null) {
                    cache.isDirty = true;
                }
            }
        }
    }

    /**
     * DELIVER_ITEMS candidates for a worker with items to store: the chests (that the chunk scan
     * already found) in range with room for them, nearest first. Assignment then picks the first
     * one the worker can reach, so an unreachable nearest chest no longer traps it in a loop.
     */
    private List<JobRecord> deliveryCandidates(ServerLevel level, WorkerInfo worker, Pokemon pokemon) {
        BlockPos workerPos = worker.position;

        WorkerInventory inventory = WorkerInventoryManager.getOrCreate(pokemon);
        net.minecraft.world.item.ItemStack firstItem = inventory.firstCargo(worker.suitabilities);

        // From the chests the chunk scan already found (this used to scan ~38k blocks itself)
        List<BlockPos> chests = new ArrayList<>();
        int chunkRadius = (DELIVERY_SEARCH_RADIUS >> 4) + 1;
        int baseChunkX = workerPos.getX() >> 4;
        int baseChunkZ = workerPos.getZ() >> 4;

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                ChunkJobCache cache = chunkCaches.get(new ChunkPos(baseChunkX + dx, baseChunkZ + dz));
                if (cache == null) {
                    continue;
                }
                for (BlockPos chestPos : cache.chests) {
                    if (Math.abs(chestPos.getX() - workerPos.getX()) > DELIVERY_SEARCH_RADIUS
                            || Math.abs(chestPos.getZ() - workerPos.getZ()) > DELIVERY_SEARCH_RADIUS
                            || Math.abs(chestPos.getY() - workerPos.getY()) > DELIVERY_SEARCH_VERTICAL) {
                        continue;
                    }
                    if (!nearEnoughToOwner(worker, chestPos)) {
                        continue;
                    }
                    // The cached position may be up to one scan old, so re-check it's still a chest
                    if (isChest(level.getBlockState(chestPos))
                            && ChestHelper.canAcceptItems(level, chestPos, firstItem)) {
                        chests.add(chestPos);
                    }
                }
            }
        }
        chests.sort(Comparator.comparingDouble(workerPos::distSqr));

        List<JobRecord> candidates = new ArrayList<>(chests.size());
        for (BlockPos chestPos : chests) {
            JobRecord job = new JobRecord();
            job.position = chestPos;
            job.type = JobType.DELIVER_ITEMS;
            job.requiredSuitabilities = EnumSet.of(Suitability.TRANSPORTING);
            job.discoveryTime = level.getGameTime();
            job.priority = 10; // Highest priority - must deliver before doing anything else
            candidates.add(job);
        }

        if (DEBUG && candidates.isEmpty()) {
            Palworks.LOGGER.warn(
                    "No chest found for {} to deliver {} items!",
                    pokemon.getSpecies().getName(),
                    WorkerInventoryManager.getTotalItemCount(pokemon));
        }
        return candidates;
    }

    /**
     * Pool jobs this worker could take, best first (scored by distance and priority). Only the
     * job-specific filters live here; the checks every job gets (suitability, unreachable memory,
     * stand position, path) are in {@link #assignFirstReachable}.
     */
    private List<JobRecord> poolCandidates(WorkerInfo worker) {
        Pokemon pokemon = getPokemonByUUID(worker.pokemonUUID);
        WorkerInventory inventory = pokemon != null ? WorkerInventoryManager.getOrCreate(pokemon) : null;
        boolean carriesSeeds = inventory != null && inventory.hasAnySeed();

        // Open farmland in its range, worked out only if there's a seed chest to consider
        List<BlockPos> openFarmland = null;

        // Collect viable candidates and score them cheaply (NO pathfinding in this pass).
        List<JobRecord> candidates = new ArrayList<>();
        for (JobRecord job : availableJobs.values()) {
            // Cheap pre-filter so the list (and the sort) stays small; checked again on assignment
            if (!canDoJob(worker, job)
                    || !inWorkRange(worker, job.position)
                    || isRecentlyUnreachable(worker, job.position)
                    || !canWorkerDo(worker, job)) {
                continue;
            }

            // Planters fetch seeds only once they've run out (then plant until the stack is gone);
            // room to carry them is canWorkerDo's. For farmland, "run out" means of seeds the
            // open farmland in range can take, and it only goes for a chest with some of those:
            // seeds that grow elsewhere (medicinal leek, cocoa beans) don't count either way.
            if (job.type == JobType.GET_SEEDS && inventory != null) {
                if (job.requiredSuitabilities.contains(Suitability.PLANTING)) {
                    if (openFarmland == null) {
                        openFarmland = openFarmlandSpots(worker.position);
                    }
                    if (carriesSeedForAny(inventory, openFarmland) || seedToFetch(job.position, openFarmland) == null) {
                        continue;
                    }
                } else if (carriesSeeds) {
                    continue;
                }
            }

            candidates.add(job);
        }

        // Harvest everything first, then plant: while this worker has any harvest it could take,
        // planting jobs wait. Unreachable harvests drop out of candidates (recentlyFailedJobs), so
        // they can't hold planting back.
        if (candidates.stream().anyMatch(j -> j.type == JobType.HARVEST_CROPS)) {
            candidates.removeIf(j -> j.type == JobType.PLANT_SEEDS);
        }

        // Sort by score (lower is better) so only the most promising jobs get path-checked.
        candidates.sort(Comparator.comparingDouble(j -> scoreJob(worker, j)));
        return candidates;
    }

    // ============================================
    // Assignment pipeline: every job reaches a worker through here
    // ============================================

    /**
     * Assign the worker the first job in {@code candidates} (best first) that it can actually do
     * and reach. This is the only way a job is handed to a worker: pool jobs, deliveries and
     * follow-up jobs all come through here, so they all get the same checks, in this order:
     * <ol>
     *   <li>the worker has every suitability the job needs;</li>
     *   <li>it hasn't recently failed to reach that spot (see recentlyFailedJobs);</li>
     *   <li>there's somewhere to stand to do it (WorkPositions);</li>
     *   <li>there's a real path to that spot.</li>
     * </ol>
     * Candidates that fail 3 or 4 are remembered as unreachable, so the next call moves on to
     * others. Path checks are expensive, so at most MAX_PATH_CHECKS_PER_WORKER run per call.
     *
     * @return the job assigned, or null if none of the candidates worked out
     */
    @Nullable
    private JobRecord assignFirstReachable(WorkerInfo worker, List<JobRecord> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        PokemonEntity entity = getEntityByUUID(worker.pokemonUUID);
        if (entity == null) {
            return null; // can't check reachability without the entity; try again next tick
        }
        refreshUnreachableMemory(worker);
        long now = level.getGameTime();

        int pathChecks = 0;
        for (JobRecord job : candidates) {
            // Only jobs within its work range: a far one (found round some other worker, or left
            // over from one) would send it off across the world, and checking a path to it loads
            // chunks out there. A party Pokémon's must be near its owner too.
            if (!canDoJob(worker, job)
                    || !inWorkRange(worker, job.position)
                    || isRecentlyUnreachable(worker, job.position)
                    || !canWorkerDo(worker, job)) {
                continue;
            }
            BlockPos standPos = WorkPositions.standingPosFor(level, job.position, job.type, entity);
            WorkerPathfinder.Result path =
                    standPos == null ? new WorkerPathfinder.Result(Reach.NO, null) : canPathTo(entity, standPos);
            Reach reach = path.reach();
            if (reach == Reach.UNKNOWN) {
                // The worker can't pathfind at all right now (e.g. it was just sent out and is still
                // dropping to the ground, or is mid-step): that says nothing about the jobs, so
                // don't remember any as unreachable. Try again next tick.
                return null;
            }
            if (reach == Reach.YES) {
                // Take it out of the pool first so no other worker gets it too (a delivery isn't
                // in the pool, so this does nothing for one)
                availableJobs.remove(JobKey.of(job), job);
                assignJobToWorker(level, worker, job);
                if (path.path() != null) {
                    WorkerNavigator.offer(entity, standPos, path.path()); // its walk starts on it
                }
                return job;
            }
            // Remember it, so next time this worker checks other candidates instead of
            // pathfinding to the same unreachable ones again.
            rememberUnreachable(worker.pokemonUUID, worker.position, job.position, now);
            pathChecks++;
            if (pathChecks >= MAX_PATH_CHECKS_PER_WORKER) {
                break;
            }
        }
        return null;
    }

    /** The spots this worker currently remembers it couldn't reach (for the debug overlay). */
    public List<BlockPos> unreachableFor(UUID workerUUID, long now) {
        Map<BlockPos, Long> failed = recentlyFailedJobs.get(workerUUID);
        if (failed == null) {
            return List.of();
        }
        List<BlockPos> spots = new ArrayList<>();
        failed.forEach((pos, expiry) -> {
            if (expiry > now) {
                spots.add(pos);
            }
        });
        return spots;
    }

    /** Whether the worker recently failed to reach this spot (and nothing has changed since). */
    private boolean isRecentlyUnreachable(WorkerInfo worker, BlockPos pos) {
        Map<BlockPos, Long> failed = recentlyFailedJobs.get(worker.pokemonUUID);
        if (failed == null) {
            return false;
        }
        Long expiry = failed.get(pos);
        return expiry != null && expiry > level.getGameTime();
    }

    /** Reachability was judged from where the worker stood; once it has moved away, judge again. */
    private void refreshUnreachableMemory(WorkerInfo worker) {
        BlockPos anchor = failureAnchors.get(worker.pokemonUUID);
        if (anchor != null
                && anchor.distSqr(worker.position) > UNREACHABLE_FORGET_MOVE_DIST * UNREACHABLE_FORGET_MOVE_DIST) {
            forgetUnreachable(worker.pokemonUUID);
        }
    }

    /** Whether the worker carries a seed that can be planted at {@code plantPos}. */
    private static boolean carriesSeedFor(Level level, WorkerInventory inventory, BlockPos plantPos) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && CropAdapter.canPlantSeed(stack.getItem(), level, plantPos.below())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code pos} is somewhere this worker may be sent: within its own work range and,
     * for one from a party, within work range of its owner too (see {@link #nearEnoughToOwner}).
     */
    private static boolean inWorkRange(WorkerInfo worker, BlockPos pos) {
        return WorkRange.contains(worker.position, pos) && nearEnoughToOwner(worker, pos);
    }

    /**
     * A party Pokémon only works near its owner ({@link WorkRange#OWNER_RANGE}), so it doesn't head
     * off to (or path-check) jobs far from them, which Cobblemon would teleport it back from part
     * way; with its owner not in this level, nowhere is. Pasture Pokémon aren't limited.
     */
    private static boolean nearEnoughToOwner(WorkerInfo worker, BlockPos pos) {
        return !worker.fromParty || WorkRange.nearOwner(worker.ownerPosition, pos);
    }

    /**
     * Whether a worker has every suitability a job needs (e.g. nether trees need Nether as well as
     * Lumbering).
     */
    private static boolean canDoJob(WorkerInfo worker, JobRecord job) {
        return worker.suitabilities.containsAll(job.requiredSuitabilities);
    }

    /**
     * Assignment score for a (worker, job) pair. Lower is better: nearer + higher priority,
     * with a large discount for consecutive jobs hinted to this worker.
     */
    private double scoreJob(WorkerInfo worker, JobRecord job) {
        double distance = worker.position.distSqr(job.position);
        double priorityPenalty = (10 - job.priority) * 100; // higher priority = lower penalty
        double score = distance + priorityPenalty;
        if (job.isConsecutiveJob && job.reservedByWorker != null && job.reservedByWorker.equals(worker.pokemonUUID)) {
            score *= 0.1; // 90% discount for consecutive jobs
        }
        return score;
    }

    /**
     * Record that a worker has a job (its reservation). Low-level: only
     * {@link #assignFirstReachable} calls this, after the checks every job gets. Don't call it
     * directly, or the job skips those checks.
     */
    private void assignJobToWorker(ServerLevel level, WorkerInfo worker, JobRecord job) {
        Pokemon pokemon = getPokemonByUUID(worker.pokemonUUID);
        if (pokemon == null) {
            // Pokemon entity not found - put job back in available pool
            if (DEBUG) {
                Palworks.LOGGER.warn(
                        "Failed to assign {} job at {} - Pokemon entity not found for UUID {}",
                        job.type,
                        job.position,
                        worker.pokemonUUID);
            }
            availableJobs.put(JobKey.of(job), job);
            return;
        }

        ReservationId reservation = new ReservationId(job.position, job.type, level.getGameTime());

        // WorkerState (NBT) is the single source of truth for the reservation.
        WorkerState state = WorkerStateManager.getOrCreate(pokemon);
        state.setCurrentReservation(reservation);
        state.setAssignedJob(job.type);
        // Start from the job's first step, or from where the previous worker left it (handed over)
        state.startJob(job.resumeStep, job.resumeData);
        WorkerStateManager.save(pokemon, state);

        // Mirror into the in-memory caches; registerWorker re-derives these from
        // WorkerState each tick, so they stay reconciled with the source of truth.
        worker.currentReservation = reservation;
        worker.idle = false;
        worker.lastAssignmentTime = System.currentTimeMillis();
        job.reservedByWorker = worker.pokemonUUID;
        reservedJobs.put(JobKey.of(job), job);

        if (DEBUG) {
            String dist = String.format("%.1f", Math.sqrt(worker.position.distSqr(job.position)));
            Palworks.LOGGER.debug(
                    "Assigned {} job at {} to worker {} (distance: {})",
                    job.type,
                    job.position,
                    pokemon.getSpecies().getName(),
                    dist);

            // Send chat message to nearby players
            sendDebugMessage(
                    level,
                    String.format(
                            "§a%s§f assigned §e%s§f at [%d, %d, %d] (dist: %s)",
                            pokemon.getSpecies().getName(),
                            job.type.name(),
                            job.position.getX(),
                            job.position.getY(),
                            job.position.getZ(),
                            dist));
        }
    }

    /**
     * Get a Pokemon entity by UUID.
     */
    @Nullable
    private Pokemon getPokemonByUUID(UUID uuid) {
        PokemonEntity entity = getEntityByUUID(uuid);
        return entity != null ? entity.getPokemon() : null;
    }

    /**
     * Get a registered worker's PokemonEntity (Mob) by Pokemon UUID for navigation checks.
     * Only workers are looked up, and registerWorker() keeps entityCache current for them.
     */
    @Nullable
    private PokemonEntity getEntityByUUID(UUID uuid) {
        PokemonEntity cached = entityCache.get(uuid);
        return cached != null && cached.isAlive() ? cached : null;
    }

    /**
     * Whether an entity can walk to a spot, and the path there if it can. {@link Reach#UNKNOWN}
     * when no path can be planned at the moment: it's in the air (just sent out and still dropping,
     * mid-jump, ...). That isn't "unreachable"; treating it as such made workers skip the jobs right
     * next to them for up to 30 seconds.
     *
     * <p>Walking workers are planned by {@link WorkerPathfinder}, which checks their whole body all
     * the way there. No "close enough" shortcut: a job can be one block away yet blocked by a fence
     * or ledge (common on farms). A flying or swimming one is left to its own navigation, which must
     * reach the tile (standPos is a standable tile, not the worked block, so canReach() is right).
     */
    private WorkerPathfinder.Result canPathTo(Mob entity, BlockPos standPos) {
        if (entity == null) {
            return new WorkerPathfinder.Result(Reach.UNKNOWN, null);
        }
        if (WorkerPathfinder.handles(entity)) {
            return WorkerPathfinder.find(entity, standPos);
        }
        net.minecraft.world.level.pathfinder.Path path = entity.getNavigation().createPath(standPos, 1);
        if (path == null) {
            return new WorkerPathfinder.Result(Reach.UNKNOWN, null);
        }
        return new WorkerPathfinder.Result(path.canReach() ? Reach.YES : Reach.NO, null);
    }

    // ============================================
    // Consecutive Job System
    // ============================================

    /**
     * Called when a worker completes a job.
     * Creates consecutive jobs for harvest chains.
     */
    public void onJobCompleted(Pokemon pokemon, ReservationId completedReservation, Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        BlockPos jobPos = completedReservation.getStationPos();
        UUID workerUUID = pokemon.getUuid();

        if (DEBUG) {
            Palworks.LOGGER.info(
                    "{} completed {} job at {}",
                    pokemon.getSpecies().getName(),
                    completedReservation.getJobType(),
                    jobPos);

            // Send chat message to nearby players
            sendDebugMessage(
                    serverLevel,
                    String.format(
                            "§a%s§f completed §e%s§f at [%d, %d, %d]",
                            pokemon.getSpecies().getName(),
                            completedReservation.getJobType().name(),
                            jobPos.getX(),
                            jobPos.getY(),
                            jobPos.getZ()));
        }

        // Handle consecutive jobs for harvesting
        if (completedReservation.getJobType() == JobType.HARVEST_CROPS) {
            long currentTime = level.getGameTime();

            // No replant job: harvesting resets the crop in place (paid for with one of its seeds),
            // and berries regrow on their own. See CropAdapter.harvestCrop.
            if (DEBUG) {
                Palworks.LOGGER.info("Queuing pickup job for harvest at {}", jobPos);
                sendDebugMessage(serverLevel, "  §7→ Queued §eCOLLECT_DROPS§7");
            }

            // Queue 1: Collect dropped items (delay 2 ticks for items to spawn)
            queuePickup(jobPos, workerUUID, currentTime + CONSECUTIVE_JOB_PICKUP_DELAY);
        }

        // A pickup that left items behind (they didn't fit): they get a job of their own again, for
        // any transporter with room for them
        if (completedReservation.getJobType() == JobType.COLLECT_DROPS) {
            queuePickup(jobPos, null, level.getGameTime());
        }

        // IMPORTANT: Remove completed job entirely (don't put back in available pool!)
        JobKey completedKey = JobKey.of(completedReservation);
        reservedJobs.remove(completedKey);
        availableJobs.remove(completedKey); // Also remove from available in case it's there

        // Mark worker as idle so they can receive new assignments
        WorkerInfo workerInfo = activeWorkers.get(workerUUID);
        if (workerInfo != null) {
            workerInfo.idle = true;
            workerInfo.currentReservation = null;
        }
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
        WorkerInfo info = activeWorkers.get(workerUUID);

        rememberUnreachable(workerUUID, info != null ? info.position : null, pos, serverLevel.getGameTime());

        // If it had already done part of the job, hand that progress over with it
        WorkerState state = WorkerStateManager.get(pokemon);
        if (state != null) {
            captureProgress(state, JobKey.of(reservation));
        }

        if (DEBUG) {
            Palworks.LOGGER.debug(
                    "{} abandoned {} at {}; won't retry it until terrain changes or it moves (max {} ticks)",
                    pokemon.getSpecies().getName(),
                    reservation.getJobType(),
                    pos,
                    JOB_FAILURE_BLACKLIST_TICKS);
        }
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
        reservedJobs.remove(JobKey.of(reservation));
        availableJobs.remove(JobKey.of(reservation));

        WorkerInfo workerInfo = activeWorkers.get(pokemon.getUuid());
        if (workerInfo != null) {
            workerInfo.idle = true;
            workerInfo.currentReservation = null;
        }

        if (DEBUG) {
            Palworks.LOGGER.info(
                    "{}'s {} at {} no longer exists - removed from pool",
                    pokemon.getSpecies().getName(),
                    reservation.getJobType(),
                    pos);
        }
    }

    /**
     * Called when a worker gives its job back because it can't do it any more (see
     * {@link #canWorkerDo}: its seeds were taken, its inventory filled up). The job goes back in the
     * pool, with any progress (see {@link #captureProgress}), for a worker that can do it: this one
     * isn't offered it again until it can, since jobs are handed out by the same check. Not
     * remembered as unreachable: nothing's wrong with the spot.
     */
    public void onJobGivenBack(Pokemon pokemon, ReservationId reservation) {
        JobKey key = JobKey.of(reservation);
        WorkerState state = WorkerStateManager.get(pokemon);
        if (state != null) {
            captureProgress(state, key);
        }
        releaseJobReservation(key);

        WorkerInfo workerInfo = activeWorkers.get(pokemon.getUuid());
        if (workerInfo != null) {
            workerInfo.idle = true;
            workerInfo.currentReservation = null;
        }

        if (DEBUG) {
            Palworks.LOGGER.info(
                    "{} can't do its {} at {} any more - given back to the pool",
                    pokemon.getSpecies().getName(),
                    reservation.getJobType(),
                    reservation.getStationPos());
        }
    }

    /**
     * Process consecutive job queue (delayed job creation).
     */
    private void processConsecutiveJobs(ServerLevel level) {
        long currentTime = level.getGameTime();

        if (DEBUG && !consecutiveJobQueue.isEmpty()) {
            Palworks.LOGGER.info(
                    "Processing consecutive job queue ({} jobs queued, current time: {})",
                    consecutiveJobQueue.size(),
                    currentTime);
        }

        // Process all ready requests
        while (!consecutiveJobQueue.isEmpty()) {
            ConsecutiveJobRequest request = consecutiveJobQueue.peek();

            // Check if ready to process (delay elapsed)
            if (currentTime < request.creationTime) {
                if (DEBUG) {
                    long ticksRemaining = request.creationTime - currentTime;
                    Palworks.LOGGER.info(
                            "Next job ({}) not ready yet - {} ticks remaining", request.nextJobType, ticksRemaining);
                }
                break; // Not ready yet, and queue is ordered
            }

            consecutiveJobQueue.poll();

            if (DEBUG) {
                Palworks.LOGGER.info(
                        "Creating consecutive job: {} at {}", request.nextJobType, request.sourceJobLocation);
            }

            if (request.nextJobType == JobType.COLLECT_DROPS) {
                // Create collect drops job for items at harvest location
                createCollectDropsJob(level, request);
            }
        }
    }

    /**
     * Create a collect drops job for items at a location.
     */
    private void createCollectDropsJob(ServerLevel level, ConsecutiveJobRequest request) {
        // Scan for items at harvest location
        List<ItemEntity> items = itemsToPickUp(level, request.sourceJobLocation);

        if (items.isEmpty()) {
            if (DEBUG) {
                Palworks.LOGGER.debug("No items found at {} for collect drops job", request.sourceJobLocation);
            }
            return;
        }

        // Create collect drops job
        JobRecord collectJob = new JobRecord();
        collectJob.position = request.sourceJobLocation;
        collectJob.type = JobType.COLLECT_DROPS;
        collectJob.requiredSuitabilities = EnumSet.of(Suitability.TRANSPORTING);
        collectJob.discoveryTime = level.getGameTime();
        collectJob.isConsecutiveJob = true;
        collectJob.priority = HIGH_PRIORITY;
        collectJob.reservedByWorker = request.suggestedWorkerUUID; // Hint for assignment

        // Offer it to the suggested worker first (same checks as any job: suitability, reachable);
        // otherwise it goes to the pool for anyone
        WorkerInfo suggestedWorker =
                request.suggestedWorkerUUID == null ? null : activeWorkers.get(request.suggestedWorkerUUID);
        if (suggestedWorker != null
                && suggestedWorker.idle
                && assignFirstReachable(suggestedWorker, List.of(collectJob)) != null) {
            if (DEBUG) {
                Palworks.LOGGER.debug(
                        "Assigned consecutive collect drops job to original harvester at {}",
                        request.sourceJobLocation);
            }
        } else {
            // Otherwise add to available jobs
            availableJobs.put(JobKey.of(collectJob), collectJob);

            if (DEBUG) {
                Palworks.LOGGER.debug(
                        "Added consecutive collect drops job to available pool at {} ({} items)",
                        request.sourceJobLocation,
                        items.size());
            }
        }
    }

    /**
     * Called when a worker completes chopping a tree (which it also replants, as part of the chop).
     * Queues a job to collect the drops.
     */
    public void onTreeChopped(Pokemon pokemon, BlockPos basePos, Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        UUID workerUUID = pokemon.getUuid();
        long currentTime = level.getGameTime();

        if (DEBUG) {
            Palworks.LOGGER.info(
                    "{} chopped tree at base {}", pokemon.getSpecies().getName(), basePos);

            sendDebugMessage(
                    serverLevel,
                    String.format(
                            "§a%s§f chopped tree, queuing §eCOLLECT_DROPS§f",
                            pokemon.getSpecies().getName()));
        }

        // Queue 1: Collect dropped items (delay 2 ticks for items to spawn)
        queuePickup(basePos, workerUUID, currentTime + CONSECUTIVE_JOB_PICKUP_DELAY);
    }

    /**
     * Queue a job to pick up the items lying round {@code pos} (within {@link #PICKUP_RADIUS}),
     * created at {@code gameTime} if there are any then. {@code suggestedWorker}, if given, is
     * offered it first (still only if it's a transporter with room for them).
     */
    public void queuePickup(BlockPos pos, @Nullable UUID suggestedWorker, long gameTime) {
        ConsecutiveJobRequest pickupRequest = new ConsecutiveJobRequest();
        pickupRequest.sourceJobLocation = pos;
        pickupRequest.nextJobType = JobType.COLLECT_DROPS;
        pickupRequest.requiredSuitability = Suitability.TRANSPORTING;
        pickupRequest.suggestedWorkerUUID = suggestedWorker;
        pickupRequest.creationTime = gameTime;
        consecutiveJobQueue.add(pickupRequest);
    }

    /** The items lying round a pickup job's spot. */
    public static List<ItemEntity> itemsToPickUp(Level level, BlockPos pos) {
        return level.getEntitiesOfClass(
                ItemEntity.class,
                new AABB(pos).inflate(PICKUP_RADIUS),
                item -> item.isAlive() && !item.getItem().isEmpty());
    }

    // ============================================
    // Cache Management
    // ============================================

    /**
     * Called when a block changes to invalidate cache.
     */
    public void onBlockChanged(Level level, BlockPos pos) {
        // Invalidate chunk cache when blocks change
        ChunkPos chunk = new ChunkPos(pos);
        ChunkJobCache cache = chunkCaches.get(chunk);
        if (cache != null) {
            cache.isDirty = true;

            if (DEBUG) {
                Palworks.LOGGER.debug("Invalidated chunk cache at {} due to block change at {}", chunk, pos);
            }
        }

        // Remove any jobs at this position
        availableJobs.keySet().removeIf(key -> key.pos().equals(pos));
        reservedJobs.keySet().removeIf(key -> key.pos().equals(pos));
    }

    /**
     * Release a job reservation (make it available again). A delivery is dropped instead: it's
     * only meaningful to the worker carrying the items, and back in the pool (at the top priority)
     * it would send other transporters to deliver nothing.
     */
    private void releaseJobReservation(JobKey key) {
        JobRecord job = reservedJobs.remove(key);
        if (job == null || job.type == JobType.DELIVER_ITEMS) {
            return;
        }
        job.reservedByWorker = null;
        if (job.isHandedOver()) {
            job.discoveryTime = level.getGameTime(); // don't let the pool's expiry drop it straight away
        }
        availableJobs.put(key, job);
    }

    /**
     * If the worker has done part of its job (past its first step), store that progress on the job,
     * so whoever takes it next carries on from there instead of starting over. Call before the
     * worker's reservation (and with it, its progress) is cleared.
     */
    private void captureProgress(WorkerState state, JobKey key) {
        JobRecord job = reservedJobs.get(key);
        if (job != null && state.hasJobProgress()) {
            job.resumeStep = state.getJobStep();
            job.resumeData = state.getJobData().copy();
        }
    }

    /**
     * Clean up expired data (old reservations, stale caches).
     */
    private void cleanupExpiredData(ServerLevel level) {
        long currentTime = level.getGameTime();

        // Follow-up jobs (e.g. picking up a harvest's drops) don't come from the chunk scan, so a
        // rescan never removes them: drop the ones that are gone (the items were picked up or
        // despawned), or transporters keep being sent to them. Handed-over jobs are left alone:
        // their original target (a chopped tree) being gone is expected.
        if (currentTime % FOLLOW_UP_RECHECK_INTERVAL == 0) {
            availableJobs.entrySet().removeIf(entry -> {
                JobRecord job = entry.getValue();
                return job.isConsecutiveJob && !job.isHandedOver() && !isJobStillThere(level, job.type, job.position);
            });
        }

        // Remove stale chunk caches (older than cache lifetime), and their jobs: no worker is near
        // enough to rescan them, so nothing would ever notice those jobs disappearing.
        chunkCaches.entrySet().removeIf(entry -> {
            ChunkJobCache cache = entry.getValue();
            boolean stale = !cache.isDirty && (currentTime - cache.lastScanTime) > JOB_CACHE_LIFETIME_TICKS * 2;
            if (stale) {
                removeJobsOf(cache);
            }
            return stale;
        });

        // Remove jobs that have been available too long (game time, so a paused game doesn't
        // count: that used to drop every job, handed-over ones included, after a 5-minute pause)
        availableJobs
                .entrySet()
                .removeIf(entry -> (currentTime - entry.getValue().discoveryTime) > JOB_POOL_EXPIRY_TICKS);

        // Expire per-worker job-failure blacklist entries (stored as game ticks).
        recentlyFailedJobs.values().forEach(failed -> failed.entrySet().removeIf(e -> e.getValue() <= currentTime));
        recentlyFailedJobs.entrySet().removeIf(e -> e.getValue().isEmpty());
        failureAnchors.keySet().retainAll(recentlyFailedJobs.keySet());

        // Release orphaned reservations (jobs reserved by workers that no longer exist)
        List<JobKey> orphanedJobs = new ArrayList<>();
        for (Map.Entry<JobKey, JobRecord> entry : reservedJobs.entrySet()) {
            JobRecord job = entry.getValue();
            if (job.reservedByWorker != null && !activeWorkers.containsKey(job.reservedByWorker)) {
                orphanedJobs.add(entry.getKey());
                if (DEBUG) {
                    Palworks.LOGGER.debug(
                            "Found orphaned job {} at {} - worker {} no longer exists",
                            job.type,
                            job.position,
                            job.reservedByWorker);
                }
            }
        }

        for (JobKey key : orphanedJobs) {
            releaseJobReservation(key);
            if (DEBUG) {
                Palworks.LOGGER.debug("Released orphaned {} job reservation at {}", key.type(), key.pos());
            }
        }
    }

    // ============================================
    // Debug Helpers
    // ============================================

    /**
     * Send a debug message to all players who have debug enabled.
     */
    private void sendDebugMessage(ServerLevel level, String message) {
        // Goes only to players who opted in via /palworks debug. These call sites are still gated
        // by DEBUG, so these verbose assignment-detail lines only appear in a debug build.
        DebugManager.broadcastActivity(level, message);
    }

    // ============================================
    // Debug Information
    // ============================================

    /**
     * Get debug information about current job state.
     * Returns a list of formatted strings for display.
     */
    public List<String> getJobsDebugInfo(ServerLevel level) {
        List<String> lines = new ArrayList<>();

        // Header
        lines.add("§6=== Palworks Job Debug ===");

        // Available jobs summary
        lines.add("");
        lines.add("§e[Available Jobs: " + availableJobs.size() + "]");
        if (availableJobs.isEmpty()) {
            lines.add("  §7(none)");
        } else {
            // Group by type
            Map<JobType, List<JobRecord>> byType =
                    availableJobs.values().stream().collect(Collectors.groupingBy(j -> j.type));
            for (Map.Entry<JobType, List<JobRecord>> entry : byType.entrySet()) {
                JobType type = entry.getKey();
                List<JobRecord> jobs = entry.getValue();
                lines.add(String.format(
                        "  §a%s§f: %d jobs (need §b%s§f)",
                        type.name(), jobs.size(), jobs.get(0).requiredSuitabilities));
                int shown = 0;
                for (JobRecord job : jobs) {
                    if (shown >= JOB_POSITIONS_SHOWN) {
                        lines.add(String.format("    §7... and %d more", jobs.size() - JOB_POSITIONS_SHOWN));
                        break;
                    }
                    lines.add(String.format(
                            "    §7[%d, %d, %d] pri=%d",
                            job.position.getX(), job.position.getY(), job.position.getZ(), job.priority));
                    shown++;
                }
            }
        }

        // Reserved jobs
        lines.add("");
        lines.add("§e[Reserved Jobs: " + reservedJobs.size() + "]");
        if (reservedJobs.isEmpty()) {
            lines.add("  §7(none)");
        } else {
            for (JobRecord job : reservedJobs.values()) {
                String workerName = "unknown";
                if (job.reservedByWorker != null) {
                    Pokemon pokemon = getPokemonByUUID(job.reservedByWorker);
                    if (pokemon != null) {
                        workerName = pokemon.getSpecies().getName();
                    }
                }
                lines.add(String.format(
                        "  §a%s§f at [%d, %d, %d] -> §b%s",
                        job.type.name(), job.position.getX(), job.position.getY(), job.position.getZ(), workerName));
            }
        }

        // Active workers
        lines.add("");
        lines.add("§e[Active Workers: " + activeWorkers.size() + "]");
        if (activeWorkers.isEmpty()) {
            lines.add("  §7(none)");
        } else {
            for (WorkerInfo worker : activeWorkers.values()) {
                Pokemon pokemon = getPokemonByUUID(worker.pokemonUUID);
                String name = pokemon != null ? pokemon.getSpecies().getName() : "unknown";
                String status;
                if (worker.idle) {
                    status = "§7IDLE";
                } else if (worker.currentReservation != null) {
                    status = "§a" + worker.currentReservation.getJobType().name();
                } else {
                    status = "§cNO_RESERVATION";
                }

                // Get suitabilities as short string
                String suits = worker.suitabilities.stream()
                        .map(s -> s.name().substring(0, Math.min(3, s.name().length())))
                        .collect(Collectors.joining(","));

                lines.add(String.format("  §b%s§f: %s §7[%s]", name, status, suits));
            }
        }

        // Check for workers that should be able to take available jobs but aren't
        lines.add("");
        lines.add("§e[Assignment Analysis]");
        for (WorkerInfo worker : activeWorkers.values()) {
            if (!worker.idle || worker.currentReservation != null) continue;

            Pokemon pokemon = getPokemonByUUID(worker.pokemonUUID);
            String name = pokemon != null ? pokemon.getSpecies().getName() : "unknown";
            Mob entity = pokemon != null ? (Mob) pokemon.getEntity() : null;

            // Count jobs by category for this worker
            int noSuitability = 0;
            int noPath = 0;
            int canAssign = 0;
            Map<JobType, Integer> noPathByType = new EnumMap<>(JobType.class);

            for (JobRecord job : availableJobs.values()) {
                // Check suitability
                if (!canDoJob(worker, job)) {
                    noSuitability++;
                    continue;
                }

                // Check pathfinding to the resolved stand position
                BlockPos standPos =
                        entity != null ? WorkPositions.standingPosFor(level, job.position, job.type, entity) : null;
                if (entity != null
                        && (standPos == null || canPathTo(entity, standPos).reach() != Reach.YES)) {
                    noPath++;
                    noPathByType.merge(job.type, 1, Integer::sum);
                    continue;
                }

                canAssign++;
            }

            // Show summary for this worker
            if (canAssign > 0) {
                lines.add(String.format(
                        "  §a%s§f: %d assignable, %d no path, %d wrong suitability",
                        name, canAssign, noPath, noSuitability));
            } else if (noPath > 0) {
                lines.add(String.format(
                        "  §c%s§f: 0 assignable, §c%d no path§f, %d wrong suitability", name, noPath, noSuitability));
                // Show breakdown of path failures by job type
                for (Map.Entry<JobType, Integer> entry : noPathByType.entrySet()) {
                    lines.add(String.format(
                            "    §7- %s: %d jobs can't path", entry.getKey().name(), entry.getValue()));
                }
            } else if (noSuitability > 0) {
                lines.add(String.format("  §7%s§f: no matching suitabilities (%d jobs)", name, noSuitability));
            } else {
                lines.add(String.format("  §7%s§f: no jobs available", name));
            }
        }

        if (activeWorkers.values().stream().allMatch(w -> !w.idle || w.currentReservation != null)) {
            lines.add("  §7(all workers busy)");
        }

        return lines;
    }

    // ============================================
    // Data Structures
    // ============================================

    /**
     * Information about an active worker Pokemon.
     */
    public static class WorkerInfo {
        public UUID pokemonUUID;
        public BlockPos position;
        public boolean fromParty; // From its owner's party (not a pasture): works near its owner

        @Nullable
        public BlockPos ownerPosition; // Where its owner is, for one from a party (null if not in this level)

        public Set<Suitability> suitabilities;
        public ReservationId currentReservation;
        public boolean idle;
        public long lastAssignmentTime;
        public long lastSeenTick; // Game tick of the worker's last registerWorker() call

        public WorkerInfo() {
            this.suitabilities = EnumSet.noneOf(Suitability.class);
            this.idle = true;
            this.lastAssignmentTime = 0;
        }
    }

    /** What identifies a job: where it is and what it is (several jobs can share a block). */
    record JobKey(BlockPos pos, JobType type) {
        static JobKey of(JobRecord job) {
            return new JobKey(job.position, job.type);
        }

        static JobKey of(ReservationId reservation) {
            return new JobKey(reservation.getStationPos(), reservation.getJobType());
        }
    }

    /**
     * A discovered job opportunity.
     */
    public static class JobRecord {
        public BlockPos position;
        public JobType type;
        public Set<Suitability> requiredSuitabilities = EnumSet.noneOf(Suitability.class); // Worker needs all of them
        public long discoveryTime; // Game time it was found (or handed over)
        public UUID reservedByWorker; // null if available
        public int priority; // 0-10, higher = more important
        public boolean isConsecutiveJob;
        // Progress left by a previous worker (handed over): the step to carry on from, and the
        // job's data (e.g. where to replant after a chop). 0 / null for a fresh job.
        public int resumeStep;

        @Nullable
        public net.minecraft.nbt.CompoundTag resumeData;

        /** Whether a previous worker already did part of this job. */
        public boolean isHandedOver() {
            return resumeStep > 0;
        }

        public JobRecord() {
            this.priority = 5; // Default priority
            this.isConsecutiveJob = false;
        }
    }

    /**
     * Cached job information for a chunk.
     */
    public static class ChunkJobCache {
        public ChunkPos chunk;
        public Map<JobType, List<BlockPos>> cachedJobs;
        public List<BlockPos> chests = new ArrayList<>(); // Chests seen in the last scan (delivery targets)
        public long lastScanTime;
        public boolean isDirty;

        public ChunkJobCache() {
            this.cachedJobs = new EnumMap<>(JobType.class);
            this.isDirty = false;
        }
    }

    /**
     * A request for a consecutive job (delayed creation).
     */
    public static class ConsecutiveJobRequest {
        public BlockPos sourceJobLocation;
        public JobType nextJobType;
        public Suitability requiredSuitability;
        public UUID suggestedWorkerUUID;
        public long creationTime; // Game time when this job should be created

        public ConsecutiveJobRequest() {
            this.creationTime = 0;
        }
    }
}
