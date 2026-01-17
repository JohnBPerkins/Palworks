package dev.palworks.ai;

import dev.palworks.Palworks;
import dev.palworks.crop.CropAdapter;
import dev.palworks.job.JobType;
import dev.palworks.reservation.ReservationId;
import dev.palworks.station.ChestHelper;
import dev.palworks.station.StationDetector;
import dev.palworks.station.StationState;
import dev.palworks.station.StationStateManager;
import dev.palworks.suitability.Suitability;
import dev.palworks.tree.TreeDetector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Scans for available jobs within a base's work radius.
 * Implements sparse scanning to avoid performance issues.
 */
public class JobScanner {
    private static final boolean DEBUG = true;
    // Sparse scanning: max blocks to check per tick
    // Increased to ensure we can reach distant blocks
    private static final int MAX_BLOCKS_PER_TICK = 256;

    private static final java.util.Random RANDOM = new java.util.Random();

    /**
     * Simplified scan for jobs without requiring a base.
     * Scans within a radius around the worker.
     *
     * @param level The world
     * @param suitability The suitability to match
     * @param scanCenter The position to start scanning from (usually worker position)
     * @param radius The radius to scan within
     * @param maxResults Maximum number of results to return
     * @return List of available job positions
     */
    public static List<JobOpportunity> scanForJobsSimple(
            Level level,
            Suitability suitability,
            BlockPos scanCenter,
            int radius,
            int maxResults
    ) {
        List<JobOpportunity> opportunities = new ArrayList<>();
        int blocksScanned = 0;

        // Convert suitability to job types
        List<JobType> matchingJobs = getJobTypesForSuitability(suitability);

        if (DEBUG) {
            Palworks.LOGGER.debug("Scanning for jobs at {} with radius {} for suitability {}", scanCenter, radius, suitability.getId());
        }

        // PHASE 1: Always check nearby blocks first (radius 0-2)
        // This ensures we don't miss jobs right next to the worker
        for (int r = 0; r <= Math.min(2, radius) && blocksScanned < MAX_BLOCKS_PER_TICK; r++) {
            for (int dx = -r; dx <= r && blocksScanned < MAX_BLOCKS_PER_TICK; dx++) {
                for (int dy = -4; dy <= 4 && blocksScanned < MAX_BLOCKS_PER_TICK; dy++) {
                    for (int dz = -r; dz <= r && blocksScanned < MAX_BLOCKS_PER_TICK; dz++) {
                        // Only check blocks on the edge of current radius
                        if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r) {
                            continue;
                        }

                        BlockPos checkPos = scanCenter.offset(dx, dy, dz);
                        blocksScanned++;

                        // Check if position is in range (simple distance check)
                        if (scanCenter.distSqr(checkPos) > radius * radius) {
                            continue;
                        }

                        // Check for matching job opportunities
                        JobOpportunity opportunity = checkPosition(level, checkPos, matchingJobs, suitability);
                        if (opportunity != null) {
                            opportunities.add(opportunity);
                            if (opportunities.size() >= maxResults) {
                                if (DEBUG) {
                                    Palworks.LOGGER.debug("Found {} jobs in nearby scan, stopping", opportunities.size());
                                }
                                return opportunities;
                            }
                        }
                    }
                }
            }
        }

        // PHASE 2: If still have scan budget, check distant blocks with random offset
        // Start from a random radius offset to avoid always scanning the same blocks
        int startRadius = 3 + RANDOM.nextInt(Math.max(1, (radius - 3) / 2)); // Random start in outer half

        if (DEBUG) {
            Palworks.LOGGER.debug("Phase 2: scanning from radius {} to {} (randomized)", startRadius, radius);
        }

