package dev.palworks.mixin.client;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.palworks.animation.WorkAspects;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Turns a worker's body with it as it turns to face its work (WorkerAI.turnTowards). The server
 * sets its body, head and yaw together, but only the yaw and head reach the client; the client
 * works the body out itself, and vanilla's {@code BodyRotationControl} leaves a standing mob's body
 * up to 75° off its head, swinging it round only once the head has been still for half a second.
 * So a worker's move played with its body still angled off the block, and the body came round
 * partway through it.
 *
 * <p>Only while it's at its job ({@link WorkAspects#WORKING}: it has that from the tick it arrives,
 * as its step's ticks start then, its move waiting to settle, so the turn to its work is covered).
 * Everywhere else its body is vanilla's, so its head can look about on its own. Client only; listed
 * in palworks-common.mixins.json.
 */
@Mixin(Mob.class)
public abstract class MobBodyTurnMixin {
    @Inject(method = "tickHeadTurn", at = @At("RETURN"))
    private void palworks$bodyFacesWork(float yRot, float animStep, CallbackInfoReturnable<Float> cir) {
        if ((Object) this instanceof PokemonEntity pokemon
                && pokemon.level().isClientSide()
                && pokemon.getAspects().contains(WorkAspects.WORKING)) {
            pokemon.setYBodyRot(pokemon.getYRot());
        }
    }
}
