package dev.palworks.job;

import dev.palworks.Palworks;
import dev.palworks.crop.CropAdapter;
import dev.palworks.crop.Watering;
import dev.palworks.job.JobPool.JobKey;
import dev.palworks.job.WorkerRegistry.WorkerInfo;
import dev.palworks.station.ChestHelper;
import dev.palworks.station.FurnaceHelper;
import dev.palworks.suitability.Suitability;
import dev.palworks.tree.TreeDetector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;

/**
 * Finds a level's jobs: scans the chunks round its workers, a few a tick, putting what it finds in
 * the {@link JobPool} and taking out what's gone. Each chunk's scan is cached
 * ({@link ChunkJobCache}) until it's stale or something in it changes.
 */
final class JobScanner {

    // Soft cap on blocks scanned per tick. Checked between chunks (a started chunk always
    // finishes), so a tick may overshoot by up to one chunk's band. ~2 chunk-bands at 32 vertical.
    private static final int MAX_BLOCKS_SCANNED_PER_TICK = 32768;
    // 2s: max staleness of a chunk's job scan (also bounds furnace/crop discovery latency)
    static final int JOB_CACHE_LIFETIME_TICKS = 40;
    // What a chunk counts against MAX_BLOCKS_SCANNED_PER_TICK however little of it is scanned
    // (looking it up, its sections' palettes, replacing its cache): at most 32 a tick, enough to
    // keep 1280 fresh, so a server's many mostly empty chunks are spread over the ticks
    private static final int CHUNK_SCAN_COST = 1024;
    private static final boolean MINING_JOBS_ENABLED = false; // Flip on once MINE_RESOURCE breaks ores

    private final JobPool pool;

    // Chunk-based job cache (performance optimization)
    private final Map<ChunkPos, ChunkJobCache> chunkCaches = new ConcurrentHashMap<>();

    // The chunks in some worker's range at the last scan: their caches are kept (dropStaleCaches)
    private Set<ChunkPos> chunksNearWorkers = Set.of();

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
        chunksNearWorkers = scanRanges.keySet();

        for (ChunkPos chunk : dueChunks(scanRanges.keySet(), this::lastScan, currentTime)) {
            // Block budget is checked between chunks; a chunk we start always finishes.
            if (blocksScannedThisTick >= MAX_BLOCKS_SCANNED_PER_TICK) {
                break;
            }

            // Scan this chunk's worker-height band for jobs
            int[] range = scanRanges.get(chunk);
            blocksScannedThisTick += scanChunkForJobs(level, chunk, range[0], range[1]);
        }

