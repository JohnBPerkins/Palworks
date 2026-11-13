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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
 * the {@link JobPool} and taking out what's gone. What each chunk's scan found is kept in its
 * {@link ChunkIndex} until it's stale or something in it changes.
 */
final class JobScanner {

    // Soft cap on blocks scanned per tick. Checked between chunks (a started chunk always
    // finishes), so a tick may overshoot by up to one chunk's band. ~2 chunk-bands at 32 vertical.
    private static final int MAX_BLOCKS_SCANNED_PER_TICK = 32768;
    // 5 minutes: how often a chunk already indexed is scanned again in full. Its index is kept up to
    // date as blocks and containers change (onBlockChanged, onContainerChanged), so this only catches
    // what changes without the game saying so (a world editor writing a chunk directly)
    static final int RESCAN_TICKS = 6000;
    // What a chunk counts against MAX_BLOCKS_SCANNED_PER_TICK however little of it is scanned
    // (looking it up, its sections' palettes, replacing its cache): at most 32 a tick, enough to
    // keep 1280 fresh, so a server's many mostly empty chunks are spread over the ticks
    private static final int CHUNK_SCAN_COST = 1024;
    private static final boolean MINING_JOBS_ENABLED = false; // Flip on once MINE_RESOURCE breaks ores

    private final JobPool pool;

    // What each scanned chunk holds: its jobs and the places workers use
    private final Map<ChunkPos, ChunkIndex> chunkCaches = new ConcurrentHashMap<>();

    // How often (ticks) to drop the indexes of chunks that have unloaded
    private static final int DROP_UNLOADED_TICKS = 100;

    // Blocks that changed in indexed chunks since the last tick, and the blocks next to them whose
    // jobs can depend on them: looked at again next tick (see onBlockChanged). Changes can come from
    // off the server thread (mods), so it's a concurrent set.
    private final Set<BlockPos> changed = ConcurrentHashMap.newKeySet();

    // Blocks to look at again at a set time, with nothing changing to say so: a watered crop wanting
    // water again once its cooldown's over. Earliest first; only the server thread uses it.
    private final java.util.Queue<Recheck> rechecks =
            new java.util.PriorityQueue<>(Comparator.comparingLong(Recheck::at));

    private record Recheck(long at, BlockPos pos) {}

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

        // What changed since last tick, in chunks already scanned: just those blocks, looked at again
        updateChanged(level);

        // Get chunks near active workers, each with the Y band to scan (around worker height). One
        // already indexed but not over all of that band counts as never scanned.
        Map<ChunkPos, int[]> scanRanges = getChunkScanRangesNearWorkers(level, workers);
        ToLongFunction<ChunkPos> lastScan = chunk -> {
            int[] band = scanRanges.get(chunk);
            ChunkIndex index = chunkCaches.get(chunk);
            return index != null && !index.coversBand(band[0], band[1]) ? NEVER_SCANNED : lastScan(chunk);
        };

