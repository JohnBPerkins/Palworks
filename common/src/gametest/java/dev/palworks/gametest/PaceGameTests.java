package dev.palworks.gametest;

import com.cobblemon.mod.common.api.moves.animations.ActionEffects;
import com.cobblemon.mod.common.entity.generic.GenericBedrockEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.animation.SlowedPause;
import dev.palworks.animation.WorkAnimations;
import dev.palworks.job.JobType;
import dev.palworks.reservation.ReservationId;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import dev.palworks.worker.WorkerStatusSync;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.phys.AABB;

/**
 * A slowed worker's pace (see dev.palworks.worker.Pace): it walks slower, but still visibly walks,
 * and its work's moves play at their usual speed, after a pause, so they still land as the work happens.
 */
public class PaceGameTests {
    private static final String WORKER = "bulbasaur level=20";

    @GameTest(template = Templates.EMPTY)
    public void cobblemonHasTheEffectATiredWorkerShows(GameTestHelper helper) {
        helper.assertTrue(
                ActionEffects.INSTANCE.getActionEffects().get(SlowedPause.TIRED) != null,
                "Cobblemon's " + SlowedPause.TIRED + " effect is loaded");
        helper.succeed();
    }

    @GameTest(template = Templates.EMPTY, batch = "pace_pause", timeoutTicks = 600)
    public void aSlowedWorkerPausesBeforeItsMove(GameTestHelper helper) {
        // Its move plays at its usual speed, so the time a slowed worker loses goes in a pause first
        // (players are told, to pulse its icons), then the move
        Workers.isolate(helper);
        helper.getLevel().setDayTime(MORNING);
        BlockPos crop = new BlockPos(2, 1, 3);
        helper.setBlock(crop.below(), Blocks.FARMLAND);
        helper.setBlock(crop, ((CropBlock) Blocks.WHEAT).getStateForAge(7));
        PokemonEntity worker = Workers.sendOutPastured(helper, WORKER, new BlockPos(2, 1, 1));
        worker.getPokemon().setCurrentFullness(0);
        AABB around = new AABB(helper.absolutePos(crop)).inflate(1);
        long[] pausing = {-1, -1};
        long[] played = {-1};
        helper.onEachTick(() -> {
            long now = helper.getLevel().getGameTime();
            boolean paused = worker.getAspects().contains(WorkerStatusSync.PAUSING);
            if (paused && pausing[0] < 0) {
                pausing[0] = now;
            }
            if (!paused && pausing[0] >= 0 && pausing[1] < 0) {
                pausing[1] = now;
            }
            if (played[0] < 0
                    && !helper.getLevel()
                            .getEntitiesOfClass(GenericBedrockEntity.class, around)
                            .isEmpty()) {
                played[0] = now;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(played[0] >= 0 && pausing[1] >= 0, "it paused, and its move played");
            long paused = pausing[1] - pausing[0];
            helper.assertTrue(
                    Math.abs(paused - SlowedPause.SHORTEST_PAUSE_TICKS) <= 2,
                    "it paused " + paused + " ticks, not " + SlowedPause.SHORTEST_PAUSE_TICKS);
            helper.assertTrue(
                    played[0] >= pausing[1] - 1,
                    "its move started at " + played[0] + ", after its pause (" + pausing[0] + " to " + pausing[1]
                            + ")");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "pace_move_lands", timeoutTicks = 600)
    public void aSlowedWorkersMoveStillLandsAsItsCropGoes(GameTestHelper helper) {
        // At half pace, but its move plays at its usual speed (its leaves fly at theirs), after its
        // pause: they still reach the crop as it goes, not long before
        Workers.isolate(helper);
        helper.getLevel().setDayTime(MORNING);
        BlockPos crop = new BlockPos(2, 1, 3);
        helper.setBlock(crop.below(), Blocks.FARMLAND);
        helper.setBlock(crop, ((CropBlock) Blocks.WHEAT).getStateForAge(7));
        PokemonEntity worker = Workers.sendOutPastured(helper, WORKER, new BlockPos(2, 1, 1));
        worker.getPokemon().setCurrentFullness(0);
        WorkerStateManager.getOrCreate(worker.getPokemon()).setTired(true); // with no food, half pace
        int impact = WorkAnimations.forSpecies(WorkAnimations.HARVEST, WorkAnimations.speciesOf(worker.getPokemon()))
                .impactTicks();
        AABB around = new AABB(helper.absolutePos(crop)).inflate(1);
        long[] played = {-1};
        long[] harvested = {-1};
        helper.onEachTick(() -> {
            long now = helper.getLevel().getGameTime();
            // Its move's stand-in target appears at the crop as it starts (see WorkAnimator)
            if (played[0] < 0
                    && !helper.getLevel()
                            .getEntitiesOfClass(GenericBedrockEntity.class, around)
                            .isEmpty()) {
                played[0] = now;
            }
            if (harvested[0] < 0 && !((CropBlock) Blocks.WHEAT).isMaxAge(helper.getBlockState(crop))) {
                harvested[0] = now;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(played[0] >= 0 && harvested[0] >= 0, "its move played, and the wheat went");
            long took = harvested[0] - played[0];
            helper.assertTrue(
                    Math.abs(took - impact) <= 2,
                    "the wheat went " + took + " ticks into its move, not as it landed (" + impact + " in)");
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "pace_aspect", timeoutTicks = 200)
    public void aSlowedWorkerTellsClientsItsPace(GameTestHelper helper) {
        // Its pace as an aspect, which Cobblemon syncs to players, for them to slow its animations
        Workers.isolate(helper);
        PokemonEntity worker = Workers.sendOut(helper, WORKER, new BlockPos(1, 1, 1));
        Pokemon pokemon = worker.getPokemon();
        helper.startSequence()
                .thenExecute(() -> pokemon.setCurrentFullness(0))
                .thenWaitUntil(() -> helper.assertTrue(
                        worker.getAspects().contains("palworks-pace-75"),
                        "empty: three quarters pace, " + worker.getAspects()))
                .thenExecute(() -> pokemon.setCurrentFullness(pokemon.getMaxFullness()))
                .thenWaitUntil(() -> helper.assertTrue(
                        worker.getAspects().stream().noneMatch(aspect -> aspect.startsWith("palworks-pace-")),
                        "fed: full pace, no aspect"))
                .thenSucceed();
    }

    // ---- How fast a slowed worker really walks ----

    private static final long MORNING = 1_000; // not bedtime: a pastured worker stays up and works

    /**
     * Two workers side by side walk the same 13 blocks to a ripe crop each, one fed and rested, the
     * other slowed by {@code slowDown}: the slowed one covers ground at about {@code pace} of the
     * other's speed, and all the way there Cobblemon counts it as walking, so it has its walk pose
     * (it moves more than Cobblemon's 0.005 of walk distance a tick: below that it stands still in
     * its idle pose and slides along).
     */
    private static void race(GameTestHelper helper, boolean pastured, Consumer<WorkerState> slowDown, double pace) {
        Workers.isolate(helper);
        helper.getLevel().setDayTime(MORNING);
        Walker fed = new Walker(helper, pastured, 2);
        Walker slowed = new Walker(helper, pastured, 13);
        slowed.worker.getPokemon().setCurrentFullness(0); // what slowDown adds to, if anything
        slowDown.accept(slowed.state);
        helper.onEachTick(() -> {
            fed.tick();
            slowed.tick();
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(fed.arrived() && slowed.arrived(), "both walked to their crops");
            double ratio = slowed.speed() / fed.speed();
            helper.assertTrue(
                    Math.abs(ratio - pace) < 0.12,
                    "the slowed one walked at " + ratio + " of the fed one's speed (" + slowed.speed() + " against "
                            + fed.speed() + " blocks a tick), not " + pace + "; its movement speed "
                            + slowed.worker.getAttributeValue(Attributes.MOVEMENT_SPEED) + " against "
                            + fed.worker.getAttributeValue(Attributes.MOVEMENT_SPEED));
            helper.assertTrue(
                    slowed.walkingTicks >= slowed.movingTicks * 0.9,
                    "Cobblemon counted it walking " + slowed.walkingTicks + " of the " + slowed.movingTicks
                            + " ticks it moved");
        });
    }

    /** A worker in its lane, walking to its crop: how far it went, in how long, and how much of it walking. */
    private static final class Walker {
        final PokemonEntity worker;
        final WorkerState state;
        double distance;
        int movingTicks;
        int walkingTicks;
        private double lastX;
        private double lastZ;
        private boolean arrived;

        Walker(GameTestHelper helper, boolean pastured, int lane) {
            BlockPos crop = new BlockPos(lane, 1, 14);
            helper.setBlock(crop.below(), Blocks.FARMLAND);
            helper.setBlock(crop, ((CropBlock) Blocks.WHEAT).getStateForAge(7));
            BlockPos start = new BlockPos(lane, 1, 1);
            worker = pastured ? Workers.sendOutPastured(helper, WORKER, start) : Workers.sendOut(helper, WORKER, start);
            state = WorkerStateManager.getOrCreate(worker.getPokemon());
            lastX = worker.getX();
            lastZ = worker.getZ();
        }

        void tick() {
            ReservationId job = state.getCurrentReservation();
            boolean walkingThere = job != null && job.getJobType() == JobType.HARVEST_CROPS && !state.isWorking();
            double step = Math.hypot(worker.getX() - lastX, worker.getZ() - lastZ);
            lastX = worker.getX();
            lastZ = worker.getZ();
            if (job != null && job.getJobType() == JobType.HARVEST_CROPS && state.isWorking()) {
                arrived = true;
            }
            if (!walkingThere || arrived || step < 1e-4) {
                return;
            }
            distance += step;
            movingTicks++;
            if (worker.isPokemonWalking()) {
                walkingTicks++;
            }
        }

        boolean arrived() {
            return arrived;
        }

        double speed() {
            return movingTicks == 0 ? 0 : distance / movingTicks;
        }
    }

    @GameTest(template = Templates.ARENA, batch = "pace_walk_empty", timeoutTicks = 600)
    public void anEmptyWorkerWalksAtThreeQuartersSpeedAndStillWalks(GameTestHelper helper) {
        race(helper, false, state -> {}, 0.75);
    }

    @GameTest(template = Templates.ARENA, batch = "pace_walk_slowest", timeoutTicks = 800)
    public void anEmptyTiredWorkerWalksAtHalfSpeedAndStillWalks(GameTestHelper helper) {
        // Tired is for pastured workers only (party workers never sleep)
        race(helper, true, state -> state.setTired(true), 0.5);
    }
}
