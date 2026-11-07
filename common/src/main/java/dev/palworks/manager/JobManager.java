package dev.palworks.manager;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.crop.CropAdapter;
import dev.palworks.job.JobType;
import dev.palworks.reservation.ReservationId;
import dev.palworks.station.FurnaceHelper;
import dev.palworks.station.StationDetector;
import dev.palworks.station.StationState;
import dev.palworks.station.StationStateManager;
import dev.palworks.station.ChestHelper;
import dev.palworks.suitability.Suitability;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import dev.palworks.ai.WorkPositions;
import dev.palworks.job.JobBlockTags;
import dev.palworks.tree.TreeDetector;
import net.minecraft.world.phys.AABB;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import dev.palworks.debug.DebugManager;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Central job management system for all worker Pokemon in a world.
 * Handles job discovery, assignment, and consecutive job chains.
 *
 * Designed for 50+ workers with chunk-based caching and performance optimization.
 */
public class JobManager {
    private static final boolean DEBUG = Palworks.DEBUG;

    // Performance tuning constants
    // Soft cap on blocks scanned per tick. Checked between chunks (a started chunk always
    // finishes), so a tick may overshoot by up to one chunk's band. ~2 chunk-bands at 32 vertical.
    private static final int MAX_BLOCKS_SCANNED_PER_TICK = 32768;
    private static final int JOB_CACHE_LIFETIME_TICKS = 40;      // 2s: max staleness of a chunk's job scan
                                                                 // (also bounds furnace/crop discovery latency)
    private static final int ASSIGNMENT_BATCH_SIZE = 10;          // Workers assigned per tick
    private static final int WORKER_SCAN_RADIUS = 32;            // Block radius to scan around workers
    private static final int VERTICAL_SCAN_RADIUS = 32;          // Y range above/below a worker to scan
    private static final int MAX_PATH_CHECKS_PER_WORKER = 8;     // Cap expensive createPath() calls per assignment
    private static final int DELIVERY_SEARCH_RADIUS = 32;        // Horizontal reach for picking a delivery chest
    private static final int DELIVERY_SEARCH_VERTICAL = 4;       // Y reach for picking a delivery chest
    // A worker won't retry a job it couldn't reach until terrain near it changes, it moves
    // UNREACHABLE_FORGET_MOVE_DIST blocks, or this backstop runs out (see recentlyFailedJobs).
    private static final int JOB_FAILURE_BLACKLIST_TICKS = 600;  // 30s backstop
    private static final int UNREACHABLE_FORGET_MOVE_DIST = 8;   // Blocks a worker moves before retrying jobs
    private static final int WORKER_STALE_TICKS = 5;             // Unregister a worker this long after it stops ticking
                                                                 // (a little slack so a skipped tick isn't a despawn)
    private static final int CONSECUTIVE_JOB_PICKUP_DELAY = 2;   // Ticks to wait for items to spawn
    private static final int CONSECUTIVE_JOB_PLANT_DELAY = 25;   // Ticks to wait before replanting
    private static final int HIGH_PRIORITY = 9;                   // Priority for consecutive jobs
    private static final boolean MINING_JOBS_ENABLED = false;     // Flip on once MINE_RESOURCE breaks ores
    private static final int REPLANT_PRIORITY = 7;                // Below harvests (8), above other planting (5)

    // Singleton instances (one per ServerLevel)
    private static final Map<String, JobManager> instances = new ConcurrentHashMap<>();
    // Same instances keyed by the level itself, for the per-block-change hook (no string building).
    private static final Map<ServerLevel, JobManager> byLevel = new ConcurrentHashMap<>();

