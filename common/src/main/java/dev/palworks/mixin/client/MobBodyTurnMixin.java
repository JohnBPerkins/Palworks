package dev.palworks.mixin.client;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Turns an owned Pokémon's body with it when the server turns it where it stands, as a worker is
 * turned to face its work (WorkerAI.turnTowards). The server sets its body, head and yaw together,
 * but only the yaw and head reach the client; the client works the body out itself, and vanilla's
 * {@code BodyRotationControl} leaves a standing mob's body up to 75° off its head, swinging it
 * round only once the head has been still for half a second. So a worker's move played with its
 * body still angled off the block, and the body came round partway through it.
 *
 * <p>Only while its yaw is turning (a standing Pokémon looking about turns just its head), so the
 * body is otherwise vanilla's. Client only; listed in palworks-common.mixins.json.
 */
@Mixin(Mob.class)
public abstract class MobBodyTurnMixin {
    @Inject(method = "tickHeadTurn", at = @At("RETURN"))
    private void palworks$bodyFollowsYaw(float yRot, float animStep, CallbackInfoReturnable<Float> cir) {
        if ((Object) this instanceof PokemonEntity pokemon
                && pokemon.level().isClientSide()
                && pokemon.getOwnerUUID() != null
                && Mth.wrapDegrees(pokemon.getYRot() - pokemon.yRotO) != 0.0f) {
            pokemon.setYBodyRot(pokemon.getYRot());
        }
    }
}
