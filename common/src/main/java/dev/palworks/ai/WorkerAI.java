package dev.palworks.ai;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.ai.path.WorkerNavigator;
import dev.palworks.animation.WorkAnimation;
import dev.palworks.animation.WorkAnimations;
import dev.palworks.animation.WorkAnimator;
import dev.palworks.animation.WorkAspectSync;
import dev.palworks.debug.DebugManager;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.job.JobType;
import dev.palworks.job.StepRunner;
import dev.palworks.manager.JobManager;
import dev.palworks.reservation.ReservationId;
import dev.palworks.suitability.Suitability;
import dev.palworks.suitability.SuitabilityDetector;
import dev.palworks.tree.TreeDetector;
import dev.palworks.worker.WorkActivity;
import dev.palworks.worker.WorkActivitySync;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Main AI controller for Pokémon workers.
 * Handles job scanning, navigation, and work execution.
 */
public class WorkerAI {
    // Debug mode - enable at launch with -Dpalworks.debug=true
    private static final boolean DEBUG = Palworks.DEBUG;

    // Cooldown before finding new work after completing a job (in ticks)
    private static final int JOB_COMPLETE_COOLDOWN = 60; // 3 seconds

    // Arrived once in its stance (see WorkStance) within this squared distance of the stand tile,
    // where it was headed (the tile, or one next to it): it doesn't fidget to hit the exact tile.
    // Anywhere else in its stance only counts once its walk has ended, so it doesn't stop short on
    // the wrong side of something (a fence round a crop) just because it's passing near enough.
    private static final double ARRIVAL_DISTSQ = 1.5;

    // Its walk has ended next to the stand tile: a diagonal neighbour, or one a block up or down. A
    // path counts as reaching a tile when it ends within a block of it, so a worker that can't fit
    // on the tile itself (a big Pokémon, a tight spot under leaves) stops next to it. If that isn't
    // in its stance, it steps in (STEP_IN_TICKS), then settles for where it is: that used to be
    // "not arrived" but also "not stuck", so it stood there until the 30s reservation expiry.
    private static final double SETTLED_DISTSQ = 3.0;
    // Most ticks a worker spends stepping from where its walk ended onto its stand tile before it
    // settles for working from where it is (something's in the way: another mob, say).
    public static final int STEP_IN_TICKS = 40;
    // How near the middle of its stand tile counts as on it (crossing its edge doesn't)
    private static final double ON_TILE_DIST = 0.35;

    // How often (ticks) to flush in-progress worker state to NBT. Between flushes the state
    // lives in the WorkerStateManager cache; staggered per-entity to avoid a save spike.
    private static final int WORK_SAVE_INTERVAL = 20;

