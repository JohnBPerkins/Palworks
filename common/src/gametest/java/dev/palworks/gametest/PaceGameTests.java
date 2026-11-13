package dev.palworks.gametest;

import com.bedrockk.molang.runtime.MoLangRuntime;
import com.cobblemon.mod.common.api.moves.animations.ActionEffectContext;
import com.cobblemon.mod.common.api.moves.animations.ActionEffectTimeline;
import com.cobblemon.mod.common.api.moves.animations.ActionEffects;
import com.cobblemon.mod.common.api.moves.animations.keyframes.PauseActionEffectKeyframe;
import dev.palworks.animation.WorkAnimator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
}
