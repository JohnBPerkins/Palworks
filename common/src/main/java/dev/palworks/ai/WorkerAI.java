package dev.palworks.ai;

import com.bedrockk.molang.runtime.MoLangRuntime;
import com.cobblemon.mod.common.api.molang.MoLangFunctions;
import com.cobblemon.mod.common.api.moves.animations.ActionEffectContext;
import com.cobblemon.mod.common.api.moves.animations.ActionEffectTimeline;
import com.cobblemon.mod.common.api.moves.animations.ActionEffects;
import com.cobblemon.mod.common.api.moves.animations.TargetsProvider;
import com.cobblemon.mod.common.api.moves.animations.UsersProvider;
import com.cobblemon.mod.common.entity.generic.GenericBedrockEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.debug.DebugManager;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.job.JobType;
import dev.palworks.manager.JobManager;
import dev.palworks.reservation.ReservationId;
import dev.palworks.suitability.Suitability;
import dev.palworks.tree.TreeDetector;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Main AI controller for Pokémon workers.
 * Handles job scanning, navigation, and work execution.
 */
public class WorkerAI {
    // Debug mode - enable at launch with -Dpalworks.debug=true
    private static final boolean DEBUG = Palworks.DEBUG;

    // Cooldown before finding new work after completing a job (in ticks)
    private static final int JOB_COMPLETE_COOLDOWN = 60; // 3 seconds

    // How often to scan for new jobs when idle (in ticks)
    private static final int JOB_SCAN_INTERVAL = 40; // 2 seconds

    // Arrived when within this squared distance of the stand tile. The stand tile sits a block back
    // from a directional station (see WorkPositions), so this radius stays clear of the station's
    // side tiles (front-only) while keeping arrival prompt — no fidgeting to hit an exact tile.
    private static final double ARRIVAL_DISTSQ = 1.5;

    // Only count a path failure when the pathfinder stops MORE than this far short of the stand
    // tile. Closer-but-not-arrived keeps approaching instead of failing (the 30s reservation expiry
    // is the backstop), which avoids spurious "stuck" right next to the target.
    private static final double APPROACH_GIVEUP_DISTSQ = 6.25;

    // Work radius around the worker (no base required)
    private static final int WORK_RADIUS = 32;

    // How often (ticks) to flush in-progress worker state to NBT. Between flushes the state
    // lives in the WorkerStateManager cache; staggered per-entity to avoid a save spike.
    private static final int WORK_SAVE_INTERVAL = 20;

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

        // New workers start enabled (see WorkerState), so a disabled state means it was turned off
        // on purpose (/palworks worker disable). Respect that instead of re-enabling it.
        if (!state.isEnabled()) {
            return false;
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

            // Safeguard: If timestamp is 0 or very old (before 2020), the data is corrupted
            // Refresh the reservation with a new timestamp instead of expiring it
            long timestamp = reservation.getTimestamp();
            if (timestamp < 1577836800000L) { // Jan 1, 2020 in milliseconds
                Palworks.LOGGER.warn("{} has corrupted reservation timestamp ({}) for {} - refreshing",
                    pokemon.getSpecies().getName(), timestamp, reservation.getJobType());
                // Create a new reservation with fresh timestamp
                ReservationId refreshedReservation = new ReservationId(
                    reservation.getStationPos(),
                    reservation.getJobType()
                );
                state.setCurrentReservation(refreshedReservation);
                WorkerStateManager.save(pokemon, state);
                reservation = refreshedReservation;
            }

            if (!isActivelyWorking && reservation.isExpired(30000)) { // 30 second timeout
                Palworks.LOGGER.info("{} reservation expired for {}",
                    pokemon.getSpecies().getName(), reservation.getJobType());
                if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                    JobManager.getInstance(serverLevel).onJobAbandoned(pokemon, reservation, level);
                }
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

        // Resolve the exact tile to stand on (e.g. in front of a furnace's face), sized to this mon.
        BlockPos standPos = WorkPositions.standingPosFor(level, targetPos, reservation.getJobType(), entity);
        if (standPos == null) {
            // No valid place to stand to do this job (front blocked / no standable tile). Give up.
            if (DEBUG) {
                Palworks.LOGGER.warn("{} found no valid stand position for {} at {} - abandoning",
                    pokemon.getSpecies().getName(), reservation.getJobType(), targetPos);
            }
            if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                JobManager.getInstance(serverLevel).onJobAbandoned(pokemon, reservation, level);
            }
            state.clearReservation();
            state.setCooldown(reservation.getJobType().getRequiredSuitability(), JOB_COMPLETE_COOLDOWN);
            WorkerStateManager.save(pokemon, state);
            return false;
        }

