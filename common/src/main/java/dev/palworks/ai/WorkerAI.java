package dev.palworks.ai;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.Palworks;
import dev.palworks.ai.path.WorkerNavigator;
import dev.palworks.animation.WorkAnimator;
import dev.palworks.animation.WorkAspectSync;
import dev.palworks.block.WorkerBedBlock;
import dev.palworks.debug.DebugManager;
import dev.palworks.hunger.EatingDisplay;
import dev.palworks.hunger.Hunger;
import dev.palworks.hunger.HungerMath;
import dev.palworks.inventory.WorkerInventory;
import dev.palworks.inventory.WorkerInventoryManager;
import dev.palworks.job.JobManager;
import dev.palworks.job.JobType;
import dev.palworks.job.StepRunner;
import dev.palworks.job.handler.JobContext;
import dev.palworks.job.handler.JobHandler;
import dev.palworks.job.handler.JobHandlerRegistry;
import dev.palworks.job.handler.Step;
import dev.palworks.mood.Mood;
import dev.palworks.reservation.ReservationId;
import dev.palworks.sleep.Sleep;
import dev.palworks.sleep.SleepPose;
import dev.palworks.sleep.SleepSchedule;
import dev.palworks.worker.WorkActivity;
import dev.palworks.worker.WorkActivitySync;
import dev.palworks.worker.WorkSpeed;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import dev.palworks.worker.WorkerStatusSync;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Main AI controller for Pokémon workers.
 * Handles job scanning, navigation, and work execution.
 */
