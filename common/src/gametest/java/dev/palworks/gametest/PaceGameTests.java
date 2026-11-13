package dev.palworks.gametest;

import com.bedrockk.molang.runtime.MoLangRuntime;
import com.cobblemon.mod.common.api.moves.animations.ActionEffectContext;
import com.cobblemon.mod.common.api.moves.animations.ActionEffectTimeline;
import com.cobblemon.mod.common.api.moves.animations.ActionEffects;
import com.cobblemon.mod.common.api.moves.animations.keyframes.PauseActionEffectKeyframe;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.animation.WorkAnimator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * A slowed worker's pace (see dev.palworks.worker.Pace): its work's move effects wait longer, to
 * match its slowed animations.
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
                        worker.getAspects().contains("palworks-pace-25"),
                        "empty: a quarter pace, " + worker.getAspects()))
                .thenExecute(() -> pokemon.setCurrentFullness(pokemon.getMaxFullness()))
                .thenWaitUntil(() -> helper.assertTrue(
                        worker.getAspects().stream().noneMatch(aspect -> aspect.startsWith("palworks-pace-")),
                        "fed: full pace, no aspect"))
                .thenSucceed();
    }
}
