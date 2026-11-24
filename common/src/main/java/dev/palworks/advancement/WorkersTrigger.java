package dev.palworks.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code palworks:workers}: how many of the player's Pokémon are working at once, at least {@code
 * min}. Told each time another of them starts working.
 */
public final class WorkersTrigger extends SimpleCriterionTrigger<WorkersTrigger.TriggerInstance> {
    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    public void trigger(ServerPlayer player, int working) {
        trigger(player, instance -> working >= instance.min());
    }

    public record TriggerInstance(Optional<ContextAwarePredicate> player, int min)
            implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        EntityPredicate.ADVANCEMENT_CODEC
                                .optionalFieldOf("player")
                                .forGetter(TriggerInstance::player),
                        Codec.INT.optionalFieldOf("min", 1).forGetter(TriggerInstance::min))
                .apply(instance, TriggerInstance::new));
    }
}
