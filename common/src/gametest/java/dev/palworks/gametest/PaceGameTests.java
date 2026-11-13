package dev.palworks.gametest;

import com.bedrockk.molang.runtime.MoLangRuntime;
import com.cobblemon.mod.common.api.moves.animations.ActionEffectContext;
import com.cobblemon.mod.common.api.moves.animations.ActionEffectTimeline;
import com.cobblemon.mod.common.api.moves.animations.ActionEffects;
import com.cobblemon.mod.common.api.moves.animations.keyframes.PauseActionEffectKeyframe;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.animation.WorkAnimator;
import dev.palworks.job.JobType;
import dev.palworks.reservation.ReservationId;
import dev.palworks.worker.WorkerState;
import dev.palworks.worker.WorkerStateManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

/**
 * A slowed worker's pace (see dev.palworks.worker.Pace): it walks slower, but still visibly walks,
 * and its work's move effects wait longer, to match its slowed animations.
 */
public class PaceGameTests {

    private static ActionEffectContext context(
            ActionEffectTimeline timeline, MoLangRuntime runtime, ServerLevel level) {
        return new ActionEffectContext(
                timeline, new HashSet<>(), List.of(), runtime, true, false, new ArrayList<>(), level);
    }

    @GameTest(template = Templates.EMPTY)
    public void aSlowedWorkersEffectWaitsLongerAtItsPauses(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ActionEffectTimeline timeline = ActionEffects.INSTANCE
                .getActionEffects()
                .get(ResourceLocation.fromNamespaceAndPath("cobblemon", "ember"));
        helper.assertTrue(timeline != null, "Cobblemon's Ember effect is loaded");
        PauseActionEffectKeyframe pause = new PauseActionEffectKeyframe(); // a 1 second (20 tick) pause

        // A battle's effect (a runtime that isn't a slowed worker's), and a worker's at half pace
        MoLangRuntime battle = new MoLangRuntime();
        MoLangRuntime halfPace = new MoLangRuntime();
        WorkAnimator.stretch(halfPace, 0.5);
        CompletableFuture<?> normal = pause.playWhenTrue(context(timeline, battle, level));
        CompletableFuture<?> slowed = pause.playWhenTrue(context(timeline, halfPace, level));

        helper.runAfterDelay(25, () -> {
            helper.assertTrue(normal.isDone(), "the battle's pause is over after a second");
            helper.assertFalse(slowed.isDone(), "the half-pace worker's isn't");
        });
        helper.runAfterDelay(45, () -> {
            helper.assertTrue(slowed.isDone(), "it's over after two");
            helper.succeed();
        });
    }

    @GameTest(template = Templates.EMPTY, batch = "pace_aspect", timeoutTicks = 200)
    public void aSlowedWorkerTellsClientsItsPace(GameTestHelper helper) {
        // Its pace as an aspect, which Cobblemon syncs to players, for them to slow its animations
        Workers.isolate(helper);
        PokemonEntity worker = Workers.sendOut(helper, "bulbasaur level=20", new BlockPos(1, 1, 1));
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
            worker = pastured
                    ? Workers.sendOutPastured(helper, "bulbasaur level=20", start)
                    : Workers.sendOut(helper, "bulbasaur level=20", start);
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
