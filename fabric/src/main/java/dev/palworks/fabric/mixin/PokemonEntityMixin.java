package dev.palworks.fabric.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.worker.WorkerTicking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to inject worker AI ticking into Cobblemon's PokemonEntity.
 */
@Mixin(PokemonEntity.class)
public class PokemonEntityMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        PokemonEntity entity = (PokemonEntity) (Object) this;
        WorkerTicking.onPokemonTick(entity);
    }
}
