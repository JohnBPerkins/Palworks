package dev.palworks.mixin;

import com.cobblemon.mod.common.CobblemonMemories;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.entity.pokemon.ai.sensors.DrowsySensor;
import dev.palworks.sleep.Sleep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps Cobblemon from making a pastured worker drowsy while it has a job (so it doesn't doze off
 * mid-job) and in its bedtime (when Palworks puts it to bed: see {@link Sleep}). It's no longer
 * drowsy, so Cobblemon's own wake-up task wakes it if it was napping. Otherwise, an idle worker
 * gets drowsy and naps as any pastured Pokémon does. Listed in palworks-common.mixins.json.
 */
@Mixin(value = DrowsySensor.Companion.class, remap = false)
public abstract class DrowsySensorMixin {

    @Inject(method = "drowsyLogic", at = @At("HEAD"), cancellable = true, require = 1)
    private void palworks$notWhileWorking(PokemonEntity entity, CallbackInfo ci) {
        if (Sleep.blocksCobblemonDrowsiness(entity)) {
            entity.getBrain().eraseMemory(CobblemonMemories.POKEMON_DROWSY);
            ci.cancel();
        }
    }
}