        // Scan in a spiral pattern starting from random offset
        for (int r = startRadius; r <= radius && blocksScanned < MAX_BLOCKS_PER_TICK; r++) {
            for (int dx = -r; dx <= r && blocksScanned < MAX_BLOCKS_PER_TICK; dx++) {
                for (int dy = -4; dy <= 4 && blocksScanned < MAX_BLOCKS_PER_TICK; dy++) {
                    for (int dz = -r; dz <= r && blocksScanned < MAX_BLOCKS_PER_TICK; dz++) {
                        // Only check blocks on the edge of current radius
                        if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r) {
                            continue;
                        }

                        BlockPos checkPos = scanCenter.offset(dx, dy, dz);
                        blocksScanned++;

                        // Check if position is in range (simple distance check)
                        if (scanCenter.distSqr(checkPos) > radius * radius) {
                            continue;
                        }

                        // Check for matching job opportunities
                        JobOpportunity opportunity = checkPosition(level, checkPos, matchingJobs, suitability);
                        if (opportunity != null) {
                            opportunities.add(opportunity);
                            if (opportunities.size() >= maxResults) {
                                if (DEBUG) {
                                    Palworks.LOGGER.debug("Found {} jobs, stopping scan", opportunities.size());
                                }
                                return opportunities;
                            }
                        }
                    }
                }
            }
        }

        if (DEBUG) {
            Palworks.LOGGER.debug("Scanned {} blocks, found {} jobs", blocksScanned, opportunities.size());
        }

        return opportunities;
    }

    /**
     * Check a sific position for job opportunities.
     */
    @Nullable
    private static JobOpportunity checkPosition(
            Level level,
            BlockPos pos,
            List<JobType> matchingJobs,
            Suitability suitability
    ) {
        Block block = level.getBlockState(pos).getBlock();

        // Check for station-based jobs (furnaces, crafting tables, etc.)
        JobType stationJob = StationDetector.getJobType(block);
        if (stationJob != null && matchingJobs.contains(stationJob)) {
            // Special handling for furnaces (Kindling jobs)
            if (stationJob == JobType.FUEL_FURNACE) {
                if (DEBUG) {
                    Palworks.LOGGER.debug("Found furnace at {}, checking if needs fuel...", pos);
                }
                // Check if furnace needs fuel (has input but not burning)
                if (dev.palworks.station.FurnaceHelper.needsFuel(level, pos)) {
                    if (DEBUG) {
                        Palworks.LOGGER.debug("Furnace at {} is available for Kindling job!", pos);
                    }
                    return new JobOpportunity(pos, JobType.FUEL_FURNACE, suitability);
                } else {
                    if (DEBUG) {
                        Palworks.LOGGER.debug("Furnace at {} does not need fuel (already lit or no input)", pos);
                    }
                }
            } else {
                // Other stations use the station state system
                StationState station = StationStateManager.get(level, pos);
                if (station != null && station.isEnabled() && !station.isReserved()) {
                    if (station.hasQueuedJobs() || station.hasCurrentJob()) {
                        return new JobOpportunity(pos, stationJob, suitability);
                    }
                }
            }
        }

        // Check for gathering jobs (harvestable crops)
        if (suitability == Suitability.GATHERING && CropAdapter.isFullyGrown(level, pos)) {
            return new JobOpportunity(pos, JobType.HARVEST_CROPS, suitability);
        }

        // Check for planting jobs (empty farmland)
        if (suitability == Suitability.PLANTING && CropAdapter.isPlantableSurface(level, pos)) {
            // Exclude soul sand from PLANTING - that requires NETHER suitability
            if (block == Blocks.SOUL_SAND) {
                return null;
            }

            BlockPos abovePos = pos.above();
            if (level.getBlockState(abovePos).isAir()) {
                return new JobOpportunity(abovePos, JobType.PLANT_SEEDS, suitability);
            }
        }

        // Check for nether planting jobs (soul sand)
        if (suitability == Suitability.NETHER && block == Blocks.SOUL_SAND) {
            BlockPos abovePos = pos.above();
            if (level.getBlockState(abovePos).isAir()) {
                return new JobOpportunity(abovePos, JobType.PLANT_SEEDS, suitability);
            }
        }

        // Check for pickup jobs (chests with seeds for planting workers)
        // Special case: PLANTING workers can pick up seeds even without TRANSPORTING suitability
        if (suitability == Suitability.PLANTING && isChest(block)) {
            if (hasPlantableSeeds(level, pos)) {
                if (DEBUG) {
                    Palworks.LOGGER.debug("Found chest with seeds at {} for PLANTING worker", pos);
                }
                return new JobOpportunity(pos, JobType.GET_SEEDS, suitability);
            }
        }

        // Check for pickup jobs (chests with nether wart for nether workers)
        // Special case: NETHER workers can pick up nether wart even without TRANSPORTING suitability
        if (suitability == Suitability.NETHER && isChest(block)) {
            if (ChestHelper.hasItem(level, pos, Items.NETHER_WART)) {
                if (DEBUG) {
                    Palworks.LOGGER.debug("Found chest with nether wart at {} for NETHER worker", pos);
                }
                return new JobOpportunity(pos, JobType.GET_SEEDS, suitability);
            }
        }

        // Check for lumbering jobs (trees)
        // Uses TreeDetector for proper BFS-based tree validation
        if (suitability == Suitability.LUMBERING && TreeDetector.isLog(level.getBlockState(pos))) {
            if (TreeDetector.isValidTree(level, pos)) {
                return new JobOpportunity(pos, JobType.CHOP_TREE, suitability);
            }
        }

        // Check for mining jobs (ores)
        if (suitability == Suitability.MINING && isOre(block)) {
            return new JobOpportunity(pos, JobType.MINE_RESOURCE, suitability);
        }

        return null;
    }

    /**
     * Get all job types that match a given suitability.
     */
    private static List<JobType> getJobTypesForSuitability(Suitability suitability) {
        List<JobType> jobs = new ArrayList<>();

        for (JobType job : JobType.values()) {
            if (job.getRequiredSuitability() == suitability) {
                jobs.add(job);
            }
        }

        return jobs;
    }

    /**
     * Check if a block is an ore.
     */
    private static boolean isOre(Block block) {
        return block == Blocks.COAL_ORE ||
               block == Blocks.IRON_ORE ||
               block == Blocks.COPPER_ORE ||
               block == Blocks.GOLD_ORE ||
               block == Blocks.DIAMOND_ORE ||
               block == Blocks.EMERALD_ORE ||
               block == Blocks.LAPIS_ORE ||
               block == Blocks.REDSTONE_ORE ||
               block == Blocks.DEEPSLATE_COAL_ORE ||
               block == Blocks.DEEPSLATE_IRON_ORE ||
               block == Blocks.DEEPSLATE_COPPER_ORE ||
               block == Blocks.DEEPSLATE_GOLD_ORE ||
               block == Blocks.DEEPSLATE_DIAMOND_ORE ||
               block == Blocks.DEEPSLATE_EMERALD_ORE ||
               block == Blocks.DEEPSLATE_LAPIS_ORE ||
               block == Blocks.DEEPSLATE_REDSTONE_ORE ||
               block == Blocks.NETHER_GOLD_ORE ||
               block == Blocks.NETHER_QUARTZ_ORE;
    }

    /**
     * Check if a block is a chest or trapped chest.
     */
    private static boolean isChest(Block block) {
        return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST;
    }

    /**
     * Check if a chest contains any plantable seeds.
     */
    private static boolean hasPlantableSeeds(Level level, BlockPos pos) {
        // Check for common seeds
        return ChestHelper.hasItem(level, pos, Items.WHEAT_SEEDS) ||
               ChestHelper.hasItem(level, pos, Items.CARROT) ||
               ChestHelper.hasItem(level, pos, Items.POTATO) ||
               ChestHelper.hasItem(level, pos, Items.BEETROOT_SEEDS) ||
               ChestHelper.hasItem(level, pos, Items.PUMPKIN_SEEDS) ||
               ChestHelper.hasItem(level, pos, Items.MELON_SEEDS) ||
               ChestHelper.hasItem(level, pos, Items.NETHER_WART);
    }

    /**
     * Scan for nearby item entities on the ground.
     * Used by TRANSPORTING workers to pick up dropped items.
     */
    public static List<JobOpportunity> scanForItemEntities(
            Level level,
            BlockPos workerPos,
            int radius,
            int maxResults
    ) {
        List<JobOpportunity> opportunities = new ArrayList<>();

        // Search for item entities in a box around the worker
        AABB searchBox = new AABB(workerPos).inflate(radius);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, searchBox);

        for (ItemEntity itemEntity : items) {
            if (itemEntity.isAlive() && !itemEntity.getItem().isEmpty()) {
                BlockPos itemPos = itemEntity.blockPosition();
                opportunities.add(new JobOpportunity(itemPos, JobType.COLLECT_DROPS, Suitability.TRANSPORTING));

                if (opportunities.size() >= maxResults) {
                    break;
                }
            }
        }

        return opportunities;
    }

    /**
     * Scan for nearby chests to deliver items to.
     * Used by TRANSPORTING workers to store items.
     */
    public static List<JobOpportunity> scanForDeliveryChests(
            Level level,
            BlockPos workerPos,
            int radius,
            int maxResults
    ) {
        List<JobOpportunity> opportunities = new ArrayList<>();

        // Simple scan for chests in range
        for (int x = -radius; x <= radius; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos checkPos = workerPos.offset(x, y, z);
                    Block block = level.getBlockState(checkPos).getBlock();

                    if (isChest(block) && ChestHelper.canAcceptItems(level, checkPos, net.minecraft.world.item.ItemStack.EMPTY)) {
                        opportunities.add(new JobOpportunity(checkPos, JobType.DELIVER_ITEMS, Suitability.TRANSPORTING));

                        if (opportunities.size() >= maxResults) {
                            return opportunities;
                        }
                    }
                }
            }
        }

        return opportunities;
    }

    /**
     * Represents a job opportunity found during scanning.
     */
    public static class JobOpportunity {
        private final BlockPos position;
        private final JobType jobType;
        private final Suitability suitability;
        private final double priority;

        public JobOpportunity(BlockPos position, JobType jobType, Suitability suitability) {
            this(position, jobType, suitability, 1.0);
        }

        public JobOpportunity(BlockPos position, JobType jobType, Suitability suitability, double priority) {
            this.position = position;
            this.jobType = jobType;
            this.suitability = suitability;
            this.priority = priority;
        }

        public BlockPos getPosition() {
            return position;
        }

        public JobType getJobType() {
            return jobType;
        }

        public Suitability getSuitability() {
            return suitability;
        }

        public double getPriority() {
            return priority;
        }

        /**
         * Create a reservation for this job opportunity.
         */
        public ReservationId createReservation() {
            return new ReservationId(position, jobType);
        }
    }
}
