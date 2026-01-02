package dev.palworks.ai;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.reservation.ReservationId;
import dev.palworks.suitability.Suitability;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import net.minecraft.core.BlockPos;
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

        // Try to find new work
        return findAndReserveJob(pokemon, entity, level, state);
    }

    /**
     * Find and reserve a new job for the worker.
     */
    private static boolean findAndReserveJob(
            Pokemon pokemon,
            Mob entity,
            Level level,
            WorkerState state
    ) {
        // Get worker's suitabilities
        Set<Suitability> suitabilities = state.getSuitabilities();
        if (suitabilities.isEmpty()) {
            // No suitabilities - just return false silently (many Pokemon don't have work skills)
            return false;
        }

        BlockPos entityPos = entity.blockPosition();

        // Special handling for TRANSPORTING workers - pickup items then deliver to chests
        if (suitabilities.contains(Suitability.TRANSPORTING)) {
            boolean hasItems = !state.getWorkInventory().isEmpty();

            if (hasItems) {
                // Has items - look for chests to deliver to
                List<JobScanner.JobOpportunity> deliveryOpportunities = JobScanner.scanForDeliveryChests(
                        level,
                        entityPos,
                        WORK_RADIUS,
                        3
                );

                if (!deliveryOpportunities.isEmpty()) {
                    JobScanner.JobOpportunity opportunity = deliveryOpportunities.get(0);
                    ReservationId reservation = opportunity.createReservation();

                    state.setCurrentReservation(reservation);
                    state.setAssignedJob(opportunity.getJobType());
                    WorkerStateManager.save(pokemon, state);

                    if (!level.isClientSide && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                        serverLevel.getServer().getPlayerList().broadcastSystemMessage(
                            net.minecraft.network.chat.Component.literal("[Palworks] " + pokemon.getSpecies().getName() + " found " + opportunity.getJobType().name() + " job!"),
                            false
                        );
                    }

                    return true;
                }
            } else {
                // No items - look for item entities on ground to pick up
                List<JobScanner.JobOpportunity> pickupOpportunities = JobScanner.scanForItemEntities(
                        level,
                        entityPos,
                        WORK_RADIUS,
                        3
                );

                if (!pickupOpportunities.isEmpty()) {
                    JobScanner.JobOpportunity opportunity = pickupOpportunities.get(0);
                    ReservationId reservation = opportunity.createReservation();

                    state.setCurrentReservation(reservation);
                    state.setAssignedJob(opportunity.getJobType());
                    WorkerStateManager.save(pokemon, state);

                    if (!level.isClientSide && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                        serverLevel.getServer().getPlayerList().broadcastSystemMessage(
                            net.minecraft.network.chat.Component.literal("[Palworks] " + pokemon.getSpecies().getName() + " found " + opportunity.getJobType().name() + " job!"),
                            false
                        );
                    }

                    return true;
                }
            }
        }

        // Scan for jobs matching each suitability
        for (Suitability suitability : suitabilities) {
            // Check cooldown
            if (state.isOnCooldown(suitability)) {
                continue;
            }

            // Scan for jobs (using simplified scanForJobsSimple without base requirement)
            List<JobScanner.JobOpportunity> opportunities = JobScanner.scanForJobsSimple(
                    level,
                    suitability,
                    entityPos,
                    WORK_RADIUS,
                    3 // Max 3 results
            );

            if (!opportunities.isEmpty()) {
                // For planting jobs, check if we have the correct seeds
                // If not, find and reserve a pickup job for the correct seeds first
                for (JobScanner.JobOpportunity opportunity : opportunities) {
                    if (opportunity.getJobType() == dev.palworks.job.JobType.PLANT_SEEDS) {
                        // Check what seed is needed for this planting job
                        BlockPos plantPos = opportunity.getPosition();
                        BlockPos surfacePos = plantPos.below();
                        net.minecraft.world.level.block.Block surfaceBlock = level.getBlockState(surfacePos).getBlock();

                        net.minecraft.world.item.Item requiredSeed = null;
                        if (surfaceBlock == net.minecraft.world.level.block.Blocks.SOUL_SAND) {
                            requiredSeed = net.minecraft.world.item.Items.NETHER_WART;
                        } else if (surfaceBlock == net.minecraft.world.level.block.Blocks.FARMLAND) {
                            // For farmland, accept any farmland seed (will use what's in inventory)
                            // But reject if we only have nether wart
                            if (state.hasItemInWorkInventory(net.minecraft.world.item.Items.NETHER_WART)) {
                                continue; // Skip this farmland job, we have wrong seeds
                            }
                            requiredSeed = null; // Any farmland seed is ok
                        }

                        // Check if we have the required seed
                        if (requiredSeed != null && !state.hasItemInWorkInventory(requiredSeed)) {
                            // Don't have the required seed - need to get it first
                            // Look for a chest with this seed
                            List<JobScanner.JobOpportunity> pickupOpps = JobScanner.scanForJobsSimple(
                                level, suitability, entityPos, WORK_RADIUS, 3
                            ).stream()
                            .filter(opp -> opp.getJobType() == dev.palworks.job.JobType.PICKUP_ITEMS)
                            .toList();

                            if (!pickupOpps.isEmpty()) {
                                // Found a chest with seeds - pick those up first
                                JobScanner.JobOpportunity pickupOpp = pickupOpps.get(0);
                                ReservationId reservation = pickupOpp.createReservation();

                                state.setCurrentReservation(reservation);
                                state.setAssignedJob(pickupOpp.getJobType());
                                WorkerStateManager.save(pokemon, state);

                                if (!level.isClientSide && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                                    serverLevel.getServer().getPlayerList().broadcastSystemMessage(
                                        net.minecraft.network.chat.Component.literal("[Palworks] " + pokemon.getSpecies().getName() + " found " + pickupOpp.getJobType().name() + " job!"),
                                        false
                                    );
                                }
                                return true;
                            }
                            // No chest with required seeds - skip this planting job
                        }
                    }
                }

                // Reserve the first available job (after checking prerequisites)
                JobScanner.JobOpportunity opportunity = opportunities.get(0);
                ReservationId reservation = opportunity.createReservation();

                state.setCurrentReservation(reservation);
                state.setAssignedJob(opportunity.getJobType());
                WorkerStateManager.save(pokemon, state);

                // Send chat message if on server
                if (!level.isClientSide && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                    serverLevel.getServer().getPlayerList().broadcastSystemMessage(
                        net.minecraft.network.chat.Component.literal("[Palworks] " + pokemon.getSpecies().getName() + " found " + opportunity.getJobType().name() + " job!"),
                        false
                    );
                }

                return true;
            }
        }

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
        double distance = Math.sqrt(distanceSq);

        if (DEBUG) {
            Palworks.LOGGER.debug("{} executing job at {} (distance: {})",
                pokemon.getSpecies().getName(), targetPos, String.format("%.2f", distance));
        }

        // Check if close enough to work
        if (distanceSq <= ARRIVAL_DISTANCE * ARRIVAL_DISTANCE) {
            if (DEBUG) {
                String inventoryInfo = state.getWorkInventory().isEmpty()
                    ? "empty"
                    : state.getWorkInventoryCount() + "x " + state.getWorkInventory().getItem().toString();
                Palworks.LOGGER.debug("{} arrived at job location, performing work (ticks: {}, inventory: {})",
                    pokemon.getSpecies().getName(), state.getConsecutiveWorkTicks(), inventoryInfo);
            }

            // Keep worker stationary while working (for animations)
            entity.getNavigation().stop();
            entity.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);

            // At job location - perform work
            boolean workComplete = performWork(pokemon, entity, level, state, reservation);

            if (workComplete) {
                // Job complete - clear reservation and set cooldown
                state.clearReservation();
                state.setCooldown(reservation.getJobType().getRequiredSuitability(), JOB_COMPLETE_COOLDOWN);
                state.resetPathFailures();

                // Send chat message
                if (!level.isClientSide && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
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
            if (DEBUG) {
                Palworks.LOGGER.debug("{} navigating to job at {} (distance: {}, navDone: {})",
                    pokemon.getSpecies().getName(), targetPos, String.format("%.2f", distance), entity.getNavigation().isDone());
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

        // For PICKUP_ITEMS jobs, extract seeds from chest OR pick up items from ground
        if (reservation.getJobType() == dev.palworks.job.JobType.PICKUP_ITEMS && state.getConsecutiveWorkTicks() == 0) {
            BlockPos targetPos = reservation.getStationPos();

            // Check if this is a PLANTING worker picking up from chest, NETHER worker picking up nether wart, or TRANSPORTING worker picking up from ground
            if (state.getSuitabilities().contains(Suitability.PLANTING)) {
                // PLANTING worker - extract seeds from chest
                net.minecraft.world.item.ItemStack extracted = extractSeedsFromChest(level, targetPos, state);

                if (!extracted.isEmpty()) {
                    int leftover = state.addToWorkInventory(extracted);
                    state.syncWorkInventoryToHeldItem(pokemon);

                    if (DEBUG) {
                        Palworks.LOGGER.info("{} picked up {} {} seeds from chest at {} ({} leftover)",
                            pokemon.getSpecies().getName(),
                            extracted.getCount() - leftover,
                            extracted.getItem().toString(),
                            targetPos,
                            leftover);
                    }
                } else {
                    if (DEBUG) {
                        Palworks.LOGGER.warn("{} failed to extract seeds from chest at {}",
                            pokemon.getSpecies().getName(), targetPos);
                    }
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
                        Palworks.LOGGER.info("{} picked up {} nether wart from chest at {} ({} leftover)",
                            pokemon.getSpecies().getName(),
                            extracted.getCount() - leftover,
                            targetPos,
                            leftover);
                    }
                } else {
                    if (DEBUG) {
                        Palworks.LOGGER.warn("{} failed to extract nether wart from chest at {}",
                            pokemon.getSpecies().getName(), targetPos);
                    }
                }
            } else if (state.getSuitabilities().contains(Suitability.TRANSPORTING)) {
                // TRANSPORTING worker - pick up items from ground
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
                        Palworks.LOGGER.info("{} picked up {} items from ground at {}",
                            pokemon.getSpecies().getName(), pickedUp, targetPos);
                    }
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
            case HARVEST_CROPS, PLANT_SEEDS, PICKUP_ITEMS, DELIVER_ITEMS -> 20; // 1 second
            case CHOP_TREE -> 60; // 3 seconds
            case MINE_RESOURCE -> 80; // 4 seconds
            case FUEL_FURNACE -> 200; // 10 seconds (matches one smelting operation)
            default -> 40; // 2 seconds default
        };
    }

    /**
     * Execute the actual job action (break block, place block, etc.).
     * Note: FUEL_FURNACE ignition now happens at the start of performWork, not here.
     */
    private static void executeJobAction(Pokemon pokemon, Level level, BlockPos pos, dev.palworks.job.JobType jobType) {
        // Simplified - real implementation would handle each job type properly
        switch (jobType) {
            case FUEL_FURNACE -> {
                // Furnace was already ignited at the start of work
                // This just marks completion
            }
            case HARVEST_CROPS -> dev.palworks.crop.CropAdapter.harvestCrop(level, pos);
            case CHOP_TREE, MINE_RESOURCE -> level.destroyBlock(pos, true);
            // Other job types would be implemented here
        }
    }

    /**
     * Navigate the entity to a target position.
     * Placeholder - real implementation would use pathfinding.
     */
    private static void navigateToJob(Mob entity, BlockPos targetPos) {
        // Simplified pathfinding - just move toward target
        // Real implementation would use entity.getNavigation().moveTo(...)
        entity.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 1.0);
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