        for (ChunkPos chunk : dueChunks(scanRanges.keySet(), lastScan, currentTime)) {
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

    /**
     * A block changed (every change on the server comes through here: see
     * JobManager#onBlockStateChange). If its chunk is indexed, it and the blocks whose jobs can
     * depend on it are looked at again next tick: the farmland under it (planting needs air above),
     * the block above it, and the four beside it (a stem's fruit). Must stay cheap: a map lookup
     * for a chunk that isn't indexed.
     */
    void onBlockChanged(BlockPos pos) {
        if (!chunkCaches.containsKey(new ChunkPos(pos))) {
            return;
        }
        changed.add(pos.immutable());
        changed.add(pos.below());
        changed.add(pos.above());
        changed.add(pos.north());
        changed.add(pos.south());
        changed.add(pos.east());
        changed.add(pos.west());
    }

    /**
     * A block entity's contents changed (JobManager#onContainerChanged): if it's a chest or furnace
     * an index knows (seeds put in or taken out, a furnace loaded or burnt out), it's looked at again
     * next tick. Called very often (every hopper move, every furnace that's burning), so a map lookup
     * and a set lookup for anything else.
     */
    void onContainerChanged(BlockPos pos) {
        ChunkIndex index = chunkCaches.get(new ChunkPos(pos));
        if (index != null && index.holdsWork(pos)) {
            changed.add(pos.immutable());
        }
    }

    /** Look at the block at {@code pos} again at game time {@code at} (a crop watered, wanting water again then). */
    void recheckAt(BlockPos pos, long at) {
        rechecks.add(new Recheck(at, pos.immutable()));
    }

    /**
     * Look again at each block that changed (see {@link #onBlockChanged}), in its chunk's index: the
     * place it is (a chest, a trough, a bed) and the job it gives, if any. A job it gave before and
     * doesn't now leaves the pool, unless another block of the chunk still gives it.
     */
    private void updateChanged(ServerLevel level) {
        long gameTime = level.getGameTime();
        while (!rechecks.isEmpty() && rechecks.peek().at() <= gameTime) {
            changed.add(rechecks.poll().pos());
        }
        if (changed.isEmpty()) {
            return;
        }
        List<BlockPos> blocks = new ArrayList<>(changed);
        changed.removeAll(blocks);
        treeBlocksSeenThisScan.clear();
        for (BlockPos pos : blocks) {
            ChunkIndex index = chunkCaches.get(new ChunkPos(pos));
            if (index == null || index.isDirty() || !index.covers(pos.getY()) || !level.isLoaded(pos)) {
                continue; // not indexed (a neighbour over the edge), or due a full scan anyway
            }
            BlockState state = level.getBlockState(pos);
            index.removePlace(pos);
            index.addPlace(pos, state);
            JobKey before = index.removeJob(pos);
            JobKey now = null;
            JobRecord job = scanPositionForJob(level, pos, state);
            if (job != null) {
                now = JobKey.of(job);
                index.addJob(pos, now);
                if (!pool.isReserved(now)) {
                    pool.offer(job);
                }
            }
            if (before != null && !before.equals(now) && !index.hasJob(before)) {
                pool.removeScanned(before);
            }
        }
    }

    /** {@link #dueChunks}' "last scanned" for a chunk never scanned, or changed since its scan. */
    static final long NEVER_SCANNED = Long.MIN_VALUE;

    /** When a chunk was last scanned, or {@link #NEVER_SCANNED} if it never was or it's changed since. */
    private long lastScan(ChunkPos chunk) {
        ChunkIndex cache = chunkCaches.get(chunk);
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
            if (last == NEVER_SCANNED || now - last >= RESCAN_TICKS) {
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
            // Round its home too (its pasture block), where its troughs and beds are, so
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
    private int scanChunkForJobs(ServerLevel level, ChunkPos chunkPos, int wantMinY, int wantMaxY) {
        ChunkIndex previous = chunkCaches.get(chunkPos);
        // Whole sections, with one more each way, so a worker stepping up or down a block doesn't
        // ask for a band this scan doesn't cover; and never less than the last scan covered
        int scanMinY = Math.max(
                level.getMinBuildHeight(),
                SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(wantMinY) - 1));
        int scanMaxY = Math.min(
                level.getMaxBuildHeight(),
                SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(wantMaxY - 1) + 2));
        if (previous != null) {
            scanMinY = Math.min(scanMinY, previous.minY());
            scanMaxY = Math.max(scanMaxY, previous.maxY());
        }
        ChunkIndex cache = new ChunkIndex(level.getGameTime(), scanMinY, scanMaxY);
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

                        // Remember the places workers use (chests, troughs, beds), so
                        // delivery, eating and sleeping can pick one without a block search
                        cache.addPlace(pos.immutable(), state);

                        // Check for job opportunities at this position
                        JobRecord job = scanPositionForJob(level, pos, state);
                        // Indexed even if a worker has it: when it's done, or given back, the
                        // block's next change says whether it's still there
                        if (job != null) {
                            cache.addJob(pos.immutable(), JobKey.of(job));
                        }
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
    private void removeJobsNotFoundAgain(ChunkIndex previous, ChunkIndex current) {
        Set<JobKey> stillThere = current.jobs();
        for (JobKey job : previous.jobs()) {
            if (!stillThere.contains(job)) {
                pool.removeScanned(job);
            }
        }
    }

    /** Remove all of a chunk's available jobs (when its index itself is dropped). */
    private void removeJobsOf(ChunkIndex cache) {
        cache.jobs().forEach(pool::removeScanned);
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

        // Check for get seeds jobs (chests with farmland seeds or nether wart): one per chest, for
        // planters and Nether workers alike; what each would take is JobAssigner.seedToFetch's
        if (JobChecks.isChest(state)
                && (JobChecks.hasPlantableSeeds(level, pos) || ChestHelper.hasItem(level, pos, Items.NETHER_WART))) {
            return JobRecord.of(JobType.GET_SEEDS, pos, now);
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

    /** Whether the chunk {@code pos} is in has been scanned and is indexed. */
    boolean isIndexed(BlockPos pos) {
        return chunkCaches.containsKey(new ChunkPos(pos));
    }

    /** Whether the block at {@code pos} is in its chunk's index as a chest, trough, bed or furnace. */
    boolean isKnownPlace(BlockPos pos) {
        ChunkIndex index = chunkCaches.get(new ChunkPos(pos));
        return index != null && index.isPlace(pos);
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
                ChunkIndex cache = chunkCaches.get(new ChunkPos(baseChunkX + dx, baseChunkZ + dz));
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
                ChunkIndex cache = chunkCaches.get(new ChunkPos(x, z));
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
        return near(center, chunkRadius, ChunkIndex::chests);
    }

    /**
     * The troughs the last scans found in the square of chunks {@code chunkRadius} round
     * {@code center}'s (where workers eat). Up to a scan old: check they're still troughs.
     */
    List<BlockPos> troughsNear(BlockPos center, int chunkRadius) {
        return near(center, chunkRadius, ChunkIndex::troughs);
    }

    /**
     * The beds the last scans found in the square of chunks {@code chunkRadius} round
     * {@code center}'s (where pastured workers sleep). Up to a scan old: check they're still beds.
     */
    List<BlockPos> bedsNear(BlockPos center, int chunkRadius) {
        return near(center, chunkRadius, ChunkIndex::beds);
    }

    private List<BlockPos> near(
            BlockPos center, int chunkRadius, java.util.function.Function<ChunkIndex, Set<BlockPos>> found) {
        List<BlockPos> positions = new ArrayList<>();
        int baseChunkX = center.getX() >> 4;
        int baseChunkZ = center.getZ() >> 4;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                ChunkIndex cache = chunkCaches.get(new ChunkPos(baseChunkX + dx, baseChunkZ + dz));
                if (cache != null) {
                    positions.addAll(found.apply(cache));
                }
            }
        }
        return positions;
    }

    /**
     * Drop the indexes of chunks that have unloaded, and their jobs. A chunk's index is kept for as
     * long as it's loaded, worker near or not, so the troughs and beds by a pasture are still known
     * when its workers have wandered off. Nothing changes in an unloaded chunk (but by a world
     * editor), and one loaded again is scanned afresh when a worker's near it.
     */
    void dropUnloaded(ServerLevel level) {
        if (level.getGameTime() % DROP_UNLOADED_TICKS != 0) {
            return;
        }
        chunkCaches.entrySet().removeIf(entry -> {
            ChunkPos chunk = entry.getKey();
            boolean unloaded = !level.hasChunk(chunk.x, chunk.z);
            if (unloaded) {
                removeJobsOf(entry.getValue());
            }
            return unloaded;
        });
    }

    /**
     * What a chunk holds, as its last scan found it: its jobs, each by the block it was found from
     * (a ripe crop is its own; a pumpkin stem's is its pumpkin; a log at a tree's base, the tree's),
     * and the places workers use. Kept so the chunk isn't rescanned every tick.
     */
    private static final class ChunkIndex {
        private final long scanTime;
        // The band of heights scanned, [minY, maxY): changes outside it are left alone
        private final int minY;
        private final int maxY;
        private final Map<BlockPos, JobKey> jobsBySource = new HashMap<>();
        private final Set<BlockPos> chests = new LinkedHashSet<>(); // delivery targets
        private final Set<BlockPos> troughs = new LinkedHashSet<>(); // where workers eat
        private final Set<BlockPos> beds = new LinkedHashSet<>(); // where pastured workers sleep
        private final Set<BlockPos> furnaces = new LinkedHashSet<>(); // what kindlers light
        private boolean dirty;

        ChunkIndex(long scanTime, int minY, int maxY) {
            this.scanTime = scanTime;
            this.minY = minY;
            this.maxY = maxY;
        }

        /** Whether a block at this height is in the band scanned. */
        boolean covers(int y) {
            return y >= minY && y < maxY;
        }

        /** Whether the band scanned covers all of [minY, maxY). */
        boolean coversBand(int from, int to) {
            return from >= minY && to <= maxY;
        }

        int minY() {
            return minY;
        }

        int maxY() {
            return maxY;
        }

        /** Game time of the scan. */
        long scanTime() {
            return scanTime;
        }

        /** Every job found in the chunk. */
        Set<JobKey> jobs() {
            return new HashSet<>(jobsBySource.values());
        }

        /** A job found from the block at {@code source}. */
        void addJob(BlockPos source, JobKey job) {
            jobsBySource.put(source, job);
        }

        /** Forget the job found from the block at {@code source}, returning it (null if none). */
        @Nullable
        JobKey removeJob(BlockPos source) {
            return jobsBySource.remove(source);
        }

        /** Whether some block of the chunk gives this job. */
        boolean hasJob(JobKey job) {
            return jobsBySource.containsValue(job);
        }

        /** Remember the block at {@code pos} if it's a place workers use. */
        void addPlace(BlockPos pos, BlockState state) {
            if (JobChecks.isChest(state)) {
                chests.add(pos);
            } else if (JobChecks.isTrough(state)) {
                troughs.add(pos);
            } else if (JobChecks.isBed(state)) {
                beds.add(pos);
            } else if (state.is(JobBlockTags.FURNACES)) {
                furnaces.add(pos);
            }
        }

        /** Forget the block at {@code pos} as a place (it's changed: see addPlace for what it is now). */
        void removePlace(BlockPos pos) {
            chests.remove(pos);
            troughs.remove(pos);
            beds.remove(pos);
            furnaces.remove(pos);
        }

        /** Whether the block at {@code pos} is a chest, trough, bed or furnace. */
        boolean isPlace(BlockPos pos) {
            return chests.contains(pos) || troughs.contains(pos) || beds.contains(pos) || furnaces.contains(pos);
        }

        /** Whether the block at {@code pos} is a chest or furnace, whose contents make its job. */
        boolean holdsWork(BlockPos pos) {
            return chests.contains(pos) || furnaces.contains(pos);
        }

        /** Chests seen in the scan. */
        Set<BlockPos> chests() {
            return chests;
        }

        /** Troughs seen in the scan. */
        Set<BlockPos> troughs() {
            return troughs;
        }

        /** Beds seen in the scan. */
        Set<BlockPos> beds() {
            return beds;
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
