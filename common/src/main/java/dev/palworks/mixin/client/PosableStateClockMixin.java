package dev.palworks.mixin.client;

import com.cobblemon.mod.common.client.entity.PokemonClientDelegate;
import com.cobblemon.mod.common.client.render.models.blockbench.PosableState;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.worker.Pace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Plays a slowed worker's animations slower (see Pace): every animation of a Pokémon (its walk, its
 * idle, the move it works with) runs on one clock, {@code getAnimationSeconds()}, its age in ticks
 * over 20. For a Pokémon, this runs that clock at its pace, from its {@code palworks-pace-} aspect:
 * a quarter-pace worker's legs move a quarter as fast, as it walks a quarter as fast, and its work
 * move lands as late as the server has its work happen. The clock goes up by its pace each tick, so
 * it doesn't jump when its pace changes. The same in Cobblemon 1.7 and 1.8. Client only; listed
 * in palworks-common.mixins.json.
 */
@Mixin(value = PosableState.class, remap = false)
public abstract class PosableStateClockMixin {
    @Shadow
    private int age;

    @Shadow
    public abstract float getPartialTicks();

    // The paced clock, in ticks (NaN until it's first asked), and the age it was last brought up to
    @Unique
    private float palworks$clock = Float.NaN;

    @Unique
    private int palworks$clockAge;

    @Inject(method = "getAnimationSeconds()F", at = @At("HEAD"), cancellable = true)
    private void palworks$atPace(CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof PokemonClientDelegate delegate)) {
            return;
        }
        PokemonEntity entity = delegate.currentEntity; // the field: its getter throws until it's set
        float pace = entity == null ? 1f : (float) Pace.fromAspects(entity.getAspects());
        int ticks = age - palworks$clockAge;
        if (Float.isNaN(palworks$clock) || ticks < 0) {
            palworks$clock = age; // first asked, or its age went back (a new entity): from where it is
        } else if (ticks > 0) {
            palworks$clock += ticks * pace;
        }
        palworks$clockAge = age;
        cir.setReturnValue((palworks$clock + getPartialTicks() * pace) / 20f);
    }
}