public class WorkerAI {

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
        // Holding the food it's eating, and dropping crumbs
        EatingDisplay.tick(entity, level, WorkerStateManager.getOrCreate(pokemon));
        // After the tick, so they reflect where it ended up (e.g. a job dropped this tick): what
        // its owner is shown, the aspects its poser animates from, and whether the animation it's
        // playing still belongs to a step it's on.
        WorkerState state = WorkerStateManager.getOrCreate(pokemon);
        WorkActivity activity = WorkActivitySync.update(pokemon, entity, state, level.getGameTime());
        WorkAspectSync.update(pokemon, activity, level.getGameTime());
        // What's holding it back (hungry), shown over its head
        WorkerStatusSync.update(pokemon, state);
        WorkAnimator.update(entity, state);
        return handled;
    }

    private static boolean tickWork(Pokemon pokemon, Mob entity, Level level) {
        // Skip eligibility check - if the entity exists and we're calling this, it's eligible enough
        // The commands already filter for owned Pokemon

        WorkerState state = WorkerStateManager.getOrCreate(pokemon);

        // Before the enabled check: a disabled worker still shows what it's carrying. One that's
        // eating shows the food instead.
        ItemStack eating = EatingDisplay.eating(level, state);
        showItem(
                entity,
                eating.isEmpty() ? WorkerInventoryManager.getOrCreate(pokemon).getFirstItem() : eating);

        // New workers start enabled (see WorkerState), so a disabled state means it was turned off
        // on purpose (/palworks worker disable). Respect that instead of re-enabling it. Its
        // hunger is Cobblemon's again: no rest after its last job carries over.
        if (!state.isEnabled()) {
            state.setRestUntil(0);
            if (state.isAsleep() && entity instanceof PokemonEntity pokemonEntity) {
                wake(pokemon, pokemonEntity, state); // and its sleep is Cobblemon's again
            }
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

        // Its work uses up its fullness, a little all the time, whether it has a job or not (the work
        // it does uses up more, as it does it: see Hunger.didWork)
        Hunger.tick(pokemon, state);
        // Its mood: down while Hungry or Tired, back up while it's neither and not at work (see Mood)
        Mood.tick(pokemon, state);

        // A pastured worker sleeps in its bedtime (see Sleep): asleep where it is, it does nothing else
        if (entity instanceof PokemonEntity pokemonEntity && tickSleep(pokemon, pokemonEntity, state)) {
            return true;
        }

        // Handle current reservation
        ReservationId reservation = state.getCurrentReservation();
        if (reservation != null) {
            if (Palworks.LOGGER.isTraceEnabled()) {
                Palworks.LOGGER.trace(
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
                dropJob(pokemon, entity, state);
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
                dropJob(pokemon, entity, state);
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
        WorkStance stance = JobHandlerRegistry.stanceFor(jobType, entity);
        BlockPos standPos = WorkPositions.standingPosFor(level, targetPos, jobType, entity, stance);
        if (standPos == null) {
            // The job itself may be gone (e.g. its tree was chopped, leaving nothing to stand by),
            // or not one it can do any more: drop it for that, not as unreachable, which would
            // wrongly remember the spot.
            if (stopIfItCantGoOn(pokemon, entity, level, state, reservation)) {
                return false;
            }
            // No valid place to stand to do this job (front blocked / no standable tile). Give up.
            Palworks.LOGGER.debug(
                    "{} found no valid stand position for {} at {} - abandoning",
                    pokemon.getSpecies().getName(),
                    reservation.getJobType(),
                    targetPos);
            if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
                JobManager.getInstance(serverLevel).onJobAbandoned(pokemon, reservation, level);
            }
            dropJob(pokemon, entity, state);
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
            Palworks.LOGGER.debug(
                    "{}'s owner moved away from its {} job at {} - dropping",
                    pokemon.getSpecies().getName(),
                    reservation.getJobType(),
                    targetPos);
            state.resetPathFailures();
            dropJob(pokemon, entity, state);
            return false;
        }

        // Arrived once it's in the job's stance (see WorkStance): near its stand tile, or wherever its
        // walk ended. Cobblemon's navigation calls a walk done a little short of the tile, so if it
        // isn't in its stance there, it steps the last bit straight in, and settles where it is if
        // something stops it.
        boolean walkEnded = entity.getNavigation().isDone() && state.hasWalkedToJob();
        // Where exactly it stands: the middle of its tile, or of the bed it lies in (a 2 by 2 bed's
        // middle is where its four blocks meet)
        Vec3 spot = WorkPositions.standPoint(level, targetPos, jobType, standPos);
        double offX = entity.getX() - spot.x;
        double offZ = entity.getZ() - spot.z;
        boolean onStand = distanceSq == 0 && offX * offX + offZ * offZ <= ON_TILE_DIST * ON_TILE_DIST;
        boolean inStance = WorkPositions.inStance(level, entity, targetPos, jobType, stance);
        boolean nextToStand = distanceSq <= SETTLED_DISTSQ;
        if (walkEnded && nextToStand && !onStand && !inStance && state.getStepInTicks() < STEP_IN_TICKS) {
            if (holdForAnimation(entity)) {
                return true;
            }
            state.addStepInTick();
            entity.getMoveControl().setWantedPosition(spot.x, standPos.getY(), spot.z, 0.6);
            return true;
        }
        boolean arrived =
                onStand || (inStance && (distanceSq <= ARRIVAL_DISTSQ || walkEnded)) || (walkEnded && nextToStand);
        if (arrived) {
            return workAtJob(pokemon, entity, level, state, reservation);
        }
        return walkToJob(pokemon, entity, level, state, reservation, standPos, distanceSq);
    }

    /**
     * The worker is at its job: hold still, face it, and run a tick of its current step. Returns
     * false if the job ended without finishing (it went, or the worker can't do it any more).
     */
    private static boolean workAtJob(
            Pokemon pokemon, Mob entity, Level level, WorkerState state, ReservationId reservation) {
        BlockPos targetPos = reservation.getStationPos();
        // Only log on first tick of work
        if (Palworks.LOGGER.isDebugEnabled() && !state.isWorking()) {
            WorkerInventory inventory = WorkerInventoryManager.getOrCreate(pokemon);
            String inventoryInfo = inventory.isEmpty() ? "empty" : inventory.getTotalItemCount() + " items";
            Palworks.LOGGER.debug(
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

            // Job complete - clear reservation
            state.clearReservation();
            state.resetPathFailures();
            restAfterJob(pokemon, entity, level, state, reservation.getJobType());

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
    }

    /**
     * The worker isn't at its job yet: walk it there, giving the job up if it's stuck. Returns false
     * if it gave the job up.
     */
    private static boolean walkToJob(
            Pokemon pokemon,
            Mob entity,
            Level level,
            WorkerState state,
            ReservationId reservation,
            BlockPos standPos,
            double distanceSq) {
        BlockPos targetPos = reservation.getStationPos();
        // Need to navigate to job
        // Only log when starting navigation (not every tick)
        if (Palworks.LOGGER.isTraceEnabled()
                && !state.isWorking()
                && entity.getNavigation().isDone()) {
            Palworks.LOGGER.trace(
                    "{} navigating to {} job at {} (distance: {} blocks)",
                    pokemon.getSpecies().getName(),
                    reservation.getJobType(),
                    targetPos,
                    String.format("%.1f", Math.sqrt(distanceSq)));
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
        navigateToJob(entity, standPos, walkSpeed(pokemon, state, reservation.getJobType()));
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
                state.resetPathFailures();
                dropJob(pokemon, entity, state);
                return false;
            }
        }

        // Path/idle state lives in the cache; flush periodically (staggered) for durability.
        if ((level.getGameTime() + entity.getId()) % WORK_SAVE_INTERVAL == 0) {
            WorkerStateManager.save(pokemon, state);
        }
        return true;
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
        JobHandler handler = JobHandlerRegistry.forJob(reservation.getJobType());

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
        if (outcome == StepRunner.Outcome.REPEATING
                && reservation.getJobType() != JobType.SLEEP
                && entity instanceof PokemonEntity pokemonEntity
                && Sleep.isBedtimeFor(pokemonEntity, state)) {
            // Its bedtime: it doesn't go round again (it's off to bed)
            state.resetJobProgress();
            return StepRunner.Outcome.FINISHED;
        }
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
        StepRunner.Outcome reason = StepRunner.stopReason(
                JobHandlerRegistry.forJob(reservation.getJobType()).steps(), state, ctx);
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
        Palworks.LOGGER.debug(
                "{}'s {} job at {} {} (step {}) - stopping",
                pokemon.getSpecies().getName(),
                reservation.getJobType(),
                reservation.getStationPos(),
                reason == StepRunner.Outcome.GONE ? "is gone" : "isn't one it can do any more",
                state.getJobStep());
        if (level instanceof ServerLevel serverLevel) {
            JobManager jobs = JobManager.getInstance(serverLevel);
            if (reason == StepRunner.Outcome.GONE) {
                jobs.onJobInvalidated(pokemon, reservation, level);
            } else {
                jobs.onJobGivenBack(pokemon, reservation); // before its progress is cleared
            }
        }
        state.resetPathFailures();
        dropJob(pokemon, entity, state);
    }

    /**
     * Drop the worker's job: stop walking to it and clear its reservation (and with it, its
     * progress), then save. Whatever else dropping it means (forgetting path
     * failures, telling JobManager) is the caller's, before this.
     */
    private static void dropJob(Pokemon pokemon, Mob entity, WorkerState state) {
        entity.getNavigation().stop(); // don't keep walking to it
        WorkerNavigator.forget(entity);
        state.clearReservation();
        WorkerStateManager.save(pokemon, state);
    }

    /**
     * Walk the worker to its stand tile, at normal walking speed, along a path its whole body fits
     * along (see {@link WorkerNavigator}). The path ends on the tile itself, so a stance only just
     * off its tile (right up against a trunk) gets there too.
     */
    private static void navigateToJob(Mob entity, BlockPos standPos, double speed) {
        WorkerNavigator.walkTo(entity, standPos, speed);
    }

    /** Normal walking speed (0.6), slower for a worker with no fullness left; never on its way to eat. */
    private static double walkSpeed(Pokemon pokemon, WorkerState state, JobType jobType) {
        return isForItsNeeds(jobType) ? 0.6 : 0.6 * WorkSpeed.of(pokemon, state);
    }

    /** Eating and going to bed: what makes it better, so never slowed down. */
    private static boolean isForItsNeeds(JobType jobType) {
        return !jobType.isWork();
    }

    /**
     * After a job, a worker with no fullness left, or Tired, rests before taking another, for long
     * enough that it gets through its work at its slower speed (see {@link HungerMath#restTicks}).
     * Not after eating or sleeping, which is what makes it better.
     */
    private static void restAfterJob(Pokemon pokemon, Mob entity, Level level, WorkerState state, JobType jobType) {
        double speed = WorkSpeed.of(pokemon, state);
        if (isForItsNeeds(jobType) || speed >= 1) {
            return;
        }
        long rest = HungerMath.restTicks(JobHandlerRegistry.jobTicks(jobType, entity), speed);
        state.setRestUntil(level.getGameTime() + rest);
    }

    /**
     * How long an idle worker waits in its bedtime for a bed before it sleeps where it is: long
     * enough for JobAssigner to offer it one (it gets a turn every few ticks).
     */
    private static final int BED_SEARCH_TICKS = 40;

    /**
     * A pastured worker's sleep (see {@link Sleep}), each tick. Returns true while it sleeps where it
     * is, so does nothing else; in a bed, it's its SLEEP job that runs (and it sleeps once it's lying
     * there). Outside its bedtime it wakes, Tired if under half its sleep was in a bed.
     */
    private static boolean tickSleep(Pokemon pokemon, PokemonEntity entity, WorkerState state) {
        if (!Sleep.appliesTo(pokemon, state) || !Sleep.isBedtime(entity)) {
            state.resetBedtimeIdleTicks();
            state.setWokenUntil(0);
            if (state.isAsleep() || state.isInBed() || state.getBedSleepTicks() + state.getGroundSleepTicks() > 0) {
                wake(pokemon, entity, state); // its night's over, however much of it it slept
            }
            return false;
        }
        ReservationId reservation = state.getCurrentReservation();
        boolean inBedJob = reservation != null && reservation.getJobType() == JobType.SLEEP;
        if (state.isInBed() && !inBedJob) {
            // Its bed's gone from under it (broken): that wakes it
            state.setWokenUntil(entity.level().getGameTime() + Sleep.WOKEN_TICKS);
        }
        if (Sleep.isWoken(entity, state)) {
            // Woken in the night (hit, hurt, its bed broken, recalled): up for a while, then back to bed
            if (state.isAsleep() || state.isInBed()) {
                interruptSleep(pokemon, entity, state);
            }
            if (inBedJob) {
                dropJob(pokemon, entity, state);
            }
            state.resetBedtimeIdleTicks();
            return false;
        }
        if (inBedJob) {
            // In its bed once it's lying there (its sleep step has begun); on its way, awake
            if (state.isWorking()) {
                if (!state.isInBed()) {
                    lieDown(entity, reservation.getStationPos());
                    state.setInBed(true);
                }
                fallAsleep(state);
                state.addSleepTick(true);
                SleepPose.hold(entity);
            }
            return false;
        }
        if (reservation != null) {
            state.resetBedtimeIdleTicks(); // finishing the job it had first
            return false;
        }
        if (state.isAsleep()) {
            // Asleep where it is (no bed to be had): there till morning, unless something wakes it
            state.addSleepTick(false);
            SleepPose.hold(entity);
            return true;
        }
        state.addBedtimeIdleTick();
        if (state.getBedtimeIdleTicks() > BED_SEARCH_TICKS) {
            Palworks.LOGGER.debug(
                    "{} found no bed, and sleeps where it is",
                    pokemon.getSpecies().getName());
            fallAsleep(state);
        }
        return false;
    }

    /** It lies down in the very middle of its bed, on top of it, however near its walk got it. */
    private static void lieDown(PokemonEntity entity, BlockPos bedPos) {
        if (entity.level().getBlockState(bedPos).getBlock() instanceof WorkerBedBlock bed) {
            Vec3 middle = bed.center(bedPos);
            entity.moveTo(middle.x, middle.y, middle.z, entity.getYRot(), entity.getXRot());
        }
    }

    /**
     * Something woke it in the night: awake, and out of its bed, but the night isn't over, so how it
     * slept so far still counts for whether it wakes Tired in the morning.
     */
    private static void interruptSleep(Pokemon pokemon, PokemonEntity entity, WorkerState state) {
        Palworks.LOGGER.debug("{} is woken in the night", pokemon.getSpecies().getName());
        state.setAsleep(false);
        state.setInBed(false);
        SleepPose.wake(entity);
        WorkerStateManager.save(pokemon, state);
    }

    /** It falls asleep: Tired no more (it's sleeping it off), and it stops looking for a bed. */
    private static void fallAsleep(WorkerState state) {
        if (!state.isAsleep()) {
            state.setAsleep(true);
            state.setTired(false);
            state.resetBedtimeIdleTicks();
        }
    }

    /** It wakes: Tired if under half its sleep was in a bed, and out of its sleep pose. */
    private static void wake(Pokemon pokemon, PokemonEntity entity, WorkerState state) {
        boolean tired = SleepSchedule.wakesTired(state.getBedSleepTicks(), state.getGroundSleepTicks());
        Palworks.LOGGER.debug(
                "{} wakes up{} ({} ticks in a bed, {} without)",
                pokemon.getSpecies().getName(),
                tired ? " Tired" : "",
                state.getBedSleepTicks(),
                state.getGroundSleepTicks());
        state.setTired(tired);
        state.resetSleepTicks();
        state.setAsleep(false);
        state.setInBed(false);
        SleepPose.wake(entity);
        WorkerStateManager.save(pokemon, state);
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
     * Show an item on the worker (the first it's carrying, or the food it's eating), without
     * touching its real Cobblemon held item.
     *
     * <p>Cobblemon renders a Pokemon's held item if it has one (and it isn't hidden), and otherwise
     * whatever is in the entity's main hand, which Cobblemon itself never uses. So a display copy
     * goes in the main hand. Covers every way the inventory changes (jobs, the inventory screen,
     * debug commands) because it runs every tick; the comparison keeps it from resending.
     */
    private static void showItem(Mob entity, ItemStack carried) {
        if (ItemStack.isSameItemSameComponents(entity.getMainHandItem(), carried)) {
            return;
        }
        // Count doesn't matter for display, and one item keeps the copy obviously cosmetic.
        entity.setItemSlot(EquipmentSlot.MAINHAND, carried.copyWithCount(1));
        // The copy is purely visual: it must never drop (on death, or when vanilla swaps a mob's
        // equipment), or it would duplicate the item that's still in the worker's inventory (or
        // the trough).
        entity.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
    }
}
