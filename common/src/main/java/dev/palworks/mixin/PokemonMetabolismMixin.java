package dev.palworks.mixin;

import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.hunger.Hunger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pauses Cobblemon's own fullness drain (its metabolism) for a Pokémon whose work is on while it's
 * out in the world: Palworks drains it instead, by the work it does (see {@link Hunger}). In its
 * ball, or with its work off, Cobblemon drains it as usual.
 *
 * <p>By name alone, so it matches both of Cobblemon's versions of the method:
 * {@code tickMetabolism()} up to 1.7.x, and {@code tickMetabolism(int)} (the ticks that passed)
 * from 1.8. Only one exists in any version. Listed in palworks-common.mixins.json.
 */
@Mixin(value = Pokemon.class, remap = false)
public abstract class PokemonMetabolismMixin {

    @Inject(method = "tickMetabolism", at = @At("HEAD"), cancellable = true, require = 1)
    private void palworks$pauseWhileWorking(CallbackInfo ci) {
        if (Hunger.pausesCobblemonDrain((Pokemon) (Object) this)) {
            ci.cancel();
        }
    }
}