    // Core registries
    private final Map<UUID, WorkerInfo> activeWorkers;           // All active workers
    private final Map<BlockPos, JobRecord> availableJobs;        // Discovered jobs
    private final Map<BlockPos, JobRecord> reservedJobs;         // Currently assigned

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
        this.consecutiveJobQueue = new LinkedList<>();
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
            BlockPos w = worker.position;
            if (w == null
                    || Math.abs(w.getX() - pos.getX()) > WORKER_SCAN_RADIUS
                    || Math.abs(w.getZ() - pos.getZ()) > WORKER_SCAN_RADIUS
                    || Math.abs(w.getY() - pos.getY()) > VERTICAL_SCAN_RADIUS) {
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
                releaseJobReservation(removed.currentReservation.getStationPos());
            }
            return;
        }

        // Ensure suitabilities are initialized
        if (state.areSuitabilitiesDirty() || state.getSuitabilities().isEmpty()) {
            state.initializeSuitabilities(pokemon);
            WorkerStateManager.save(pokemon, state);
        }

        // Create or update worker info
        WorkerInfo info = activeWorkers.computeIfAbsent(uuid, k -> new WorkerInfo());

        // WorkerState (NBT) is the single source of truth for a worker's reservation.
        // If the worker has dropped its reservation (job complete, stuck, expired, or
        // corrupted) but JobManager still has the job reserved, release it back to the
        // available pool so it can be reassigned. Without this, abandoned jobs leak.
        ReservationId stateReservation = state.getCurrentReservation();
        if (info.currentReservation != null && stateReservation == null) {
            releaseJobReservation(info.currentReservation.getStationPos());
            if (DEBUG) {
                Palworks.LOGGER.debug("{} dropped its reservation; released job at {} back to the pool",
                    pokemon.getSpecies().getName(), info.currentReservation.getStationPos());
            }
        }

        info.pokemonUUID = uuid;
        BlockPos entityPos = entity.blockPosition();
        info.position = entityPos;
        info.suitabilities = state.getSuitabilities();
        info.currentReservation = stateReservation;
        info.idle = (stateReservation == null);

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
                releaseJobReservation(removed.currentReservation.getStationPos());

                if (DEBUG) {
                    Palworks.LOGGER.debug("Unregistered worker {} and released job at {}",
                        pokemonUUID, removed.currentReservation.getStationPos());
                }
            }
        }
        // Drop per-worker caches so they don't leak after the worker is gone.
        entityCache.remove(pokemonUUID);
        forgetUnreachable(pokemonUUID);
        WorkerInventoryManager.unload(pokemonUUID);
        WorkerStateManager.unload(pokemonUUID);
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
            if (cache != null && !cache.isDirty &&
                (currentTime - cache.lastScanTime < JOB_CACHE_LIFETIME_TICKS)) {
                continue;
            }

            // Scan this chunk's worker-height band for jobs
            int[] range = entry.getValue();
            blocksScannedThisTick += scanChunkForJobs(level, chunk, range[0], range[1]);
        }

        if (DEBUG && blocksScannedThisTick > 0) {
            Palworks.LOGGER.debug("Scanned {} blocks across {} chunks ({} jobs available)",
                blocksScannedThisTick, scanRanges.size(), availableJobs.size());
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
                        ranges.put(chunk, new int[]{loY, hiY});
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
        ChunkJobCache cache = new ChunkJobCache();
        treeBlocksSeenThisScan.clear();
        cache.chunk = chunkPos;
        cache.lastScanTime = level.getGameTime();
        cache.isDirty = false;

        int blocksScanned = 0;

        // Scan every block in the worker-height band (16x16 x-z, every Y layer - no gaps).
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        if (DEBUG) {
            Palworks.LOGGER.info("Scanning chunk {} - Y band: {} to {}, chunk coords: {} to {}, {} to {}",
                chunkPos, scanMinY, scanMaxY, chunkPos.getMinBlockX(), chunkPos.getMaxBlockX(),
                chunkPos.getMinBlockZ(), chunkPos.getMaxBlockZ());
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
                    if (isChest(state.getBlock())) {
                        cache.chests.add(pos.immutable());
                    }

                    // Check for job opportunities at this position
                    JobRecord job = scanPositionForJob(level, pos, state);
                    if (job != null && !isJobReserved(job.position)) {
                        if (DEBUG) {
                            Palworks.LOGGER.info("Chunk scan: Found {} at {} (scanned pos was {}, job pos is {})",
                                job.type, job.position, new BlockPos(worldX, y, worldZ), job.position);
                        }
                        availableJobs.put(job.position, job);
                        cache.cachedJobs.computeIfAbsent(job.type, k -> new ArrayList<>())
                            .add(job.position);
                    }
                }
            }
        }

        chunkCaches.put(chunkPos, cache);
        return blocksScanned;
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
                    job.discoveryTime = System.currentTimeMillis();
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
                        job.discoveryTime = System.currentTimeMillis();
                        job.priority = 6;
                        return job;
                    }
                }
            }
        }

        // Check for gathering jobs (harvestable crops)
        if (CropAdapter.isFullyGrown(level, pos)) {
            JobRecord job = new JobRecord();
            job.position = pos.immutable(); // CRITICAL FIX: Create immutable copy
            job.type = JobType.HARVEST_CROPS;
            job.requiredSuitabilities = EnumSet.of(Suitability.GATHERING);
            job.discoveryTime = System.currentTimeMillis();
            job.priority = 8; // High priority for ripe crops
            return job;
        }

        // Check for planting jobs (empty farmland)
        if (CropAdapter.isPlantableSurface(level, pos)) {
            // Exclude soul sand from PLANTING - that requires NETHER suitability
            if (block == Blocks.SOUL_SAND) {
                BlockPos abovePos = pos.above();
                if (level.getBlockState(abovePos).isAir()) {
                    if (DEBUG) {
                        Palworks.LOGGER.info("JobManager: Found NETHER planting job - farmland at {}, planting at {}", pos, abovePos);
                    }
                    JobRecord job = new JobRecord();
                    job.position = abovePos;
                    job.type = JobType.PLANT_SEEDS;
                    job.requiredSuitabilities = EnumSet.of(Suitability.NETHER);
                    job.discoveryTime = System.currentTimeMillis();
                    job.priority = 5;
                    return job;
                }
            } else {
                BlockPos abovePos = pos.above();
                if (level.getBlockState(abovePos).isAir()) {
                    if (DEBUG) {
                        Palworks.LOGGER.info("JobManager: Found PLANTING job - farmland at {}, planting at {}", pos, abovePos);
                    }
                    JobRecord job = new JobRecord();
                    job.position = abovePos;
                    job.type = JobType.PLANT_SEEDS;
                    job.requiredSuitabilities = EnumSet.of(Suitability.PLANTING);
                    job.discoveryTime = System.currentTimeMillis();
                    job.priority = 5;
                    return job;
                }
            }
        }

        // Check for get seeds jobs (chests with seeds)
        if (isChest(block)) {
            if (hasPlantableSeeds(level, pos)) {
                JobRecord job = new JobRecord();
                job.position = pos.immutable(); // CRITICAL FIX: Create immutable copy
                job.type = JobType.GET_SEEDS;
                job.requiredSuitabilities = EnumSet.of(Suitability.PLANTING);
                job.discoveryTime = System.currentTimeMillis();
                job.priority = 6;
                return job;
            }
            if (ChestHelper.hasItem(level, pos, Items.NETHER_WART)) {
                JobRecord job = new JobRecord();
                job.position = pos.immutable(); // CRITICAL FIX: Create immutable copy
                job.type = JobType.GET_SEEDS;
                job.requiredSuitabilities = EnumSet.of(Suitability.NETHER);
                job.discoveryTime = System.currentTimeMillis();
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
                && TreeDetector.isValidGroundBlock(level.getBlockState(pos.below()))
                && !treeBlocksSeenThisScan.contains(pos)) {
            TreeDetector.TreeValidationResult tree = TreeDetector.validateTree(level, pos);
            if (!tree.isValid()) {
                return null;
            }
            treeBlocksSeenThisScan.addAll(tree.getLogPositions());
            JobRecord job = new JobRecord();
            job.position = pos.immutable(); // CRITICAL FIX: Create immutable copy
            job.type = JobType.CHOP_TREE;
            job.requiredSuitabilities = EnumSet.of(Suitability.LUMBERING);
            if (TreeDetector.isNetherStem(state)) {
                job.requiredSuitabilities.add(Suitability.NETHER); // nether trees need Lumbering + Nether
            }
            job.discoveryTime = System.currentTimeMillis();
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
            job.discoveryTime = System.currentTimeMillis();
            job.priority = 7; // High priority for ores
            return job;
        }

        return null;
    }

    // Helper methods for job detection
    private boolean isChest(Block block) {
        return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST;
    }

    private boolean hasPlantableSeeds(Level level, BlockPos pos) {
        return ChestHelper.hasItem(level, pos, Items.WHEAT_SEEDS) ||
               ChestHelper.hasItem(level, pos, Items.CARROT) ||
               ChestHelper.hasItem(level, pos, Items.POTATO) ||
               ChestHelper.hasItem(level, pos, Items.BEETROOT_SEEDS) ||
               ChestHelper.hasItem(level, pos, Items.PUMPKIN_SEEDS) ||
               ChestHelper.hasItem(level, pos, Items.MELON_SEEDS);
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
            .limit(ASSIGNMENT_BATCH_SIZE)
            .collect(Collectors.toList());

        if (idleWorkers.isEmpty()) {
            return;
        }

        int assignedCount = 0;
        for (WorkerInfo worker : idleWorkers) {
            // PRIORITY: Check if worker needs to deliver items first.
            // worker.idle is derived from WorkerState (the source of truth) every tick in
            // registerWorker, so no separate desync re-check is needed here.
            Pokemon pokemon = getPokemonByUUID(level, worker.pokemonUUID);

            if (pokemon != null && WorkerInventoryManager.hasItems(pokemon)) {
                // Check if worker needs these items for their job
                boolean needsItems = workerNeedsItemsForJob(worker, pokemon);

                if (!needsItems) {
                    // Worker has items they don't need - create DELIVER_ITEMS job
                    if (DEBUG) {
                        Palworks.LOGGER.info("{} has {} unwanted items in inventory, creating DELIVER_ITEMS job",
                            pokemon.getSpecies().getName(), WorkerInventoryManager.getTotalItemCount(pokemon));
                    }

                    JobRecord deliverJob = createDeliveryJobForWorker(level, worker, pokemon);
                    if (deliverJob != null) {
                        assignJobToWorker(level, worker, deliverJob);
                        assignedCount++;
                        continue; // Skip normal job assignment
                    }
                } else if (DEBUG && WorkerInventoryManager.getTotalItemCount(pokemon) >= 64) {
                    Palworks.LOGGER.debug("{} has {} work items (seeds), keeping them for planting",
                        pokemon.getSpecies().getName(), WorkerInventoryManager.getTotalItemCount(pokemon));
                }
            }

            // Find best job for this worker
            JobRecord bestJob = findBestJobForWorker(worker);

            if (bestJob != null) {
                // IMPORTANT: Remove from available IMMEDIATELY to prevent double-assignment
                availableJobs.remove(bestJob.position);

                assignJobToWorker(level, worker, bestJob);
                assignedCount++;
            }
        }

        if (DEBUG && assignedCount > 0) {
            Palworks.LOGGER.debug("Assigned {} jobs to idle workers ({} idle workers available)",
                assignedCount, idleWorkers.size());
        }
    }

    /**
     * Check if worker needs the items in their inventory for their job.
     * PLANTING workers need seeds, GATHERING/TRANSPORTING workers should deliver harvested crops.
     */
    private boolean workerNeedsItemsForJob(WorkerInfo worker, Pokemon pokemon) {
        WorkerInventory inventory = WorkerInventoryManager.getOrCreate(pokemon);
        if (inventory.isEmpty()) {
            return false;
        }

        // Check the first item in the inventory
        net.minecraft.world.item.ItemStack firstItem = inventory.getFirstItem();
        net.minecraft.world.item.Item item = firstItem.getItem();

        // Check if this is a seed that PLANTING workers need
        boolean isSeed = item == Items.WHEAT_SEEDS ||
                        item == Items.CARROT ||
                        item == Items.POTATO ||
                        item == Items.BEETROOT_SEEDS ||
                        item == Items.PUMPKIN_SEEDS ||
                        item == Items.MELON_SEEDS ||
                        item == Items.NETHER_WART;

        if (isSeed) {
            // Seeds are needed by PLANTING or NETHER workers
            return worker.suitabilities.contains(Suitability.PLANTING) ||
                   worker.suitabilities.contains(Suitability.NETHER);
        }

        // All other items (harvested crops, drops, etc.) should be delivered
        return false;
    }

    /**
     * Create a DELIVER_ITEMS job for a worker with items in inventory.
     */
    @Nullable
    private JobRecord createDeliveryJobForWorker(ServerLevel level, WorkerInfo worker, Pokemon pokemon) {
        BlockPos workerPos = worker.position;

        WorkerInventory inventory = WorkerInventoryManager.getOrCreate(pokemon);
        net.minecraft.world.item.ItemStack firstItem = inventory.getFirstItem();

        // Pick the nearest chest that has room, from the chests the chunk scan already found.
        // (This used to scan ~38k blocks itself, every tick while no chest was in range.)
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
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
                    double distSq = workerPos.distSqr(chestPos);
                    if (distSq >= nearestDistSq) {
                        continue;
                    }
                    // The cached position may be up to one scan old, so re-check it's still a chest
                    if (isChest(level.getBlockState(chestPos).getBlock())
                            && ChestHelper.canAcceptItems(level, chestPos, firstItem)) {
                        nearest = chestPos;
                        nearestDistSq = distSq;
                    }
                }
            }
        }

        if (nearest != null) {
            JobRecord job = new JobRecord();
            job.position = nearest;
            job.type = JobType.DELIVER_ITEMS;
            job.requiredSuitabilities = EnumSet.of(Suitability.TRANSPORTING);
            job.discoveryTime = System.currentTimeMillis();
            job.priority = 10; // Highest priority - must deliver before doing anything else
            return job;
        }

        if (DEBUG) {
            Palworks.LOGGER.warn("No chest found for {} to deliver {} items!",
                pokemon.getSpecies().getName(),
                WorkerInventoryManager.getTotalItemCount(pokemon));
        }

        return null;
    }

    /**
     * Find the best job for a given worker.
     */
    @Nullable
    private JobRecord findBestJobForWorker(WorkerInfo worker) {
        Pokemon pokemon = getPokemonByUUID(level, worker.pokemonUUID);
        PokemonEntity entity = getEntityByUUID(level, worker.pokemonUUID);
        WorkerInventory inventory = pokemon != null ? WorkerInventoryManager.getOrCreate(pokemon) : null;

        // Reachability was judged from where the worker stood; once it has moved, judge again.
        BlockPos anchor = failureAnchors.get(worker.pokemonUUID);
        if (anchor != null && anchor.distSqr(worker.position)
                > UNREACHABLE_FORGET_MOVE_DIST * UNREACHABLE_FORGET_MOVE_DIST) {
            forgetUnreachable(worker.pokemonUUID);
        }

        Map<BlockPos, Long> failed = recentlyFailedJobs.get(worker.pokemonUUID);
        long now = level.getGameTime();

        // Collect viable candidates and score them cheaply (NO pathfinding in this pass).
        List<JobRecord> candidates = new ArrayList<>();
        for (JobRecord job : availableJobs.values()) {
            // Skip if worker lacks suitability
            if (!canDoJob(worker, job)) {
                continue;
            }

            // Skip GET_SEEDS jobs if worker's inventory is full
            if (job.type == JobType.GET_SEEDS && inventory != null && !inventory.hasSpace()) {
                continue;
            }

            // Skip jobs this worker couldn't reach (until terrain changes or it moves)
            if (failed != null) {
                Long expiry = failed.get(job.position);
                if (expiry != null && expiry > now) {
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

        if (candidates.isEmpty()) {
            return null;
        }

        // Sort by score (lower is better) so we only pathfind the most promising jobs.
        candidates.sort(java.util.Comparator.comparingDouble(j -> scoreJob(worker, j)));

        // Pathfinding (createPath) is expensive - only path-check the best few candidates.
        int pathChecks = 0;
        for (JobRecord job : candidates) {
            if (entity == null) {
                return job; // can't path-check; take the best by score
            }
            BlockPos standPos = WorkPositions.standingPosFor(level, job.position, job.type, entity);
            if (standPos != null && canPathTo(entity, standPos)) {
                return job;
            }
            // Remember it, so next tick this worker checks its next candidates instead of
            // pathfinding to the same unreachable ones again.
            rememberUnreachable(worker.pokemonUUID, worker.position, job.position, now);
            if (++pathChecks >= MAX_PATH_CHECKS_PER_WORKER) {
                break;
            }
        }

        return null;
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
        if (job.isConsecutiveJob && job.reservedByWorker != null &&
            job.reservedByWorker.equals(worker.pokemonUUID)) {
            score *= 0.1; // 90% discount for consecutive jobs
        }
        return score;
    }

    /**
     * Assign a specific job to a worker.
     * NOTE: Job should already be removed from availableJobs before calling this.
     */
    private void assignJobToWorker(ServerLevel level, WorkerInfo worker, JobRecord job) {
        Pokemon pokemon = getPokemonByUUID(level, worker.pokemonUUID);
        if (pokemon == null) {
            // Pokemon entity not found - put job back in available pool
            if (DEBUG) {
                Palworks.LOGGER.warn("Failed to assign {} job at {} - Pokemon entity not found for UUID {}",
                    job.type, job.position, worker.pokemonUUID);
            }
            availableJobs.put(job.position, job);
            return;
        }

        ReservationId reservation = new ReservationId(job.position, job.type);

        // WorkerState (NBT) is the single source of truth for the reservation.
        WorkerState state = WorkerStateManager.getOrCreate(pokemon);
        state.setCurrentReservation(reservation);
        state.setAssignedJob(job.type);
        // Start the work timer at 0 for the new job. Without this, a stale consecutiveWorkTicks
        // (now kept in the WorkerStateManager cache) can be non-zero on arrival, so performWork
        // skips the onStart action (e.g. furnace ignition) - the worker "works" but does nothing.
        state.resetWorkSession();
        WorkerStateManager.save(pokemon, state);

        // Mirror into the in-memory caches; registerWorker re-derives these from
        // WorkerState each tick, so they stay reconciled with the source of truth.
        worker.currentReservation = reservation;
        worker.idle = false;
        worker.lastAssignmentTime = System.currentTimeMillis();
        job.reservedByWorker = worker.pokemonUUID;
        reservedJobs.put(job.position, job);

        if (DEBUG) {
            String dist = String.format("%.1f", Math.sqrt(worker.position.distSqr(job.position)));
            Palworks.LOGGER.debug("Assigned {} job at {} to worker {} (distance: {})",
                job.type, job.position, pokemon.getSpecies().getName(), dist);

            // Send chat message to nearby players
            sendDebugMessage(level, job.position,
                String.format("§a%s§f assigned §e%s§f at [%d, %d, %d] (dist: %s)",
                    pokemon.getSpecies().getName(), job.type.name(),
                    job.position.getX(), job.position.getY(), job.position.getZ(), dist));
        }
    }

    /**
     * Get a Pokemon entity by UUID.
     */
    @Nullable
    private Pokemon getPokemonByUUID(ServerLevel level, UUID uuid) {
        PokemonEntity entity = getEntityByUUID(level, uuid);
        return entity != null ? entity.getPokemon() : null;
    }

    /**
     * Get a registered worker's PokemonEntity (Mob) by Pokemon UUID for navigation checks.
     * Only workers are looked up, and registerWorker() keeps entityCache current for them.
     */
    @Nullable
    private PokemonEntity getEntityByUUID(ServerLevel level, UUID uuid) {
        PokemonEntity cached = entityCache.get(uuid);
        return cached != null && cached.isAlive() ? cached : null;
    }

    /**
     * Check if an entity can path to a target position.
     * Returns true if a valid path exists.
     */
    private boolean canPathTo(Mob entity, BlockPos standPos) {
        if (entity == null) {
            return false;
        }

        // No "close enough" shortcut: a job can be one block away yet blocked by a fence or ledge
        // (common on farms), so always build a real path and require it to actually reach the tile.
        // standPos is a standable tile (not the worked block itself), so canReach() is the right,
        // non-fragile test here - the old "any path object = reachable" let workers take jobs they
        // couldn't actually get to.
        net.minecraft.world.level.pathfinder.Path path = entity.getNavigation().createPath(standPos, 1);
        return path != null && path.canReach();
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
            Palworks.LOGGER.info("{} completed {} job at {}",
                pokemon.getSpecies().getName(), completedReservation.getJobType(), jobPos);

            // Send chat message to nearby players
            sendDebugMessage(serverLevel, jobPos,
                String.format("§a%s§f completed §e%s§f at [%d, %d, %d]",
                    pokemon.getSpecies().getName(), completedReservation.getJobType().name(),
                    jobPos.getX(), jobPos.getY(), jobPos.getZ()));
        }

        // Handle consecutive jobs for harvesting
        if (completedReservation.getJobType() == JobType.HARVEST_CROPS) {
            long currentTime = level.getGameTime();

            // Check if this is a berry bush - berries reset their age, don't need replanting
            boolean isBerry = dev.palworks.crop.CropAdapter.isBerryBush(level, jobPos);

            if (DEBUG) {
                if (isBerry) {
                    Palworks.LOGGER.info("Queuing pickup job for berry harvest at {} (no replanting needed)", jobPos);
                    sendDebugMessage(serverLevel, jobPos,
                        "  §7→ Queued §eCOLLECT_DROPS§7 (berry - no replanting)");
                } else {
                    Palworks.LOGGER.info("Queuing pickup + replant jobs for harvest at {}", jobPos);
                    sendDebugMessage(serverLevel, jobPos,
                        "  §7→ Queued §eCOLLECT_DROPS§7 and §ePLANT_SEEDS§7 jobs");
                }
            }

            // Queue 1: Collect dropped items (delay 2 ticks for items to spawn)
            ConsecutiveJobRequest pickupRequest = new ConsecutiveJobRequest();
            pickupRequest.sourceJobLocation = jobPos;
            pickupRequest.nextJobType = JobType.COLLECT_DROPS;
            pickupRequest.requiredSuitability = Suitability.TRANSPORTING;
            pickupRequest.suggestedWorkerUUID = workerUUID;  // Prefer same worker
            pickupRequest.creationTime = currentTime + CONSECUTIVE_JOB_PICKUP_DELAY;

            consecutiveJobQueue.add(pickupRequest);

            // Queue 2: Replant seeds (delay 25 ticks to allow pickup first)
            // SKIP for berry bushes since they reset their age instead of being replanted
            if (!isBerry) {
                ConsecutiveJobRequest plantRequest = new ConsecutiveJobRequest();
                plantRequest.sourceJobLocation = jobPos;
                plantRequest.nextJobType = JobType.PLANT_SEEDS;
                plantRequest.requiredSuitability = Suitability.PLANTING;
                plantRequest.suggestedWorkerUUID = workerUUID;  // Prefer same worker
                plantRequest.creationTime = currentTime + CONSECUTIVE_JOB_PLANT_DELAY;

                consecutiveJobQueue.add(plantRequest);
            }
        }

        // IMPORTANT: Remove completed job entirely (don't put back in available pool!)
        reservedJobs.remove(jobPos);
        availableJobs.remove(jobPos);  // Also remove from available in case it's there

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

        if (DEBUG) {
            Palworks.LOGGER.debug("{} abandoned {} at {}; won't retry it until terrain changes or it moves (max {} ticks)",
                pokemon.getSpecies().getName(), reservation.getJobType(), pos, JOB_FAILURE_BLACKLIST_TICKS);
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
        reservedJobs.remove(pos);
        availableJobs.remove(pos);

        WorkerInfo workerInfo = activeWorkers.get(pokemon.getUuid());
        if (workerInfo != null) {
            workerInfo.idle = true;
            workerInfo.currentReservation = null;
        }

        if (DEBUG) {
            Palworks.LOGGER.info("{}'s {} at {} no longer exists - removed from pool",
                pokemon.getSpecies().getName(), reservation.getJobType(), pos);
        }
    }

    /**
     * Process consecutive job queue (delayed job creation).
     */
    private void processConsecutiveJobs(ServerLevel level) {
        long currentTime = level.getGameTime();

        if (DEBUG && !consecutiveJobQueue.isEmpty()) {
            Palworks.LOGGER.info("Processing consecutive job queue ({} jobs queued, current time: {})",
                consecutiveJobQueue.size(), currentTime);
        }

        // Process all ready requests
        while (!consecutiveJobQueue.isEmpty()) {
            ConsecutiveJobRequest request = consecutiveJobQueue.peek();

            // Check if ready to process (delay elapsed)
            if (currentTime < request.creationTime) {
                if (DEBUG) {
                    long ticksRemaining = request.creationTime - currentTime;
                    Palworks.LOGGER.info("Next job ({}) not ready yet - {} ticks remaining",
                        request.nextJobType, ticksRemaining);
                }
                break;  // Not ready yet, and queue is ordered
            }

            consecutiveJobQueue.poll();

            if (DEBUG) {
                Palworks.LOGGER.info("Creating consecutive job: {} at {}",
                    request.nextJobType, request.sourceJobLocation);
            }

            if (request.nextJobType == JobType.COLLECT_DROPS) {
                // Create collect drops job for items at harvest location
                createCollectDropsJob(level, request);
            } else if (request.nextJobType == JobType.PLANT_SEEDS) {
                // Create planting job at harvest location
                createPlantJob(level, request);
            } else if (request.nextJobType == JobType.PLANT_SAPLING) {
                // Create sapling planting job at tree base
                createPlantSaplingJob(level, request);
            }
        }
    }

    /**
     * Create a collect drops job for items at a location.
     */
    private void createCollectDropsJob(ServerLevel level, ConsecutiveJobRequest request) {
        // Scan for items at harvest location
        AABB searchBox = new AABB(request.sourceJobLocation).inflate(3.0);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, searchBox);

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
        collectJob.discoveryTime = System.currentTimeMillis();
        collectJob.isConsecutiveJob = true;
        collectJob.priority = HIGH_PRIORITY;
        collectJob.reservedByWorker = request.suggestedWorkerUUID;  // Hint for assignment

        // Try to assign to suggested worker first
        WorkerInfo suggestedWorker = activeWorkers.get(request.suggestedWorkerUUID);
        if (suggestedWorker != null && suggestedWorker.idle &&
            suggestedWorker.suitabilities.contains(Suitability.TRANSPORTING)) {
            assignJobToWorker(level, suggestedWorker, collectJob);

            if (DEBUG) {
                Palworks.LOGGER.debug("Assigned consecutive collect drops job to original harvester at {}",
                    request.sourceJobLocation);
            }
        } else {
            // Otherwise add to available jobs
            availableJobs.put(collectJob.position, collectJob);

            if (DEBUG) {
                Palworks.LOGGER.debug("Added consecutive collect drops job to available pool at {} ({} items)",
                    request.sourceJobLocation, items.size());
            }
        }
    }

    /**
     * Create a planting job at a harvested location.
     */
    private void createPlantJob(ServerLevel level, ConsecutiveJobRequest request) {
        BlockPos farmlandPos = request.sourceJobLocation.below();

        // Verify the farmland still exists
        if (!CropAdapter.isPlantableSurface(level, farmlandPos)) {
            if (DEBUG) {
                Palworks.LOGGER.debug("Farmland no longer exists at {} for replant job", farmlandPos);
            }
            return;
        }

        // Verify the air above is still empty
        if (!level.getBlockState(request.sourceJobLocation).isAir()) {
            if (DEBUG) {
                Palworks.LOGGER.debug("Position {} is no longer air for replant job", request.sourceJobLocation);
            }
            return;
        }

        // Determine suitability based on farmland type
        Suitability suitability = Suitability.PLANTING;
        Block farmlandBlock = level.getBlockState(farmlandPos).getBlock();
        if (farmlandBlock == Blocks.SOUL_SAND) {
            suitability = Suitability.NETHER;
        }

        // Create planting job
        JobRecord plantJob = new JobRecord();
        plantJob.position = request.sourceJobLocation;
        plantJob.type = JobType.PLANT_SEEDS;
        plantJob.requiredSuitabilities = EnumSet.of(suitability);
        plantJob.discoveryTime = System.currentTimeMillis();
        plantJob.isConsecutiveJob = true;
        plantJob.priority = REPLANT_PRIORITY;
        plantJob.reservedByWorker = request.suggestedWorkerUUID;  // Hint for assignment

        // Always goes to the pool rather than straight to the harvester: harvests come first, and
        // findBestJobForWorker holds back planting while the worker still has harvests to do.
        availableJobs.put(plantJob.position, plantJob);

        if (DEBUG) {
            Palworks.LOGGER.debug("Added consecutive plant job to available pool at {}",
                request.sourceJobLocation);
        }
    }

    /**
     * Called when a worker completes chopping a tree.
     * Queues consecutive jobs for item collection and sapling replanting.
     */
    public void onTreeChopped(Pokemon pokemon, BlockPos basePos, net.minecraft.world.item.Item saplingItem, Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        UUID workerUUID = pokemon.getUuid();
        long currentTime = level.getGameTime();

        if (DEBUG) {
            Palworks.LOGGER.info("{} chopped tree at base {}, will replant {} sapling",
                pokemon.getSpecies().getName(), basePos, saplingItem);

            sendDebugMessage(serverLevel, basePos,
                String.format("§a%s§f chopped tree, queuing §eCOLLECT_DROPS§f and §ePLANT_SAPLING§f",
                    pokemon.getSpecies().getName()));
        }

        // Queue 1: Collect dropped items (delay 2 ticks for items to spawn)
        ConsecutiveJobRequest pickupRequest = new ConsecutiveJobRequest();
        pickupRequest.sourceJobLocation = basePos;
        pickupRequest.nextJobType = JobType.COLLECT_DROPS;
        pickupRequest.requiredSuitability = Suitability.TRANSPORTING;
        pickupRequest.suggestedWorkerUUID = workerUUID;
        pickupRequest.creationTime = currentTime + CONSECUTIVE_JOB_PICKUP_DELAY;

        consecutiveJobQueue.add(pickupRequest);

        // Queue 2: Plant sapling at base position (delay to allow pickup first)
        if (saplingItem != null) {
            ConsecutiveJobRequest saplingRequest = new ConsecutiveJobRequest();
            saplingRequest.sourceJobLocation = basePos;
            saplingRequest.nextJobType = JobType.PLANT_SAPLING;
            saplingRequest.requiredSuitability = Suitability.LUMBERING;
            saplingRequest.suggestedWorkerUUID = workerUUID;
            saplingRequest.saplingItem = saplingItem;
            saplingRequest.creationTime = currentTime + CONSECUTIVE_JOB_PLANT_DELAY;

            consecutiveJobQueue.add(saplingRequest);
        }
    }

    /**
     * Create a sapling planting job at a tree base location.
     */
    private void createPlantSaplingJob(ServerLevel level, ConsecutiveJobRequest request) {
        BlockPos plantPos = request.sourceJobLocation;

        if (request.saplingItem == null) {
            if (DEBUG) {
                Palworks.LOGGER.debug("No sapling item specified for plant job at {}", plantPos);
            }
            return;
        }

        // Verify position is valid for planting (depends on the sapling: propagules can go in water)
        if (!dev.palworks.tree.TreeDetector.canPlantSaplingAt(level, plantPos, request.saplingItem)) {
            if (DEBUG) {
                Palworks.LOGGER.debug("Cannot plant sapling at {} - position not valid", plantPos);
            }
            return;
        }

        // Create sapling planting job
        JobRecord saplingJob = new JobRecord();
        saplingJob.position = plantPos;
        saplingJob.type = JobType.PLANT_SAPLING;
        saplingJob.requiredSuitabilities = EnumSet.of(Suitability.LUMBERING);
        if (TreeDetector.isNetherSapling(request.saplingItem)) {
            saplingJob.requiredSuitabilities.add(Suitability.NETHER); // fungi, like nether trees, need both
        }
        saplingJob.discoveryTime = System.currentTimeMillis();
        saplingJob.isConsecutiveJob = true;
        saplingJob.priority = HIGH_PRIORITY;
        saplingJob.reservedByWorker = request.suggestedWorkerUUID;

        // Store sapling item in a way the worker can access it
        // We'll use a temporary map to track pending sapling plants
        pendingSaplingPlants.put(plantPos, request.saplingItem);

        // Try to assign to suggested worker first
        WorkerInfo suggestedWorker = activeWorkers.get(request.suggestedWorkerUUID);
        if (suggestedWorker != null && suggestedWorker.idle && canDoJob(suggestedWorker, saplingJob)) {
            assignJobToWorker(level, suggestedWorker, saplingJob);

            if (DEBUG) {
                Palworks.LOGGER.debug("Assigned consecutive plant sapling job to original lumberjack at {}",
                    plantPos);
            }
        } else {
            // Otherwise add to available jobs
            availableJobs.put(saplingJob.position, saplingJob);

            if (DEBUG) {
                Palworks.LOGGER.debug("Added consecutive plant sapling job to available pool at {}",
                    plantPos);
            }
        }
    }

    /**
     * Get and remove the sapling item for a pending plant job.
     */
    public net.minecraft.world.item.Item consumePendingSapling(BlockPos pos) {
        return pendingSaplingPlants.remove(pos);
    }

    // Temporary storage for sapling types at plant positions
    private final Map<BlockPos, net.minecraft.world.item.Item> pendingSaplingPlants = new ConcurrentHashMap<>();

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
                Palworks.LOGGER.debug("Invalidated chunk cache at {} due to block change at {}",
                    chunk, pos);
            }
        }

        // Remove job if it was at this position
        JobRecord removed = availableJobs.remove(pos);
        if (removed == null) {
            removed = reservedJobs.remove(pos);
        }

        if (removed != null && DEBUG) {
            Palworks.LOGGER.debug("Removed {} job at {} due to block change",
                removed.type, pos);
        }
    }

    /**
     * Release a job reservation (make it available again).
     */
    private void releaseJobReservation(BlockPos position) {
        JobRecord job = reservedJobs.remove(position);
        if (job != null) {
            job.reservedByWorker = null;
            availableJobs.put(position, job);
        }
    }

    /**
     * Check if a job is currently reserved.
     */
    private boolean isJobReserved(BlockPos position) {
        return reservedJobs.containsKey(position);
    }

    /**
     * Clean up expired data (old reservations, stale caches).
     */
    private void cleanupExpiredData(ServerLevel level) {
        long currentTime = level.getGameTime();

        // Remove stale chunk caches (older than cache lifetime)
        chunkCaches.entrySet().removeIf(entry -> {
            ChunkJobCache cache = entry.getValue();
            return !cache.isDirty && (currentTime - cache.lastScanTime) > JOB_CACHE_LIFETIME_TICKS * 2;
        });

        // Remove jobs that have been available too long. discoveryTime is wall-clock ms,
        // so it MUST be compared in ms (not game ticks) for this to ever fire.
        long nowMs = System.currentTimeMillis();
        long fiveMinutesMs = 5L * 60L * 1000L;
        availableJobs.entrySet().removeIf(entry ->
            (nowMs - entry.getValue().discoveryTime) > fiveMinutesMs);

        // Expire per-worker job-failure blacklist entries (stored as game ticks).
        recentlyFailedJobs.values().forEach(failed ->
            failed.entrySet().removeIf(e -> e.getValue() <= currentTime));
        recentlyFailedJobs.entrySet().removeIf(e -> e.getValue().isEmpty());
        failureAnchors.keySet().retainAll(recentlyFailedJobs.keySet());

        // Release orphaned reservations (jobs reserved by workers that no longer exist)
        List<BlockPos> orphanedJobs = new ArrayList<>();
        for (Map.Entry<BlockPos, JobRecord> entry : reservedJobs.entrySet()) {
            JobRecord job = entry.getValue();
            if (job.reservedByWorker != null && !activeWorkers.containsKey(job.reservedByWorker)) {
                orphanedJobs.add(entry.getKey());
                if (DEBUG) {
                    Palworks.LOGGER.debug("Found orphaned job {} at {} - worker {} no longer exists",
                        job.type, job.position, job.reservedByWorker);
                }
            }
        }

        for (BlockPos pos : orphanedJobs) {
            releaseJobReservation(pos);
            if (DEBUG) {
                Palworks.LOGGER.debug("Released orphaned job reservation at {}", pos);
            }
        }
    }

    // ============================================
    // Debug Helpers
    // ============================================

    /**
     * Send a debug message to all players who have debug enabled.
     */
    private void sendDebugMessage(ServerLevel level, BlockPos pos, String message) {
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
            Map<JobType, List<JobRecord>> byType = availableJobs.values().stream()
                .collect(Collectors.groupingBy(j -> j.type));
            for (Map.Entry<JobType, List<JobRecord>> entry : byType.entrySet()) {
                JobType type = entry.getKey();
                List<JobRecord> jobs = entry.getValue();
                lines.add(String.format("  §a%s§f: %d jobs (need §b%s§f)",
                    type.name(), jobs.size(), jobs.get(0).requiredSuitabilities));
                // Show first 3 positions
                int shown = 0;
                for (JobRecord job : jobs) {
                    if (shown >= 3) {
                        lines.add(String.format("    §7... and %d more", jobs.size() - 3));
                        break;
                    }
                    lines.add(String.format("    §7[%d, %d, %d] pri=%d",
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
                    Pokemon pokemon = getPokemonByUUID(level, job.reservedByWorker);
                    if (pokemon != null) {
                        workerName = pokemon.getSpecies().getName();
                    }
                }
                lines.add(String.format("  §a%s§f at [%d, %d, %d] -> §b%s",
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
                Pokemon pokemon = getPokemonByUUID(level, worker.pokemonUUID);
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

                lines.add(String.format("  §b%s§f: %s §7[%s]",
                    name, status, suits));
            }
        }

        // Check for workers that should be able to take available jobs but aren't
        lines.add("");
        lines.add("§e[Assignment Analysis]");
        for (WorkerInfo worker : activeWorkers.values()) {
            if (!worker.idle || worker.currentReservation != null) continue;

            Pokemon pokemon = getPokemonByUUID(level, worker.pokemonUUID);
            String name = pokemon != null ? pokemon.getSpecies().getName() : "unknown";
            Mob entity = pokemon != null ? (Mob) pokemon.getEntity() : null;

            // Count jobs by category for this worker
            int noSuitability = 0;
            int noPath = 0;
            int canAssign = 0;
            Map<JobType, Integer> noPathByType = new HashMap<>();

            for (JobRecord job : availableJobs.values()) {
                // Check suitability
                if (!canDoJob(worker, job)) {
                    noSuitability++;
                    continue;
                }

                // Check pathfinding to the resolved stand position
                BlockPos standPos = entity != null
                    ? WorkPositions.standingPosFor(level, job.position, job.type, entity) : null;
                if (entity != null && (standPos == null || !canPathTo(entity, standPos))) {
                    noPath++;
                    noPathByType.merge(job.type, 1, Integer::sum);
                    continue;
                }

                canAssign++;
            }

            // Show summary for this worker
            if (canAssign > 0) {
                lines.add(String.format("  §a%s§f: %d assignable, %d no path, %d wrong suitability",
                    name, canAssign, noPath, noSuitability));
            } else if (noPath > 0) {
                lines.add(String.format("  §c%s§f: 0 assignable, §c%d no path§f, %d wrong suitability",
                    name, noPath, noSuitability));
                // Show breakdown of path failures by job type
                for (Map.Entry<JobType, Integer> entry : noPathByType.entrySet()) {
                    lines.add(String.format("    §7- %s: %d jobs can't path",
                        entry.getKey().name(), entry.getValue()));
                }
            } else if (noSuitability > 0) {
                lines.add(String.format("  §7%s§f: no matching suitabilities (%d jobs)",
                    name, noSuitability));
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
        public Set<Suitability> suitabilities;
        public ReservationId currentReservation;
        public boolean idle;
        public long lastAssignmentTime;
        public long lastSeenTick;      // Game tick of the worker's last registerWorker() call

        public WorkerInfo() {
            this.suitabilities = new HashSet<>();
            this.idle = true;
            this.lastAssignmentTime = 0;
        }
    }

    /**
     * A discovered job opportunity.
     */
    public static class JobRecord {
        public BlockPos position;
        public JobType type;
        public Set<Suitability> requiredSuitabilities = EnumSet.noneOf(Suitability.class); // Worker needs all of them
        public long discoveryTime;
        public UUID reservedByWorker;  // null if available
        public int priority;           // 0-10, higher = more important
        public boolean isConsecutiveJob;

        public JobRecord() {
            this.priority = 5;  // Default priority
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
            this.cachedJobs = new HashMap<>();
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
        public long creationTime;  // Game time when this job should be created
        public net.minecraft.world.item.Item saplingItem;  // For PLANT_SAPLING jobs

        public ConsecutiveJobRequest() {
            this.creationTime = 0;
        }
    }
}
