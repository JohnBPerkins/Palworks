package dev.palworks.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.worker.WorkerTicking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs each worker from its own entity tick (see {@link WorkerTicking#onPokemonTick}). A mixin
 * because Architectury has no per-entity tick event. Listed in palworks-common.mixins.json.
 */
@Mixin(PokemonEntity.class)
public abstract class PokemonEntityTickMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void palworks$tickWorker(CallbackInfo ci) {
        WorkerTicking.onPokemonTick((PokemonEntity) (Object) this);
    }
}
