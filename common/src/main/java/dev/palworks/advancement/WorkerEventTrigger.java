package dev.palworks.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

/** {@code palworks:worker_event}: something happened to one of the player's workers (its {@code event}). */
public final class WorkerEventTrigger extends SimpleCriterionTrigger<WorkerEventTrigger.TriggerInstance> {
    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    public void trigger(ServerPlayer player, WorkerEvent event) {
        trigger(player, instance -> instance.event() == event);
    }

    public record TriggerInstance(Optional<ContextAwarePredicate> player, WorkerEvent event)
            implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        EntityPredicate.ADVANCEMENT_CODEC
                                .optionalFieldOf("player")
                                .forGetter(TriggerInstance::player),
                        WorkerEvent.CODEC.fieldOf("event").forGetter(TriggerInstance::event))
                .apply(instance, TriggerInstance::new));
    }
}
