package dev.palworks.mixin.client;

import com.cobblemon.mod.common.client.entity.PokemonClientDelegate;
import com.cobblemon.mod.common.client.render.models.blockbench.PosableState;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.animation.PacedClock;
import dev.palworks.worker.Pace;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Plays a slowed worker's animations slower (see Pace): every animation of a Pokémon (its walk, its
 * idle, the move it works with) runs on one clock, {@code getAnimationSeconds()}, its age in ticks
 * over 20. For a Pokémon, this runs that clock at its pace, from its {@code palworks-pace-} aspect
 * ({@link PacedClock}): a quarter-pace worker's legs move a quarter as fast, as it walks a quarter
 * as fast. Except while it plays a one-off animation (the move it works with, a cry): those go with
 * particles and sounds that play at their usual speed, so it does too, and its work move lands as
 * the server has its work happen.
 *
 * <p>An animation's sounds and particles are timed on the same clock ({@code tickEffects}).
 * Cobblemon has each tick cover its age over 20 to the next, but counts from when the animation
 * started, which it reads off {@code getAnimationSeconds()}: once the clock was behind the age (a
 * worker slowed at any time since it was sent out), every animation started was past its sounds
 * and particles before it began, and a hungry worker's cry was silent.
 *
 * <p>The same throughout Cobblemon 1.8.x. Client only; listed in palworks-common.mixins.json.
 */
@Mixin(value = PosableState.class, remap = false)
public abstract class PosableStateClockMixin {
    @Shadow
    private int age;

    @Shadow
    public abstract float getPartialTicks();

    @Shadow
    public abstract void runEffects(@Nullable Entity entity, float previousSeconds, float newSeconds);

    @Unique
    private final PacedClock palworks$clock = new PacedClock();

    @Inject(method = "getAnimationSeconds()F", at = @At("HEAD"), cancellable = true)
    private void palworks$atPace(CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof PokemonClientDelegate delegate)) {
            return;
        }
        float pace = palworks$pace(delegate);
        palworks$clock.advanceTo(age, pace);
        cir.setReturnValue(palworks$clock.seconds(getPartialTicks(), pace));
    }

    // By its name alone: its descriptor has a Minecraft class in it, which this mixin (remap = false,
    // for Cobblemon's own names) would leave in the development names
    @Inject(method = "tickEffects", at = @At("HEAD"), cancellable = true)
    private void palworks$effectsOnItsClock(@Nullable Entity entity, int previousAge, int newAge, CallbackInfo ci) {
        if (!((Object) this instanceof PokemonClientDelegate delegate)) {
            return;
        }
        palworks$clock.advanceTo(newAge, palworks$pace(delegate));
        runEffects(entity, palworks$clock.secondsBeforeLastStep(), palworks$clock.secondsNow());
        ci.cancel();
    }

    /** The pace its clock goes at now: its aspect's, but full pace while it plays a one-off animation. */
    @Unique
    private static float palworks$pace(PokemonClientDelegate delegate) {
        PokemonEntity entity = delegate.currentEntity; // the field: its getter throws until it's set
        boolean oneOff = delegate.getPrimaryAnimation() != null
                || !delegate.getActiveAnimations().isEmpty();
        return entity == null || oneOff ? 1f : (float) Pace.fromAspects(entity.getAspects());
    }
}
