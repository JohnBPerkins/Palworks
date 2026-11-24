package dev.palworks.sleep;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.palworks.advancement.WorkerAdvancements;
import dev.palworks.advancement.WorkerEvent;
import net.minecraft.server.level.ServerPlayer;

/**
 * What wakes a sleeping worker in the night (see {@link Sleep#disturb}): being hit by a player
 * (even where players can't hurt Pokémon), or hurt by anything. Its bed broken under it, and being
 * recalled, wake it too (see WorkerAI and JobManager). Neither event is changed: the hit or the hurt
 * goes ahead as it would.
 */
public final class SleepDisturbances {
    private SleepDisturbances() {}

    public static void register() {
        PlayerEvent.ATTACK_ENTITY.register((player, level, target, hand, hit) -> {
            if (!level.isClientSide
                    && target instanceof PokemonEntity pokemon
                    && Sleep.disturb(pokemon)
                    && player instanceof ServerPlayer hitter) {
                WorkerAdvancements.event(hitter, WorkerEvent.WOKE_A_WORKER);
            }
            return EventResult.pass();
        });
        EntityEvent.LIVING_HURT.register((entity, source, amount) -> {
            if (!entity.level().isClientSide && entity instanceof PokemonEntity pokemon) {
                Sleep.disturb(pokemon);
            }
            return EventResult.pass();
        });
    }
}
