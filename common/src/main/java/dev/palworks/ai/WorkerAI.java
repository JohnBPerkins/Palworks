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
import dev.palworks.job.StepRunner;
import dev.palworks.manager.JobManager;
import dev.palworks.reservation.ReservationId;
import dev.palworks.suitability.Suitability;
import dev.palworks.tree.TreeDetector;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
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

    // How stale a paused job's reservation may get before it's refreshed (well under the 30s expiry).
    private static final long PAUSED_RESERVATION_REFRESH_MS = 10_000;

    // How often (ticks) a walking worker re-checks that its job still exists (staggered per entity).
    private static final int JOB_RECHECK_INTERVAL = 10;

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

        // Before the enabled check: a disabled worker still shows what it's carrying.
        showCarriedItem(entity, WorkerInventoryManager.getOrCreate(pokemon));

        // New workers start enabled (see WorkerState), so a disabled state means it was turned off
        // on purpose (/palworks worker disable). Respect that instead of re-enabling it.
        if (!state.isEnabled()) {
            return false;
        }

        // Fainted: it can't work until it's healed. Its job has already been handed over (with any
        // progress) by JobManager.registerWorker, which stops registering fainted workers.
        if (dev.palworks.worker.WorkerEligibility.isFainted(pokemon)) {
            return false;
        }

        // In a battle (or evolving): pause. It keeps its job, and the step it reached, for however
        // long the battle takes, then carries on (registerWorker gives it no new jobs meanwhile).
        ReservationId paused = state.getCurrentReservation();
        if (dev.palworks.worker.WorkerEligibility.isBusy(entity)) {
            if (paused != null) {
                pauseJob(entity, level, state, paused);
            }
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
            // Don't expire if worker is actively working, or has already done part of the job
            boolean isActivelyWorking = state.isWorking();

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
     * Keep a busy worker's job paused: the step it was on starts over when it's back (its effect
     * hadn't happened yet), the reservation is kept fresh so it doesn't expire however long the
     * battle takes, and the worker isn't steered. Its walk to the job is stopped, but only if that's
     * still where it's headed: Cobblemon moves it into its battle position, and that's left alone.
     */
    private static void pauseJob(Mob entity, Level level, WorkerState state, ReservationId reservation) {
        if (state.getStepTicks() > 0) {
            state.restartStep();
        }
        if (reservation.isExpired(PAUSED_RESERVATION_REFRESH_MS)) {
            state.setCurrentReservation(new ReservationId(reservation.getStationPos(), reservation.getJobType()));
        }
        BlockPos heading = entity.getNavigation().getTargetPos();
        if (heading != null) {
            BlockPos standPos = WorkPositions.standingPosFor(
                level, reservation.getStationPos(), reservation.getJobType(), entity);
            if (heading.equals(standPos)) {
                entity.getNavigation().stop();
            }
        }
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
            // The job itself may be gone (e.g. its tree was chopped, leaving nothing to stand by):
            // drop it as gone, not as unreachable, which would wrongly remember the spot.
            if (level instanceof ServerLevel goneLevel
                    && !state.hasJobProgress()
                    && !JobManager.isJobStillThere(level, reservation.getJobType(), targetPos)) {
                JobManager.getInstance(goneLevel).onJobInvalidated(pokemon, reservation, level);
                state.clearReservation();
                state.resetPathFailures();
                WorkerStateManager.save(pokemon, state);
                return false;
            }
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

        // En route, make sure the job still exists, for every job type: a crop can be harvested, a
        // tree chopped, a chest emptied, a station broken while the worker walks there; without this
        // it finishes the walk for nothing. Checked every JOB_RECHECK_INTERVAL ticks (staggered), and
        // only before arrival, so it can't disturb an in-progress job (e.g. a repeating furnace,
        // which we light ourselves and would otherwise read as "not needing fuel").
        if (distanceSq > ARRIVAL_DISTSQ
                && level instanceof ServerLevel validationLevel
                && (level.getGameTime() + entity.getId()) % JOB_RECHECK_INTERVAL == 0
                // Past its first step, the job's original target may be gone by design (a chopped
                // tree still needs replanting), so only check jobs that haven't started
                && !state.hasJobProgress()
                && !JobManager.isJobStillThere(level, reservation.getJobType(), targetPos)) {
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
            if (DEBUG && !state.isWorking()) {
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
            if (DEBUG && !state.isWorking() && entity.getNavigation().isDone()) {
                double distance = Math.sqrt(distanceSq);
                Palworks.LOGGER.info("{} navigating to {} job at {} (distance: {} blocks)",
                    pokemon.getSpecies().getName(), reservation.getJobType(), targetPos,
                    String.format("%.1f", distance));
            }

            // A step only counts while standing at the job: if the worker was moved off it (pushed,
            // paused for a battle, handed a job mid-step), the step it was on starts over.
            if (state.getStepTicks() > 0) {
                state.restartStep();
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
     * Perform one tick of work at the job location: runs the current step of the job's
     * {@link JobHandler#steps()}. A step's {@code start} runs on its first tick (where its animation
     * goes), and its {@code finish} (its effect) once it has lasted its ticks; then the job moves on
     * to the next step. Returns true when the last step is done (and the job doesn't repeat).
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

        return switch (StepRunner.tick(handler.steps(), state, ctx, handler::repeatAfterComplete)) {
            case WORKING -> false;
            case REPEATING -> {
                // Some jobs (e.g. lighting a furnace that has more to smelt) go round again.
                // Refresh the reservation with a new timestamp to prevent expiration.
                state.setCurrentReservation(new ReservationId(
                    reservation.getStationPos(), reservation.getJobType()));
                yield false;
            }
            case FINISHED -> true;
        };
    }

    // ============================================================
    // Job handlers (strategy per JobType)
    // ============================================================

    /**
     * Strategy for executing one {@link JobType} at the work site: a list of timed {@link Step}s.
     * Add a job type by registering a handler in {@link #HANDLERS} rather than editing performWork().
     */
    private interface JobHandler {
        /** The job's steps, in order. The shared code walks the worker there and runs them. */
        List<Step> steps();
        /** If true when the last step is done, start the steps again instead of finishing. */
        default boolean repeatAfterComplete(JobContext ctx) { return false; }
    }

    /**
     * One timed step of a job. The worker spends {@code ticks} on it at the job: {@code start} runs
     * on the first tick (where the step's animation goes), and {@code finish} when it's over: the
     * step's effect. Effects only happen in {@code finish}, so a step is all-or-nothing: if the
     * worker leaves (pushed away, paused, the game reloads) the step simply restarts. The step
     * reached is saved, so the job carries on from it, even with a different worker (handed over).
     */
    private record Step(String name, int ticks, StepStart onStart, StepFinish onFinish)
            implements StepRunner.TimedStep<JobContext> {
        @Override public void start(JobContext ctx) { onStart.run(ctx); }
        @Override public boolean finish(JobContext ctx) { return onFinish.run(ctx); }

        static Step of(String name, int ticks, StepFinish finish) {
            return new Step(name, ticks, ctx -> {}, finish);
        }
        /** A step that only takes time (e.g. a pause), with no effect. */
        static Step pause(String name, int ticks) {
            return of(name, ticks, ctx -> true);
        }
    }

    private interface StepStart { void run(JobContext ctx); }

    /** A step's effect. Return false to end the job here (e.g. there turned out to be nothing to do). */
    private interface StepFinish { boolean run(JobContext ctx); }

    /** Wrap a void effect as a step finish that always lets the job continue. */
    private static StepFinish effect(java.util.function.Consumer<JobContext> action) {
        return ctx -> {
            action.accept(ctx);
            return true;
        };
    }

    /** Everything a {@link JobHandler} needs to act at the work site. */
    private record JobContext(Pokemon pokemon, Mob entity, Level level,
                              WorkerState state, ReservationId reservation) {
        BlockPos pos() { return reservation.getStationPos(); }
        WorkerInventory inventory() { return WorkerInventoryManager.getOrCreate(pokemon); }
        String name() { return pokemon.getSpecies().getName(); }
        /** Data steps leave for later steps (saved, and handed over with the job). */
        net.minecraft.nbt.CompoundTag data() { return state.getJobData(); }
    }

    private static final Map<JobType, JobHandler> HANDLERS = new EnumMap<>(JobType.class);
    // Unknown/unimplemented jobs: stand for 2s and do nothing.
    private static final JobHandler DEFAULT_HANDLER = () -> List.of(Step.pause("wait", 40));

    static {
        HANDLERS.put(JobType.FUEL_FURNACE, new FuelFurnaceHandler());
        HANDLERS.put(JobType.GET_SEEDS, new GetSeedsHandler());
        HANDLERS.put(JobType.COLLECT_DROPS, new CollectDropsHandler());
        HANDLERS.put(JobType.DELIVER_ITEMS, new DeliverItemsHandler());
        HANDLERS.put(JobType.PLANT_SEEDS, new PlantSeedsHandler());
        HANDLERS.put(JobType.HARVEST_CROPS, new HarvestCropsHandler());
        HANDLERS.put(JobType.CHOP_TREE, new ChopTreeHandler());
        // MINE_RESOURCE: worker stands 4s but does not yet break the ore (pre-existing TODO).
        HANDLERS.put(JobType.MINE_RESOURCE, () -> List.of(Step.pause("wait", 80)));
    }

    private static JobHandler handlerFor(JobType type) {
        return HANDLERS.getOrDefault(type, DEFAULT_HANDLER);
    }

    private static final class FuelFurnaceHandler implements JobHandler {
        // One cycle matches one smelting operation (200 ticks): settle, then Ember (lighting the
        // furnace when its flames reach it), then wait out the rest of the smelt.
        private final List<Step> steps = List.of(
            // Start the move a few ticks after arrival, not on the arrival tick: on the first cycle
            // the worker has just stopped walking, and starting the move during the walk→idle
            // transition gets the animation dropped. The brief settle fixes the first cycle.
            Step.pause("settle", FURNACE_EFFECT_START_TICK),
            // Swap which move by changing FURNACE_IGNITE_MOVE; no other code changes. The furnace
            // lights FURNACE_IGNITE_DELAY_TICKS into the animation, when the flames reach it.
            new Step("ember", FURNACE_IGNITE_DELAY_TICKS,
                ctx -> playMoveActionEffect(ctx, FURNACE_IGNITE_MOVE),
                effect(FuelFurnaceHandler::ignite)),
            // Re-igniting once per cycle keeps it lit across repeats: the 201-tick burn covers the
            // 200-tick gap to the next ignite.
            Step.pause("smelt", 200 - FURNACE_EFFECT_START_TICK - FURNACE_IGNITE_DELAY_TICKS));
        @Override public List<Step> steps() { return steps; }

        private static void ignite(JobContext ctx) {
            int burnDuration = dev.palworks.station.FurnaceHelper.getBurnDuration();
            dev.palworks.station.FurnaceHelper.igniteFurnace(ctx.level(), ctx.pos(), burnDuration);
            if (DEBUG) {
                Palworks.LOGGER.info("{} ignited furnace at {} for {} ticks", ctx.name(), ctx.pos(), burnDuration);
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
        private final List<Step> steps = List.of(Step.of("take seeds", 20, effect(this::work)));
        @Override public List<Step> steps() { return steps; }

        private void work(JobContext ctx) {
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
                    returnToChest(ctx.level(), targetPos, extracted, leftover);
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
                    returnToChest(ctx.level(), targetPos, extracted, leftover);
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
        private final List<Step> steps = List.of(Step.of("pick up", 20, effect(this::work)));
        @Override public List<Step> steps() { return steps; }

        private void work(JobContext ctx) {
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
        private final List<Step> steps = List.of(Step.of("deliver", 20, effect(this::work)));
        @Override public List<Step> steps() { return steps; }

        private void work(JobContext ctx) {
            BlockPos chestPos = ctx.pos();
            WorkerInventory inventory = ctx.inventory();
            if (inventory.isEmpty()) {
                return;
            }

            int totalDelivered = 0;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    int count = stack.getCount();
                    int left = addItemsToChest(ctx.level(), chestPos, stack);
                    totalDelivered += count - left;
                    // Keep whatever didn't fit (it used to be deleted when a stack only partly fit)
                    inventory.setItem(i, left == 0 ? ItemStack.EMPTY : stack.copyWithCount(left));
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
        private final List<Step> steps = List.of(Step.of("plant", 20, effect(this::work)));
        @Override public List<Step> steps() { return steps; }

        private void work(JobContext ctx) {
            BlockPos plantPos = ctx.pos();
            WorkerInventory inventory = ctx.inventory();

            // Plant a carried seed: the first held item that can be planted on this block (not just
            // the first item, which may be something else, like a harvested crop).
            ItemStack seedStack = ItemStack.EMPTY;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()
                        && dev.palworks.crop.CropAdapter.canPlantSeed(stack.getItem(), ctx.level(), plantPos.below())) {
                    seedStack = stack;
                    break;
                }
            }
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
        private final List<Step> steps = List.of(Step.of("harvest", 20, effect(this::work)));
        @Override public List<Step> steps() { return steps; }

        private void work(JobContext ctx) {
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
        // Chop (3s), then replant (1s later). The replant spots and sapling are saved in the job's
        // data at the chop, so a paused job, or one another lumberjack takes over, still replants.
        private final List<Step> steps = List.of(
            Step.of("chop", 60, ChopTreeHandler::chop),
            Step.of("replant", 20, effect(ChopTreeHandler::replant)));
        @Override public List<Step> steps() { return steps; }

        /** Bring the tree down and note where to replant. Ends the job if there's no valid tree. */
        private static boolean chop(JobContext ctx) {
            Level level = ctx.level();
            BlockPos treePos = ctx.pos();
            TreeDetector.TreeValidationResult treeResult = TreeDetector.validateTree(level, treePos);

            if (!treeResult.isValid() || treeResult.getLogPositions().isEmpty()) {
                // Not a valid tree any more (partly chopped, or it's a player build). Leave it alone:
                // breaking "just this log" is how builds that fail tree detection got griefed.
                if (DEBUG) {
                    Palworks.LOGGER.warn("{} found no valid tree at {} ({}), leaving it alone",
                        ctx.name(), treePos, treeResult.getReason());
                }
                return false;
            }

            // Get sapling type BEFORE destroying the tree (found by name from its logs or leaves;
            // null for a tree with no recognisable sapling, which is chopped but not replanted)
            net.minecraft.world.item.Item saplingItem = TreeDetector.getSaplingForTree(level, treeResult);
            BlockPos basePos = treeResult.getBasePosition();

            // Where to replant: a 2x2 trunk (dark oak, giant spruce/jungle) gets a 2x2 of saplings
            // in the same spots, anything else one at its base. Worked out while the trunk is
            // still there to look at.
            java.util.List<BlockPos> saplingSpots =
                TreeDetector.saplingPositions(level, treeResult.getLogPositions(), basePos);

            // Huge fungus canopies never decay, so they come down with the stem (found before
            // anything is broken, while the tree is still intact)
            java.util.Set<BlockPos> canopy = TreeDetector.isNetherStem(level.getBlockState(treePos))
                ? TreeDetector.findNetherCanopyToBreak(level, treeResult.getLogPositions(), treePos)
                : java.util.Set.of();

            int logsChopped = 0;
            for (BlockPos logPos : treeResult.getLogPositions()) {
                if (level.destroyBlock(logPos, true)) {
                    logsChopped++;
                }
            }
            for (BlockPos canopyPos : canopy) {
                level.destroyBlock(canopyPos, true);
            }

            if (DEBUG) {
                Palworks.LOGGER.info("{} chopped tree at {} - {} logs and {} canopy blocks destroyed, base at {}, sapling: {}",
                    ctx.name(), treePos, logsChopped, canopy.size(), basePos, saplingItem);
            }

            // For the replant step (saved with the job, so it survives a pause or a handover)
            if (saplingItem != null) {
                ctx.data().putString(DATA_SAPLING,
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(saplingItem).toString());
                int[] spots = new int[saplingSpots.size() * 3];
                for (int i = 0; i < saplingSpots.size(); i++) {
                    BlockPos spot = saplingSpots.get(i);
                    spots[i * 3] = spot.getX();
                    spots[i * 3 + 1] = spot.getY();
                    spots[i * 3 + 2] = spot.getZ();
                }
                ctx.data().putIntArray(DATA_SPOTS, spots);
            }

            // Queue a job to collect the drops
            if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                JobManager.getInstance(serverLevel).onTreeChopped(ctx.pokemon(), basePos, level);
            }
            // No sapling found: chopped, but nothing to replant
            return saplingItem != null;
        }

        /** Plant the saplings noted at the chop. The sapling is free (see onTreeChopped). */
        private static void replant(JobContext ctx) {
            net.minecraft.resources.ResourceLocation saplingId =
                net.minecraft.resources.ResourceLocation.tryParse(ctx.data().getString(DATA_SAPLING));
            net.minecraft.world.item.Item saplingItem = saplingId == null ? null
                : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(saplingId).orElse(null);
            int[] spots = ctx.data().getIntArray(DATA_SPOTS);
            if (saplingItem == null || spots.length % 3 != 0) {
                return;
            }
            // The sapling is free: the leaves still drop their usual saplings as they decay, and
            // nothing had dropped yet at the chop to pay with
            int replanted = 0;
            for (int i = 0; i < spots.length; i += 3) {
                if (TreeDetector.plantSapling(ctx.level(), new BlockPos(spots[i], spots[i + 1], spots[i + 2]), saplingItem)) {
                    replanted++;
                }
            }
            if (DEBUG) {
                Palworks.LOGGER.info("{} replanted {} of {} {}", ctx.name(), replanted, spots.length / 3, saplingItem);
            }
        }

        private static final String DATA_SAPLING = "sapling";
        private static final String DATA_SPOTS = "spots";
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
     * Show the first item the worker is carrying, without touching its real Cobblemon held item.
     *
     * <p>Cobblemon renders a Pokemon's held item if it has one (and it isn't hidden), and otherwise
     * whatever is in the entity's main hand, which Cobblemon itself never uses. So a display copy
     * goes in the main hand. Covers every way the inventory changes (jobs, the inventory screen,
     * debug commands) because it runs every tick; the comparison keeps it from resending.
     */
    private static void showCarriedItem(Mob entity, WorkerInventory inventory) {
        ItemStack carried = inventory.getFirstItem();
        if (ItemStack.isSameItemSameComponents(entity.getMainHandItem(), carried)) {
            return;
        }
        // Count doesn't matter for display, and one item keeps the copy obviously cosmetic.
        entity.setItemSlot(EquipmentSlot.MAINHAND, carried.copyWithCount(1));
        // The copy is purely visual: it must never drop (on death, or when vanilla swaps a mob's
        // equipment), or it would duplicate the item that's still in the worker's inventory.
        entity.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
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
     * Add as much of a stack to a chest as fits: first onto matching stacks, then into empty slots.
     * Doesn't modify {@code stack}. Returns how many items didn't fit (the whole count if the block
     * isn't a container).
     */
    private static int addItemsToChest(Level level, BlockPos chestPos, ItemStack stack) {
        net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(chestPos);
        if (!(blockEntity instanceof net.minecraft.world.Container container)) {
            return stack.getCount();
        }

        int remaining = stack.getCount();
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack slotStack = container.getItem(i);
            if (!slotStack.isEmpty() && ItemStack.isSameItemSameComponents(slotStack, stack)) {
                int toAdd = Math.min(slotStack.getMaxStackSize() - slotStack.getCount(), remaining);
                if (toAdd > 0) {
                    slotStack.grow(toAdd);
                    remaining -= toAdd;
                }
            }
        }
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            if (container.getItem(i).isEmpty()) {
                int toAdd = Math.min(remaining, stack.getMaxStackSize());
                container.setItem(i, stack.copyWithCount(toAdd));
                remaining -= toAdd;
            }
        }

        if (remaining < stack.getCount()) {
            container.setChanged();
        }
        return remaining;
    }

    /**
     * Put back the part of an extracted stack the worker couldn't carry (it used to be deleted),
     * dropping it next to the chest if the chest has since filled up.
     */
    private static void returnToChest(Level level, BlockPos chestPos, ItemStack extracted, int leftover) {
        if (leftover <= 0) {
            return;
        }
        int stillLeft = addItemsToChest(level, chestPos, extracted.copyWithCount(leftover));
        if (stillLeft > 0) {
            net.minecraft.world.level.block.Block.popResource(level, chestPos.above(), extracted.copyWithCount(stillLeft));
        }
    }
}
