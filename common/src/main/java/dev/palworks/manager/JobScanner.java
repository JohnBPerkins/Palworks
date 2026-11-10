package dev.palworks.manager;

import dev.palworks.Palworks;
import dev.palworks.crop.CropAdapter;
import dev.palworks.job.JobBlockTags;
import dev.palworks.job.JobType;
import dev.palworks.job.WorkRange;
import dev.palworks.manager.JobPool.JobKey;
import dev.palworks.manager.WorkerRegistry.WorkerInfo;
import dev.palworks.station.ChestHelper;
import dev.palworks.station.FurnaceHelper;
import dev.palworks.suitability.Suitability;
import dev.palworks.tree.TreeDetector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Finds a level's jobs: scans the chunks round its workers, a few a tick, putting what it finds in
 * the {@link JobPool} and taking out what's gone. Each chunk's scan is cached
 * ({@link ChunkJobCache}) until it's stale or something in it changes.
 */
final class JobScanner {
    private static final boolean DEBUG = Palworks.DEBUG;

    // Soft cap on blocks scanned per tick. Checked between chunks (a started chunk always
    // finishes), so a tick may overshoot by up to one chunk's band. ~2 chunk-bands at 32 vertical.
    private static final int MAX_BLOCKS_SCANNED_PER_TICK = 32768;
    // 2s: max staleness of a chunk's job scan (also bounds furnace/crop discovery latency)
    static final int JOB_CACHE_LIFETIME_TICKS = 40;
    private static final int WORKER_SCAN_RADIUS = WorkRange.HORIZONTAL; // Block radius to scan around workers
    private static final int VERTICAL_SCAN_RADIUS = WorkRange.VERTICAL; // Y range above/below a worker to scan
    private static final boolean MINING_JOBS_ENABLED = false; // Flip on once MINE_RESOURCE breaks ores

    private final JobPool pool;

    // Chunk-based job cache (performance optimization)
    private final Map<ChunkPos, ChunkJobCache> chunkCaches = new ConcurrentHashMap<>();

    // Blocks of trees already validated during the current chunk scan, so a tree with several base
    // blocks only gets one tree search and one job. Cleared at the start of each chunk scan.
    private final Set<BlockPos> treeBlocksSeenThisScan = new HashSet<>();

    JobScanner(JobPool pool) {
        this.pool = pool;
    }

    /**
     * Scan for new jobs using chunk-based caching.
     */
    void scan(ServerLevel level, Collection<WorkerInfo> workers) {
        int blocksScannedThisTick = 0;
        long currentTime = level.getGameTime();

        // Get chunks near active workers, each with the Y band to scan (around worker height).
        Map<ChunkPos, int[]> scanRanges = getChunkScanRangesNearWorkers(level, workers);

        for (Map.Entry<ChunkPos, int[]> entry : scanRanges.entrySet()) {
            // Block budget is checked between chunks; a chunk we start always finishes.
            if (blocksScannedThisTick >= MAX_BLOCKS_SCANNED_PER_TICK) {
                break;
            }

            ChunkPos chunk = entry.getKey();
            ChunkJobCache cache = chunkCaches.get(chunk);

            // Skip if cache is fresh and not dirty
            if (cache != null && !cache.isDirty() && (currentTime - cache.scanTime() < JOB_CACHE_LIFETIME_TICKS)) {
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
                    pool.available().size());
        }
    }