        BlockPos entityPos = entity.blockPosition();
        double distanceSq = entityPos.distSqr(standPos);

        // En route, make sure the job still exists. A station can be broken, replaced, or emptied
        // while the worker walks to it; without this the worker keeps pathing to a dead job until
        // the 30s expiry. Only checked before arrival, so it can't disturb an in-progress job (e.g.
        // a repeating furnace, which we light ourselves and would otherwise read as "not needing fuel").
        if (distanceSq > ARRIVAL_DISTSQ
                && level instanceof ServerLevel validationLevel
                && !isReservedJobStillValid(level, reservation)) {
            if (DEBUG) {
                Palworks.LOGGER.info("{}'s {} job at {} no longer valid (station removed/emptied) - dropping",
                    pokemon.getSpecies().getName(), reservation.getJobType(), targetPos);
            }
            JobManager.getInstance(validationLevel).onJobInvalidated(pokemon, reservation, level);
            state.clearReservation();
            state.resetPathFailures();
            WorkerStateManager.save(pokemon, state);
            return false;
        }

        // Arrived when within ARRIVAL_DISTSQ of the stand tile. Prompt (no exact-tile fidgeting); the
        // stand tile is a block back from the station so this still excludes the station's sides.
        if (distanceSq <= ARRIVAL_DISTSQ) {
            // Only log on first tick of work
            if (DEBUG && state.getConsecutiveWorkTicks() == 0) {
                WorkerInventory inventory = WorkerInventoryManager.getOrCreate(pokemon);
                String inventoryInfo = inventory.isEmpty()
                    ? "empty"
                    : inventory.getTotalItemCount() + " items";
                Palworks.LOGGER.info("{} arrived at {} job, starting work (inventory: {})",
                    pokemon.getSpecies().getName(), reservation.getJobType(), inventoryInfo);
            }

            // Keep worker stationary while working (for animations)
            entity.getNavigation().stop();
            entity.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);

            // Face the worked block so animations/effects line up (groundwork for furnace fx, etc.)
            faceTarget(entity, targetPos);

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

