package dev.palworks.mixin;

import com.bedrockk.molang.runtime.MoLangRuntime;
import com.cobblemon.mod.common.api.molang.ExpressionLike;
import com.cobblemon.mod.common.api.moves.animations.keyframes.PauseActionEffectKeyframe;
import dev.palworks.animation.WorkAnimator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stretches the pauses of a move effect a slowed worker plays (see {@link WorkAnimator#stretch}),
 * so its particles and sounds wait as long as its slowed body animation takes to reach them. Any
 * other effect (a battle's) isn't stretched: its runtime isn't one of the worker's. The same method
 * in Cobblemon 1.7 and 1.8 (named in full, so no build fills it in from one or the other). Listed in
 * palworks-common.mixins.json.
 */
@Mixin(value = PauseActionEffectKeyframe.class, remap = false)
public abstract class PauseKeyframeMixin {

    @Redirect(
            method =
                    "playWhenTrue(Lcom/cobblemon/mod/common/api/moves/animations/ActionEffectContext;)Ljava/util/concurrent/CompletableFuture;",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/cobblemon/mod/common/api/molang/ExpressionLike;resolveFloat(Lcom/bedrockk/molang/runtime/MoLangRuntime;)F"))
    private float palworks$stretch(ExpressionLike pause, MoLangRuntime runtime) {
        return pause.resolveFloat(runtime) * WorkAnimator.stretchFor(runtime);
    }
}
