package dev.palworks.ai;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.manager.JobManager;
import dev.palworks.reservation.ReservationId;
import dev.palworks.suitability.Suitability;
import dev.palworks.tree.TreeDetector;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Set;

/**
 * Main AI controller for Pokémon workers.
 * Handles job scanning, navigation, and work execution.
 */
public class WorkerAI {
    // Debug mode - set to true to see console logs
    private static final boolean DEBUG = true;

    // Cooldown before finding new work after completing a job (in ticks)
    private static final int JOB_COMPLETE_COOLDOWN = 60; // 3 seconds

    // How often to scan for new jobs when idle (in ticks)
    private static final int JOB_SCAN_INTERVAL = 40; // 2 seconds

    // Distance threshold to consider "arrived at job"
    private static final double ARRIVAL_DISTANCE = 1.5;

    // Work radius around the worker (no base required)
    private static final int WORK_RADIUS = 32;

    /**
     * Main tick method for worker AI.
     * Call this from the Pokémon entity's tick method.
     *
     * @param pokemon The Pokémon worker
     * @param entity The mob entity (for navigation)
     * @param level The world
     * @return true if the AI handled this tick, false otherwise
     */
    public static boolean tick(Pokemon pokemon, Mob entity, Level level) {
        // Skip eligibility check - if the entity exists and we're calling this, it's eligible enough
        // The commands already filter for owned Pokemon

        WorkerState state = WorkerStateManager.getOrCreate(pokemon);

        // Auto-enable if not already enabled
        if (!state.isEnabled()) {
            state.setEnabled(true);
            WorkerStateManager.save(pokemon, state);

            // Send chat message
            if (!level.isClientSide && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                serverLevel.getServer().getPlayerList().broadcastSystemMessage(
                    net.minecraft.network.chat.Component.literal("[Palworks] " + pokemon.getSpecies().getName() + " is now a worker!"),
                    false
                );
            }
        }

        // Check if suitabilities need recalculation
        if (state.areSuitabilitiesDirty()) {
            state.initializeSuitabilities(pokemon);
            WorkerStateManager.save(pokemon, state);
        }

        // Handle current reservation
        ReservationId reservation = state.getCurrentReservation();
        if (reservation != null) {
            if (DEBUG) {
                Palworks.LOGGER.debug("{} has active reservation: {} at {}",
                    pokemon.getSpecies().getName(), reservation.getJobType(), reservation.getStationPos());
            }

            // CRITICAL FIX: Check if reservation is impossibly far away (likely corrupted Y coordinate)
            // Normal work radius is 32 blocks, so anything over 100 blocks is suspicious
            BlockPos entityPos = entity.blockPosition();
            double distanceSq = entityPos.distSqr(reservation.getStationPos());
            if (distanceSq > 100 * 100) { // 100 block radius check
                Palworks.LOGGER.warn("{} has impossible reservation distance {} blocks (entity at {}, job at {}) - clearing corrupted reservation!",
                    pokemon.getSpecies().getName(), Math.sqrt(distanceSq), entityPos, reservation.getStationPos());
                state.clearReservation();
                WorkerStateManager.save(pokemon, state);
                return false;
            }

            // Check if reservation expired
            // Don't expire if worker is actively working (has work progress)
            boolean isActivelyWorking = state.getConsecutiveWorkTicks() > 0;
            if (!isActivelyWorking && reservation.isExpired(30000)) { // 30 second timeout
                Palworks.LOGGER.info("{} reservation expired for {}",
                    pokemon.getSpecies().getName(), reservation.getJobType());
                state.clearReservation();
                WorkerStateManager.save(pokemon, state);
                return false;
            }

            // Navigate to job and perform work
            return executeJob(pokemon, entity, level, state, reservation);
        }

        // No reservation - idle (JobManager will assign work)
        return false;
    }

