package dev.palworks.mixin;

import com.cobblemon.mod.common.entity.generic.GenericBedrockEntity;
import dev.palworks.animation.WorkAnimator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A work animation's marker (see {@link WorkAnimator}) is in nothing's way. Cobblemon's
 * {@code GenericBedrockEntity} is solid, and its hitbox stays a Pokémon's size (0.6 by 1.8) whatever
 * its scale, so each marker was an unseen obstacle in the block being worked for as long as its
 * move played: a worker sent to stand in or by that block found its way there blocked (and
 * remembered the job as out of reach), a walk planned past it went round or failed, and anything
 * walking by, players too, walked into it. It could be aimed at as well: a crosshair over that
 * block landed on the marker, so a click there did nothing, and an arrow stopped at it. A mixin
 * because nothing else decides either: collisions and crosshairs ask the entity itself. On clients
 * as well: a player's own movement, and what its crosshair is on, are worked out there. Listed in
 * palworks-common.mixins.json.
 */
@Mixin(GenericBedrockEntity.class)
public abstract class GenericBedrockEntityMixin {

    @Inject(method = "canBeCollidedWith", at = @At("HEAD"), cancellable = true)
    private void palworks$markerIsNotSolid(CallbackInfoReturnable<Boolean> cir) {
        if (WorkAnimator.isMarker((GenericBedrockEntity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isPickable", at = @At("HEAD"), cancellable = true)
    private void palworks$markerIsNotAimedAt(CallbackInfoReturnable<Boolean> cir) {
        if (WorkAnimator.isMarker((GenericBedrockEntity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
