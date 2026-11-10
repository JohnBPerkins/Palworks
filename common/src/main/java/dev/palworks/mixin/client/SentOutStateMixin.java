package dev.palworks.mixin.client;

import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.activestate.SentOutState;
import dev.palworks.client.hud.WorkStatusHud;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Frees the top-left corner of a party HUD card for the work icon ({@link WorkStatusHud}): while the
 * HUD shows one for a sent-out Pokémon, Cobblemon draws a transparent icon instead of its "sent out"
 * icon. Not null: Cobblemon also uses "has a state icon" to show the card's ball as released.
 * Client only (palworks-common.mixins.json "client").
 */
@Mixin(value = SentOutState.class, remap = false)
public abstract class SentOutStateMixin {

    @Inject(method = "getIcon", at = @At("HEAD"), cancellable = true)
    private void palworks$makeRoomForWorkIcon(Pokemon pokemon, CallbackInfoReturnable<ResourceLocation> cir) {
        if (WorkStatusHud.replacesStateIcon(pokemon)) {
            cir.setReturnValue(WorkStatusHud.EMPTY_STATE_ICON);
        }
    }
}