    // How long (game ticks) a worker may hold a job without starting it before it gives it up.
    // Game time, so pausing the game doesn't count towards it.
    public static final long RESERVATION_TIMEOUT_TICKS = 30 * 20;
    // How stale a paused job's reservation may get before it's refreshed (well under the timeout).
    private static final long PAUSED_RESERVATION_REFRESH_TICKS = 10 * 20;

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
        boolean handled = tickWork(pokemon, entity, level);
        // After the tick, so they reflect where it ended up (e.g. a job dropped this tick): what
        // its owner is shown, the aspects its poser animates from, and whether the animation it's
        // playing still belongs to a step it's on.
        WorkerState state = WorkerStateManager.getOrCreate(pokemon);
        WorkActivity activity = WorkActivitySync.update(pokemon, entity, state, level.getGameTime());
        WorkAspectSync.update(pokemon, activity, level.getGameTime());
        WorkAnimator.update(entity, state);
        return handled;
    }

    private static boolean tickWork(Pokemon pokemon, Mob entity, Level level) {
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

        // Handle current reservation
        ReservationId reservation = state.getCurrentReservation();
        if (reservation != null) {
            if (DEBUG) {
                Palworks.LOGGER.debug(
                        "{} has active reservation: {} at {}",
                        pokemon.getSpecies().getName(),
                        reservation.getJobType(),
                        reservation.getStationPos());
            }

            // A saved job far outside any work range (e.g. the Pokémon was recalled and sent out
            // somewhere else) is dropped rather than walked to. Work range is 32 blocks.
            BlockPos entityPos = entity.blockPosition();
            double distanceSq = entityPos.distSqr(reservation.getStationPos());
            if (distanceSq > 100 * 100) {
                Palworks.LOGGER.warn(
                        "{} has a job {} blocks away (entity at {}, job at {}) - dropping it",
                        pokemon.getSpecies().getName(),
                        Math.sqrt(distanceSq),
                        entityPos,
                        reservation.getStationPos());
                entity.getNavigation().stop(); // dropping the job: don't keep walking to it
                WorkerNavigator.forget(entity);
                state.clearReservation();
                WorkerStateManager.save(pokemon, state);
                return false;
            }

            // Check if reservation expired
            // Don't expire if worker is actively working, or has already done part of the job
            boolean isActivelyWorking = state.isWorking();

            long now = level.getGameTime();
            if (!isActivelyWorking && reservation.isExpired(now, RESERVATION_TIMEOUT_TICKS)) {
                Palworks.LOGGER.info(
                        "{} reservation expired for {}", pokemon.getSpecies().getName(), reservation.getJobType());
                if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                    JobManager.getInstance(serverLevel).onJobAbandoned(pokemon, reservation, level);
                }
                entity.getNavigation().stop(); // dropping the job: don't keep walking to it
                WorkerNavigator.forget(entity);
                state.clearReservation();
                WorkerStateManager.save(pokemon, state);
                return false;
            }

            // Navigate to job and perform work
            return executeJob(pokemon, entity, level, state, reservation);
        }

        // No reservation - idle (JobManager will assign work), once its last job's animation is over
        return holdForAnimation(entity);
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
        long now = level.getGameTime();
        if (reservation.isExpired(now, PAUSED_RESERVATION_REFRESH_TICKS)) {
            state.setCurrentReservation(new ReservationId(reservation.getStationPos(), reservation.getJobType(), now));
        }
        if (entity.getNavigation().isDone()) {
            return;
        }
        BlockPos standPos =
                WorkPositions.standingPosFor(level, reservation.getStationPos(), reservation.getJobType(), entity);
        if (standPos != null && WorkerNavigator.isHeadingTo(entity, standPos)) {
            entity.getNavigation().stop();
            WorkerNavigator.forget(entity);
        }
    }

    /**
     * Execute the current reserved job.
     */
    private static boolean executeJob(
            Pokemon pokemon, Mob entity, Level level, WorkerState state, ReservationId reservation) {
        BlockPos targetPos = reservation.getStationPos();

        // Where it stands, from the job's stance (from its animation, which can be its species' own),
        // sized to this mon: the exact tile (e.g. in front of a furnace's face)
        JobType jobType = reservation.getJobType();
        WorkStance stance = stanceFor(jobType, entity);
        BlockPos standPos = WorkPositions.standingPosFor(level, targetPos, jobType, entity, stance);
        if (standPos == null) {
            // The job itself may be gone (e.g. its tree was chopped, leaving nothing to stand by),
            // or not one it can do any more: drop it for that, not as unreachable, which would
            // wrongly remember the spot.
            if (stopIfItCantGoOn(pokemon, entity, level, state, reservation)) {
                return false;
            }
            // No valid place to stand to do this job (front blocked / no standable tile). Give up.
            if (DEBUG) {
                Palworks.LOGGER.warn(
                        "{} found no valid stand position for {} at {} - abandoning",
                        pokemon.getSpecies().getName(),
                        reservation.getJobType(),
                        targetPos);
            }
            if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                JobManager.getInstance(serverLevel).onJobAbandoned(pokemon, reservation, level);
            }
            entity.getNavigation().stop(); // dropping the job: don't keep walking to it
            WorkerNavigator.forget(entity);
            state.clearReservation();
            state.setCooldown(reservation.getJobType().getRequiredSuitability(), JOB_COMPLETE_COOLDOWN);
            WorkerStateManager.save(pokemon, state);
            return false;
        }

        BlockPos entityPos = entity.blockPosition();
        double distanceSq = entityPos.distSqr(standPos);

        // En route, make sure the job still exists, and that it can still do it, for every job type:
        // a crop can be harvested, a tree chopped, a chest emptied, a station broken, its seeds
        // taken while the worker walks there; without this it finishes the walk for nothing. The
        // same checks as at work (see Step), for the step it's reached, every JOB_RECHECK_INTERVAL
        // ticks (staggered). At work, StepRunner asks them.
        if (distanceSq > ARRIVAL_DISTSQ
                && (level.getGameTime() + entity.getId()) % JOB_RECHECK_INTERVAL == 0
                && stopIfItCantGoOn(pokemon, entity, level, state, reservation)) {
            return false;
        }

        // A party Pokémon works near its owner: if they've moved so far off from the job it's on its
        // way to that Cobblemon would teleport it back to them once it got there, it drops the job
        // rather than head off away from them. Nothing's wrong with the job, so it isn't remembered
        // as unreachable: JobManager.registerWorker puts it back in the pool, for whoever is near
        // it (this worker too, once its owner is back).
        if (distanceSq > ARRIVAL_DISTSQ
                && !state.isWorking()
                && !state.hasJobProgress()
                && (level.getGameTime() + entity.getId()) % JOB_RECHECK_INTERVAL == 0
                && !dev.palworks.worker.WorkerEligibility.withinOwnerLeash(pokemon, level, targetPos)) {
            if (DEBUG) {
                Palworks.LOGGER.info(
                        "{}'s owner moved away from its {} job at {} - dropping",
                        pokemon.getSpecies().getName(),
                        reservation.getJobType(),
                        targetPos);
            }
            entity.getNavigation().stop(); // dropping the job: don't keep walking to it
            WorkerNavigator.forget(entity);
            state.clearReservation();
            state.resetPathFailures();
            WorkerStateManager.save(pokemon, state);
            return false;
        }

        // Arrived once it's in the job's stance (see WorkStance): near its stand tile, or wherever its
        // walk ended. Cobblemon's navigation calls a walk done a little short of the tile, so if it
        // isn't in its stance there, it steps the last bit straight in, and settles where it is if
        // something stops it.
        boolean walkEnded = entity.getNavigation().isDone() && state.hasWalkedToJob();
        double offX = entity.getX() - (standPos.getX() + 0.5);
        double offZ = entity.getZ() - (standPos.getZ() + 0.5);
        boolean onStand = distanceSq == 0 && offX * offX + offZ * offZ <= ON_TILE_DIST * ON_TILE_DIST;
        boolean inStance = WorkPositions.inStance(level, entity, targetPos, jobType, stance);
        boolean nextToStand = distanceSq <= SETTLED_DISTSQ;
        if (walkEnded && nextToStand && !onStand && !inStance && state.getStepInTicks() < STEP_IN_TICKS) {
            if (holdForAnimation(entity)) {
                return true;
            }
            state.addStepInTick();
            entity.getMoveControl()
                    .setWantedPosition(standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5, 0.6);
            return true;
        }
        boolean arrived =
                onStand || (inStance && (distanceSq <= ARRIVAL_DISTSQ || walkEnded)) || (walkEnded && nextToStand);
        if (arrived) {
            // Only log on first tick of work
            if (DEBUG && !state.isWorking()) {
                WorkerInventory inventory = WorkerInventoryManager.getOrCreate(pokemon);
                String inventoryInfo = inventory.isEmpty() ? "empty" : inventory.getTotalItemCount() + " items";
                Palworks.LOGGER.info(
                        "{} arrived at {} job, starting work (inventory: {})",
                        pokemon.getSpecies().getName(),
                        reservation.getJobType(),
                        inventoryInfo);
            }

            // Keep worker stationary while working (for animations). Only horizontally: zeroing its
            // vertical motion too stops gravity registering it as on the ground, and ground
            // navigation won't plan a path for a mob that isn't on the ground.
            entity.getNavigation().stop();
            entity.setDeltaMovement(0, entity.getDeltaMovement().y, 0);

            // Turn to face the worked block so animations/effects line up. While its last animation
            // plays out, keep facing what that was aimed at: the next job may be one it can start
            // on from where it stands (the next crop along), and it shouldn't turn to it
            // mid-animation. Its next animation waits until that's over and it's facing its work.
            BlockPos playingOut = WorkAnimator.playingOutAt(entity);
            boolean facing = turnTowards(entity, playingOut != null ? playingOut : targetPos);

            // At job location - perform work
            StepRunner.Outcome outcome =
                    performWork(pokemon, entity, level, state, reservation, playingOut == null && facing);
            if (outcome == StepRunner.Outcome.GONE || outcome == StepRunner.Outcome.UNABLE) {
                // Its job went while it worked (the furnace emptied, the crop harvested by someone
                // else), or it can't do it any more: it stopped there, without the step's effect
                stopJob(outcome, pokemon, entity, level, state, reservation);
                return false;
            }
            boolean workComplete = outcome == StepRunner.Outcome.FINISHED;

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
                    DebugManager.broadcastActivity(
                            serverLevel,
                            pokemon.getSpecies().getName() + " completed "
                                    + reservation.getJobType().name());
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
                Palworks.LOGGER.info(
                        "{} navigating to {} job at {} (distance: {} blocks)",
                        pokemon.getSpecies().getName(),
                        reservation.getJobType(),
                        targetPos,
                        String.format("%.1f", distance));
            }

            // Its last job's animation plays out where it is before it sets off for this one
            if (holdForAnimation(entity)) {
                return true;
            }

            // A step only counts while standing at the job: if the worker was moved off it (pushed,
            // paused for a battle, handed a job mid-step), the step it was on starts over.
            if (state.getStepTicks() > 0) {
                state.restartStep();
            }
            navigateToJob(entity, standPos);
            state.setWalkedToJob(true);
            state.resetStepIn();

            // Track path failures for stuck detection: its walk has ended (on the ground, so not just
            // falling or knocked about) without getting next to the stand tile, so the path can't
            // get it there. A few in a row and it gives up the job instead of standing around.
            if (entity.getNavigation().isDone() && entity.onGround() && distanceSq > SETTLED_DISTSQ) {
                state.recordPathFailure();

                if (state.isStuck()) {
                    // Give up on this job and clear reservation
                    Palworks.LOGGER.warn(
                            "{} is stuck, giving up on job at {}",
                            pokemon.getSpecies().getName(),
                            targetPos);
                    if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                        JobManager.getInstance(serverLevel).onJobAbandoned(pokemon, reservation, level);
                    }
                    entity.getNavigation().stop(); // dropping the job: don't keep walking to it
                    WorkerNavigator.forget(entity);
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
     * {@link JobHandler#steps()}. A step's animation, if it has one, plays timed to land on the
     * step's last tick, where its {@code finish} (its effect) runs; then the job moves on to the
     * next step. Returns {@link StepRunner.Outcome#FINISHED} when the last step is done (and the job
     * doesn't repeat), {@link StepRunner.Outcome#GONE} if the job went away (see
     * {@link Step#stillThere}), {@link StepRunner.Outcome#UNABLE} if the worker can't do it any more
     * (see {@link Step#canDo}), otherwise {@link StepRunner.Outcome#WORKING}.
     *
     * @param readyToAnimate whether the worker can start an animation now: it's facing its work,
     *                       and its last animation is over
     */
    private static StepRunner.Outcome performWork(
            Pokemon pokemon,
            Mob entity,
            Level level,
            WorkerState state,
            ReservationId reservation,
            boolean readyToAnimate) {
        JobContext ctx = new JobContext(pokemon, entity, level, state, reservation);
        JobHandler handler = handlerFor(reservation.getJobType());

        // A step's animation waits (just before starting) until the worker is ready for it: turned
        // to face its work, and done playing out the last one. Steps without one carry on, so a
        // furnace keeps its time.
        List<Step> steps = handler.steps();
        int index = state.getJobStep();
        if (!readyToAnimate
                && index < steps.size()
                && steps.get(index).animation() != null
                && state.getStepTicks() == steps.get(index).startTick(ctx)) {
            return StepRunner.Outcome.WORKING;
        }

        StepRunner.Outcome outcome = StepRunner.tick(steps, state, ctx, handler::repeatAfterComplete);
        if (outcome == StepRunner.Outcome.REPEATING) {
            // Some jobs (e.g. lighting a furnace that has more to smelt) go round again.
            // Refresh the reservation with a new timestamp to prevent expiration.
            state.setCurrentReservation(
                    new ReservationId(reservation.getStationPos(), reservation.getJobType(), level.getGameTime()));
            return StepRunner.Outcome.WORKING;
        }
        return outcome;
    }

    /**
     * If the worker's job can't go on at the step it's reached, stop it (see {@link #stopJob}), and
     * return true. The checks every job goes through, on the way and at work (where StepRunner asks
     * them): it's still there ({@link Step#stillThere}), and the worker can do it
     * ({@link Step#canDo}).
     */
    private static boolean stopIfItCantGoOn(
            Pokemon pokemon, Mob entity, Level level, WorkerState state, ReservationId reservation) {
        JobContext ctx = new JobContext(pokemon, entity, level, state, reservation);
        StepRunner.Outcome reason =
                StepRunner.stopReason(handlerFor(reservation.getJobType()).steps(), state, ctx);
        if (reason == null) {
            return false;
        }
        stopJob(reason, pokemon, entity, level, state, reservation);
        return true;
    }

    /**
     * Stop a job that can't go on, on the way to it or partway through. One that's gone
     * ({@link StepRunner.Outcome#GONE}) is dropped from the pool (see
     * {@link JobManager#onJobInvalidated}); one the worker can't do
     * ({@link StepRunner.Outcome#UNABLE}) is given back to it, with any progress, for a worker that
     * can (see {@link JobManager#onJobGivenBack}). Neither is remembered as unreachable: nothing's
     * wrong with the spot.
     */
    private static void stopJob(
            StepRunner.Outcome reason,
            Pokemon pokemon,
            Mob entity,
            Level level,
            WorkerState state,
            ReservationId reservation) {
        if (DEBUG) {
            Palworks.LOGGER.info(
                    "{}'s {} job at {} {} (step {}) - stopping",
                    pokemon.getSpecies().getName(),
                    reservation.getJobType(),
                    reservation.getStationPos(),
                    reason == StepRunner.Outcome.GONE ? "is gone" : "isn't one it can do any more",
                    state.getJobStep());
        }
        if (level instanceof ServerLevel serverLevel) {
            JobManager jobs = JobManager.getInstance(serverLevel);
            if (reason == StepRunner.Outcome.GONE) {
                jobs.onJobInvalidated(pokemon, reservation, level);
            } else {
                jobs.onJobGivenBack(pokemon, reservation); // before its progress is cleared
            }
        }
        entity.getNavigation().stop(); // dropping the job: don't keep walking to it
        WorkerNavigator.forget(entity);
        state.clearReservation();
        state.resetPathFailures();
        WorkerStateManager.save(pokemon, state);
    }

    // ============================================================
    // Job handlers (strategy per JobType)
    // ============================================================

    /**
     * Strategy for executing one {@link JobType} at the work site: a list of timed {@link Step}s.
     * Add a job type by registering a handler in {@link #HANDLERS} rather than editing performWork().
     */
    @FunctionalInterface
    private interface JobHandler {
        /** The job's steps, in order. The shared code walks the worker there and runs them. */
        List<Step> steps();
        /** If true when the last step is done, start the steps again instead of finishing. */
        default boolean repeatAfterComplete(JobContext ctx) {
            return false;
        }
        /**
         * Where a worker of this species stands for the whole job: the stance its animation is
         * played from (the first, if it has more than one), or {@link WorkStance#NEAR} if it has
         * none.
         */
        default WorkStance stance(@Nullable String speciesId) {
            for (Step step : steps()) {
                WorkAnimation animation = step.animationFor(speciesId);
                if (animation != null) {
                    return animation.stance();
                }
            }
            return WorkStance.NEAR;
        }
    }

    /**
     * One timed step of a job. The worker spends {@code ticks} on it at the job, and {@code finish}
     * runs when it's over: the step's effect. Effects only happen in {@code finish}, so a step is
     * all-or-nothing: if the worker leaves (pushed away, paused, the game reloads) the step simply
     * restarts, its animation with it. The step reached is saved, so the job carries on from it,
     * even with a different worker (handed over). A step that isn't {@code isNeeded} when it's
     * reached (nothing for it to do) is skipped.
     *
     * <p>A step with an {@code animation} plays it aimed at the job's block, timed so it lands as
     * {@code finish} runs; it takes at least long enough for that (see
     * {@link WorkAnimation#stepTicks}). A species can have its own version of the animation, with
     * its own timing (see {@link WorkAnimations#forSpecies}), so how long a step takes, its
     * {@code length}, is by species. Build one with {@link #animated}.
     *
     * <p>Every step checks its job is still there ({@code isThere}): when it's reached, while it goes
     * on, and right before its effect (see {@link StepRunner}); the worker also checks it on its way.
     * By default that's {@link JobManager#isJobStillThere}, the same check that found the job. A step
     * that comes after its job's target is gone by design (replanting a chopped tree) doesn't (see
     * {@link #outlivesItsTarget}). At the same points it checks the worker can do it
     * ({@link #canDo}): {@link JobManager#canWorkerDo}, the check jobs are handed out by.
     */
    private record Step(
            String name,
            ToIntFunction<String> length,
            @Nullable WorkAnimation animation,
            StepFinish onFinish,
            java.util.function.Predicate<JobContext> isNeeded,
            java.util.function.Predicate<JobContext> isThere)
            implements StepRunner.TimedStep<JobContext> {

        /** This step's animation as a worker of this species plays it, or null if it has none. */
        @Nullable
        WorkAnimation animationFor(@Nullable String speciesId) {
            // Through the accessor: PMD's NonThreadSafeSingleton crashes on a null check of a
            // record component read directly (PMD 7.28.0)
            return animation() == null ? null : WorkAnimations.forSpecies(animation(), speciesId);
        }

        /** How long this step takes a worker of this species. */
        int ticksFor(@Nullable String speciesId) {
            return length.applyAsInt(speciesId);
        }

        @Override
        public int ticks(JobContext ctx) {
            return ticksFor(ctx.species());
        }

        @Override
        public int startTick(JobContext ctx) {
            WorkAnimation played = animationFor(ctx.species());
            return played == null ? 0 : played.playAt(ticks(ctx));
        }

        @Override
        public void start(JobContext ctx) {
            WorkAnimation played = animationFor(ctx.species());
            if (played != null) {
                WorkAnimator.play(ctx.entity(), played, ctx.pos());
            }
        }

        @Override
        public boolean finish(JobContext ctx) {
            // The step's work happens now, as its animation lands: let the animation play out
            WorkAnimator.release(ctx.entity());
            return onFinish.run(ctx);
        }

        @Override
        public boolean needed(JobContext ctx) {
            return isNeeded.test(ctx);
        }

        @Override
        public boolean stillThere(JobContext ctx) {
            return isThere.test(ctx);
        }

        /**
         * The same check its job was handed out by, so a worker that gives a job back isn't handed
         * it again until it can do it. A job type that needs something of its worker adds it to
         * {@link JobManager#canWorkerDo}, not here.
         */
        @Override
        public boolean canDo(JobContext ctx) {
            return JobManager.canWorkerDo(
                    ctx.level(), ctx.pokemon(), ctx.reservation().getJobType(), ctx.pos());
        }

        /** Whether the job a step is for is still there, as the scan that found it sees it. */
        private static boolean jobInTheWorld(JobContext ctx) {
            return JobManager.isJobStillThere(ctx.level(), ctx.reservation().getJobType(), ctx.pos());
        }

        /** A step that takes time and then has its effect, with no animation. */
        static Step of(String name, int ticks, StepFinish finish) {
            return new Step(name, species -> ticks, null, finish, ctx -> true, Step::jobInTheWorld);
        }

        /**
         * A step that plays an animation and has its effect as it lands. It takes {@code minTicks},
         * or longer if the worker needs it to settle and its animation to land.
         */
        static Step animated(String name, int minTicks, WorkAnimation animation, StepFinish finish) {
            return new Step(
                    name,
                    species -> WorkAnimations.forSpecies(animation, species).stepTicks(minTicks),
                    animation,
                    finish,
                    ctx -> true,
                    Step::jobInTheWorld);
        }

        /**
         * A step that only takes time (e.g. a pause), with no effect. It still ends the job if that
         * goes meanwhile: a furnace emptied during its smelt isn't waited out.
         */
        static Step pause(String name, ToIntFunction<String> length) {
            return new Step(name, length, null, ctx -> true, ctx -> true, Step::jobInTheWorld);
        }

        /** This step, skipped when it's reached if there's nothing for it to do. */
        Step onlyIf(java.util.function.Predicate<JobContext> needed) {
            return new Step(name, length, animation, onFinish, needed, isThere);
        }

        /**
         * This step, done even though its job's target is gone: it comes after a step that does
         * away with it (a chopped tree's replant). Its effect has to cope with what it finds.
         */
        Step outlivesItsTarget() {
            return new Step(name, length, animation, onFinish, isNeeded, ctx -> true);
        }
    }

    /** A step's effect. Return false to end the job here (e.g. there turned out to be nothing to do). */
    @FunctionalInterface
    private interface StepFinish {
        boolean run(JobContext ctx);
    }

    /** Wrap a void effect as a step finish that always lets the job continue. */
    private static StepFinish effect(java.util.function.Consumer<JobContext> action) {
        return ctx -> {
            action.accept(ctx);
            return true;
        };
    }

    /** Everything a {@link JobHandler} needs to act at the work site. */
    private record JobContext(Pokemon pokemon, Mob entity, Level level, WorkerState state, ReservationId reservation) {
        BlockPos pos() {
            return reservation.getStationPos();
        }

        WorkerInventory inventory() {
            return WorkerInventoryManager.getOrCreate(pokemon);
        }

        String name() {
            return pokemon.getSpecies().getName();
        }

        /** The worker's species id, which its animations can be particular to. */
        String species() {
            return speciesOf(pokemon);
        }
        /** Data steps leave for later steps (saved, and handed over with the job). */
        net.minecraft.nbt.CompoundTag data() {
            return state.getJobData();
        }
    }

    private static final Map<JobType, JobHandler> HANDLERS = new EnumMap<>(JobType.class);
    // Unknown/unimplemented jobs: stand for 2s and do nothing.
    private static final JobHandler DEFAULT_HANDLER = () -> List.of(Step.pause("wait", species -> 40));

    static {
        HANDLERS.put(JobType.FUEL_FURNACE, new FuelFurnaceHandler());
        HANDLERS.put(JobType.GET_SEEDS, new GetSeedsHandler());
        HANDLERS.put(JobType.COLLECT_DROPS, new CollectDropsHandler());
        HANDLERS.put(JobType.DELIVER_ITEMS, new DeliverItemsHandler());
        HANDLERS.put(JobType.PLANT_SEEDS, new PlantSeedsHandler());
        HANDLERS.put(JobType.HARVEST_CROPS, new HarvestCropsHandler());
        HANDLERS.put(JobType.CHOP_TREE, new ChopTreeHandler());
        // MINE_RESOURCE: worker stands 4s but does not yet break the ore (pre-existing TODO).
        HANDLERS.put(JobType.MINE_RESOURCE, () -> List.of(Step.pause("wait", species -> 80)));
    }

    private static JobHandler handlerFor(JobType type) {
        return HANDLERS.getOrDefault(type, DEFAULT_HANDLER);
    }

    /** A job's step, for the debug overlay: its name, which of how many it is, and its length. */
    public record StepView(String name, int index, int count, int ticks) {}

    /** The step at this index of a job's steps, as a worker of this species does it, or null if there's none there. */
    @Nullable
    public static StepView stepView(JobType job, int index, @Nullable String speciesId) {
        List<Step> steps = handlerFor(job).steps();
        if (index < 0 || index >= steps.size()) {
            return null;
        }
        Step step = steps.get(index);
        return new StepView(step.name(), index, steps.size(), step.ticksFor(speciesId));
    }

    /**
     * Where this worker stands to do this job: the stance its species plays the job's animation
     * from (see {@link JobHandler#stance}). {@link WorkPositions} finds the tile from it.
     */
    public static WorkStance stanceFor(JobType job, Mob worker) {
        return handlerFor(job).stance(speciesOf(worker));
    }

    /** A worker's species id ({@code cobblemon:scizor}), or null if it isn't a Pokémon. */
    @Nullable
    public static String speciesOf(Mob worker) {
        return worker instanceof PokemonEntity pokemonEntity ? speciesOf(pokemonEntity.getPokemon()) : null;
    }

    private static String speciesOf(Pokemon pokemon) {
        return pokemon.getSpecies().getResourceIdentifier().toString();
    }

    private static final class FuelFurnaceHandler implements JobHandler {
        // One cycle matches one smelting operation: Ember, lighting the furnace as its flames reach
        // it, then wait out the rest of the smelt.
        private static final int CYCLE_TICKS = 200;
        private final Step ember =
                Step.animated("ember", 0, WorkAnimations.IGNITE_FURNACE, effect(FuelFurnaceHandler::ignite));
        private final List<Step> steps = List.of(
                ember,
                // Re-igniting once per cycle keeps it lit across repeats: the 201-tick burn covers the
                // 200-tick gap to the next ignite, however long the species' ember takes.
                Step.pause("smelt", species -> Math.max(0, CYCLE_TICKS - ember.ticksFor(species))));

        @Override
        public List<Step> steps() {
            return steps;
        }

        private static void ignite(JobContext ctx) {
            int burnDuration = dev.palworks.station.FurnaceHelper.getBurnDuration();
            dev.palworks.station.FurnaceHelper.igniteFurnace(ctx.level(), ctx.pos(), burnDuration);
            if (DEBUG) {
                Palworks.LOGGER.info("{} ignited furnace at {} for {} ticks", ctx.name(), ctx.pos(), burnDuration);
            }
        }

        @Override
        public boolean repeatAfterComplete(JobContext ctx) {
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

        @Override
        public List<Step> steps() {
            return steps;
        }

        private void work(JobContext ctx) {
            BlockPos targetPos = ctx.pos();
            WorkerInventory inventory = ctx.inventory();

            if (DEBUG) {
                Palworks.LOGGER.info(
                        "{} starting GET_SEEDS at {} (inventory: {} items, has space: {})",
                        ctx.name(),
                        targetPos,
                        inventory.getTotalItemCount(),
                        inventory.hasSpace());
            }

            // Skip extraction if inventory is full, but still complete the job
            if (!inventory.hasSpace()) {
                if (DEBUG) {
                    Palworks.LOGGER.info("{} skipping GET_SEEDS extraction - inventory already full", ctx.name());
                }
                return;
            }

            Set<Suitability> suitabilities = SuitabilityDetector.getSuitabilities(ctx.pokemon());
            if (suitabilities.contains(Suitability.PLANTING)) {
                // A seed the open farmland around it can take (see JobManager.seedToFetch)
                net.minecraft.world.item.Item seed = ctx.level() instanceof ServerLevel serverLevel
                        ? JobManager.getInstance(serverLevel)
                                .seedToFetch(targetPos, ctx.entity().blockPosition())
                        : null;
                ItemStack extracted = seed == null
                        ? ItemStack.EMPTY
                        : dev.palworks.station.ChestHelper.extractItems(ctx.level(), targetPos, seed, 64);
                if (!extracted.isEmpty()) {
                    int leftover = inventory.addItem(extracted);
                    returnToChest(ctx.level(), targetPos, extracted, leftover);
                    if (DEBUG) {
                        Palworks.LOGGER.info(
                                "{} got {} {} seeds from chest at {} ({} leftover)",
                                ctx.name(),
                                extracted.getCount() - leftover,
                                extracted.getItem().toString(),
                                targetPos,
                                leftover);
                    }
                } else if (DEBUG) {
                    Palworks.LOGGER.warn(
                            "{} FAILED to get seeds from chest at {} - chest might be empty, treating as complete",
                            ctx.name(),
                            targetPos);
                }
            } else if (suitabilities.contains(Suitability.NETHER)) {
                ItemStack extracted = dev.palworks.station.ChestHelper.extractItems(
                        ctx.level(), targetPos, net.minecraft.world.item.Items.NETHER_WART, 64);
                if (!extracted.isEmpty()) {
                    int leftover = inventory.addItem(extracted);
                    returnToChest(ctx.level(), targetPos, extracted, leftover);
                    if (DEBUG) {
                        Palworks.LOGGER.info(
                                "{} got {} nether wart from chest at {} ({} leftover)",
                                ctx.name(),
                                extracted.getCount() - leftover,
                                targetPos,
                                leftover);
                    }
                } else if (DEBUG) {
                    Palworks.LOGGER.warn(
                            "{} FAILED to get nether wart from chest at {} - chest might be empty, treating as complete",
                            ctx.name(),
                            targetPos);
                }
            } else if (DEBUG) {
                Palworks.LOGGER.warn(
                        "{} has GET_SEEDS job but lacks PLANTING/NETHER suitability! Suitabilities: {}",
                        ctx.name(),
                        suitabilities);
            }
        }
    }

    private static final class CollectDropsHandler implements JobHandler {
        private final List<Step> steps = List.of(Step.of("pick up", 20, effect(this::work)));

        @Override
        public List<Step> steps() {
            return steps;
        }

        // Picks up whatever fits (into stacks of the same item first, then free slots). What doesn't
        // fit stays on the ground, and JobManager gives it a pickup job of its own again.
        private void work(JobContext ctx) {
            BlockPos targetPos = ctx.pos();
            WorkerInventory inventory = ctx.inventory();

            int pickedUp = 0;
            for (net.minecraft.world.entity.item.ItemEntity itemEntity :
                    JobManager.itemsToPickUp(ctx.level(), targetPos)) {
                ItemStack stack = itemEntity.getItem();
                int leftover = inventory.addItem(stack);
                if (leftover == stack.getCount()) {
                    continue; // none of it fits
                }
                pickedUp += stack.getCount() - leftover;
                if (leftover == 0) {
                    itemEntity.discard();
                } else {
                    // Only part of it fit: leave the rest on the ground (it used to be deleted)
                    itemEntity.setItem(stack.copyWithCount(leftover));
                }
            }

            if (DEBUG && pickedUp > 0) {
                Palworks.LOGGER.info(
                        "{} collected {} dropped items from ground at {}", ctx.name(), pickedUp, targetPos);
            }
        }
    }

    private static final class DeliverItemsHandler implements JobHandler {
        private final List<Step> steps = List.of(Step.of("deliver", 20, effect(this::work)));

        @Override
        public List<Step> steps() {
            return steps;
        }

        private void work(JobContext ctx) {
            BlockPos chestPos = ctx.pos();
            WorkerInventory inventory = ctx.inventory();
            if (inventory.isEmpty()) {
                return;
            }

            // A worker that plants keeps its seeds to plant with; everything else goes in the chest
            Set<Suitability> suitabilities = SuitabilityDetector.getSuitabilities(ctx.pokemon());
            int totalDelivered = 0;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (WorkerInventory.isCargo(stack, suitabilities)) {
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
        private final List<Step> steps = List.of(Step.animated("plant", 20, WorkAnimations.PLANT, effect(this::work)));

        @Override
        public List<Step> steps() {
            return steps;
        }

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
                    Palworks.LOGGER.info(
                            "{} planted {} at {} ({} items remaining)",
                            ctx.name(),
                            seed.toString(),
                            plantPos,
                            inventory.getTotalItemCount());
                }
            } else if (DEBUG) {
                Palworks.LOGGER.warn("{} failed to plant {} at {}", ctx.name(), seed.toString(), plantPos);
            }
        }
    }

    private static final class HarvestCropsHandler implements JobHandler {
        private final List<Step> steps =
                List.of(Step.animated("harvest", 20, WorkAnimations.HARVEST, effect(this::work)));

        @Override
        public List<Step> steps() {
            return steps;
        }

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
        // Clear the leaves in the way to the trunk (1s, skipped if there are none), chop (3s), then
        // replant (1s later). The replant spots and sapling are saved in the job's data at the
        // chop, so a paused job, or one another lumberjack takes over, still replants.
        private final List<Step> steps = List.of(
                Step.of("clear leaves", 20, effect(ChopTreeHandler::clearLeaves))
                        .onlyIf(ChopTreeHandler::leavesInTheWay),
                Step.animated("chop", 60, WorkAnimations.CHOP, ChopTreeHandler::chop),
                // The tree's gone by now: it was just chopped
                Step.of("replant", 20, effect(ChopTreeHandler::replant)).outlivesItsTarget());

        @Override
        public List<Step> steps() {
            return steps;
        }

        /** Where the worker stands by this tree, and the leaves in its way (see WorkPositions.treeStandFor). */
        @Nullable
        private static WorkPositions.TreeStand treeStand(JobContext ctx) {
            return WorkPositions.treeStandFor(
                    ctx.level(), ctx.pos(), ctx.entity(), stanceFor(JobType.CHOP_TREE, ctx.entity()));
        }

        /** Whether leaves stand between the worker and the trunk. */
        private static boolean leavesInTheWay(JobContext ctx) {
            WorkPositions.TreeStand stand = treeStand(ctx);
            return stand != null && stand.needsClearing();
        }

        /**
         * Break the leaves between the worker and the trunk (with their usual drops, as when they
         * decay), so it can walk in and chop right up against it. Its stand spot then moves in to the
         * trunk, and it walks there before the chop starts.
         */
        private static void clearLeaves(JobContext ctx) {
            WorkPositions.TreeStand stand = treeStand(ctx);
            if (stand == null) {
                return;
            }
            for (BlockPos leaf : stand.leavesToClear()) {
                ctx.level().destroyBlock(leaf, true, ctx.entity());
            }
            // It hasn't walked to its new spot yet: don't let "close enough" chop from out here
            ctx.state().setWalkedToJob(false);
        }

        /** Bring the tree down and note where to replant. Ends the job if there's no valid tree. */
        private static boolean chop(JobContext ctx) {
            Level level = ctx.level();
            BlockPos treePos = ctx.pos();
            TreeDetector.TreeValidationResult treeResult = TreeDetector.validateTree(level, treePos);

            if (!treeResult.isValid() || treeResult.getLogPositions().isEmpty()) {
                // Not a valid tree any more (partly chopped, or it's a player build). Leave it alone:
                // breaking "just this log" is how builds that fail tree detection got griefed.
                if (DEBUG) {
                    Palworks.LOGGER.warn(
                            "{} found no valid tree at {} ({}), leaving it alone",
                            ctx.name(),
                            treePos,
                            treeResult.getReason());
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
                Palworks.LOGGER.info(
                        "{} chopped tree at {} - {} logs and {} canopy blocks destroyed, base at {}, sapling: {}",
                        ctx.name(),
                        treePos,
                        logsChopped,
                        canopy.size(),
                        basePos,
                        saplingItem);
            }

            // For the replant step (saved with the job, so it survives a pause or a handover)
            if (saplingItem != null) {
                ctx.data()
                        .putString(
                                DATA_SAPLING,
                                net.minecraft.core.registries.BuiltInRegistries.ITEM
                                        .getKey(saplingItem)
                                        .toString());
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
            net.minecraft.world.item.Item saplingItem = saplingId == null
                    ? null
                    : net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getOptional(saplingId)
                            .orElse(null);
            int[] spots = ctx.data().getIntArray(DATA_SPOTS);
            if (saplingItem == null || spots.length % 3 != 0) {
                return;
            }
            // The sapling is free: the leaves still drop their usual saplings as they decay, and
            // nothing had dropped yet at the chop to pay with
            int replanted = 0;
            for (int i = 0; i < spots.length; i += 3) {
                if (TreeDetector.plantSapling(
                        ctx.level(), new BlockPos(spots[i], spots[i + 1], spots[i + 2]), saplingItem)) {
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
     * Walk the worker to its stand tile, at normal walking speed, along a path its whole body fits
     * along (see {@link WorkerNavigator}). The path ends on the tile itself, so a stance only just
     * off its tile (right up against a trunk) gets there too.
     */
    private static void navigateToJob(Mob entity, BlockPos standPos) {
        WorkerNavigator.walkTo(entity, standPos, 0.6);
    }

    /**
     * Keep the worker where it is, facing its work, while its last animation plays out after the
     * work it was for is done. Returns whether it's holding (false once the animation is over).
     */
    private static boolean holdForAnimation(Mob entity) {
        BlockPos target = WorkAnimator.playingOutAt(entity);
        if (target == null) {
            return false;
        }
        entity.getNavigation().stop();
        entity.setDeltaMovement(0, entity.getDeltaMovement().y, 0);
        turnTowards(entity, target);
        return true;
    }

    /**
     * Turn the worker a little towards the worked block (body, head and yaw together), so work
     * animations and effects point the right way: {@link Facing#TURN_DEGREES_PER_TICK} a tick,
     * rather than snapping round. Returns whether it's facing the block now.
     */
    private static boolean turnTowards(Mob entity, BlockPos targetPos) {
        float wanted =
                Facing.yawTowards(targetPos.getX() + 0.5 - entity.getX(), targetPos.getZ() + 0.5 - entity.getZ());
        float yaw = Facing.turn(entity.getYRot(), wanted, Facing.TURN_DEGREES_PER_TICK);
        entity.setYRot(yaw);
        entity.setYBodyRot(yaw);
        entity.setYHeadRot(yaw);
        return Facing.faces(yaw, wanted);
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
            net.minecraft.world.level.block.Block.popResource(
                    level, chestPos.above(), extracted.copyWithCount(stillLeft));
        }
    }
}
