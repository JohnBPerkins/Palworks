package dev.palworks.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.palworks.net.packets.WorkerStatusPacket;
import dev.palworks.suitability.SuitabilityDetector;
import dev.palworks.worker.WorkerStateManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Tells the owner whether a Pokemon is working just before Cobblemon opens its interact wheel.
 *
 * <p>Cobblemon opens the wheel from the server: {@code showInteractionWheel} sends
 * {@code InteractPokemonUIPacket}, and the client builds the wheel when that arrives. Sending our
 * {@link WorkerStatusPacket} first, on the same connection, means it is always there by the time
 * {@code PokemonInteractHandler} adds the work toggle. Listed in palworks-common.mixins.json.
 */
@Mixin(PokemonEntity.class)
public abstract class PokemonEntityInteractMixin {

    @Inject(method = "showInteractionWheel", at = @At("HEAD"), remap = false)
    private void palworks$sendWorkerStatus(ServerPlayer player, ItemStack itemStack, CallbackInfo ci) {
        Pokemon pokemon = ((PokemonEntity) (Object) this).getPokemon();
        // Same condition Cobblemon uses to open the wheel: only the owner gets one.
        UUID ownerUUID = pokemon.getOwnerUUID();
        if (ownerUUID == null || !ownerUUID.equals(player.getUUID())) {
            return;
        }
        // A Pokemon with no work it can do gets no toggle (the client shows one only if this arrives).
        if (SuitabilityDetector.getSuitabilities(pokemon).isEmpty()) {
            return;
        }
        boolean enabled = WorkerStateManager.getOrCreate(pokemon).isEnabled();
        new WorkerStatusPacket(((PokemonEntity) (Object) this).getUUID(), enabled).sendToPlayer(player);
    }
}