    /**
     * Execute the current reserved job.
     */
    private static boolean executeJob(
            Pokemon pokemon,
            Mob entity,
            Level level,
            WorkerState state,
            ReservationId reservation
    ) {
        BlockPos targetPos = reservation.getStationPos();
        BlockPos entityPos = entity.blockPosition();
        double distanceSq = entityPos.distSqr(targetPos);

        // Check if close enough to work
        if (distanceSq <= ARRIVAL_DISTANCE * ARRIVAL_DISTANCE) {
            // Only log on first tick of work
            if (DEBUG && state.getConsecutiveWorkTicks() == 0) {
                String inventoryInfo = state.getWorkInventory().isEmpty()
                    ? "empty"
                    : state.getWorkInventoryCount() + "x " + state.getWorkInventory().getItem().toString();
                Palworks.LOGGER.info("{} arrived at {} job, starting work (inventory: {})",
                    pokemon.getSpecies().getName(), reservation.getJobType(), inventoryInfo);
            }

            // Keep worker stationary while working (for animations)
            entity.getNavigation().stop();
            entity.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);

            // At job location - perform work
            boolean workComplete = performWork(pokemon, entity, level, state, reservation);

            if (workComplete) {
                // Notify JobManager of completion (for consecutive jobs)
                if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                    JobManager.getInstance(serverLevel).onJobCompleted(pokemon, reservation, level);
                }

                // Job complete - clear reservation and set cooldown
                state.clearReservation();
                state.setCooldown(reservation.getJobType().getRequiredSuitability(), JOB_COMPLETE_COOLDOWN);
                state.resetPathFailures();

                // Send chat message
                if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                    serverLevel.getServer().getPlayerList().broadcastSystemMessage(
                        net.minecraft.network.chat.Component.literal("[Palworks] " + pokemon.getSpecies().getName() + " completed " + reservation.getJobType().name() + "!"),
                        false
                    );
                }
            }

            // IMPORTANT: Save state every tick to persist work progress!
            WorkerStateManager.save(pokemon, state);
            return true;
        } else {
            // Need to navigate to job
            // Only log when starting navigation (not every tick)
            if (DEBUG && state.getConsecutiveWorkTicks() == 0 && entity.getNavigation().isDone()) {
                double distance = Math.sqrt(distanceSq);
                Palworks.LOGGER.info("{} navigating to {} job at {} (distance: {:.1f} blocks)",
                    pokemon.getSpecies().getName(), reservation.getJobType(), targetPos, distance);
            }

            navigateToJob(entity, targetPos);

            // Track path failures for stuck detection
            if (entity.getNavigation().isDone() && distanceSq > ARRIVAL_DISTANCE * ARRIVAL_DISTANCE) {
                state.recordPathFailure();

                if (state.isStuck()) {
                    // Give up on this job and clear reservation
                    Palworks.LOGGER.warn("{} is stuck, giving up on job at {}",
                        pokemon.getSpecies().getName(), targetPos);
                    state.clearReservation();
                    state.setCooldown(reservation.getJobType().getRequiredSuitability(), JOB_COMPLETE_COOLDOWN);
                    state.resetPathFailures();
                    WorkerStateManager.save(pokemon, state);
                    return false;
                }
            }

            WorkerStateManager.save(pokemon, state);
            return true;
        }
    }

    /**
     * Perform work at the job location.
     * Returns true if work is complete, false if still in progress.
     */
    private static boolean performWork(
            Pokemon pokemon,
            Mob entity,
            Level level,
            WorkerState state,
            ReservationId reservation
    ) {
        // For FUEL_FURNACE jobs, ignite on the first tick, then maintain for duration
        if (reservation.getJobType() == dev.palworks.job.JobType.FUEL_FURNACE && state.getConsecutiveWorkTicks() == 0) {
            // Ignite the furnace immediately
            int burnDuration = dev.palworks.station.FurnaceHelper.getBurnDuration();
            boolean ignited = dev.palworks.station.FurnaceHelper.igniteFurnace(level, reservation.getStationPos(), burnDuration);

            if (DEBUG) {
                Palworks.LOGGER.info("{} ignited furnace at {} for {} ticks",
                    pokemon.getSpecies().getName(), reservation.getStationPos(), burnDuration);
            }
        }

        // For GET_SEEDS jobs, extract seeds from chest
        if (reservation.getJobType() == dev.palworks.job.JobType.GET_SEEDS && state.getConsecutiveWorkTicks() == 0) {
            BlockPos targetPos = reservation.getStationPos();

            if (DEBUG) {
                Palworks.LOGGER.info("{} starting GET_SEEDS at {} (inventory: {}, has space: {})",
                    pokemon.getSpecies().getName(), targetPos, state.getWorkInventory(), state.hasWorkInventorySpace());
            }

            // Skip extraction if inventory is full, but still complete the job
            if (!state.hasWorkInventorySpace()) {
                if (DEBUG) {
                    Palworks.LOGGER.info("{} skipping GET_SEEDS extraction - inventory already full with {}",
                        pokemon.getSpecies().getName(), state.getWorkInventory());
                }
                // Continue with normal tick cycle to complete the job
            } else {

            if (state.getSuitabilities().contains(Suitability.PLANTING)) {
                // PLANTING worker - extract seeds from chest
                net.minecraft.world.item.ItemStack extracted = extractSeedsFromChest(level, targetPos, state);

                if (!extracted.isEmpty()) {
                    int leftover = state.addToWorkInventory(extracted);
                    state.syncWorkInventoryToHeldItem(pokemon);

                    if (DEBUG) {
                        Palworks.LOGGER.info("{} got {} {} seeds from chest at {} ({} leftover, inventory now: {})",
                            pokemon.getSpecies().getName(),
                            extracted.getCount() - leftover,
                            extracted.getItem().toString(),
                            targetPos,
                            leftover,
                            state.getWorkInventory());
                    }
                } else {
                    if (DEBUG) {
                        Palworks.LOGGER.warn("{} FAILED to get seeds from chest at {} - chest might be empty, treating as complete",
                            pokemon.getSpecies().getName(), targetPos);
                    }
                    // Treat as complete even if extraction failed (chest might be empty)
                }
            } else if (state.getSuitabilities().contains(Suitability.NETHER)) {
                // NETHER worker - extract nether wart from chest
                net.minecraft.world.item.ItemStack extracted = dev.palworks.station.ChestHelper.extractItems(
                    level, targetPos, net.minecraft.world.item.Items.NETHER_WART, 64
                );

                if (!extracted.isEmpty()) {
                    int leftover = state.addToWorkInventory(extracted);
                    state.syncWorkInventoryToHeldItem(pokemon);

                    if (DEBUG) {
                        Palworks.LOGGER.info("{} got {} nether wart from chest at {} ({} leftover)",
                            pokemon.getSpecies().getName(),
                            extracted.getCount() - leftover,
                            targetPos,
                            leftover);
                    }
                } else {
                    if (DEBUG) {
                        Palworks.LOGGER.warn("{} FAILED to get nether wart from chest at {} - chest might be empty, treating as complete",
                            pokemon.getSpecies().getName(), targetPos);
                    }
                    // Treat as complete even if extraction failed
                }
            } else {
                if (DEBUG) {
                    Palworks.LOGGER.warn("{} has GET_SEEDS job but lacks PLANTING/NETHER suitability! Suitabilities: {}",
                        pokemon.getSpecies().getName(), state.getSuitabilities());
                }
                // Complete the job anyway to avoid getting stuck
            }
            } // End of hasWorkInventorySpace() else block
        }

        // For COLLECT_DROPS jobs, pick up items from ground
        if (reservation.getJobType() == dev.palworks.job.JobType.COLLECT_DROPS && state.getConsecutiveWorkTicks() == 0) {
            BlockPos targetPos = reservation.getStationPos();
            net.minecraft.world.phys.AABB searchBox = new net.minecraft.world.phys.AABB(targetPos).inflate(2.0);
            List<net.minecraft.world.entity.item.ItemEntity> items = level.getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class,
                searchBox
            );

            int pickedUp = 0;
            for (net.minecraft.world.entity.item.ItemEntity itemEntity : items) {
                if (itemEntity.isAlive() && !itemEntity.getItem().isEmpty()) {
                    net.minecraft.world.item.ItemStack stack = itemEntity.getItem();
                    int leftover = state.addToWorkInventory(stack);

                    if (leftover < stack.getCount()) {
                        // Successfully added at least some items
                        pickedUp += (stack.getCount() - leftover);
                        itemEntity.discard(); // Remove the item entity
                    }

                    if (!state.hasWorkInventorySpace()) {
                        break; // Inventory full
                    }
                }
            }

            if (pickedUp > 0) {
                state.syncWorkInventoryToHeldItem(pokemon);

                if (DEBUG) {
                    Palworks.LOGGER.info("{} collected {} dropped items from ground at {}",
                        pokemon.getSpecies().getName(), pickedUp, targetPos);
                }
            }
        }

        // For DELIVER_ITEMS jobs, deposit items into chest on first tick
        if (reservation.getJobType() == dev.palworks.job.JobType.DELIVER_ITEMS && state.getConsecutiveWorkTicks() == 0) {
            BlockPos chestPos = reservation.getStationPos();

            if (!state.getWorkInventory().isEmpty()) {
                net.minecraft.world.item.ItemStack toDeliver = state.getWorkInventory().copy();

                // Try to add items to chest
                boolean success = addItemsToChest(level, chestPos, toDeliver);

                if (success) {
                    // Clear inventory after successful delivery
                    state.clearWorkInventory();
                    state.syncWorkInventoryToHeldItem(pokemon);

                    if (DEBUG) {
                        Palworks.LOGGER.info("{} delivered {} {} to chest at {}",
                            pokemon.getSpecies().getName(),
                            toDeliver.getCount(),
                            toDeliver.getItem().toString(),
                            chestPos);
                    }
                } else {
                    if (DEBUG) {
                        Palworks.LOGGER.warn("{} failed to deliver items to chest at {}",
                            pokemon.getSpecies().getName(), chestPos);
                    }
                }
            }
        }

        // For PLANT_SEEDS jobs, plant seed on first tick
        if (reservation.getJobType() == dev.palworks.job.JobType.PLANT_SEEDS && state.getConsecutiveWorkTicks() == 0) {
            BlockPos plantPos = reservation.getStationPos();

            // Check if worker has seeds in inventory
            if (!state.getWorkInventory().isEmpty()) {
                net.minecraft.world.item.ItemStack seedStack = state.getWorkInventory();
                net.minecraft.world.item.Item seed = seedStack.getItem();

                // Plant the seed
                boolean planted = dev.palworks.crop.CropAdapter.plantSeed(seed, level, plantPos.below());

                if (planted) {
                    // Remove one seed from inventory
                    state.removeFromWorkInventory(1);

                    // Sync to held item for visual display
                    state.syncWorkInventoryToHeldItem(pokemon);

                    if (DEBUG) {
                        Palworks.LOGGER.info("{} planted {} at {} ({} seeds remaining)",
                            pokemon.getSpecies().getName(),
                            seed.toString(),
                            plantPos,
                            state.getWorkInventoryCount());
                    }
                } else {
                    if (DEBUG) {
                        Palworks.LOGGER.warn("{} failed to plant {} at {}",
                            pokemon.getSpecies().getName(), seed.toString(), plantPos);
                    }
                }
            } else {
                if (DEBUG) {
                    Palworks.LOGGER.warn("{} has no seeds to plant at {}",
                        pokemon.getSpecies().getName(), plantPos);
                }
            }
        }

        // For HARVEST_CROPS jobs, harvest on the last tick
        if (reservation.getJobType() == dev.palworks.job.JobType.HARVEST_CROPS) {
            int requiredTicks = getRequiredWorkTicks(reservation.getJobType());
            if (state.getConsecutiveWorkTicks() >= requiredTicks - 1) {
                BlockPos harvestPos = reservation.getStationPos();

                // Harvest the crop
                boolean harvested = dev.palworks.crop.CropAdapter.harvestCrop(level, harvestPos);

                if (DEBUG) {
                    if (harvested) {
                        Palworks.LOGGER.info("{} harvested crop at {}",
                            pokemon.getSpecies().getName(), harvestPos);
                    } else {
                        Palworks.LOGGER.warn("{} failed to harvest crop at {}",
                            pokemon.getSpecies().getName(), harvestPos);
                    }
                }
            }
        }

        // For CHOP_TREE jobs, break all tree logs on the last tick
        if (reservation.getJobType() == dev.palworks.job.JobType.CHOP_TREE) {
            int requiredTicks = getRequiredWorkTicks(reservation.getJobType());
            if (state.getConsecutiveWorkTicks() >= requiredTicks - 1) {
                BlockPos treePos = reservation.getStationPos();

                // Get tree validation result (includes base position and all logs)
                TreeDetector.TreeValidationResult treeResult = TreeDetector.validateTree(level, treePos);

                if (treeResult.isValid() && !treeResult.getLogPositions().isEmpty()) {
                    // Get sapling type BEFORE destroying the tree
                    net.minecraft.world.item.Item saplingItem = TreeDetector.getSaplingForTree(level, treePos);
                    BlockPos basePos = treeResult.getBasePosition();

                    int logsChopped = 0;

                    // Break each log with drops and block breaking animation
                    for (BlockPos logPos : treeResult.getLogPositions()) {
                        // destroyBlock(pos, true) drops items and plays break sound/particles
                        boolean destroyed = level.destroyBlock(logPos, true);
                        if (destroyed) {
                            logsChopped++;
                        }
                    }

                    if (DEBUG) {
                        Palworks.LOGGER.info("{} chopped tree at {} - {} logs destroyed, base at {}, sapling: {}",
                            pokemon.getSpecies().getName(), treePos, logsChopped, basePos, saplingItem);
                    }

                    // Queue consecutive jobs: collect drops + replant sapling
                    if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                        JobManager.getInstance(serverLevel).onTreeChopped(pokemon, basePos, saplingItem, level);
                    }
                } else {
                    // Tree might have been partially destroyed or invalid
                    // Just try to break the single block
                    level.destroyBlock(treePos, true);

                    if (DEBUG) {
                        Palworks.LOGGER.warn("{} could not detect full tree at {}, broke single block",
                            pokemon.getSpecies().getName(), treePos);
                    }
                }
            }
        }

        // For PLANT_SAPLING jobs, plant sapling on the last tick
        if (reservation.getJobType() == dev.palworks.job.JobType.PLANT_SAPLING) {
            int requiredTicks = getRequiredWorkTicks(reservation.getJobType());
            if (state.getConsecutiveWorkTicks() >= requiredTicks - 1) {
                BlockPos plantPos = reservation.getStationPos();

                // Get the sapling type from JobManager's pending saplings
                net.minecraft.world.item.Item saplingItem = null;
                if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                    saplingItem = JobManager.getInstance(serverLevel).consumePendingSapling(plantPos);
                }

                if (saplingItem != null) {
                    boolean planted = TreeDetector.plantSapling(level, plantPos, saplingItem);

                    if (DEBUG) {
                        if (planted) {
                            Palworks.LOGGER.info("{} planted {} sapling at {}",
                                pokemon.getSpecies().getName(), saplingItem, plantPos);
                        } else {
                            Palworks.LOGGER.warn("{} failed to plant {} sapling at {}",
                                pokemon.getSpecies().getName(), saplingItem, plantPos);
                        }
                    }
                } else {
                    if (DEBUG) {
                        Palworks.LOGGER.warn("{} has no sapling to plant at {}",
                            pokemon.getSpecies().getName(), plantPos);
                    }
                }
            }
        }

        // Track work session
        state.recordWorkTick();

        // Require worker to stay for the full duration
        int requiredTicks = getRequiredWorkTicks(reservation.getJobType());

        if (state.getConsecutiveWorkTicks() >= requiredTicks) {
            // Check if furnace still has more work to do
            if (reservation.getJobType() == dev.palworks.job.JobType.FUEL_FURNACE) {
                BlockPos furnacePos = reservation.getStationPos();

                // Check if furnace has MORE items queued (count > 1 in input slot)
                // This prevents working on the last item that's currently being processed
                if (dev.palworks.station.FurnaceHelper.hasQueuedItems(level, furnacePos)) {
                    if (DEBUG) {
                        Palworks.LOGGER.info("{} continuing - furnace still has items to smelt",
                            pokemon.getSpecies().getName());
                    }

                    // Reset work ticks to start another cycle
                    state.resetWorkSession();

                    // Refresh the reservation with a new timestamp to prevent expiration
                    ReservationId newReservation = new ReservationId(
                        reservation.getStationPos(),
                        reservation.getJobType()
                    );
                    state.setCurrentReservation(newReservation);

                    // Don't return true yet - continue working
                    return false;
                }
            }

            // Work complete - no more items or not a furnace job
            state.resetWorkSession();
            return true;
        }

        return false;
    }

    /**
     * Get the number of consecutive work ticks required for a job type.
     */
    private static int getRequiredWorkTicks(dev.palworks.job.JobType jobType) {
        // Different jobs take different amounts of time
        return switch (jobType) {
            case HARVEST_CROPS, PLANT_SEEDS, GET_SEEDS, COLLECT_DROPS, DELIVER_ITEMS, PLANT_SAPLING -> 20; // 1 second
            case CHOP_TREE -> 60; // 3 seconds
            case MINE_RESOURCE -> 80; // 4 seconds
            case FUEL_FURNACE -> 200; // 10 seconds (matches one smelting operation)
            default -> 40; // 2 seconds default
        };
    }

    /**
     * Execute the actual job action (break block, place block, etc.).
     * Note: Most job actions are now handled directly in performWork for proper timing.
     * This method is kept for any remaining edge cases.
     */
    private static void executeJobAction(Pokemon pokemon, Level level, BlockPos pos, dev.palworks.job.JobType jobType) {
        switch (jobType) {
            case FUEL_FURNACE -> {
                // Furnace ignition handled at start of performWork
            }
            case HARVEST_CROPS -> {
                // Handled in performWork on last tick
            }
            case CHOP_TREE -> {
                // Handled in performWork - breaks all tree logs
            }
            case MINE_RESOURCE -> level.destroyBlock(pos, true);
            // Other job types would be implemented here
        }
    }

    /**
     * Navigate the entity to a target position using built-in pathfinding.
     */
    private static void navigateToJob(Mob entity, BlockPos targetPos) {
        // Use normal walking speed (0.6 = 60% speed, feels more natural than 1.0 running)
        entity.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 0.6);
    }

    /**
     * Extract plantable seeds from a chest.
     * Returns the extracted ItemStack, or EMPTY if no seeds found.
     */
    private static net.minecraft.world.item.ItemStack extractSeedsFromChest(
            Level level,
            BlockPos chestPos,
            WorkerState state
    ) {
        // Try to extract each type of seed in priority order
        net.minecraft.world.item.Item[] seedPriority = {
            net.minecraft.world.item.Items.WHEAT_SEEDS,
            net.minecraft.world.item.Items.CARROT,
            net.minecraft.world.item.Items.POTATO,
            net.minecraft.world.item.Items.BEETROOT_SEEDS,
            net.minecraft.world.item.Items.PUMPKIN_SEEDS,
            net.minecraft.world.item.Items.MELON_SEEDS,
            net.minecraft.world.item.Items.NETHER_WART
        };

        for (net.minecraft.world.item.Item seed : seedPriority) {
            if (dev.palworks.station.ChestHelper.hasItem(level, chestPos, seed)) {
                // Extract up to 64 seeds (full stack)
                net.minecraft.world.item.ItemStack extracted =
                    dev.palworks.station.ChestHelper.extractItems(level, chestPos, seed, 64);

                if (!extracted.isEmpty()) {
                    return extracted;
                }
            }
        }

        return net.minecraft.world.item.ItemStack.EMPTY;
    }

    /**
     * Add items to a chest.
     * Returns true if successful.
     */
    private static boolean addItemsToChest(Level level, BlockPos chestPos, net.minecraft.world.item.ItemStack stack) {
        net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(chestPos);
        if (!(blockEntity instanceof net.minecraft.world.Container container)) {
            return false;
        }

        // Try to add items to the chest
        for (int i = 0; i < container.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack slotStack = container.getItem(i);

            if (slotStack.isEmpty()) {
                // Empty slot - put items here
                container.setItem(i, stack.copy());
                container.setChanged();
                return true;
            } else if (net.minecraft.world.item.ItemStack.isSameItemSameComponents(slotStack, stack)) {
                // Matching stack - try to merge
                int space = slotStack.getMaxStackSize() - slotStack.getCount();
                if (space > 0) {
                    int toAdd = Math.min(space, stack.getCount());
                    slotStack.grow(toAdd);
                    container.setChanged();
                    return true;
                }
            }
        }

        return false; // Chest is full
    }
}