        if (Palworks.LOGGER.isTraceEnabled() && blocksScannedThisTick > 0) {
            Palworks.LOGGER.trace(
                    "Scanned {} blocks across {} chunks ({} jobs available)",
                    blocksScannedThisTick,
                    scanRanges.size(),
                    pool.available().size());
        }
    }

    /** {@link #dueChunks}' "last scanned" for a chunk never scanned, or changed since its scan. */
    static final long NEVER_SCANNED = Long.MIN_VALUE;

    /** When a chunk was last scanned, or {@link #NEVER_SCANNED} if it never was or it's changed since. */
    private long lastScan(ChunkPos chunk) {
        ChunkJobCache cache = chunkCaches.get(chunk);
        return cache == null || cache.isDirty() ? NEVER_SCANNED : cache.scanTime();
    }

    /**
     * The chunks due a scan, most overdue first: those never scanned (or changed since their scan),
     * then those whose scan is oldest. In this order every chunk gets its turn, however many there
     * are. When more go stale than the per-tick budget gets through (a server's many bases), each
     * one's scan just gets older, all of them falling behind together. Taking them in any fixed
     * order instead scanned the same ones every time and the rest never, and a worker with any of
     * those in range was never given a job ({@link #hasScannedAround}).
     */
    static List<ChunkPos> dueChunks(Collection<ChunkPos> chunks, ToLongFunction<ChunkPos> lastScan, long now) {
        record Due(ChunkPos chunk, long lastScan) {}
        List<Due> due = new ArrayList<>();
        for (ChunkPos chunk : chunks) {
            long last = lastScan.applyAsLong(chunk);
            if (last == NEVER_SCANNED || now - last >= JOB_CACHE_LIFETIME_TICKS) {
                due.add(new Due(chunk, last));
            }
        }
        due.sort(Comparator.comparingLong(Due::lastScan));
        List<ChunkPos> ordered = new ArrayList<>(due.size());
        for (Due chunk : due) {
            ordered.add(chunk.chunk());
        }
        return ordered;
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
        int chunkRadius = WorkRange.chunkRadius();
        int verticalRadius = WorkRange.vertical(); // Y range above/below a worker to scan

        for (WorkerInfo worker : workers) {
            addScanRange(ranges, worker.position, chunkRadius, verticalRadius, worldMinY, worldMaxY);
            // Round its home too (its pasture, or its owner), where its troughs and beds are, so
            // they're still known however far it's wandered for work
            if (worker.home != null) {
                addScanRange(ranges, worker.home, chunkRadius, verticalRadius, worldMinY, worldMaxY);
            }
        }

        return ranges;
    }

    /** Add the chunks within {@code chunkRadius} of {@code center} to scan, in a band round its height. */
    private static void addScanRange(
            Map<ChunkPos, int[]> ranges,
            BlockPos center,
            int chunkRadius,
            int verticalRadius,
            int worldMinY,
            int worldMaxY) {
        int loY = Math.max(worldMinY, center.getY() - verticalRadius);
        int hiY = Math.min(worldMaxY, center.getY() + verticalRadius + 1); // exclusive
        int baseChunkX = center.getX() >> 4;
        int baseChunkZ = center.getZ() >> 4;
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

    /**
     * Scan a single chunk for job opportunities.
     */
    private int scanChunkForJobs(ServerLevel level, ChunkPos chunkPos, int scanMinY, int scanMaxY) {
        ChunkJobCache previous = chunkCaches.get(chunkPos);
        ChunkJobCache cache = new ChunkJobCache(level.getGameTime());
        treeBlocksSeenThisScan.clear();

        // Counted against the budget even if every section is skipped: otherwise empty chunks
        // were free, and all of them, scanned together, were rescanned together in one tick.
        int blocksScanned = CHUNK_SCAN_COST;

        // Scan every block in the worker-height band (16x16 x-z, every Y layer - no gaps), a chunk
        // section (16 blocks high) at a time
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        if (Palworks.LOGGER.isTraceEnabled()) {
            Palworks.LOGGER.trace(
                    "Scanning chunk {} - Y band: {} to {}, chunk coords: {} to {}, {} to {}",
                    chunkPos,
                    scanMinY,
                    scanMaxY,
                    chunkPos.getMinBlockX(),
                    chunkPos.getMaxBlockX(),
                    chunkPos.getMinBlockZ(),
                    chunkPos.getMaxBlockZ());
        }

        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        LevelChunkSection[] sections = chunk.getSections();
        for (int sectionY = SectionPos.blockToSectionCoord(scanMinY);
                sectionY <= SectionPos.blockToSectionCoord(scanMaxY - 1);
                sectionY++) {
            int index = chunk.getSectionIndexFromSectionY(sectionY);
            if (index < 0 || index >= sections.length) {
                continue;
            }
            // A section made only of air and plain terrain holds no job (JobChecks.canNeverBeWork):
            // its palette, the block types it's made of, says so without looking at its 4096 blocks.
            // That's most sections: solid ground below the surface, open sky above it.
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(state -> !JobChecks.canNeverBeWork(state))) {
                continue;
            }
            int bottom = SectionPos.sectionToBlockCoord(sectionY);
            int yFrom = Math.max(scanMinY, bottom);
            int yTo = Math.min(scanMaxY, bottom + 16);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = yFrom; y < yTo; y++) {
                        int worldX = chunkPos.getMinBlockX() + x;
                        int worldZ = chunkPos.getMinBlockZ() + z;
                        pos.set(worldX, y, worldZ);
                        blocksScanned++;

                        BlockState state = level.getBlockState(pos);

                        // Remember chests and troughs so delivery and eating can pick one without
                        // their own block search
                        if (JobChecks.isChest(state)) {
                            cache.addChest(pos.immutable());
                        } else if (JobChecks.isTrough(state)) {
                            cache.addTrough(pos.immutable());
                        } else if (JobChecks.isBed(state)) {
                            cache.addBed(pos.immutable());
                        }

                        // Check for job opportunities at this position
                        JobRecord job = scanPositionForJob(level, pos, state);
                        if (job != null && !pool.isReserved(JobKey.of(job))) {
                            if (Palworks.LOGGER.isTraceEnabled()) {
                                Palworks.LOGGER.trace(
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

        // A crop still growing, not watered lately (Watering)
        if (Watering.needsWater(level, pos)) {
            return JobRecord.of(JobType.WATER_CROPS, pos, now);
        }

        // Check for planting jobs (empty farmland, or soul sand: that's Nether work)
        if (CropAdapter.isPlantableSurface(level, pos)) {
            BlockPos abovePos = pos.above();
            if (level.getBlockState(abovePos).isAir()) {
                boolean nether = state.is(Blocks.SOUL_SAND);
                if (Palworks.LOGGER.isTraceEnabled()) {
                    Palworks.LOGGER.trace(
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
        int chunkRadius = WorkRange.chunkRadius(); // same chunks as getChunkScanRangesNearWorkers
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
        int chunkRadius = WorkRange.chunkRadius();
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
        return near(center, chunkRadius, ChunkJobCache::chests);
    }

    /**
     * The troughs the last scans found in the square of chunks {@code chunkRadius} round
     * {@code center}'s (where workers eat). Up to a scan old: check they're still troughs.
     */
    List<BlockPos> troughsNear(BlockPos center, int chunkRadius) {
        return near(center, chunkRadius, ChunkJobCache::troughs);
    }

    /**
     * The beds the last scans found in the square of chunks {@code chunkRadius} round
     * {@code center}'s (where pastured workers sleep). Up to a scan old: check they're still beds.
     */
    List<BlockPos> bedsNear(BlockPos center, int chunkRadius) {
        return near(center, chunkRadius, ChunkJobCache::beds);
    }

    private List<BlockPos> near(
            BlockPos center, int chunkRadius, java.util.function.Function<ChunkJobCache, List<BlockPos>> found) {
        List<BlockPos> positions = new ArrayList<>();
        int baseChunkX = center.getX() >> 4;
        int baseChunkZ = center.getZ() >> 4;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                ChunkJobCache cache = chunkCaches.get(new ChunkPos(baseChunkX + dx, baseChunkZ + dz));
                if (cache != null) {
                    positions.addAll(found.apply(cache));
                }
            }
        }
        return positions;
    }

    /**
     * Remove stale chunk caches (older than twice the cache lifetime) of chunks no worker is near,
     * and their jobs: nothing will rescan those, so nothing would ever notice their jobs
     * disappearing. A chunk near a worker keeps its cache until it's rescanned, however long that
     * takes: when the scan falls behind (more chunks than it gets through in a lifetime), dropping
     * those too left most of them looking never scanned, so no worker was ever given a job
     * ({@link #hasScannedAround}).
     */
    void dropStaleCaches(long gameTime) {
        chunkCaches.entrySet().removeIf(entry -> {
            ChunkJobCache cache = entry.getValue();
            boolean stale = !chunksNearWorkers.contains(entry.getKey())
                    && !cache.isDirty()
                    && (gameTime - cache.scanTime()) > JOB_CACHE_LIFETIME_TICKS * 2;
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
        private final List<BlockPos> troughs = new ArrayList<>(); // where workers eat
        private final List<BlockPos> beds = new ArrayList<>(); // where pastured workers sleep
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

        /** Troughs seen in the scan. */
        List<BlockPos> troughs() {
            return troughs;
        }

        void addTrough(BlockPos pos) {
            troughs.add(pos);
        }

        /** beds seen in the scan. */
        List<BlockPos> beds() {
            return beds;
        }

        void addBed(BlockPos pos) {
            beds.add(pos);
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