    /**
     * Get chunks near active workers, each mapped to the [minY, maxY) band to scan.
     * The band is bounded to a vertical window around the contributing worker(s) so the
     * scan can cover every layer (no Y gaps) without sweeping the entire build height.
     */
    private Map<ChunkPos, int[]> getChunkScanRangesNearWorkers(ServerLevel level, Collection<WorkerInfo> workers) {
        Map<ChunkPos, int[]> ranges = new HashMap<>();
        int worldMinY = level.getMinBuildHeight();
        int worldMaxY = level.getMaxBuildHeight();
        int chunkRadius = WORKER_SCAN_RADIUS / 16; // Convert block radius to chunks

        for (WorkerInfo worker : workers) {
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
        ChunkJobCache cache = new ChunkJobCache(level.getGameTime());
        treeBlocksSeenThisScan.clear();

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
                    if (JobChecks.isChest(state)) {
                        cache.addChest(pos.immutable());
                    }

                    // Check for job opportunities at this position
                    JobRecord job = scanPositionForJob(level, pos, state);
                    if (job != null && !pool.isReserved(JobKey.of(job))) {
                        if (DEBUG) {
                            Palworks.LOGGER.info(
                                    "Chunk scan: Found {} at {} (scanned pos was {}, job pos is {})",
                                    job.type(),
                                    job.position(),
                                    new BlockPos(worldX, y, worldZ),
                                    job.position());
                        }
                        pool.offer(job);
                        cache.addJob(job.type(), job.position());
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
        previous.jobs().forEach((type, positions) -> {
            List<BlockPos> stillThere = current.jobs().getOrDefault(type, List.of());
            for (BlockPos pos : positions) {
                if (!stillThere.contains(pos)) {
                    pool.removeScanned(new JobKey(pos, type));
                }
            }
        });
    }

    /** Remove all of a chunk cache's available jobs (when the cache itself is dropped). */
    private void removeJobsOf(ChunkJobCache cache) {
        cache.jobs().forEach((type, positions) -> positions.forEach(pos -> pool.removeScanned(new JobKey(pos, type))));
    }

    /**
     * Detects the job opportunity (if any) at a single world position.
     */
    @Nullable
    private JobRecord scanPositionForJob(Level level, BlockPos pos, BlockState state) {
        long now = level.getGameTime();

        // Furnaces that need lighting (Kindling)
        if (state.is(JobBlockTags.FURNACES) && FurnaceHelper.needsFuel(level, pos)) {
            return JobRecord.of(JobType.FUEL_FURNACE, pos, now);
        }

        // Pumpkins and melons: a stem that has grown its fruit is "attached" to it. The harvest job
        // goes on the fruit (the stem itself is never harvested).
        BlockPos fruitPos = CropAdapter.attachedFruitPos(state, pos);
        if (fruitPos != null) {
            return JobRecord.of(JobType.HARVEST_CROPS, fruitPos, now);
        }

        // Check for gathering jobs (harvestable crops)
        if (CropAdapter.isFullyGrown(level, pos)) {
            // Nether wart is Nether work (Ghost types), like planting it and fetching it from
            // chests: Gathering alone let any Grass type harvest it
            return state.is(Blocks.NETHER_WART)
                    ? JobRecord.of(JobType.HARVEST_CROPS, pos, now, EnumSet.of(Suitability.NETHER))
                    : JobRecord.of(JobType.HARVEST_CROPS, pos, now);
        }

        // Check for planting jobs (empty farmland, or soul sand: that's Nether work)
        if (CropAdapter.isPlantableSurface(level, pos)) {
            BlockPos abovePos = pos.above();
            if (level.getBlockState(abovePos).isAir()) {
                boolean nether = state.is(Blocks.SOUL_SAND);
                if (DEBUG) {
                    Palworks.LOGGER.info(
                            "JobManager: Found {} planting job - farmland at {}, planting at {}",
                            nether ? "NETHER" : "PLANTING",
                            pos,
                            abovePos);
                }
                return nether
                        ? JobRecord.of(JobType.PLANT_SEEDS, abovePos, now, EnumSet.of(Suitability.NETHER))
                        : JobRecord.of(JobType.PLANT_SEEDS, abovePos, now);
            }
        }

        // Check for get seeds jobs (chests with seeds)
        if (JobChecks.isChest(state)) {
            if (JobChecks.hasPlantableSeeds(level, pos)) {
                return JobRecord.of(JobType.GET_SEEDS, pos, now);
            }
            if (ChestHelper.hasItem(level, pos, Items.NETHER_WART)) {
                return JobRecord.of(JobType.GET_SEEDS, pos, now, EnumSet.of(Suitability.NETHER));
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
            // The same spot for the tree whichever base this scan found it from, so a tree seen
            // from two chunks (or by scans of different heights) is one job, not one per base
            BlockPos chopPos = TreeDetector.chopPosition(level, tree);
            return TreeDetector.isNetherStem(state)
                    ? JobRecord.of(
                            JobType.CHOP_TREE, chopPos, now, EnumSet.of(Suitability.LUMBERING, Suitability.NETHER))
                    : JobRecord.of(JobType.CHOP_TREE, chopPos, now);
        }

        // Check for mining jobs (ores). Off until mining is implemented (MINE_RESOURCE's handler
        // doesn't break the ore yet), so workers don't walk to ores and stand there forever.
        if (MINING_JOBS_ENABLED && state.is(JobBlockTags.ORES)) {
            return JobRecord.of(JobType.MINE_RESOURCE, pos, now);
        }

        return null;
    }

    /**
     * Whether every chunk in a worker's range has been scanned for jobs. The scan gets through a
     * couple of chunks a tick, so a worker just sent out (or arrived somewhere new) waits the few
     * ticks until it's done: assigning it straight away handed it the best of the jobs found so
     * far, which could be the far side of its range with nearer ones not found yet.
     */
    boolean hasScannedAround(WorkerInfo worker) {
        int chunkRadius = WORKER_SCAN_RADIUS / 16; // same chunks as getChunkScanRangesNearWorkers
        int baseChunkX = worker.position.getX() >> 4;
        int baseChunkZ = worker.position.getZ() >> 4;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                ChunkJobCache cache = chunkCaches.get(new ChunkPos(baseChunkX + dx, baseChunkZ + dz));
                if (cache == null || cache.isDirty()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Have the chunks in a worker's range round {@code center} scanned again as soon as possible. */
    void rescanAround(BlockPos center) {
        int chunkRadius = WORKER_SCAN_RADIUS / 16;
        ChunkPos chunk = new ChunkPos(center);
        markForRescan(chunk.x - chunkRadius, chunk.z - chunkRadius, chunk.x + chunkRadius, chunk.z + chunkRadius);
    }

    /**
     * Have the chunks covering a box be scanned again as soon as possible, after lots of blocks in
     * it changed at once (e.g. /palworks testarea). Until they have, no worker near them is given a
     * job, so none takes a far one before the nearer ones are found.
     */
    void rescanArea(BlockPos min, BlockPos max) {
        markForRescan(min.getX() >> 4, min.getZ() >> 4, max.getX() >> 4, max.getZ() >> 4);
    }

    private void markForRescan(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                ChunkJobCache cache = chunkCaches.get(new ChunkPos(x, z));
                if (cache != null) {
                    cache.markDirty();
                }
            }
        }
    }

    /**
     * The chests the last scans found in the square of chunks {@code chunkRadius} round
     * {@code center}'s (delivery targets). Up to a scan old: check they're still chests.
     */
    List<BlockPos> chestsNear(BlockPos center, int chunkRadius) {
        List<BlockPos> chests = new ArrayList<>();
        int baseChunkX = center.getX() >> 4;
        int baseChunkZ = center.getZ() >> 4;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                ChunkJobCache cache = chunkCaches.get(new ChunkPos(baseChunkX + dx, baseChunkZ + dz));
                if (cache != null) {
                    chests.addAll(cache.chests());
                }
            }
        }
        return chests;
    }

    /**
     * Remove stale chunk caches (older than twice the cache lifetime), and their jobs: no worker is
     * near enough to rescan them, so nothing would ever notice those jobs disappearing.
     */
    void dropStaleCaches(long gameTime) {
        chunkCaches.entrySet().removeIf(entry -> {
            ChunkJobCache cache = entry.getValue();
            boolean stale = !cache.isDirty() && (gameTime - cache.scanTime()) > JOB_CACHE_LIFETIME_TICKS * 2;
            if (stale) {
                removeJobsOf(cache);
            }
            return stale;
        });
    }

    /** What the last scan of one chunk found, so it isn't rescanned every tick. */
    private static final class ChunkJobCache {
        private final long scanTime;
        private final Map<JobType, List<BlockPos>> jobs = new EnumMap<>(JobType.class);
        private final List<BlockPos> chests = new ArrayList<>(); // delivery targets
        private boolean dirty;

        ChunkJobCache(long scanTime) {
            this.scanTime = scanTime;
        }

        /** Game time of the scan. */
        long scanTime() {
            return scanTime;
        }

        /** Job positions found, by type. */
        Map<JobType, List<BlockPos>> jobs() {
            return jobs;
        }

        void addJob(JobType type, BlockPos pos) {
            jobs.computeIfAbsent(type, k -> new ArrayList<>()).add(pos);
        }

        /** Chests seen in the scan. */
        List<BlockPos> chests() {
            return chests;
        }

        void addChest(BlockPos pos) {
            chests.add(pos);
        }

        /** Whether something in the chunk changed since, so it needs scanning again. */
        boolean isDirty() {
            return dirty;
        }

        void markDirty() {
            dirty = true;
        }
    }
}