                // Opt-in activity message (only players who ran /palworks debug see it)
                if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                    DebugManager.broadcastActivity(serverLevel,
                        pokemon.getSpecies().getName() + " completed " + reservation.getJobType().name());
                }
            }

            // Work progress lives in the WorkerStateManager cache; flush to NBT on completion
            // (a transition) or on a staggered heartbeat - not every tick.
            if (workComplete || (level.getGameTime() + entity.getId()) % WORK_SAVE_INTERVAL == 0) {
                WorkerStateManager.save(pokemon, state);
            }
            return true;
        } else {
            // Need to navigate to job
            // Only log when starting navigation (not every tick)
            if (DEBUG && state.getConsecutiveWorkTicks() == 0 && entity.getNavigation().isDone()) {
                double distance = Math.sqrt(distanceSq);
                Palworks.LOGGER.info("{} navigating to {} job at {} (distance: {} blocks)",
                    pokemon.getSpecies().getName(), reservation.getJobType(), targetPos,
                    String.format("%.1f", distance));
            }

            navigateToJob(entity, standPos);

            // Track path failures for stuck detection. Only count a failure when the pathfinder has
            // stopped WELL SHORT of the stand tile; if it's close but not yet on the front tile,
            // keep approaching (avoids spurious "stuck" right next to the furnace).
            if (entity.getNavigation().isDone() && distanceSq > APPROACH_GIVEUP_DISTSQ) {
                state.recordPathFailure();

                if (state.isStuck()) {
                    // Give up on this job and clear reservation
                    Palworks.LOGGER.warn("{} is stuck, giving up on job at {}",
                        pokemon.getSpecies().getName(), targetPos);
                    if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                        JobManager.getInstance(serverLevel).onJobAbandoned(pokemon, reservation, level);
                    }
                    state.clearReservation();
                    state.setCooldown(reservation.getJobType().getRequiredSuitability(), JOB_COMPLETE_COOLDOWN);
                    state.resetPathFailures();
                    WorkerStateManager.save(pokemon, state);
                    return false;
                }
            }

            // Path/idle state lives in the cache; flush periodically (staggered) for durability.
            if ((level.getGameTime() + entity.getId()) % WORK_SAVE_INTERVAL == 0) {
                WorkerStateManager.save(pokemon, state);
            }
            return true;
        }
    }

    /**
     * Perform work at the job location. Dispatches to the {@link JobHandler} for this job type:
     * {@code onStart} on the first work tick, {@code onFinish} on the last, then advances the
     * work timer. Returns true when the job is complete (and not repeating).
     */
    private static boolean performWork(
            Pokemon pokemon,
            Mob entity,
            Level level,
            WorkerState state,
            ReservationId reservation
    ) {
        JobContext ctx = new JobContext(pokemon, entity, level, state, reservation);
        JobHandler handler = handlerFor(reservation.getJobType());
        int required = handler.requiredTicks();
        int ticks = state.getConsecutiveWorkTicks();

        if (ticks == 0) {
            handler.onStart(ctx);
        }
        handler.onWorkTick(ctx, ticks);
        if (ticks >= required - 1) {
            handler.onFinish(ctx);
        }

        // Track work session
        state.recordWorkTick();

        if (state.getConsecutiveWorkTicks() >= required) {
            // Some jobs (e.g. fueling a furnace with a queue) repeat instead of completing.
            if (handler.repeatAfterComplete(ctx)) {
                state.resetWorkSession();
                // Refresh the reservation with a new timestamp to prevent expiration
                state.setCurrentReservation(new ReservationId(
                    reservation.getStationPos(), reservation.getJobType()));
                return false;
            }
            state.resetWorkSession();
            return true;
        }

        return false;
    }

    // ============================================================
    // Job handlers (strategy per JobType)
    // ============================================================

    /**
     * Strategy for executing one {@link JobType} at the work site. Add a job type by registering
     * a handler in {@link #HANDLERS} rather than editing performWork().
     */
    private interface JobHandler {
        /** Total consecutive work ticks the worker must stay to complete the job. */
        int requiredTicks();
        /** Runs once on the first work tick (consecutiveWorkTicks == 0). */
        default void onStart(JobContext ctx) {}
        /** Runs every work tick; {@code ticksElapsed} is the 0-based tick within this session. */
        default void onWorkTick(JobContext ctx, int ticksElapsed) {}
        /** Runs on the final work tick (consecutiveWorkTicks == requiredTicks - 1). */
        default void onFinish(JobContext ctx) {}
        /** If true when the timer completes, reset and work again instead of finishing. */
        default boolean repeatAfterComplete(JobContext ctx) { return false; }
    }

    /** Everything a {@link JobHandler} needs to act at the work site. */
    private record JobContext(Pokemon pokemon, Mob entity, Level level,
                              WorkerState state, ReservationId reservation) {
        BlockPos pos() { return reservation.getStationPos(); }
        WorkerInventory inventory() { return WorkerInventoryManager.getOrCreate(pokemon); }
        String name() { return pokemon.getSpecies().getName(); }
    }

    private static final Map<JobType, JobHandler> HANDLERS = new EnumMap<>(JobType.class);
    // Unknown/unimplemented jobs: stand for 2s and do nothing.
    private static final JobHandler DEFAULT_HANDLER = () -> 40;

    static {
        HANDLERS.put(JobType.FUEL_FURNACE, new FuelFurnaceHandler());
        HANDLERS.put(JobType.GET_SEEDS, new GetSeedsHandler());
        HANDLERS.put(JobType.COLLECT_DROPS, new CollectDropsHandler());
        HANDLERS.put(JobType.DELIVER_ITEMS, new DeliverItemsHandler());
        HANDLERS.put(JobType.PLANT_SEEDS, new PlantSeedsHandler());
        HANDLERS.put(JobType.HARVEST_CROPS, new HarvestCropsHandler());
        HANDLERS.put(JobType.CHOP_TREE, new ChopTreeHandler());
        HANDLERS.put(JobType.PLANT_SAPLING, new PlantSaplingHandler());
        // MINE_RESOURCE: worker stands 4s but does not yet break the ore (pre-existing TODO).
        HANDLERS.put(JobType.MINE_RESOURCE, () -> 80);
    }

    private static JobHandler handlerFor(JobType type) {
        return HANDLERS.getOrDefault(type, DEFAULT_HANDLER);
    }

    private static final class FuelFurnaceHandler implements JobHandler {
        @Override public int requiredTicks() { return 200; } // matches one smelting operation

        @Override public void onWorkTick(JobContext ctx, int ticksElapsed) {
            if (ticksElapsed == FURNACE_EFFECT_START_TICK) {
                // Start the move a few ticks after arrival, not on the arrival tick: on the first
                // cycle the worker has just stopped walking, and starting the move during the
                // walk→idle transition gets the animation dropped (later cycles play fine because
                // the worker has been standing). The brief settle delay fixes the first cycle.
                // Swap which move by changing FURNACE_IGNITE_MOVE; no other code changes.
                playMoveActionEffect(ctx, FURNACE_IGNITE_MOVE);
            } else if (ticksElapsed == FURNACE_EFFECT_START_TICK + FURNACE_IGNITE_DELAY_TICKS) {
                // Light the furnace when the move's flames reach it (FURNACE_IGNITE_DELAY_TICKS into
                // the animation) so the fire starts on impact. Re-igniting once per cycle keeps it lit
                // across repeats — the 201-tick burn covers the 200-tick gap to the next ignite, so
                // the phase offset doesn't matter for smelting continuity.
                int burnDuration = dev.palworks.station.FurnaceHelper.getBurnDuration();
                dev.palworks.station.FurnaceHelper.igniteFurnace(ctx.level(), ctx.pos(), burnDuration);
                if (DEBUG) {
                    Palworks.LOGGER.info("{} ignited furnace at {} for {} ticks", ctx.name(), ctx.pos(), burnDuration);
                }
            }
        }

        @Override public boolean repeatAfterComplete(JobContext ctx) {
            // Keep going only if the furnace still has more than the item being smelted.
            if (dev.palworks.station.FurnaceHelper.hasQueuedItems(ctx.level(), ctx.pos())) {
                if (DEBUG) {
                    Palworks.LOGGER.info("{} continuing - furnace still has items to smelt", ctx.name());
                }
                return true;
            }
            return false;
        }
    }

    private static final class GetSeedsHandler implements JobHandler {
        @Override public int requiredTicks() { return 20; }

        @Override public void onStart(JobContext ctx) {
            BlockPos targetPos = ctx.pos();
            WorkerInventory inventory = ctx.inventory();

            if (DEBUG) {
                Palworks.LOGGER.info("{} starting GET_SEEDS at {} (inventory: {} items, has space: {})",
                    ctx.name(), targetPos, inventory.getTotalItemCount(), inventory.hasSpace());
            }

            // Skip extraction if inventory is full, but still complete the job
            if (!inventory.hasSpace()) {
                if (DEBUG) {
                    Palworks.LOGGER.info("{} skipping GET_SEEDS extraction - inventory already full", ctx.name());
                }
                return;
            }

            if (ctx.state().getSuitabilities().contains(Suitability.PLANTING)) {
                ItemStack extracted = extractSeedsFromChest(ctx.level(), targetPos);
                if (!extracted.isEmpty()) {
                    int leftover = inventory.addItem(extracted);
                    if (DEBUG) {
                        Palworks.LOGGER.info("{} got {} {} seeds from chest at {} ({} leftover)",
                            ctx.name(), extracted.getCount() - leftover, extracted.getItem().toString(), targetPos, leftover);
                    }
                } else if (DEBUG) {
                    Palworks.LOGGER.warn("{} FAILED to get seeds from chest at {} - chest might be empty, treating as complete",
                        ctx.name(), targetPos);
                }
            } else if (ctx.state().getSuitabilities().contains(Suitability.NETHER)) {
                ItemStack extracted = dev.palworks.station.ChestHelper.extractItems(
                    ctx.level(), targetPos, net.minecraft.world.item.Items.NETHER_WART, 64);
                if (!extracted.isEmpty()) {
                    int leftover = inventory.addItem(extracted);
                    if (DEBUG) {
                        Palworks.LOGGER.info("{} got {} nether wart from chest at {} ({} leftover)",
                            ctx.name(), extracted.getCount() - leftover, targetPos, leftover);
                    }
                } else if (DEBUG) {
                    Palworks.LOGGER.warn("{} FAILED to get nether wart from chest at {} - chest might be empty, treating as complete",
                        ctx.name(), targetPos);
                }
            } else if (DEBUG) {
                Palworks.LOGGER.warn("{} has GET_SEEDS job but lacks PLANTING/NETHER suitability! Suitabilities: {}",
                    ctx.name(), ctx.state().getSuitabilities());
            }
        }
    }

    private static final class CollectDropsHandler implements JobHandler {
        @Override public int requiredTicks() { return 20; }

        @Override public void onStart(JobContext ctx) {
            BlockPos targetPos = ctx.pos();
            WorkerInventory inventory = ctx.inventory();
            net.minecraft.world.phys.AABB searchBox = new net.minecraft.world.phys.AABB(targetPos).inflate(2.0);
            List<net.minecraft.world.entity.item.ItemEntity> items = ctx.level().getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class, searchBox);

            int pickedUp = 0;
            for (net.minecraft.world.entity.item.ItemEntity itemEntity : items) {
                if (itemEntity.isAlive() && !itemEntity.getItem().isEmpty()) {
                    ItemStack stack = itemEntity.getItem();
                    int leftover = inventory.addItem(stack);
                    if (leftover < stack.getCount()) {
                        pickedUp += (stack.getCount() - leftover);
                        itemEntity.discard();
                    }
                    if (!inventory.hasSpace()) {
                        break;
                    }
                }
            }

            if (pickedUp > 0) {
                if (DEBUG) {
                    Palworks.LOGGER.info("{} collected {} dropped items from ground at {}",
                        ctx.name(), pickedUp, targetPos);
                }
            }
        }
    }

    private static final class DeliverItemsHandler implements JobHandler {
        @Override public int requiredTicks() { return 20; }

        @Override public void onStart(JobContext ctx) {
            BlockPos chestPos = ctx.pos();
            WorkerInventory inventory = ctx.inventory();
            if (inventory.isEmpty()) {
                return;
            }

            int totalDelivered = 0;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    ItemStack toDeliver = stack.copy();
                    if (addItemsToChest(ctx.level(), chestPos, toDeliver)) {
                        totalDelivered += stack.getCount();
                        inventory.setItem(i, ItemStack.EMPTY);
                    }
                }
            }

            if (DEBUG) {
                if (totalDelivered > 0) {
                    Palworks.LOGGER.info("{} delivered {} items to chest at {}", ctx.name(), totalDelivered, chestPos);
                } else {
                    Palworks.LOGGER.warn("{} failed to deliver items to chest at {}", ctx.name(), chestPos);
                }
            }
        }
    }

    private static final class PlantSeedsHandler implements JobHandler {
        @Override public int requiredTicks() { return 20; }

        @Override public void onStart(JobContext ctx) {
            BlockPos plantPos = ctx.pos();
            WorkerInventory inventory = ctx.inventory();
            ItemStack seedStack = inventory.getFirstItem();
            if (seedStack.isEmpty()) {
                if (DEBUG) {
                    Palworks.LOGGER.warn("{} has no seeds to plant at {}", ctx.name(), plantPos);
                }
                return;
            }

            net.minecraft.world.item.Item seed = seedStack.getItem();
            boolean planted = dev.palworks.crop.CropAdapter.plantSeed(seed, ctx.level(), plantPos.below());
            if (planted) {
                seedStack.shrink(1);
                inventory.setChanged();
                if (DEBUG) {
                    Palworks.LOGGER.info("{} planted {} at {} ({} items remaining)",
                        ctx.name(), seed.toString(), plantPos, inventory.getTotalItemCount());
                }
            } else if (DEBUG) {
                Palworks.LOGGER.warn("{} failed to plant {} at {}", ctx.name(), seed.toString(), plantPos);
            }
        }
    }

    private static final class HarvestCropsHandler implements JobHandler {
        @Override public int requiredTicks() { return 20; }

        @Override public void onFinish(JobContext ctx) {
            BlockPos harvestPos = ctx.pos();
            boolean harvested = dev.palworks.crop.CropAdapter.harvestCrop(ctx.level(), harvestPos);
            if (DEBUG) {
                if (harvested) {
                    Palworks.LOGGER.info("{} harvested crop at {}", ctx.name(), harvestPos);
                } else {
                    Palworks.LOGGER.warn("{} failed to harvest crop at {}", ctx.name(), harvestPos);
                }
            }
        }
    }

    private static final class ChopTreeHandler implements JobHandler {
        @Override public int requiredTicks() { return 60; }

        @Override public void onFinish(JobContext ctx) {
            Level level = ctx.level();
            BlockPos treePos = ctx.pos();
            TreeDetector.TreeValidationResult treeResult = TreeDetector.validateTree(level, treePos);

            if (treeResult.isValid() && !treeResult.getLogPositions().isEmpty()) {
                // Get sapling type BEFORE destroying the tree
                net.minecraft.world.item.Item saplingItem = TreeDetector.getSaplingForTree(level, treePos);
                BlockPos basePos = treeResult.getBasePosition();

                int logsChopped = 0;
                for (BlockPos logPos : treeResult.getLogPositions()) {
                    if (level.destroyBlock(logPos, true)) {
                        logsChopped++;
                    }
                }

                if (DEBUG) {
                    Palworks.LOGGER.info("{} chopped tree at {} - {} logs destroyed, base at {}, sapling: {}",
                        ctx.name(), treePos, logsChopped, basePos, saplingItem);
                }

                // Queue consecutive jobs: collect drops + replant sapling
                if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                    JobManager.getInstance(serverLevel).onTreeChopped(ctx.pokemon(), basePos, saplingItem, level);
                }
            } else if (DEBUG) {
                // Not a valid tree any more (partly chopped, or it's a player build). Leave it alone:
                // breaking "just this log" is how builds that fail tree detection got griefed.
                Palworks.LOGGER.warn("{} found no valid tree at {} ({}), leaving it alone",
                    ctx.name(), treePos, treeResult.getReason());
            }
        }
    }

    private static final class PlantSaplingHandler implements JobHandler {
        @Override public int requiredTicks() { return 20; }

        @Override public void onFinish(JobContext ctx) {
            Level level = ctx.level();
            BlockPos plantPos = ctx.pos();

            net.minecraft.world.item.Item saplingItem = null;
            if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                saplingItem = JobManager.getInstance(serverLevel).consumePendingSapling(plantPos);
            }

            if (saplingItem != null) {
                boolean planted = TreeDetector.plantSapling(level, plantPos, saplingItem);
                if (DEBUG) {
                    if (planted) {
                        Palworks.LOGGER.info("{} planted {} sapling at {}", ctx.name(), saplingItem, plantPos);
                    } else {
                        Palworks.LOGGER.warn("{} failed to plant {} sapling at {}", ctx.name(), saplingItem, plantPos);
                    }
                }
            } else if (DEBUG) {
                Palworks.LOGGER.warn("{} has no sapling to plant at {}", ctx.name(), plantPos);
            }
        }
    }

    /**
     * Navigate the entity to a target position using built-in pathfinding.
     */
    private static void navigateToJob(Mob entity, BlockPos standPos) {
        // Normal walking speed; the worker arrives when within ARRIVAL_DISTSQ of the stand tile.
        entity.getNavigation().moveTo(standPos.getX(), standPos.getY(), standPos.getZ(), 0.6);
    }

    /**
     * Turn the worker to face the worked block (body + head + yaw), so work animations and effects
     * point the right way. Called once the worker has arrived at its stand position.
     */
    private static void faceTarget(Mob entity, BlockPos targetPos) {
        double dx = (targetPos.getX() + 0.5) - entity.getX();
        double dz = (targetPos.getZ() + 0.5) - entity.getZ();
        float yaw = (float) (net.minecraft.util.Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        entity.setYRot(yaw);
        entity.setYBodyRot(yaw);
        entity.setYHeadRot(yaw);
    }

    /**
     * Whether the reserved job still exists in the world, used to drop a reservation when its
     * station is broken/replaced/emptied while the worker is en route. Only FUEL_FURNACE is
     * validated, reusing the same predicate scanning uses ({@link dev.palworks.station.FurnaceHelper#needsFuel});
     * other job types are treated as still valid (unchanged behavior).
     */
    private static boolean isReservedJobStillValid(Level level, ReservationId reservation) {
        if (reservation.getJobType() == JobType.FUEL_FURNACE) {
            return dev.palworks.station.FurnaceHelper.needsFuel(level, reservation.getStationPos());
        }
        return true;
    }

    /** Which Cobblemon move's action effect a Kindling worker performs to light a furnace. Swap this
     * one id to use a different move's animation/particles/sound — no other code changes. */
    private static final ResourceLocation FURNACE_IGNITE_MOVE = ResourceLocation.fromNamespaceAndPath("cobblemon", "ember");
    /** Ticks after arrival before the worker performs the move, to let it settle out of its walk
     * animation first (otherwise the move animation is dropped on the first smelt cycle). */
    private static final int FURNACE_EFFECT_START_TICK = 8;
    /** Ticks into the move animation at which the furnace lights, tuned so the fire starts when the
     * flames reach it (Ember's impact is ~1.75s in). Re-tune if you change FURNACE_IGNITE_MOVE. */
    private static final int FURNACE_IGNITE_DELAY_TICKS = 35;
    /** Height within the furnace block (0=bottom, 1=top) the flames aim at — low so they hit the
     * fire chamber near the base rather than the block's center. */
    private static final double FURNACE_TARGET_Y = 0.125;

    /**
     * Play a Cobblemon move's full action-effect presentation (animation + particles + sound, with
     * the move's own timing) on the worker, by driving Cobblemon's {@code ActionEffectTimeline}
     * exactly as a battle does — minus the battle context. {@code run()} broadcasts the S2C packets
     * server-side to nearby players itself, reading the user/target entities from the providers, so
     * there's nothing else to send. Move-agnostic: pass any action-effect id (the data lives in
     * Cobblemon's datapack, keyed by move name) — swap the move by changing the id at the call site.
     *
     * <p>The move's particle keyframes are built around a <em>target</em> entity (the flames travel
     * to and land on it), and a furnace is a block. So we spawn a tiny, invisible, non-saving posable
     * marker at the furnace to stand in as the target, and remove it as soon as the effect's timeline
     * finishes. This is what makes the breath aim at the furnace and the impact land there.
     *
     * <p>Fragile only in that it leans on Cobblemon internals (the {@code ActionEffects} registry,
     * {@code ActionEffectContext} shape, {@code GenericBedrockEntity}); guarded with try/catch so an
     * internals change can't break a worker tick. See .references/worker-ai.md.
     */
    private static void playMoveActionEffect(JobContext ctx, ResourceLocation actionEffectId) {
        if (!(ctx.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        GenericBedrockEntity targetMarker = null;
        try {
            ActionEffectTimeline timeline =
                ActionEffects.INSTANCE.getActionEffects().get(actionEffectId);
            if (timeline == null) {
                if (DEBUG) {
                    Palworks.LOGGER.warn("No Cobblemon action effect registered for {}", actionEffectId);
                }
                return;
            }

            BlockPos furnace = ctx.pos();
            targetMarker = new GenericBedrockEntity(serverLevel);
            targetMarker.setSavesToWorld(false);
            targetMarker.setNoGravity(true);
            targetMarker.setScale(0.01f); // effectively invisible; just a position/locator anchor
            targetMarker.moveTo(furnace.getX() + 0.5, furnace.getY() + FURNACE_TARGET_Y, furnace.getZ() + 0.5, 0.0f, 0.0f);
            serverLevel.addFreshEntity(targetMarker);

            MoLangRuntime runtime = new MoLangRuntime();
            MoLangFunctions.INSTANCE.addStandardFunctions(runtime.getEnvironment().query);
            List<Object> providers = List.of(
                new UsersProvider(ctx.entity()), new TargetsProvider(targetMarker));
            ActionEffectContext context = new ActionEffectContext(
                timeline, new HashSet<>(), providers, runtime,
                /* canBeInterrupted */ true, /* interrupted */ false, new ArrayList<>(), serverLevel);

            GenericBedrockEntity marker = targetMarker; // effectively final for the callback
            timeline.run(context).whenComplete((result, error) -> marker.discard());
        } catch (Exception e) {
            if (targetMarker != null) {
                targetMarker.discard();
            }
            Palworks.LOGGER.warn("Failed to play Cobblemon action effect {} on {}: {}",
                actionEffectId, ctx.name(), e.toString());
        }
    }

    /**
     * Extract plantable seeds from a chest.
     * Returns the extracted ItemStack, or EMPTY if no seeds found.
     */
    private static ItemStack extractSeedsFromChest(Level level, BlockPos chestPos) {
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
                ItemStack extracted = dev.palworks.station.ChestHelper.extractItems(level, chestPos, seed, 64);

                if (!extracted.isEmpty()) {
                    return extracted;
                }
            }
        }

        return ItemStack.EMPTY;
    }

    /**
     * Add items to a chest.
     * Returns true if successful.
     */
    private static boolean addItemsToChest(Level level, BlockPos chestPos, ItemStack stack) {
        net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(chestPos);
        if (!(blockEntity instanceof net.minecraft.world.Container container)) {
            return false;
        }

        // Try to add items to the chest
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slotStack = container.getItem(i);

            if (slotStack.isEmpty()) {
                // Empty slot - put items here
                container.setItem(i, stack.copy());
                container.setChanged();
                return true;
            } else if (ItemStack.isSameItemSameComponents(slotStack, stack)) {
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
